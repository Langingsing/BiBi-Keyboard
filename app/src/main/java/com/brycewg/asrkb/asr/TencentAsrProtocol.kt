package com.brycewg.asrkb.asr

import java.util.Base64
import java.util.TreeMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Tencent real-time ASR v2 wire protocol, independent of Android and transport.
 * https://cloud.tencent.com/document/product/1093/48982
 * https://cloud.tencent.com/document/product/1093/131127
 */
internal object TencentAsrProtocol {
    const val HOST = "asr.cloud.tencent.com"
    const val SAMPLE_RATE = 16000
    const val BYTES_PER_SECOND = SAMPLE_RATE * 2
    const val FRAME_MS = 200L
    const val FRAME_BYTES = 6400
    const val END_MESSAGE = "{\"type\":\"end\"}"

    fun signedUrl(
        appId: String,
        secretId: String,
        secretKey: String,
        engineModelType: String,
        timestamp: Long,
        nonce: Long,
        voiceId: String
    ): String {
        require(appId.matches(Regex("[0-9]+"))) { "Invalid Tencent AppID" }
        require(secretId.isNotBlank() && secretKey.isNotBlank()) { "Missing Tencent credentials" }
        require(engineModelType.matches(Regex("16k_[A-Za-z0-9_.-]+"))) {
            "Tencent streaming requires a 16 kHz model"
        }
        require(timestamp > 0 && nonce in 1..9_999_999_999L)
        require(voiceId.isNotBlank() && voiceId.length <= 128)
        val parameters = sortedMapOf(
            "engine_model_type" to engineModelType,
            "expired" to (timestamp + 3600).toString(),
            "needvad" to "1",
            "nonce" to nonce.toString(),
            "secretid" to secretId,
            "timestamp" to timestamp.toString(),
            "voice_format" to "1",
            "voice_id" to voiceId
        )
        val path = "$HOST/asr/v2/$appId"
        // Sign raw values before URL encoding. In particular, '+' in the signature must be %2B.
        val signature = sign(canonicalRequest(path, parameters), secretKey)
        val query = parameters.entries.joinToString("&") { (key, value) ->
            "${encodeQueryComponent(key)}=${encodeQueryComponent(value)}"
        }
        return "wss://$path?$query&signature=${encodeQueryComponent(signature)}"
    }

    fun canonicalRequest(path: String, parameters: Map<String, String>): String = path + "?" + parameters.filterKeys { it != "signature" }.toSortedMap()
        .entries.joinToString("&") { (key, value) -> "$key=$value" }

    fun sign(canonicalRequest: String, secretKey: String): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secretKey.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        return Base64.getEncoder().encodeToString(mac.doFinal(canonicalRequest.toByteArray(Charsets.UTF_8)))
    }

    fun encodeQueryComponent(value: String): String = buildString {
        val hex = "0123456789ABCDEF"
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val n = byte.toInt() and 0xff
            if (n in 65..90 || n in 97..122 || n in 48..57 || n == 45 || n == 46 || n == 95 || n == 126) {
                append(n.toChar())
            } else {
                append('%').append(hex[n ushr 4]).append(hex[n and 15])
            }
        }
    }

    fun parseMessage(text: String): TencentAsrMessage {
        val root = Json.parseToJsonElement(text) as? JsonObject
            ?: throw IllegalArgumentException("Invalid Tencent response")
        fun number(obj: JsonObject, key: String): Int = (obj[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            ?: throw IllegalArgumentException("Missing Tencent numeric field")
        val code = number(root, "code")
        // Never propagate untrusted server messages, which may echo signed URLs or credentials.
        if (code != 0) return TencentAsrMessage(code = code)
        val final = if (root.containsKey("final")) number(root, "final") else 0
        require(final in 0..1) { "Invalid Tencent final flag" }
        val legacy = root["result"]?.takeUnless { it is JsonNull }
        val v2 = root["sentences"]?.takeUnless { it is JsonNull }
        val sentences = if (v2 != null) {
            // Official Java SDK: sentences: { sentence_list: [ SentenceItem, ... ] }.
            // Also tolerate the direct object/array representations used by API documentation.
            val items = when (v2) {
                is JsonArray -> v2
                is JsonObject -> if (v2.containsKey("sentence_list")) {
                    v2["sentence_list"] as? JsonArray
                        ?: throw IllegalArgumentException("Invalid Tencent sentence list")
                } else {
                    JsonArray(listOf(v2))
                }
                else -> throw IllegalArgumentException("Invalid Tencent sentences")
            }
            items.map { value ->
                val obj = value as? JsonObject ?: throw IllegalArgumentException("Invalid Tencent sentence")
                val index = number(obj, "sentence_id")
                val type = number(obj, "sentence_type")
                val transcript = (obj["sentence"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: throw IllegalArgumentException("Missing Tencent sentence text")
                require(index >= 0 && type in 0..1) { "Invalid Tencent sentence" }
                TencentAsrSentence(index, if (type == 1) 2 else 1, transcript)
            }
        } else if (legacy != null) {
            val obj = legacy as? JsonObject ?: throw IllegalArgumentException("Invalid Tencent result")
            val index = number(obj, "index")
            val sliceType = number(obj, "slice_type")
            val transcript = (obj["voice_text_str"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("Missing Tencent transcript")
            require(index >= 0 && sliceType in 0..2) { "Invalid Tencent sentence" }
            listOf(TencentAsrSentence(index, sliceType, transcript))
        } else {
            emptyList()
        }
        return TencentAsrMessage(
            code = code,
            isFinal = final == 1,
            sentences = sentences,
            hasResult = legacy != null || v2 != null
        )
    }
}

internal data class TencentAsrSentence(val index: Int, val sliceType: Int, val text: String)
internal data class TencentAsrMessage(
    val code: Int,
    val isFinal: Boolean = false,
    val sentences: List<TencentAsrSentence> = emptyList(),
    val hasResult: Boolean = false
) {
    val isAcknowledgement: Boolean get() = code == 0 && !isFinal && !hasResult
}

/** Sentence indexes are stable identities, not append-only deltas. */
internal class TencentAsrTranscript {
    private val sentences = TreeMap<Int, TencentAsrSentence>()

    fun update(sentence: TencentAsrSentence): String {
        val old = sentences[sentence.index]
        // A delayed interim result must never replace a committed sentence.
        if (old?.sliceType != 2 || sentence.sliceType == 2) sentences[sentence.index] = sentence
        return text()
    }

    fun text(): String = sentences.values.joinToString("") { it.text }.trim()
}
