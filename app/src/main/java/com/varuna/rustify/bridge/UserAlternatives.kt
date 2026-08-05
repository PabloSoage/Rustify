package com.varuna.rustify.bridge

import android.content.Context
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

    private fun ensure(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            runCatching {
                val f = File(context.filesDir, FILE)
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
    fun reload(context: Context) {
        synchronized(this) {
            ids.clear()
            loaded = false
        }
        ensure(context)
    }

    fun isUserSet(context: Context, trackId: String?): Boolean {
        if (trackId.isNullOrBlank()) return false
        ensure(context)
        return ids.contains(trackId)
    }

    fun add(context: Context, trackId: String) {
        if (trackId.isBlank()) return
        ensure(context)
        if (ids.add(trackId)) persist(context)
    }

    fun remove(context: Context, trackId: String) {
        ensure(context)
        if (ids.remove(trackId)) persist(context)
    }

    private fun persist(context: Context) {
        runCatching {
            val arr = JSONArray()
            synchronized(ids) { ids.forEach { arr.put(it) } }
            File(context.filesDir, FILE).writeText(arr.toString())
        }
    }
}
