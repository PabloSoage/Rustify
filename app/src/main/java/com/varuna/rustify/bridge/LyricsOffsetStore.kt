package com.varuna.rustify.bridge

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File

/**
 * Ajuste manual de sincronía de la letra por pista (en ms). **Positivo = adelanta la letra**
 * (aparece antes); negativo la retrasa. Se persiste en `filesDir/lyric_offsets.json`, siguiendo la
 * misma convención que `youtube_mappings.json`.
 *
 * [version] es un contador reactivo: se incrementa en cada [set] para que las vistas de letra en
 * Compose vuelvan a leer su offset sin acoplarse al almacén.
 */
object LyricsOffsetStore {
    private const val FILE = "lyric_offsets.json"
    private val cache = HashMap<String, Long>()
    @Volatile private var loaded = false

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    private fun ensure(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            runCatching {
                val f = File(context.filesDir, FILE)
                if (f.exists()) {
                    val obj = JSONObject(f.readText())
                    obj.keys().forEach { k -> cache[k] = obj.optLong(k, 0L) }
                }
            }
            loaded = true
        }
    }

    /** Offset en ms para [trackId] (0 si no hay ajuste). */
    fun get(context: Context, trackId: String): Long {
        if (trackId.isBlank()) return 0L
        ensure(context)
        return cache[trackId] ?: 0L
    }

    fun set(context: Context, trackId: String, offsetMs: Long) {
        if (trackId.isBlank()) return
        ensure(context)
        if (offsetMs == 0L) cache.remove(trackId) else cache[trackId] = offsetMs
        persist(context)
        _version.value += 1
    }

    private fun persist(context: Context) {
        runCatching {
            val obj = JSONObject()
            cache.forEach { (k, v) -> obj.put(k, v) }
            File(context.filesDir, FILE).writeText(obj.toString())
        }
    }
}
