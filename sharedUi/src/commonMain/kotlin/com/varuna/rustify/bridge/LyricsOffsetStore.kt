package com.varuna.rustify.bridge

import com.varuna.rustify.bridge.LyricsOffsetStore.set
import com.varuna.rustify.bridge.LyricsOffsetStore.version
import com.varuna.rustify.ui.AppFile
import com.varuna.rustify.ui.child
import com.varuna.rustify.ui.readTextOrNull
import com.varuna.rustify.ui.writeAllText
import com.varuna.rustify.util.Lock
import com.varuna.rustify.util.locked
import com.varuna.rustify.util.optLong
import com.varuna.rustify.util.parseObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.concurrent.Volatile

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
    private val lock = Lock()
    @Volatile private var loaded = false

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    private fun ensure(dir: AppFile) {
        if (loaded) return
        lock.locked {
            if (loaded) return
            runCatching {
                val text = dir.child(FILE).readTextOrNull()
                if (text != null) {
                    val obj = parseObject(text)
                    obj.forEach { (k, v) -> cache[k] = v.optLong(0L) }
                }
            }
            loaded = true
        }
    }

    /** Offset in ms for [trackId] (0 if no adjustment). */
    /** [dir] is the app's files folder. */
    fun get(dir: AppFile, trackId: String): Long {
        if (trackId.isBlank()) return 0L
        ensure(dir)
        return cache[trackId] ?: 0L
    }

    fun set(dir: AppFile, trackId: String, offsetMs: Long) {
        if (trackId.isBlank()) return
        ensure(dir)
        if (offsetMs == 0L) cache.remove(trackId) else cache[trackId] = offsetMs
        persist(dir)
        _version.value += 1
    }

    private fun persist(dir: AppFile) {
        runCatching {
            val obj = JsonObject(cache.mapValues { JsonPrimitive(it.value) })
            dir.child(FILE).writeAllText(obj.toString())
        }
    }
}
