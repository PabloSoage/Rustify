package com.varuna.rustify.dj

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * External AI provider. Calls an OpenAI-compatible endpoint (`/v1/chat/completions` or `/openai`)
 * using `java.net.HttpURLConnection` (no new dependencies).
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
    private val fallback: DjProvider = HeuristicDjProvider()
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

    private fun buildRequestBody(context: DjContext, request: String): String {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", DjPrompt.systemPrompt(context)))
        messages.put(JSONObject().put("role", "user").put("content", DjPrompt.userPrompt(context, request)))
        return JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("temperature", 0.8)
        }.toString()
    }

    private fun postChatCompletions(body: String): String {
        val url = resolveEndpoint(baseUrl)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
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

    /**
     * Accepts base URLs with or without a path. If it already contains `/openai` or
     * `/chat/completions` it is kept as-is; if it ends in `/v1`, `/chat/completions` is appended;
     * otherwise the standard OpenAI layout is assumed.
     */
    private fun resolveEndpoint(base: String): String {
        val b = base.trim().trimEnd('/')
        return when {
            b.endsWith("/openai") -> b
            b.endsWith("/chat/completions") -> b
            b.endsWith("/v1") -> "$b/chat/completions"
            else -> "$b/v1/chat/completions"
        }
    }

    /** Extracts `choices[0].message.content` from the OpenAI-compatible response. */
    private fun extractAssistantContent(raw: String): String {
        val obj = JSONObject(raw)
        val choices = obj.optJSONArray("choices") ?: return ""
        val first = choices.optJSONObject(0) ?: return ""
        return first.optJSONObject("message")?.optString("content")
            ?: first.optString("text", "")
    }

}
