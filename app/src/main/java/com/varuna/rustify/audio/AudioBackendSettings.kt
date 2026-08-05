package com.varuna.rustify.audio

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the order and activation state of the audio backends.
 *
 * There are two separate lists (streaming vs download), each reorderable and with
 * its own set of toggles. They are stored as two independent JSON arrays in
 * `SharedPreferences("rustify_settings")`:
 *
 * ```json
 * // key: "audio_backends_stream_order"  |  "audio_backends_download_order"
 * [ { "id": "ytdlp", "enabled": true } ]
 * ```
 *
 * Forward compatibility: when reading, any *known* provider missing from the JSON
 * is appended at the end **disabled**. Ids in the JSON that are no longer known
 * providers are ignored (removing a provider does not break existing settings).
 */
object AudioBackendSettings {

    private const val PREFS = "rustify_settings"
    const val KEY_STREAM = "audio_backends_stream_order"
    const val KEY_DOWNLOAD = "audio_backends_download_order"

    /** An entry in the backend list: stable id + activation flag. */
    data class BackendEntry(val id: String, val enabled: Boolean)

    /**
     * Providers enabled on a fresh install. Everything else ships off and is opt-in from Settings:
     * Deezer needs an ARL before it can do anything, and public Invidious instances are routinely
     * blocked by YouTube/Cloudflare (they answer /stats so they look healthy, then fail to resolve
     * audio). Leaving them on by default only produced slow, confusing fallbacks.
     */
    private val DEFAULT_ON = setOf(YtDlpAudioSource.ID)

    /**
     * Reads the stored order for [key], appending any missing [knownIds] at the end
     * as `enabled=false`. Never throws: if the JSON is corrupt, it falls back to the default.
     */
    fun loadOrder(context: Context, key: String, knownIds: List<String>): List<BackendEntry> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(key, null)
        val parsed = mutableListOf<BackendEntry>()
        // Sane default: on a fresh install only the providers in DEFAULT_ON start enabled, so the
        // user hears audio without touching Settings but isn't silently routed through backends that
        // need setup (Deezer needs an ARL) or that are unreliable in practice (public Invidious
        // instances are widely blocked by YouTube/Cloudflare). Appending new providers as disabled
        // only applies when prefs already existed, so upgrades preserve the user's choices.
        val isFirstRun = raw.isNullOrBlank()
        if (!isFirstRun) {
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i)
                    if (o != null) {
                        val id = o.optString("id", "")
                        if (id.isNotBlank()) parsed.add(BackendEntry(id, o.optBoolean("enabled", false)))
                    }
                }
            }
        }
        // Append missing known providers: on first run only the DEFAULT_ON ones start enabled;
        // on upgrade anything new arrives disabled so it can't change existing behaviour.
        val present = parsed.map { it.id }.toMutableSet()
        for (id in knownIds) {
            if (id !in present) {
                parsed.add(BackendEntry(id, isFirstRun && id in DEFAULT_ON))
                present.add(id)
            }
        }
        // Drop unknown ids (providers removed in future versions).
        return parsed.filter { it.id in knownIds }
    }

    fun saveOrder(context: Context, key: String, entries: List<BackendEntry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().apply { put("id", e.id); put("enabled", e.enabled) })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putString(key, arr.toString()) }
    }

    /** Enabled ids in priority order — what the chain consumes. */
    fun enabledIds(order: List<BackendEntry>): List<String> =
        order.filter { it.enabled }.map { it.id }
}