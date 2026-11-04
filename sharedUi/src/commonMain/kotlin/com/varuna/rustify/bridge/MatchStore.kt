package com.varuna.rustify.bridge

import com.varuna.rustify.player.PlayerController
import com.varuna.rustify.ui.AppFile
import com.varuna.rustify.ui.child
import com.varuna.rustify.ui.fullPath
import com.varuna.rustify.ui.readTextOrNull
import com.varuna.rustify.ui.writeAllText
import com.varuna.rustify.util.optString
import com.varuna.rustify.ui.writeTextAtomically
import com.varuna.rustify.util.parseObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
    private fun file(filesDir: AppFile) = filesDir.child("youtube_mappings.json")

    /** All matches (trackId to youtubeId). */
    suspend fun readAll(filesDir: AppFile): Map<String, String> = withContext(Dispatchers.IO) {
        runCatching {
            val text = file(filesDir).readTextOrNull() ?: return@runCatching emptyMap()
            val obj = parseObject(text)
            buildMap { obj.forEach { (k, v) -> put(k, v.optString()) } }
        }.getOrDefault(emptyMap())
    }

    private suspend fun writeAll(filesDir: AppFile, map: Map<String, String>) {
        withContext(Dispatchers.IO) {
            runCatching {
                val obj = JsonObject(map.mapValues { JsonPrimitive(it.value) })
                file(filesDir).writeAllText(obj.toString())
            }
        }
        reload(filesDir)
    }

    /**
     * Creates or updates a match: written here, then the player takes it as the user's choice
     * ([PlayerController.chooseRecording]: marked, set in the core's live map, and the recording
     * that was wrong forgotten everywhere it was kept, the stored audio included).
     */
    suspend fun put(filesDir: AppFile, player: PlayerController, trackId: String, youtubeId: String) {
        if (trackId.isBlank() || youtubeId.isBlank()) return
        val m = readAll(filesDir).toMutableMap()
        m[trackId] = youtubeId
        writeAll(filesDir, m)
        runCatching { player.chooseRecording(trackId, youtubeId) }
    }

    /** Removes a match (and its user mark): back to the automatic match or the local file. */
    suspend fun remove(filesDir: AppFile, player: PlayerController, trackId: String) {
        val m = readAll(filesDir).toMutableMap()
        if (m.remove(trackId) != null) writeAll(filesDir, m)
        player.clearRecording(trackId)
    }

    /** Test hook to verify reload was called without linking native libraries. */
    internal var onReloadForTesting: (suspend (AppFile) -> Unit)? = null

    private suspend fun reload(filesDir: AppFile) {
        val hook = onReloadForTesting
        if (hook != null) {
            hook(filesDir)
        } else {
            runCatching { NativeEngine.initCacheDir(filesDir.fullPath) }
        }
    }

    /**
     * Validates that [rawJson] is a JSON object where every key and value is a String.
     * Returns the parsed map if valid, or null if malformed or containing non-string values.
     */
    fun validateMappings(rawJson: String): Map<String, String>? = runCatching {
        val root = Json.parseToJsonElement(rawJson)
        val obj = root as? JsonObject ?: return@runCatching null
        buildMap<String, String> {
            for ((key, elem) in obj) {
                val prim = elem as? JsonPrimitive ?: return@runCatching null
                if (!prim.isString) return@runCatching null
                put(key, prim.content)
            }
        }
    }.getOrNull()

    /**
     * Imports mappings from [rawJson]. Validates format and model before modifying disk.
     * If validation fails, leaves existing file untouched and returns false.
     * If valid, writes the mappings and reloads the engine; false when it could not be written.
     */
    suspend fun importAll(filesDir: AppFile, rawJson: String): Boolean {
        val map = validateMappings(rawJson) ?: return false
        val written = withContext(Dispatchers.IO) {
            runCatching { file(filesDir).writeTextAtomically(JsonObject(map.mapValues { JsonPrimitive(it.value) }).toString()) }
        }.isSuccess
        if (written) reload(filesDir)
        return written
    }
}
