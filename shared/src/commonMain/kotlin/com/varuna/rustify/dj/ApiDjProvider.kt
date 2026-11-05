package com.varuna.rustify.dj

import com.varuna.rustify.platform.httpClient
import com.varuna.rustify.platform.systemHttpUserAgent
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import kotlinx.coroutines.Dispatchers
import com.varuna.rustify.platform.IO
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

/**
 * External AI provider. Calls an OpenAI-compatible endpoint (`/v1/chat/completions` or `/openai`)
 * over the platform's Ktor client (`platform/Http.kt`), with the request `HttpURLConnection` made
 * before: the same headers, the system's own User-Agent, fifteen seconds to connect. The JSON is
 * kotlinx since 5.x, as on every target.
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

    private suspend fun postChatCompletions(body: String): String {
        val url = resolveEndpoint(baseUrl)
        val response = http.post(url) {
            timeout {
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = readTimeoutMs.toLong()
            }
            header(HttpHeaders.UserAgent, systemHttpUserAgent())
            header(HttpHeaders.Accept, "application/json")
            if (apiKey.isNotBlank()) header(HttpHeaders.Authorization, "Bearer $apiKey")
            setBody(TextContent(body, ContentType.Application.Json))
        }
        val code = response.status.value
        // The answer, or what the server said about refusing it, as UTF-8.
        val text = response.body<ByteArray>().decodeToString()
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

        // One client for every provider: the timeouts are set per request, and each OkHttp client
        // keeps its own threads.
        private val http by lazy { httpClient { install(HttpTimeout) } }
    }

    /** org.json's `optString`, which this read before 5.x: absent or null is "". */
    private fun kotlinx.serialization.json.JsonElement?.text(): String = when (this) {
        null, JsonNull -> ""
        is JsonPrimitive -> content
        else -> toString()
    }

}
