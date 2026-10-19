package com.brycewg.asrkb.asr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TencentStreamSessionTest {
    @Test
    fun websocketOpenDoesNotSendPcmBeforeServiceAcknowledgement() = runTest {
        val f = Fixture(this)
        f.session.start()
        f.session.enqueuePcm(ByteArray(6400))
        f.service.open()
        runCurrent()
        assertTrue(f.service.audio.isEmpty())
        val waiting = async { f.session.awaitReady(1000) }
        runCurrent()
        assertFalse(waiting.isCompleted)
        f.service.ack()
        runCurrent()
        assertTrue(waiting.await())
        assertEquals(1, f.service.audio.size)
        f.session.cancel()
    }

    @Test
    fun stopBeforeAckDrainsInOrderAtRealtimePaceThenWaitsForFinal() = runTest {
        val f = Fixture(this)
        f.session.start()
        val pcm = ByteArray(12800) { (it % 251).toByte() }
        f.session.enqueuePcm(pcm.copyOfRange(0, 1000))
        f.session.enqueuePcm(pcm.copyOfRange(1000, 9000))
        f.session.enqueuePcm(pcm.copyOfRange(9000, pcm.size))
        f.session.finish()
        f.session.finish()
        runCurrent()
        assertTrue(f.service.audio.isEmpty())
        assertEquals(1, f.stops)
        f.service.ack()
        runCurrent()
        assertEquals(listOf(0L), f.service.audio.map { it.first })
        advanceTimeBy(199)
        runCurrent()
        assertEquals(1, f.service.audio.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(0L, 200L), f.service.audio.map { it.first })
        assertArrayEquals(pcm, f.service.audio.flatMap { it.second.toList() }.toByteArray())
        advanceTimeBy(200)
        runCurrent()
        assertEquals(listOf(400L to TencentAsrProtocol.END_MESSAGE), f.service.text)
        assertFalse(f.service.closed)
        assertTrue(f.finals.isEmpty())
        advanceTimeBy(2000)
        runCurrent()
        assertFalse(f.service.closed)
        f.service.sentence(0, 2, "完成。")
        f.service.final()
        f.service.final()
        f.service.closedByServer()
        runCurrent()
        assertEquals(listOf("完成。"), f.finals)
        assertTrue(f.errors.isEmpty())
        assertTrue(f.service.closed)
        assertEquals(1, f.stops)
    }

    @Test
    fun finishFlushesShortSampleAlignedTailAndRejectsLateAudio() = runTest {
        val f = Fixture(this)
        f.session.start()
        f.service.ack()
        f.session.enqueuePcm(ByteArray(3200) { 7 })
        runCurrent()
        assertTrue(f.service.audio.isEmpty())
        f.session.finish()
        assertFalse(f.session.enqueuePcm(ByteArray(6400)))
        runCurrent()
        assertEquals(3200, f.service.audio.single().second.size)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(100L, f.service.text.single().first)
        f.service.final()
        runCurrent()
        assertEquals(listOf(""), f.finals)
    }

    @Test
    fun serviceErrorsCloseWithoutSendingPcmOrLeakingErrorPayload() = runTest {
        val f = Fixture(this)
        f.session.start()
        f.session.enqueuePcm(ByteArray(6400))
        f.service.message("""{"code":4002,"message":"wss://example?signature=secret"}""")
        runCurrent()
        assertTrue(f.service.audio.isEmpty())
        assertTrue(f.service.cancelled)
        assertEquals(1, f.errors.size)
        assertFalse(f.errors.single().contains("signature"))
        assertTrue(f.finals.isEmpty())
    }

    @Test
    fun queueOverflowFailsExplicitlyInsteadOfDroppingAudio() = runTest {
        val f = Fixture(this, maxQueuedBytes = 6400)
        f.session.start()
        assertTrue(f.session.enqueuePcm(ByteArray(6400)))
        assertFalse(f.session.enqueuePcm(ByteArray(2)))
        f.service.ack()
        runCurrent()
        assertEquals(1, f.errors.size)
        assertTrue(f.errors.single().contains("overflow"))
        assertTrue(f.service.audio.isEmpty())
        assertTrue(f.service.cancelled)
    }

    @Test
    fun socketBackpressureIsAlsoBounded() = runTest {
        val f = Fixture(this)
        f.session.start()
        f.service.queuedBytes = 64000
        f.service.ack()
        f.session.enqueuePcm(ByteArray(6400))
        runCurrent()
        assertTrue(f.service.audio.isEmpty())
        assertEquals(1, f.errors.size)
        assertTrue(f.service.cancelled)
    }

    @Test
    fun handshakeTimeoutFailsExactlyOnce() = runTest {
        val f = Fixture(this)
        f.session.start()
        runCurrent()
        advanceTimeBy(10000)
        runCurrent()
        f.service.failed()
        f.service.closedByServer()
        assertEquals(1, f.errors.size)
        assertTrue(f.errors.single().contains("handshake"))
        assertFalse(f.session.awaitReady(1))
        assertTrue(f.service.cancelled)
    }

    @Test
    fun finalTimeoutStartsAfterDrainAndDoesNotFabricatePartialSuccess() = runTest {
        val f = Fixture(this)
        f.session.start()
        f.service.ack()
        f.service.sentence(0, 1, "unfinished")
        f.session.enqueuePcm(ByteArray(12800))
        f.session.finish()
        runCurrent()
        advanceTimeBy(400)
        runCurrent()
        assertEquals(400L, f.service.text.single().first)
        advanceTimeBy(14999)
        runCurrent()
        assertTrue(f.errors.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, f.errors.size)
        assertTrue(f.errors.single().contains("final result timed out"))
        assertTrue(f.finals.isEmpty())
    }

    @Test
    fun earlyServerCloseAfterStopIsFailureNotPartialFinal() = runTest {
        val f = Fixture(this)
        f.session.start()
        f.service.ack()
        f.service.sentence(0, 1, "partial")
        f.session.finish()
        runCurrent()
        f.service.closedByServer()
        f.service.final()
        runCurrent()
        assertTrue(f.finals.isEmpty())
        assertEquals(1, f.errors.size)
    }

    @Test
    fun cancellationSuppressesOldSocketCallbacksAndLeavesNewSessionIntact() = runTest {
        val old = Fixture(this)
        old.session.start()
        old.session.enqueuePcm(ByteArray(6400))
        old.session.cancel()
        val fresh = Fixture(this)
        fresh.session.start()
        fresh.service.ack()
        fresh.service.sentence(0, 2, "new")
        old.service.ack()
        old.service.sentence(0, 2, "stale")
        old.service.final()
        old.service.failed()
        runCurrent()
        assertTrue(old.errors.isEmpty())
        assertTrue(old.finals.isEmpty())
        assertTrue(old.partials.isEmpty())
        assertTrue(old.service.audio.isEmpty())
        assertEquals(0, old.stops)
        assertTrue(fresh.session.awaitReady(1))
        fresh.service.final()
        runCurrent()
        assertEquals(listOf("new"), fresh.finals)
    }

    @Test
    fun cancelledParentScopeReleasesSocketWithoutTerminalCallback() = runTest {
        val parent = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        val f = Fixture(this, parentScope = parent)
        f.session.start()
        runCurrent()
        parent.cancel()
        runCurrent()
        assertTrue(f.service.cancelled)
        assertFalse(f.session.awaitReady(1))
        assertTrue(f.errors.isEmpty())
        assertTrue(f.finals.isEmpty())
    }

    @Test
    fun alreadyCancelledScopeDoesNotLeakANewSocket() = runTest {
        val parent = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        parent.cancel()
        val f = Fixture(this, parentScope = parent)
        f.session.start()
        runCurrent()
        assertTrue(f.service.cancelled)
        assertTrue(f.errors.isEmpty())
    }

    @Test
    fun rejectedSocketSendTerminatesAndDoesNotSendEnd() = runTest {
        val f = Fixture(this)
        f.service.acceptSends = false
        f.session.start()
        f.service.ack()
        f.session.enqueuePcm(ByteArray(6400))
        f.session.finish()
        runCurrent()
        assertEquals(1, f.errors.size)
        assertTrue(f.service.text.isEmpty())
        assertTrue(f.finals.isEmpty())
    }

    @Test
    fun inputInactivityTimesOutAndMalformedJsonDoesNotAuthenticate() = runTest {
        val idle = Fixture(this)
        idle.session.start()
        idle.service.ack()
        runCurrent()
        advanceTimeBy(5000)
        runCurrent()
        assertTrue(idle.errors.single().contains("input timed out"))
        val malformed = Fixture(this)
        malformed.session.start()
        malformed.service.message("not json")
        runCurrent()
        assertEquals(1, malformed.errors.size)
        assertFalse(malformed.session.awaitReady(1))
    }

    @Test
    fun v2SentenceListsReplaceInterimsAndAggregateAllSentencesAtFinal() = runTest {
        val f = Fixture(this)
        f.session.start()
        f.service.ack()
        f.service.message("""{"code":0,"sentences":{"sentence_list":[{"sentence":"hello ","sentence_id":1,"sentence_type":0}]}}""")
        f.service.message("""{"code":0,"sentences":{"sentence_list":[{"sentence":"hello ","sentence_id":1,"sentence_type":1},{"sentence":"world","sentence_id":2,"sentence_type":0}]}}""")
        f.session.finish()
        runCurrent()
        f.service.message("""{"code":0,"final":1,"sentences":{"sentence_list":[{"sentence":"world!","sentence_id":2,"sentence_type":1}]}}""")
        runCurrent()
        assertEquals(listOf("hello world!"), f.finals)
        assertEquals("hello world!", f.partials.last())
        assertTrue(f.errors.isEmpty())
    }

    @Test
    fun v2ResultBeforeAckFailsWithoutUploadingAudio() = runTest {
        val f = Fixture(this)
        f.session.start()
        f.session.enqueuePcm(ByteArray(6400))
        f.service.message("""{"code":0,"sentences":{"sentence_list":[]}}""")
        runCurrent()
        assertTrue(f.service.audio.isEmpty())
        assertEquals(1, f.errors.size)
        assertFalse(f.session.awaitReady(1))
    }

    private class Fixture(
        scope: TestScope,
        maxQueuedBytes: Int = 320000,
        parentScope: CoroutineScope = scope
    ) {
        val service = MockTencentService { scope.testScheduler.currentTime }
        val partials = mutableListOf<String>()
        val finals = mutableListOf<String>()
        val errors = mutableListOf<String>()
        var stops = 0
        val session = TencentStreamSession(
            scope = parentScope,
            request = Request.Builder().url("https://example.invalid/asr").build(),
            socketFactory = service,
            onPartial = { partials.add(it) },
            onFinal = { finals.add(it) },
            onError = { errors.add(it) },
            onStopped = { stops++ },
            dispatcher = StandardTestDispatcher(scope.testScheduler),
            nowMs = { scope.testScheduler.currentTime },
            maxQueuedBytes = maxQueuedBytes
        )
    }

    /** In-process mock service. No credentials, microphone or network access is used. */
    private class MockTencentService(private val nowMs: () -> Long) :
        WebSocket.Factory,
        WebSocket {
        private lateinit var request: Request
        private lateinit var listener: WebSocketListener
        val audio = mutableListOf<Pair<Long, ByteArray>>()
        val text = mutableListOf<Pair<Long, String>>()
        var cancelled = false
        var closed = false
        var queuedBytes = 0L
        var acceptSends = true
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            this.request = request
            this.listener = listener
            return this
        }
        override fun request(): Request = request
        override fun queueSize(): Long = queuedBytes
        override fun send(bytes: ByteString): Boolean {
            if (!acceptSends) return false
            audio.add(nowMs() to bytes.toByteArray())
            return true
        }
        override fun send(text: String): Boolean {
            if (!acceptSends) return false
            this.text.add(nowMs() to text)
            return true
        }
        override fun close(code: Int, reason: String?): Boolean {
            closed = true
            return true
        }
        override fun cancel() {
            cancelled = true
        }
        fun open() = listener.onOpen(
            this,
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("upgrade").build()
        )
        fun message(json: String) = listener.onMessage(this, json)
        fun ack() = message("""{"code":0,"message":"success"}""")
        fun final() = message("""{"code":0,"final":1}""")
        fun sentence(index: Int, slice: Int, text: String) = message("""{"code":0,"result":{"index":$index,"slice_type":$slice,"voice_text_str":"$text"}}""")
        fun closedByServer() {
            listener.onClosing(this, 1000, "closed")
            listener.onClosed(this, 1000, "closed")
        }
        fun failed() = listener.onFailure(this, IllegalStateException("secret URL"), null)
    }
}
