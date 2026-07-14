package com.varuna.rustify.dj

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * E90 — Catálogo de proveedores de IA para el modo API del DJ.
 *
 * Todos los built-in son **gratuitos y sin clave privada** (endpoints OpenAI-compatible públicos).
 * El usuario puede: elegir uno, ocultar los que no quiera, y añadir proveedores personalizados
 * (base URL + modelo + key opcional). La selección se materializa en las prefs que ya lee
 * [DjSettings] (`dj_api_base_url` / `dj_api_model` / `dj_api_key`), así que [DjEngine] no cambia.
 *
 * ⚠️ Los ids de modelo de Pollinations son best-effort (su catálogo puede cambiar); si uno deja de
 * responder, el indicador de latencia lo marcará y el usuario puede ocultarlo / usar otro.
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
    const val KEY_CUSTOM = "dj_custom_providers"   // JSON array de proveedores del usuario
    const val KEY_HIDDEN = "dj_hidden_providers"   // ids built-in ocultados, separados por coma

    /**
     * Built-in keyless. Ordenados de **más rápido** a **más capaz** (Pollinations expone varios
     * modelos por el mismo endpoint OpenAI-compatible; todos gratis y sin key).
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

    /** Lista visible = built-in no ocultados + personalizados. */
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
            prefs(context).edit().putString(KEY_HIDDEN, hidden.joinToString(",")).apply()
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
        prefs(context).edit().putString(KEY_CUSTOM, arr.toString()).apply()
    }

    /** Selecciona un proveedor → escribe base/model/key en las prefs que consume [DjEngine]. */
    fun select(context: Context, provider: DjApiProvider) {
        prefs(context).edit()
            .putString(KEY_SELECTED, provider.id)
            .putString(DjSettings.KEY_API_BASE_URL, provider.baseUrl)
            .putString(DjSettings.KEY_API_MODEL, provider.model)
            .putString(DjSettings.KEY_API_KEY, provider.apiKey)
            .apply()
    }

    fun selectedId(context: Context): String? = prefs(context).getString(KEY_SELECTED, null)

    // ── Indicador de latencia / congestión ──────────────────────────────────────────────

    enum class Latency(val ms: Long?) {
        FAST(null), OK(null), SLOW(null), DOWN(null), UNKNOWN(null)
    }

    /** Clasifica una medición en ms a un nivel de congestión. */
    fun classify(ms: Long?): Latency = when {
        ms == null -> Latency.DOWN
        ms < 900 -> Latency.FAST
        ms < 2500 -> Latency.OK
        else -> Latency.SLOW
    }

    /**
     * Mide latencia aproximada al host del proveedor (conexión + primer byte), con timeout corto.
     * No consume tokens: hace un GET ligero al endpoint (que puede responder 4xx/405 — nos vale,
     * medimos el round-trip). Devuelve ms o null si no hay respuesta a tiempo.
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
                conn.responseCode // fuerza el round-trip
            } finally {
                conn.disconnect()
            }
            (System.nanoTime() - start) / 1_000_000
        }.getOrNull()
    }
}
