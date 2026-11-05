// sharedUi/src/commonMain/kotlin/com/varuna/rustify/dj/OnlineDjTts.kt
package com.varuna.rustify.dj

import com.varuna.rustify.platform.IO
import com.varuna.rustify.platform.formUrlEncode
import com.varuna.rustify.platform.httpClient
import com.varuna.rustify.ui.AppPlatform
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Shared fetching and health-checking for online DJ voice engines (Pollinations, Google Translate,
 * and OpenAI-compatible TTS).
 *
 * Each engine outputs MP3 audio:
 *  - On iOS, played directly via `AVAudioPlayer`.
 *  - On Desktop, decoded to WAV via [com.varuna.rustify.bridge.NativeEngine.decodeToWav] and played via `AudioSystem`.
 */
object OnlineDjTts {

    private const val UA = "Mozilla/5.0 (Linux; Android 12) Rustify"

    /**
     * Downloads speech audio as an MP3 byte array for the specified [engine] ("pollinations", "gtranslate", "openai").
     * Returns null if the request fails or does not contain valid audio.
     */
    suspend fun fetchAudio(platform: AppPlatform, engine: String, text: String): ByteArray? = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext null
        when (engine) {
            "pollinations" -> fetchPollinations(platform, text)
            "gtranslate" -> fetchGoogleTranslate(platform, text)
            "openai" -> fetchOpenAi(platform, text)
            else -> null
        }
    }

    private suspend fun fetchPollinations(platform: AppPlatform, text: String): ByteArray? {
        val voice = DjSettings.voiceCloudVoice(platform).ifBlank { "alloy" }
        val enc = formUrlEncode(text).replace("+", "%20")
        val url = "${DjSettings.POLLINATIONS_TTS_BASE}/$enc?model=openai-audio&voice=$voice&referrer=rustify"
        val client = httpClient {
            install(HttpTimeout) {
                connectTimeoutMillis = 6_000
                socketTimeoutMillis = 12_000
                requestTimeoutMillis = 15_000
            }
        }
        return runCatching {
            val response = client.get(url) {
                header("User-Agent", UA)
                header("Accept", "audio/mpeg, audio/*;q=0.9, */*;q=0.5")
                header("Referer", "https://rustify.app/")
            }
            if (!response.status.isSuccess()) return@runCatching null
            val bytes = response.body<ByteArray>()
            if (bytes.size >= 1024 && looksLikeAudio(bytes)) bytes else null
        }.getOrNull()
    }

    private suspend fun fetchGoogleTranslate(platform: AppPlatform, text: String): ByteArray? {
        val tl = googleTtsLang(platform)
        val chunks = chunkForTts(text, 190)
        if (chunks.isEmpty()) return null
        val client = httpClient {
            install(HttpTimeout) {
                connectTimeoutMillis = 6_000
                socketTimeoutMillis = 12_000
                requestTimeoutMillis = 15_000
            }
        }
        return runCatching {
            val totalBytes = ArrayList<ByteArray>()
            for (c in chunks) {
                val enc = formUrlEncode(c).replace("+", "%20")
                val url = "https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&tl=$tl&q=$enc"
                val response = client.get(url) {
                    header("User-Agent", UA)
                    header("Referer", "https://translate.google.com/")
                }
                if (!response.status.isSuccess()) return@runCatching null
                val bytes = response.body<ByteArray>()
                totalBytes.add(bytes)
            }
            val totalLen = totalBytes.sumOf { it.size }
            if (totalLen < 512) return@runCatching null
            val combined = ByteArray(totalLen)
            var offset = 0
            for (b in totalBytes) {
                b.copyInto(combined, destinationOffset = offset)
                offset += b.size
            }
            if (looksLikeAudio(combined)) combined else null
        }.getOrNull()
    }

    private suspend fun fetchOpenAi(platform: AppPlatform, text: String): ByteArray? {
        val endpoint = DjSettings.voiceCloudUrl(platform).ifBlank { return null }
        val key = DjSettings.voiceCloudKey(platform)
        val voice = DjSettings.voiceCloudVoice(platform)
        val client = httpClient {
            install(HttpTimeout) {
                connectTimeoutMillis = 8_000
                socketTimeoutMillis = 15_000
                requestTimeoutMillis = 20_000
            }
        }
        val body = buildJsonObject {
            put("model", "tts-1")
            put("input", text)
            put("voice", voice)
            put("response_format", "mp3")
        }.toString()

        return runCatching {
            val response = client.post(endpoint) {
                contentType(ContentType.Application.Json)
                if (key.isNotBlank()) header("Authorization", "Bearer $key")
                setBody(body)
            }
            if (!response.status.isSuccess()) return@runCatching null
            val bytes = response.body<ByteArray>()
            if (bytes.size >= 512 && looksLikeAudio(bytes)) bytes else null
        }.getOrNull()
    }

    /** Health ping for a Pollinations voice: measures latency in ms or null on error. */
    suspend fun probeVoice(voice: String): Long? = withContext(Dispatchers.IO) {
        runCatching {
            val mark = TimeSource.Monotonic.markNow()
            val enc = formUrlEncode("Hi").replace("+", "%20")
            val url = "${DjSettings.POLLINATIONS_TTS_BASE}/$enc?model=openai-audio&voice=$voice&referrer=rustify"
            val client = httpClient {
                install(HttpTimeout) {
                    connectTimeoutMillis = 5_000
                    socketTimeoutMillis = 10_000
                    requestTimeoutMillis = 12_000
                }
            }
            val response = client.get(url) {
                header("User-Agent", UA)
                header("Accept", "audio/mpeg, audio/*;q=0.9, */*;q=0.5")
                header("Referer", "https://rustify.app/")
            }
            if (response.status.isSuccess()) {
                val bytes = response.body<ByteArray>()
                if (bytes.size >= 512 && looksLikeAudio(bytes)) mark.elapsedNow().inWholeMilliseconds else null
            } else null
        }.getOrNull()
    }

    /** Health ping for Google Translate TTS: measures latency in ms or null on error. */
    suspend fun probeGoogleTranslate(platform: AppPlatform): Long? = withContext(Dispatchers.IO) {
        runCatching {
            val mark = TimeSource.Monotonic.markNow()
            val tl = googleTtsLang(platform)
            val enc = formUrlEncode("Hi").replace("+", "%20")
            val url = "https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&tl=$tl&q=$enc"
            val client = httpClient {
                install(HttpTimeout) {
                    connectTimeoutMillis = 5_000
                    socketTimeoutMillis = 8_000
                    requestTimeoutMillis = 10_000
                }
            }
            val response = client.get(url) {
                header("User-Agent", UA)
                header("Referer", "https://translate.google.com/")
            }
            if (response.status.isSuccess()) mark.elapsedNow().inWholeMilliseconds else null
        }.getOrNull()
    }

    /** `tl` language code for Google Translate TTS. */
    fun googleTtsLang(platform: AppPlatform): String {
        val lang = DjSettings.voiceLanguage(platform)
        return lang.substringBefore('-').lowercase().ifBlank { "en" }
    }

    /** Splits [text] into chunks of ≤[maxLen] chars at word boundaries. */
    fun chunkForTts(text: String, maxLen: Int): List<String> {
        val t = text.trim()
        if (t.isBlank()) return emptyList()
        if (t.length <= maxLen) return listOf(t)
        val out = ArrayList<String>()
        var cur = StringBuilder()
        for (word in t.split(" ")) {
            if (cur.isNotEmpty() && cur.length + 1 + word.length > maxLen) {
                out.add(cur.toString())
                cur = StringBuilder()
            }
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(word)
            while (cur.length > maxLen) {
                out.add(cur.substring(0, maxLen))
                cur = StringBuilder(cur.substring(maxLen))
            }
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    /** Heuristic check that the received byte buffer is audio and not an error response. */
    fun looksLikeAudio(b: ByteArray): Boolean {
        if (b.size < 4) return false
        // MP3 with ID3 header
        if (b[0] == 'I'.code.toByte() && b[1] == 'D'.code.toByte() && b[2] == '3'.code.toByte()) return true
        // MP3 frame sync: 11 bits set to 1
        if (b[0] == 0xFF.toByte() && (b[1].toInt() and 0xE0) == 0xE0) return true
        // Ogg header: OggS
        if (b[0] == 'O'.code.toByte() && b[1] == 'g'.code.toByte() && b[2] == 'g'.code.toByte() && b[3] == 'S'.code.toByte()) return true
        // WAV header: RIFF
        if (b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte()) return true
        // JSON or XML / HTML error responses
        val c0 = b[0].toInt().toChar()
        if (c0 == '{' || c0 == '<') return false
        if (b.size >= 5 && b.decodeToString(0, 5).equals("Error", ignoreCase = true)) return false
        return true
    }
}
