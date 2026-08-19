package com.varuna.rustify.bridge

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Kotlin access to the YouTube match file (`filesDir/youtube_mappings.json`,
 * `{spotifyTrackId: youtubeVideoId}`).
 *
 * Everything here is `suspend`, for the reason the whole bridge is: reading and writing this file
 * is disk I/O, and [reload] crosses JNI into a `block_on`. None of that belongs on the thread that
 * draws the screen — and every caller here *is* a screen.
 *
 * IMPORTANT: after writing, the Rust map must be reloaded via [NativeEngine.initCacheDir], which
 * performs `*lock = load_from_disk`, i.e. REPLACES the in-memory map. Without that reload, edits
 * are persisted to disk but never reflected in the running engine.
 */
object MatchStore {
    private fun file(context: Context) = File(context.filesDir, "youtube_mappings.json")

    /** All matches (trackId to youtubeId). */
    suspend fun readAll(context: Context): Map<String, String> = withContext(Dispatchers.IO) {
        runCatching {
            val f = file(context)
            if (!f.exists()) return@runCatching emptyMap()
            val obj = JSONObject(f.readText())
            buildMap { obj.keys().forEach { k -> put(k, obj.optString(k)) } }
        }.getOrDefault(emptyMap())
    }

    private suspend fun writeAll(context: Context, map: Map<String, String>) {
        withContext(Dispatchers.IO) {
            runCatching {
                val obj = JSONObject()
                map.forEach { (k, v) -> obj.put(k, v) }
                file(context).writeText(obj.toString())
            }
        }
        reload(context)
    }

    /** Creates or updates a match and marks it as a user choice. */
    suspend fun put(context: Context, trackId: String, youtubeId: String) {
        if (trackId.isBlank() || youtubeId.isBlank()) return
        val m = readAll(context).toMutableMap()
        m[trackId] = youtubeId
        writeAll(context, m)
        UserAlternatives.add(context, trackId)
        // Reflect the change in Rust's live map immediately.
        runCatching { NativeEngine.setAlternativeTrackNative(trackId, youtubeId) }
        // And drop any audio already cached for this track: the user has just said the recording
        // was wrong, and a cache that kept serving it would make the choice look like it did
        // nothing at all.
        com.varuna.rustify.audio.StreamRouting.forget(context, trackId)
    }

    /** Removes a match (and its user mark) — reverts to auto-match or local. */
    suspend fun remove(context: Context, trackId: String) {
        val m = readAll(context).toMutableMap()
        if (m.remove(trackId) != null) writeAll(context, m)
        UserAlternatives.remove(context, trackId)
        com.varuna.rustify.audio.StreamRouting.forget(context, trackId)
    }

    private suspend fun reload(context: Context) {
        runCatching { NativeEngine.initCacheDir(context.filesDir.absolutePath) }
    }
}
