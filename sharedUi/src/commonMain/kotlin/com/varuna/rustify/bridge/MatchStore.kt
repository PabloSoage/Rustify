package com.varuna.rustify.bridge

import com.varuna.rustify.player.PlayerController
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
    private fun file(filesDir: File) = File(filesDir, "youtube_mappings.json")

    /** All matches (trackId to youtubeId). */
    suspend fun readAll(filesDir: File): Map<String, String> = withContext(Dispatchers.IO) {
        runCatching {
            val f = file(filesDir)
            if (!f.exists()) return@runCatching emptyMap()
            val obj = JSONObject(f.readText())
            buildMap { obj.keys().forEach { k -> put(k, obj.optString(k)) } }
        }.getOrDefault(emptyMap())
    }

    private suspend fun writeAll(filesDir: File, map: Map<String, String>) {
        withContext(Dispatchers.IO) {
            runCatching {
                val obj = JSONObject()
                map.forEach { (k, v) -> obj.put(k, v) }
                file(filesDir).writeText(obj.toString())
            }
        }
        reload(filesDir)
    }

    /**
     * Creates or updates a match: written here, then the player takes it as the user's choice
     * ([PlayerController.chooseRecording]: marked, set in the core's live map, and the recording
     * that was wrong forgotten everywhere it was kept, the stored audio included).
     */
    suspend fun put(filesDir: File, player: PlayerController, trackId: String, youtubeId: String) {
        if (trackId.isBlank() || youtubeId.isBlank()) return
        val m = readAll(filesDir).toMutableMap()
        m[trackId] = youtubeId
        writeAll(filesDir, m)
        runCatching { player.chooseRecording(trackId, youtubeId) }
    }

    /** Removes a match (and its user mark): back to the automatic match or the local file. */
    suspend fun remove(filesDir: File, player: PlayerController, trackId: String) {
        val m = readAll(filesDir).toMutableMap()
        if (m.remove(trackId) != null) writeAll(filesDir, m)
        player.clearRecording(trackId)
    }

    private suspend fun reload(filesDir: File) {
        runCatching { NativeEngine.initCacheDir(filesDir.absolutePath) }
    }
}
