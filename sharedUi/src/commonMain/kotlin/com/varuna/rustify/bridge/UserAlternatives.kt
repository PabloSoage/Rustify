package com.varuna.rustify.bridge

import org.json.JSONArray
import java.io.File

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
    private val ids = java.util.Collections.synchronizedSet(HashSet<String>())
    @Volatile private var loaded = false

    private fun ensure(filesDir: File) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            runCatching {
                val f = File(filesDir, FILE)
                if (f.exists()) {
                    val arr = JSONArray(f.readText())
                    for (i in 0 until arr.length()) ids.add(arr.optString(i))
                }
            }
            loaded = true
        }
    }

    /**
     * Drops the in-memory cache and re-reads the file. Needed after a Drive restore rewrites it
     * behind our back, otherwise every restored mapping keeps showing up as "auto".
     */
    fun reload(filesDir: File) {
        synchronized(this) {
            ids.clear()
            loaded = false
        }
        ensure(filesDir)
    }

    fun isUserSet(filesDir: File, trackId: String?): Boolean {
        if (trackId.isNullOrBlank()) return false
        ensure(filesDir)
        return ids.contains(trackId)
    }

    fun add(filesDir: File, trackId: String) {
        if (trackId.isBlank()) return
        ensure(filesDir)
        if (ids.add(trackId)) persist(filesDir)
    }

    fun remove(filesDir: File, trackId: String) {
        ensure(filesDir)
        if (ids.remove(trackId)) persist(filesDir)
    }

    private fun persist(filesDir: File) {
        runCatching {
            val arr = JSONArray()
            synchronized(ids) { ids.forEach { arr.put(it) } }
            File(filesDir, FILE).writeText(arr.toString())
        }
    }
}
