package com.brycewg.asrkb.asr

import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * One session owns one socket, queue, transcript and terminal gate. No Android dependencies.
 * PCM is bounded, reframed to 200 ms and paced even while draining after stop. The socket stays
 * open until Tencent's final=1, rather than guessing that a fixed delay is enough to finalize.
 */
internal class TencentStreamSession(
    private val scope: CoroutineScope,
    private val request: Request,
    private val socketFactory: WebSocket.Factory,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onStopped: () -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val handshakeTimeoutMs: Long = 10_000,
    private val finalTimeoutMs: Long = 15_000,
    private val idleTimeoutMs: Long = 5_000,
    private val maxQueuedBytes: Int = TencentAsrProtocol.BYTES_PER_SECOND * 10,
    private val maxSocketQueueBytes: Long = TencentAsrProtocol.BYTES_PER_SECOND * 2L
) {
    private val lock = Any()
    private val queue = ArrayDeque<ByteArray>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val ready = CompletableDeferred<Boolean>()
    private val completed = CompletableDeferred<Unit>()
    private val transcript = TencentAsrTranscript()
    private var socket: WebSocket? = null
    private var sender: Job? = null
    private var queuedBytes = 0
    private var headOffset = 0
    private var started = false
    private var acknowledged = false
    private var inputClosed = false
    private var stoppedNotified = false
    private var terminal = false
    private var lastPartial = ""

    fun start() {
        synchronized(lock) {
            if (started || terminal) return
            started = true
        }
        try {
            val opened = socketFactory.newWebSocket(request, socketListener)
            synchronized(lock) {
                if (terminal) {
                    opened.cancel()
                    return
                }
                socket = opened
                sender = scope.launch(dispatcher) { sendLoop() }
                // Also covers a scope that was already cancelled before launch ran.
                sender?.invokeOnCompletion { cancel() }
            }
        } catch (_: Exception) {
            fail("Tencent ASR connection could not be started")
        }
    }

    suspend fun awaitReady(timeoutMs: Long): Boolean = try {
        withTimeoutOrNull(timeoutMs) { ready.await() } == true && synchronized(lock) { !terminal }
    } catch (_: CancellationException) {
        currentCoroutineContext().ensureActive()
        false
    }

    fun enqueuePcm(pcm: ByteArray): Boolean {
        if (pcm.isEmpty()) return false
        val error = synchronized(lock) {
            if (terminal || inputClosed) return false
            when {
                pcm.size % 2 != 0 -> "Tencent ASR requires PCM16LE audio"
                pcm.size > maxQueuedBytes - queuedBytes -> "Tencent ASR audio queue overflow; check the network"
                else -> {
                    queue.addLast(pcm.copyOf())
                    queuedBytes += pcm.size
                    wake.trySend(Unit)
                    return true
                }
            }
        }
        fail(error)
        return false
    }

    /** Closes input only; queued PCM and the final service response are still awaited. */
    fun finish() {
        synchronized(lock) {
            if (terminal || inputClosed) return
            inputClosed = true
            wake.trySend(Unit)
        }
        notifyStopped()
    }

    /** Silent abort, including when a newer recording replaces this session. */
    fun cancel() {
        terminate(error = null, finalText = null)
    }

    fun fail(message: String) {
        terminate(error = message, finalText = null)
    }

    private val socketListener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (synchronized(lock) { terminal }) return
            val message = try {
                TencentAsrProtocol.parseMessage(text)
            } catch (_: Exception) {
                fail("Invalid response from Tencent ASR")
                return
            }
            if (message.code != 0) {
                fail(serviceError(message.code))
                return
            }
            var partial: String? = null
            var finalText: String? = null
            var invalid = false
            synchronized(lock) {
                if (terminal) return
                if (message.isAcknowledgement) {
                    acknowledged = true
                    ready.complete(true)
                } else if (!acknowledged) {
                    invalid = true
                } else {
                    message.sentences.forEach { transcript.update(it) }
                    if (message.hasResult) {
                        val textNow = transcript.text()
                        if (textNow != lastPartial) {
                            lastPartial = textNow
                            partial = textNow
                        }
                    }
                    if (message.isFinal) finalText = transcript.text()
                }
            }
            if (invalid) {
                fail("Tencent ASR returned results before its handshake")
                return
            }
            partial?.let { safeCallback { onPartial(it) } }
            finalText?.let { terminate(error = null, finalText = it) }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            fail("Unexpected binary response from Tencent ASR")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // Close before final=1 is failure, including after the user released the microphone.
            fail("Tencent ASR connection closed before the final result ($code)")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            fail("Tencent ASR connection closed before the final result ($code)")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // Throwable/response may contain the signed URL. Deliberately do not log or expose them.
            fail("Tencent ASR network connection failed${response?.code?.let { " (HTTP $it)" }.orEmpty()}")
        }
    }

    private suspend fun sendLoop() {
        try {
            if (withTimeoutOrNull(handshakeTimeoutMs) { ready.await() } != true) {
                fail("Tencent ASR handshake timed out")
                return
            }
            var nextSendMs = nowMs()
            while (true) {
                currentCoroutineContext().ensureActive()
                val frame = synchronized(lock) {
                    if (terminal) return
                    takeFrame()
                }
                if (frame != null) {
                    delay((nextSendMs - nowMs()).coerceAtLeast(0))
                    val sent = synchronized(lock) {
                        if (terminal) return
                        val target = socket
                        target != null &&
                            target.queueSize() <= maxSocketQueueBytes - frame.size &&
                            target.send(frame.toByteString())
                    }
                    if (!sent) {
                        fail("Tencent ASR audio upload failed; check the network")
                        return
                    }
                    // No catch-up bursts after scheduling/network delays, including a prebuffer.
                    nextSendMs = nowMs() + (frame.size * 1000L / TencentAsrProtocol.BYTES_PER_SECOND)
                    continue
                }
                if (synchronized(lock) { inputClosed }) {
                    delay((nextSendMs - nowMs()).coerceAtLeast(0))
                    val sent = synchronized(lock) {
                        if (terminal) return
                        socket?.send(TencentAsrProtocol.END_MESSAGE) == true
                    }
                    if (!sent) {
                        fail("Tencent ASR end-of-audio upload failed")
                    } else if (withTimeoutOrNull(finalTimeoutMs) { completed.await() } == null) {
                        fail("Tencent ASR final result timed out")
                    }
                    return
                }
                if (withTimeoutOrNull(idleTimeoutMs) { wake.receive() } == null) {
                    fail("Tencent ASR audio input timed out")
                    return
                }
            }
        } catch (_: CancellationException) {
            // Parent cancellation is an abort, never a fabricated successful transcript.
        } catch (_: Exception) {
            fail("Tencent ASR audio upload failed")
        } finally {
            cancel()
        }
    }

    /** Called under lock. A final short (sample-aligned) frame is flushed only after finish. */
    private fun takeFrame(): ByteArray? {
        if (queuedBytes == 0 || (!inputClosed && queuedBytes < TencentAsrProtocol.FRAME_BYTES)) return null
        val frame = ByteArray(minOf(queuedBytes, TencentAsrProtocol.FRAME_BYTES))
        var written = 0
        while (written < frame.size) {
            val head = queue.first
            val count = minOf(head.size - headOffset, frame.size - written)
            head.copyInto(frame, written, headOffset, headOffset + count)
            headOffset += count
            written += count
            queuedBytes -= count
            if (headOffset == head.size) {
                queue.removeFirst()
                headOffset = 0
            }
        }
        return frame
    }

    private fun notifyStopped() {
        val notify = synchronized(lock) {
            if (stoppedNotified) {
                false
            } else {
                stoppedNotified = true
                true
            }
        }
        if (notify) safeCallback(onStopped)
    }

    private fun terminate(error: String?, finalText: String?) {
        val target: WebSocket?
        val job: Job?
        synchronized(lock) {
            if (terminal) return
            terminal = true
            inputClosed = true
            queue.clear()
            queuedBytes = 0
            target = socket
            socket = null
            job = sender
            ready.complete(false)
            completed.complete(Unit)
            wake.close()
        }
        job?.cancel()
        if (finalText != null) {
            if (target?.close(1000, "complete") == false) target.cancel()
        } else {
            target?.cancel()
        }
        // Silent cancellation is used for restart/disposal; do not send stale callbacks.
        if (error != null || finalText != null) notifyStopped()
        if (error != null) safeCallback { onError(error) }
        if (finalText != null) safeCallback { onFinal(finalText) }
    }

    private fun safeCallback(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) { /* Client callbacks must not leak transport resources. */ }
    }

    private fun serviceError(code: Int): String {
        val detail = when (code) {
            4001 -> "invalid parameters"
            4002 -> "authentication failed; check AppID, SecretId, SecretKey and device time"
            4003 -> "ASR service is not enabled for this AppID"
            4004 -> "ASR quota exhausted"
            4005 -> "account balance insufficient"
            4006 -> "concurrency limit reached"
            4007 -> "invalid audio format"
            4008 -> "audio upload timed out"
            else -> "recognition failed"
        }
        return "Tencent ASR $code: $detail"
    }
}
