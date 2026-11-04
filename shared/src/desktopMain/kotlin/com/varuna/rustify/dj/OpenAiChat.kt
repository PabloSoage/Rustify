// shared/src/desktopMain/kotlin/com/varuna/rustify/dj/OpenAiChat.kt
package com.varuna.rustify.dj

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * An OpenAI-compatible chat completion, streamed: `"stream": true`, and the answer read as it comes
 * from the server-sent events' `data:` lines (`choices[0].delta.content`, until `data: [DONE]`). For
 * the desktop's chat with the DJ's model and its lyrics translation, against what the DJ asks: a
 * model server on this computer ([LocalLlmDetector], [LocalModelManager]) or the service set in the
 * DJ's settings, through the same endpoint rules as [ApiDjProvider].
 *
 * A server that ignores `stream` and answers with the whole completion at once is read too.
 */
class OpenAiChat(
    private val baseUrl: String,
    private val model: String,
    private val apiKey: String = "",
    /** Between two pieces of the answer: a model on this computer's CPU can take a while to start. */
    private val readTimeoutMs: Int = 120_000,
) {
    /** One message of the conversation: `system`, `user` or `assistant`. */
    data class Message(val role: String, val content: String)

    /**
     * Sends [messages] and returns the whole answer; [onText] gets each piece as it arrives, on an IO
     * thread. Throws when the server refuses (with its status and what it said) or cannot be reached.
     * Cancelling the caller closes the connection.
     */
    suspend fun stream(
        messages: List<Message>,
        temperature: Double = 0.7,
        maxTokens: Int? = null,
        onText: (String) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        val conn = (URL(ApiDjProvider.resolveEndpoint(baseUrl)).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream, application/json")
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
        }
        // A read blocked on the socket does not see a cancellation; closing the connection ends it.
        val closer = launch {
            try { awaitCancellation() } finally { conn.disconnect() }
        }
        try {
            conn.outputStream.use { it.write(requestBody(model, messages, temperature, maxTokens).toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val said = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                throw IOException("HTTP $code: ${errorMessage(said) ?: said.take(200)}")
            }
            val answer = StringBuilder()
            val rest = StringBuilder()
            var events = false
            val reader = conn.inputStream.bufferedReader(Charsets.UTF_8)
            try {
                var done = false
                while (!done) {
                    ensureActive()
                    val line = reader.readLine() ?: break
                    when (val event = parseEvent(line)) {
                        is Event.Text -> {
                            events = true
                            answer.append(event.text)
                            onText(event.text)
                        }
                        Event.Done -> { events = true; done = true }
                        is Event.Failed -> throw IOException(event.message)
                        Event.Empty -> events = true
                        Event.NotData -> if (!events) rest.append(line).append('\n')
                    }
                }
            } finally {
                reader.close()
            }
            if (!events) {
                // Not a stream: the whole completion in one body.
                val whole = completionContent(rest.toString())
                    ?: throw IOException("not an OpenAI-compatible answer: ${rest.take(200)}")
                if (whole.isNotEmpty()) onText(whole)
                answer.append(whole)
            }
            answer.toString()
        } finally {
            closer.cancel()
            conn.disconnect()
        }
    }

    /** What one line of the stream says. */
    sealed interface Event {
        data class Text(val text: String) : Event
        data object Done : Event
        data class Failed(val message: String) : Event

        /** A `data:` line with nothing to show: the role, the finish reason, the usage, a keep-alive. */
        data object Empty : Event

        /** Not a `data:` line: an `event:` or `id:` field, a blank separator, or a body that is no stream. */
        data object NotData : Event
    }

    companion object {
        fun requestBody(model: String, messages: List<Message>, temperature: Double, maxTokens: Int?): String = buildJsonObject {
            put("model", model)
            putJsonArray("messages") {
                messages.forEach { m ->
                    addJsonObject {
                        put("role", m.role)
                        put("content", m.content)
                    }
                }
            }
            put("temperature", temperature)
            if (maxTokens != null) put("max_tokens", maxTokens)
            put("stream", true)
        }.toString()

        /** One line of the server-sent events. */
        fun parseEvent(line: String): Event {
            if (!line.startsWith("data:")) return Event.NotData
            val data = line.removePrefix("data:").trim()
            if (data.isEmpty()) return Event.Empty
            if (data == "[DONE]") return Event.Done
            val obj = runCatching { Json.parseToJsonElement(data) }.getOrNull() as? JsonObject ?: return Event.Empty
            errorMessage(obj)?.let { return Event.Failed(it) }
            val choice = (obj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return Event.Empty
            // `delta` while streaming; some servers send the last piece as a `message`.
            val piece = ((choice["delta"] ?: choice["message"]) as? JsonObject)?.get("content").string()
                ?: choice["text"].string()
            return if (piece.isNullOrEmpty()) Event.Empty else Event.Text(piece)
        }

        /** `choices[0].message.content` of a completion that was not streamed; null when it is not one. */
        fun completionContent(body: String): String? {
            val obj = runCatching { Json.parseToJsonElement(body.trim()) }.getOrNull() as? JsonObject ?: return null
            val choice = (obj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
            return (choice["message"] as? JsonObject)?.get("content").string() ?: choice["text"].string() ?: ""
        }

        /** What an error body says: OpenAI's `{"error":{"message":…}}`, or a plain `{"error":"…"}` (Ollama). */
        fun errorMessage(body: String): String? =
            (runCatching { Json.parseToJsonElement(body.trim()) }.getOrNull() as? JsonObject)?.let { errorMessage(it) }

        private fun errorMessage(obj: JsonObject): String? = when (val e = obj["error"]) {
            null -> null
            is JsonObject -> e["message"].string() ?: e.toString()
            else -> e.string() ?: e.toString()
        }

        private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
