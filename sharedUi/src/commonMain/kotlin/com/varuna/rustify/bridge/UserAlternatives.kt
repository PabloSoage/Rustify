package com.varuna.rustify.bridge

import com.varuna.rustify.ui.AppFile
import com.varuna.rustify.ui.child
import com.varuna.rustify.ui.readTextOrNull
import com.varuna.rustify.ui.writeAllText
import com.varuna.rustify.util.Lock
import com.varuna.rustify.util.locked
import com.varuna.rustify.util.optString
import com.varuna.rustify.util.parseArray
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.concurrent.Volatile

/**
 * Set of Spotify trackIds for which the user explicitly chose a YouTube alternative
 * (from the alternatives dialog).
 *
 * Exists to distinguish a user-confirmed match from an auto-persisted one. An older resolver
 * persisted every auto-matched hint into `youtube_mappings.json` as if it were a user mapping,
 * which made `playTrack` treat any mapping as a "user alternative" and skip the local match even
 * when the user had never chosen anything. With this set, the local match wins over a stale
 * auto-mapping but not over a real user choice.
 *
 * Persisted as a JSON array in `filesDir/user_alternatives.json`.
 */
object UserAlternatives {
    private const val FILE = "user_alternatives.json"
    // Guarded by [lock], which is what the synchronized set and the loader's monitor used to be.
    private val ids = HashSet<String>()
    private val lock = Lock()
    @Volatile private var loaded = false

    private fun ensure(filesDir: AppFile) {
        if (loaded) return
        lock.locked {
            if (loaded) return
            runCatching {
                val text = filesDir.child(FILE).readTextOrNull()
                if (text != null) {
                    val arr = parseArray(text)
                    for (element in arr) ids.add(element.optString())
                }
            }
            loaded = true
        }
    }

    /**
     * Drops the in-memory cache and re-reads the file. Needed after a Drive restore rewrites it
     * behind our back, otherwise every restored mapping keeps showing up as "auto".
     */
    fun reload(filesDir: AppFile) {
        lock.locked {
            ids.clear()
            loaded = false
        }
        ensure(filesDir)
    }

    fun isUserSet(filesDir: AppFile, trackId: String?): Boolean {
        if (trackId.isNullOrBlank()) return false
        ensure(filesDir)
        return lock.locked { ids.contains(trackId) }
    }

    fun add(filesDir: AppFile, trackId: String) {
        if (trackId.isBlank()) return
        ensure(filesDir)
        if (lock.locked { ids.add(trackId) }) persist(filesDir)
    }

    fun remove(filesDir: AppFile, trackId: String) {
        ensure(filesDir)
        if (lock.locked { ids.remove(trackId) }) persist(filesDir)
    }

    private fun persist(filesDir: AppFile) {
        runCatching {
            val arr = JsonArray(lock.locked { ids.map { JsonPrimitive(it) } })
            filesDir.child(FILE).writeAllText(arr.toString())
        }
    }
}
