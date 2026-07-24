package com.varuna.rustify.bridge

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Kotlin access to the YouTube match file (`filesDir/youtube_mappings.json`,
 * `{spotifyTrackId: youtubeVideoId}`).
 *
 * IMPORTANT: after writing, the Rust map must be reloaded via [NativeEngine.initCacheDirNative]
 * (which performs `*lock = load_from_disk`, i.e. REPLACES the in-memory map). Without this reload,
 * edits are persisted to disk but never reflected in the running engine.
 */
object MatchStore {
    private fun file(context: Context) = File(context.filesDir, "youtube_mappings.json")

    /** All matches (trackId -> youtubeId). */
    fun readAll(context: Context): Map<String, String> = runCatching {
        val f = file(context)
        if (!f.exists()) return emptyMap()
        val obj = JSONObject(f.readText())
        buildMap { obj.keys().forEach { k -> put(k, obj.optString(k)) } }
    }.getOrDefault(emptyMap())

    private fun writeAll(context: Context, map: Map<String, String>) {
        runCatching {
            val obj = JSONObject()
            map.forEach { (k, v) -> obj.put(k, v) }
            file(context).writeText(obj.toString())
        }
        reload(context)
    }

    /** Creates/updates a match and marks it as a user choice. */
    fun put(context: Context, trackId: String, youtubeId: String) {
        if (trackId.isBlank() || youtubeId.isBlank()) return
        val m = readAll(context).toMutableMap()
        m[trackId] = youtubeId
        writeAll(context, m)
        UserAlternatives.add(context, trackId)
        // Reflect the change in Rust's live map immediately.
        runCatching { NativeEngine.setAlternativeTrackNative(trackId, youtubeId) }
    }

    /** Removes a match (and its user mark) — reverts to auto-match / local. */
    fun remove(context: Context, trackId: String) {
        val m = readAll(context).toMutableMap()
        if (m.remove(trackId) != null) writeAll(context, m)
        UserAlternatives.remove(context, trackId)
    }

    private fun reload(context: Context) {
        runCatching { NativeEngine.initCacheDirNative(context.filesDir.absolutePath) }
    }
}
