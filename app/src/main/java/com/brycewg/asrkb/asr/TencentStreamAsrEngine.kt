package com.brycewg.asrkb.asr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import androidx.core.content.ContextCompat
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

/** Tencent Cloud real-time ASR, 16 kHz / mono / signed PCM16LE WebSocket streaming. */
class TencentStreamAsrEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: Prefs,
    private val listener: StreamingAsrEngine.Listener,
    private val externalPcmMode: Boolean = false
) : StreamingAsrEngine,
    ExternalPcmConsumer,
    AudioFrameSinkOwner,
    CancelableAsrEngine {

    override var audioFrameSink: AudioFrameSink? = null
    private val lock = Any()
    private var session: TencentStreamSession? = null
    private var audioJob: Job? = null
    private var running = false
    private val externalLeveler = VadInputLevelerBranch(sampleRate = TencentAsrProtocol.SAMPLE_RATE)

    override val isRunning: Boolean get() = synchronized(lock) { running }

    override fun start() {
        synchronized(lock) {
            if (running) return
            // Retire the previous session before checking new settings. Late callbacks cannot
            // affect this recording, and an interrupted handshake cannot send old buffered audio.
            session?.cancel()
            session = null
            audioJob?.cancel()
            audioJob = null
            if (!externalPcmMode &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                listener.onError(context.getString(R.string.error_record_permission_denied))
                return
            }
            val appId = prefs.tencentAppId.trim()
            val secretId = prefs.tencentSecretId.trim()
            val secretKey = prefs.tencentSecretKey.trim()
            if (appId.isEmpty() || secretId.isEmpty() || secretKey.isEmpty()) {
                listener.onError(context.getString(R.string.error_missing_tencent_credentials))
                return
            }
            val request = try {
                Request.Builder().url(
                    TencentAsrProtocol.signedUrl(
                        appId = appId,
                        secretId = secretId,
                        secretKey = secretKey,
                        engineModelType = prefs.tencentEngineModelType.trim().ifBlank { "16k_zh" },
                        timestamp = System.currentTimeMillis() / 1000,
                        nonce = random.nextInt(Int.MAX_VALUE - 1).toLong() + 1,
                        voiceId = UUID.randomUUID().toString()
                    )
                ).build()
            } catch (_: Exception) {
                listener.onError(errorMessage("Invalid Tencent AppID or 16 kHz engine model"))
                return
            }
            externalLeveler.reset()
            lateinit var active: TencentStreamSession
            active = TencentStreamSession(
                scope = scope,
                request = request,
                socketFactory = client,
                onPartial = { text -> withCurrent(active) { listener.onPartial(text) } },
                onFinal = { text -> withCurrent(active) { listener.onFinal(text) } },
                onError = { message -> withCurrent(active) { listener.onError(errorMessage(message)) } },
                onStopped = {
                    withCurrent(active) {
                        running = false
                        audioJob?.cancel()
                        audioJob = null
                        listener.onStopped()
                    }
                }
            )
            session = active
            running = true
            active.start()
            if (!externalPcmMode && session === active && running) startCapture(active)
        }
    }

    override fun stop() {
        synchronized(lock) {
            val active = session ?: return
            running = false
            audioJob?.cancel()
            audioJob = null
            active.finish()
        }
    }

    override fun cancel() {
        synchronized(lock) {
            val old = session
            session = null
            running = false
            audioJob?.cancel()
            audioJob = null
            old?.cancel()
        }
    }

    override suspend fun awaitReady(timeoutMs: Long): Boolean {
        val active = synchronized(lock) { session } ?: return false
        return active.awaitReady(timeoutMs) && synchronized(lock) { session === active }
    }

    override fun appendPcm(pcm: ByteArray, sampleRate: Int, channels: Int) {
        synchronized(lock) {
            if (!externalPcmMode || !running) return
            val active = session ?: return
            if (sampleRate != TencentAsrProtocol.SAMPLE_RATE || channels != 1 || pcm.size % 2 != 0) {
                active.fail("Tencent ASR requires 16 kHz mono PCM16LE audio")
                return
            }
            if (active.enqueuePcm(pcm)) {
                val leveled = externalLeveler.process(pcm)
                try {
                    listener.onAmplitude(leveled.stableAmplitude)
                } catch (_: Exception) { }
            }
        }
    }

    private fun startCapture(active: TencentStreamSession) {
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val capture = AudioCaptureManager(
                context = context,
                sampleRate = TencentAsrProtocol.SAMPLE_RATE,
                channelConfig = AudioFormat.CHANNEL_IN_MONO,
                audioFormat = AudioFormat.ENCODING_PCM_16BIT,
                chunkMillis = TencentAsrProtocol.FRAME_MS.toInt(),
                audioFrameSinkProvider = { audioFrameSink }
            )
            var vad: VadDetector? = null
            try {
                vad = if (isVadAutoStopEnabled(context, prefs)) {
                    VadDetector(
                        context,
                        TencentAsrProtocol.SAMPLE_RATE,
                        prefs.autoStopSilenceWindowMs,
                        prefs.autoStopSilenceSensitivity
                    )
                } else {
                    null
                }
                val leveler = VadInputLevelerBranch(sampleRate = TencentAsrProtocol.SAMPLE_RATE)
                val limiter = RecordingDurationLimiter.fromPrefs(prefs, TencentAsrProtocol.SAMPLE_RATE)
                capture.startCapture().collect { pcm ->
                    withCurrent(active) {
                        if (running && active.enqueuePcm(pcm)) {
                            val leveled = leveler.process(pcm)
                            try {
                                listener.onAmplitude(leveled.stableAmplitude)
                            } catch (_: Exception) { }
                            val silence = vad?.shouldStop(leveled.leveledPcm, leveled.leveledPcm.size) == true
                            if (silence || limiter.acceptPcm(pcm.size)) stop()
                        }
                    }
                }
                // A shared continuous-capture source can complete naturally.
                withCurrent(active) { stop() }
            } catch (_: CancellationException) {
                // stop()/cancel() owns finalization; do not turn cancellation into an error.
            } catch (_: Exception) {
                active.fail("Tencent ASR audio capture failed")
            } finally {
                try {
                    vad?.release()
                } catch (_: Exception) { }
            }
        }
        audioJob = job
        job.start()
    }

    private fun withCurrent(active: TencentStreamSession, block: () -> Unit) {
        synchronized(lock) {
            if (session === active) block()
        }
    }

    private fun errorMessage(detail: String): String = context.getString(R.string.error_recognize_failed_with_reason, detail)

    private companion object {
        val random = SecureRandom()

        // The URL contains SecretId and a short-lived signature. Never use ApiLogInterceptor,
        // ApiCallLogger, or log Request/Response/Throwable objects for this connection.
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
    }
}
