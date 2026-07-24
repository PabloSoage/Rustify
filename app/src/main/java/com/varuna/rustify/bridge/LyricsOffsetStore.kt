package com.varuna.rustify.bridge

import android.content.Context
import com.varuna.rustify.bridge.LyricsOffsetStore.set
import com.varuna.rustify.bridge.LyricsOffsetStore.version
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File

/**
 * Manual per-track lyric synchronization offset (in ms). Positive advances the lyrics (they appear
 * earlier); negative delays them. Persisted in `filesDir/lyric_offsets.json`, following the same
 * convention as `youtube_mappings.json`.
 *
 * [version] is a reactive counter: it is incremented on every [set] so that Compose lyric views
 * re-read their offset without being coupled to the store.
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

    /** Offset in ms for [trackId] (0 if no adjustment). */
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
