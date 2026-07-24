package com.varuna.rustify.dj

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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

    private fun prefs(context: Context) =
        context.getSharedPreferences(DjSettings.PREFS, Context.MODE_PRIVATE)

    private fun hiddenIds(context: Context): Set<String> =
        prefs(context).getString(KEY_HIDDEN, "")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
            ?: emptySet()

    fun customProviders(context: Context): List<DjApiProvider> {
        val raw = prefs(context).getString(KEY_CUSTOM, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                DjApiProvider(
                    id = o.optString("id"),
                    label = o.optString("label"),
                    baseUrl = o.optString("baseUrl"),
                    model = o.optString("model"),
                    apiKey = o.optString("apiKey", ""),
                    builtIn = false
                )
            }
        }.getOrDefault(emptyList())
    }

    /** Visible list = non-hidden built-ins + custom ones. */
    fun visibleProviders(context: Context): List<DjApiProvider> {
        val hidden = hiddenIds(context)
        return BUILT_IN.filter { it.id !in hidden } + customProviders(context)
    }

    fun addCustom(context: Context, label: String, baseUrl: String, model: String, apiKey: String) {
        val id = "custom-" + baseUrl.hashCode().toString() + "-" + model.hashCode().toString()
        val list = customProviders(context).filter { it.id != id } +
            DjApiProvider(id, label.ifBlank { model.ifBlank { baseUrl } }, baseUrl.trim(), model.trim(), apiKey.trim(), builtIn = false)
        persistCustom(context, list)
    }

    fun removeProvider(context: Context, provider: DjApiProvider) {
        if (provider.builtIn) {
            val hidden = hiddenIds(context) + provider.id
            prefs(context).edit { putString(KEY_HIDDEN, hidden.joinToString(",")) }
        } else {
            persistCustom(context, customProviders(context).filter { it.id != provider.id })
        }
    }

    private fun persistCustom(context: Context, list: List<DjApiProvider>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id); put("label", p.label); put("baseUrl", p.baseUrl)
                put("model", p.model); put("apiKey", p.apiKey)
            })
        }
        prefs(context).edit { putString(KEY_CUSTOM, arr.toString()) }
    }

    /** Selects a provider → writes base/model/key to the prefs consumed by [DjEngine]. */
    fun select(context: Context, provider: DjApiProvider) {
        prefs(context).edit {
            putString(KEY_SELECTED, provider.id)
            putString(DjSettings.KEY_API_BASE_URL, provider.baseUrl)
            putString(DjSettings.KEY_API_MODEL, provider.model)
            putString(DjSettings.KEY_API_KEY, provider.apiKey)
        }
    }

    fun selectedId(context: Context): String? = prefs(context).getString(KEY_SELECTED, null)

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
            val start = System.nanoTime()
            val conn = (URL(baseUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Rustify-DJ")
            }
            try {
                conn.connect()
                conn.responseCode // forces the round-trip
            } finally {
                conn.disconnect()
            }
            (System.nanoTime() - start) / 1_000_000
        }.getOrNull()
    }
}
