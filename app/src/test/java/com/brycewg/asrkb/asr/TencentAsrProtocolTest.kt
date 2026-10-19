package com.brycewg.asrkb.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TencentAsrProtocolTest {
    @Test
    fun canonicalSigningSortsRawParametersAndExcludesSignature() {
        assertEquals(
            "asr.cloud.tencent.com/asr/v2/1?a=腾讯 +/=&z=last",
            TencentAsrProtocol.canonicalRequest(
                "asr.cloud.tencent.com/asr/v2/1",
                mapOf("z" to "last", "signature" to "excluded", "a" to "腾讯 +/=")
            )
        )
    }

    @Test
    fun hmacSha1MatchesRfc2202Vector() {
        assertEquals(
            "7/zfauXrL6LSdBbV8YTfnCWafHk=",
            TencentAsrProtocol.sign("what do ya want for nothing?", "Jefe")
        )
    }

    @Test
    fun queryEncodingIsUtf8Rfc3986AndNeverFormEncoding() {
        assertEquals("%2B%2F%3D%20%26%3F%25%E8%85%BE%E8%AE%AF", TencentAsrProtocol.encodeQueryComponent("+/= &?%腾讯"))
        assertEquals("abcABC012-._~", TencentAsrProtocol.encodeQueryComponent("abcABC012-._~"))
    }

    @Test
    fun signedUrlUsesRawValuesForSignatureAndEncodedValuesOnWire() {
        val url = TencentAsrProtocol.signedUrl(
            "1250000000",
            "test+id/=",
            "dummy-secret",
            "16k_zh",
            1700000000,
            123456,
            "voice-id"
        )
        assertEquals(
            "wss://asr.cloud.tencent.com/asr/v2/1250000000?" +
                "engine_model_type=16k_zh&expired=1700003600&needvad=1&nonce=123456&" +
                "secretid=test%2Bid%2F%3D&timestamp=1700000000&voice_format=1&voice_id=voice-id&" +
                "signature=h%2BYfo%2BkbDqxhvlPlRmEoD%2BDILIs%3D",
            url
        )
        assertFalse(url.contains("dummy-secret"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAppIdPathInjection() {
        TencentAsrProtocol.signedUrl("1/../../other", "id", "key", "16k_zh", 1, 1, "voice")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejects8kModelsBecauseCaptureIs16k() {
        TencentAsrProtocol.signedUrl("1", "id", "key", "8k_zh", 1, 1, "voice")
    }

    @Test
    fun acceptsRegionalAndVersioned16kModelIdentifiers() {
        for (model in listOf("16k_zh-TW", "16k_zh_en_2.0")) {
            assertTrue(TencentAsrProtocol.signedUrl("1", "id", "key", model, 1, 1, "voice").contains("engine_model_type=$model"))
        }
    }

    @Test
    fun parsesAckPartialSentenceAndFinalMarker() {
        assertTrue(TencentAsrProtocol.parseMessage("""{"code":0,"message":"success","voice_id":"v"}""").isAcknowledgement)
        val message = TencentAsrProtocol.parseMessage(
            """{"code":0,"result":{"index":3,"slice_type":2,"voice_text_str":"你好。"},"final":1}"""
        )
        assertTrue(message.isFinal)
        assertFalse(message.isAcknowledgement)
        assertEquals(TencentAsrSentence(3, 2, "你好。"), message.sentences.single())
    }

    @Test
    fun optionalNullResultIsAcceptedOnAckAndFinal() {
        assertTrue(TencentAsrProtocol.parseMessage("""{"code":0,"result":null}""").isAcknowledgement)
        assertTrue(TencentAsrProtocol.parseMessage("""{"code":0,"final":1,"result":null}""").isFinal)
    }

    @Test
    fun parsesV2SdkSentenceListWithoutMistakingItForAnAck() {
        val message = TencentAsrProtocol.parseMessage(
            """{"code":0,"sentences":{"sentence_list":[{"sentence":"你好。","sentence_id":1,"sentence_type":1,"speaker_id":0},{"sentence":"世界","sentence_id":2,"sentence_type":0,"speaker_id":-1}]}}"""
        )
        assertFalse(message.isAcknowledgement)
        assertEquals(listOf(TencentAsrSentence(1, 2, "你好。"), TencentAsrSentence(2, 1, "世界")), message.sentences)
    }

    @Test
    fun supportsDirectV2ObjectAndArrayAndEmptyResultIsNotAck() {
        val item = """{"sentence":"hello","sentence_id":1,"sentence_type":1}"""
        for (value in listOf(item, "[$item]")) {
            val message = TencentAsrProtocol.parseMessage("""{"code":0,"sentences":$value}""")
            assertFalse(message.isAcknowledgement)
            assertEquals(listOf(TencentAsrSentence(1, 2, "hello")), message.sentences)
        }
        assertFalse(TencentAsrProtocol.parseMessage("""{"code":0,"sentences":{"sentence_list":[]}}""").isAcknowledgement)
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformedV2SentenceIsNotSilentlyDiscarded() {
        TencentAsrProtocol.parseMessage("""{"code":0,"sentences":{"sentence_list":[{"sentence":"bad","sentence_id":1,"sentence_type":9}]}}""")
    }

    @Test
    fun errorParsingDoesNotExposeServerText() {
        assertEquals(TencentAsrMessage(4002), TencentAsrProtocol.parseMessage("""{"code":4002,"message":"secret=value"}"""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingCodeDoesNotAccidentallyAuthenticate() {
        TencentAsrProtocol.parseMessage("""{"message":"success"}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidSentenceIsRejected() {
        TencentAsrProtocol.parseMessage("""{"code":0,"result":{"index":-1,"slice_type":2,"voice_text_str":"bad"}}""")
    }

    @Test
    fun sentencesReplaceInterimsWithoutDuplicatingCommittedText() {
        val result = TencentAsrTranscript()
        assertEquals("你", result.update(TencentAsrSentence(0, 0, "你")))
        assertEquals("你好", result.update(TencentAsrSentence(0, 1, "你好")))
        assertEquals("你好。", result.update(TencentAsrSentence(0, 2, "你好。")))
        assertEquals("你好。", result.update(TencentAsrSentence(0, 1, "你")))
        assertEquals("你好。再见。", result.update(TencentAsrSentence(2, 2, "再见。")))
        assertEquals("你好。世界！再见。", result.update(TencentAsrSentence(1, 2, "世界！")))
        assertEquals("你好。世界！再见。", result.update(TencentAsrSentence(1, 2, "世界！")))
    }

    @Test
    fun emptySentenceCanReplaceAnInterim() {
        val result = TencentAsrTranscript()
        result.update(TencentAsrSentence(0, 1, "hallucination"))
        assertEquals("", result.update(TencentAsrSentence(0, 2, "")))
    }
}
