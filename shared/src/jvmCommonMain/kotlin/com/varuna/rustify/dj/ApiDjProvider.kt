package com.varuna.rustify.dj

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * External AI provider. Calls an OpenAI-compatible endpoint (`/v1/chat/completions` or `/openai`)
 * using `java.net.HttpURLConnection` (no new dependencies). JVM-only for that reason, so it lives in
 * the source set Android and the desktop share; the JSON is kotlinx since 5.x, as on every target.
 *
 * Defaults to Pollinations AI (keyless, free, best-effort). [apiKey] is optional: when blank, no
 * Authorization header is sent (public endpoint). No third-party private key is embedded.
 *
 * Design:
 *  - system prompt (DJ role) + user context + NL request.
 *  - The model is asked to respond with JSON `{intro, seeds:[...], queries:[...]}` — not final URIs.
 *  - That response is parsed into a [DjPlan] with [DjSeed]s; the [DjEngine] resolves them to real tracks.
 *  - On any failure (network, invalid JSON, rate limit) it degrades to the [fallback] heuristic.
 */
class ApiDjProvider(
    private val baseUrl: String,
    private val model: String,
    private val apiKey: String,
    private val fallback: DjProvider = HeuristicDjProvider(),
    /** How long to wait for the answer: a model on this computer's CPU writes slower than a service. */
    private val readTimeoutMs: Int = 30_000
) : DjProvider {

    override suspend fun plan(context: DjContext, request: String): DjPlan = withContext(Dispatchers.IO) {
        runCatching {
            val body = buildRequestBody(context, request)
            val raw = postChatCompletions(body)
            val content = extractAssistantContent(raw)
            DjPrompt.parsePlan(content) ?: fallback.plan(context, request)
        }.getOrElse {
            // Graceful degradation: if the API fails, use the offline heuristic.
            fallback.plan(context, request)
        }
    }

    private fun buildRequestBody(context: DjContext, request: String): String = buildJsonObject {
        put("model", model)
        putJsonArray("messages") {
            addJsonObject {
                put("role", "system")
                put("content", DjPrompt.systemPrompt(context))
            }
            addJsonObject {
                put("role", "user")
                put("content", DjPrompt.userPrompt(context, request))
            }
        }
        put("temperature", 0.8)
    }.toString()

    private fun postChatCompletions(body: String): String {
        val url = resolveEndpoint(baseUrl)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        conn.disconnect()
        if (code !in 200..299) throw RuntimeException("DJ API HTTP $code: ${text.take(200)}")
        return text
    }

    /** Extracts `choices[0].message.content` from the OpenAI-compatible response. */
    private fun extractAssistantContent(raw: String): String {
        val obj = Json.parseToJsonElement(raw) as? JsonObject ?: return ""
        val choices = obj["choices"] as? JsonArray ?: return ""
        val first = choices.firstOrNull() as? JsonObject ?: return ""
        val message = first["message"] as? JsonObject
        return if (message != null) message["content"].text() else first["text"].text()
    }

    internal companion object {
        /**
         * Accepts base URLs with or without a path. If it already contains `/openai` or
         * `/chat/completions` it is kept as-is; if it ends in `/v1`, `/chat/completions` is appended;
         * otherwise the standard OpenAI layout is assumed. The desktop's chat asks the same endpoint.
         */
        fun resolveEndpoint(base: String): String {
            val b = base.trim().trimEnd('/')
            return when {
                b.endsWith("/openai") -> b
                b.endsWith("/chat/completions") -> b
                b.endsWith("/v1") -> "$b/chat/completions"
                else -> "$b/v1/chat/completions"
            }
        }
    }

    /** org.json's `optString`, which this read before 5.x: absent or null is "". */
    private fun kotlinx.serialization.json.JsonElement?.text(): String = when (this) {
        null, JsonNull -> ""
        is JsonPrimitive -> content
        else -> toString()
    }

}
