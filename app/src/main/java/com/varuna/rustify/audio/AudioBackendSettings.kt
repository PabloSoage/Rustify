package com.varuna.rustify.audio

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/**
 * E60 — Persistencia del orden y activación de los backends de audio.
 *
 * El usuario eligió **dos listas separadas** (streaming vs descarga), cada una
 * reordenable y con su propio set de toggles. Se guardan como dos JSON arrays
 * independientes en `SharedPreferences("rustify_settings")`:
 *
 * ```json
 * // key: "audio_backends_stream_order"  |  "audio_backends_download_order"
 * [ { "id": "ytdlp", "enabled": true } ]
 * ```
 *
 * Compatibilidad hacia adelante: al leer, cualquier provider *conocido* que no
 * aparezca en el JSON se anexa al final **desactivado** (Fase 0 = idéntica a hoy:
 * sólo `ytdlp` activo). Los ids del JSON que ya no sean providers conocidos se
 * ignoran (no rompen la UI vieja si se borra un provider).
 */
object AudioBackendSettings {

    private const val PREFS = "rustify_settings"
    const val KEY_STREAM = "audio_backends_stream_order"
    const val KEY_DOWNLOAD = "audio_backends_download_order"

    /** Una entrada de la lista de backends: id estable + flag de activación. */
    data class BackendEntry(val id: String, val enabled: Boolean)

    /**
     * Lee el orden guardado para [key], anexando los [knownIds] que falten al final
     * como `enabled=false`. Nunca lanza: si el JSON está corrupto, cae al default.
     */
    fun loadOrder(context: Context, key: String, knownIds: List<String>): List<BackendEntry> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(key, null)
        val parsed = mutableListOf<BackendEntry>()
        // Default sane: si nunca hubo prefs guardadas, todos los providers conocidos
        // arrancan ACTIVOS (ytdlp es el único backend hoy → el usuario oye audio sin
        // tocar Ajustes). Anexar nuevos providers como desactivados sólo aplica si ya
        // había prefs (forward-compat ante upgrades sin reventar la elección del usuario).
        val defaultEnabled = raw.isNullOrBlank()
        if (!defaultEnabled) {
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
        // Anexa providers conocidos ausentes: activos si es primera ejecución, desactivados si upgrade.
        val present = parsed.map { it.id }.toMutableSet()
        for (id in knownIds) {
            if (id !in present) { parsed.add(BackendEntry(id, defaultEnabled)); present.add(id) }
        }
        // Filtra ids desconocidos (providers eliminados en versiones futuras).
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

    /** IDs activos en orden de prioridad — lo que consume la cadena. */
    fun enabledIds(order: List<BackendEntry>): List<String> =
        order.filter { it.enabled }.map { it.id }
}