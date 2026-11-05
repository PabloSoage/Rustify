package com.varuna.rustify.dj

import com.varuna.rustify.platform.httpClient
import com.varuna.rustify.ui.AppPlatform
import com.varuna.rustify.util.optString
import com.varuna.rustify.util.parseArray
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.Dispatchers
import com.varuna.rustify.platform.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlin.time.TimeSource

/**
 * Catalog of AI providers for the DJ's API mode.
 *
 * All built-ins are free and require no private key (public OpenAI-compatible endpoints). The user
 * can pick one, hide the ones they do not want, and add custom providers (base URL + model +
 * optional key). The selection is written to the prefs that [DjSettings] already reads
 * (`dj_api_base_url` / `dj_api_model` / `dj_api_key`), so [DjEngine] does not change.
 *
 * Pollinations model ids are best-effort (its catalog may change); if one stops responding, the
 * latency indicator flags it and the user can hide it or use another.
 */
data class DjApiProvider(
    val id: String,
    val label: String,
    val baseUrl: String,
    val model: String,
    val apiKey: String = "",
    val builtIn: Boolean = true
)

object DjProviders {
    const val KEY_SELECTED = "dj_provider_id"
    const val KEY_CUSTOM = "dj_custom_providers"   // JSON array of the user's providers
    const val KEY_HIDDEN = "dj_hidden_providers"   // hidden built-in ids, comma-separated

    /**
     * Keyless built-ins. Ordered from fastest to most capable (Pollinations exposes several models
     * through the same OpenAI-compatible endpoint; all free and keyless).
     */
    val BUILT_IN: List<DjApiProvider> = listOf(
        DjApiProvider("poll-openai-fast", "OpenAI Fast (Pollinations)", "https://text.pollinations.ai/openai", "openai-fast"),
        DjApiProvider("poll-openai", "OpenAI (Pollinations)", "https://text.pollinations.ai/openai", "openai"),
        DjApiProvider("poll-mistral", "Mistral (Pollinations)", "https://text.pollinations.ai/openai", "mistral"),
        DjApiProvider("poll-llama", "Llama (Pollinations)", "https://text.pollinations.ai/openai", "llama"),
        DjApiProvider("poll-deepseek", "DeepSeek (Pollinations)", "https://text.pollinations.ai/openai", "deepseek"),
    )

    private fun prefs(platform: AppPlatform) = platform.prefs(DjSettings.PREFS)

    private fun hiddenIds(platform: AppPlatform): Set<String> =
        (prefs(platform).getString(KEY_HIDDEN) ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    fun customProviders(platform: AppPlatform): List<DjApiProvider> {
        val raw = prefs(platform).getString(KEY_CUSTOM) ?: return emptyList()
        return runCatching {
            parseArray(raw).map { element ->
                val o = element as JsonObject
                DjApiProvider(
                    id = o["id"].optString(),
                    label = o["label"].optString(),
                    baseUrl = o["baseUrl"].optString(),
                    model = o["model"].optString(),
                    apiKey = o["apiKey"].optString(""),
                    builtIn = false
                )
            }
        }.getOrDefault(emptyList())
    }

    /** Visible list = non-hidden built-ins + custom ones. */
    fun visibleProviders(platform: AppPlatform): List<DjApiProvider> {
        val hidden = hiddenIds(platform)
        return BUILT_IN.filter { it.id !in hidden } + customProviders(platform)
    }

    fun addCustom(platform: AppPlatform, label: String, baseUrl: String, model: String, apiKey: String) {
        val id = "custom-" + baseUrl.hashCode().toString() + "-" + model.hashCode().toString()
        val list = customProviders(platform).filter { it.id != id } +
            DjApiProvider(id, label.ifBlank { model.ifBlank { baseUrl } }, baseUrl.trim(), model.trim(), apiKey.trim(), builtIn = false)
        persistCustom(platform, list)
    }

    fun removeProvider(platform: AppPlatform, provider: DjApiProvider) {
        if (provider.builtIn) {
            val hidden = hiddenIds(platform) + provider.id
            prefs(platform).putString(KEY_HIDDEN, hidden.joinToString(","))
        } else {
            persistCustom(platform, customProviders(platform).filter { it.id != provider.id })
        }
    }

    private fun persistCustom(platform: AppPlatform, list: List<DjApiProvider>) {
        val arr = buildJsonArray {
            list.forEach { p ->
                addJsonObject {
                    put("id", p.id); put("label", p.label); put("baseUrl", p.baseUrl)
                    put("model", p.model); put("apiKey", p.apiKey)
                }
            }
        }
        prefs(platform).putString(KEY_CUSTOM, arr.toString())
    }

    /** Selects a provider → writes base/model/key to the prefs consumed by [DjEngine]. */
    fun select(platform: AppPlatform, provider: DjApiProvider) {
        prefs(platform).apply {
            putString(KEY_SELECTED, provider.id)
            putString(DjSettings.KEY_API_BASE_URL, provider.baseUrl)
            putString(DjSettings.KEY_API_MODEL, provider.model)
            putString(DjSettings.KEY_API_KEY, provider.apiKey)
        }
    }

    fun selectedId(platform: AppPlatform): String? = prefs(platform).getString(KEY_SELECTED)

    // ── Latency / congestion indicator ───────────────────────────────────────────────────

    enum class Latency(val ms: Long?) {
        FAST(null), OK(null), SLOW(null), DOWN(null), UNKNOWN(null)
    }

    /** Classifies a measurement in ms into a congestion level. */
    fun classify(ms: Long?): Latency = when {
        ms == null -> Latency.DOWN
        ms < 900 -> Latency.FAST
        ms < 2500 -> Latency.OK
        else -> Latency.SLOW
    }

    /**
     * Measures approximate latency to the provider host (connection + first byte), with a short
     * timeout. Consumes no tokens: it does a lightweight GET to the endpoint (which may respond
     * 4xx/405 — that is fine, we measure the round-trip). Returns ms, or null if there is no timely
     * response.
     */
    suspend fun measureLatency(baseUrl: String): Long? = withContext(Dispatchers.IO) {
        runCatching {
            val start = TimeSource.Monotonic.markNow()
            // Up to the status line, as HttpURLConnection's responseCode was: the body is not read.
            latencyHttp.prepareGet(baseUrl) { header(HttpHeaders.UserAgent, "Rustify-DJ") }.execute { it.status }
            start.elapsedNow().inWholeMilliseconds
        }.getOrNull()
    }

    /** Four seconds to connect and four between bytes, the probe's timeouts before it moved to Ktor. */
    private val latencyHttp by lazy {
        httpClient {
            install(HttpTimeout) {
                connectTimeoutMillis = 4_000
                socketTimeoutMillis = 4_000
            }
        }
    }
}
