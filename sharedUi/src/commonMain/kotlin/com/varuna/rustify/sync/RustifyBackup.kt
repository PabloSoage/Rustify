package com.varuna.rustify.sync

import com.varuna.rustify.bridge.NativeEngine
import com.varuna.rustify.bridge.SpotifyRepository
import com.varuna.rustify.bridge.UserAlternatives
import com.varuna.rustify.bridge.YtMusicRepository
import com.varuna.rustify.metrics.ListeningTracker
import com.varuna.rustify.platform.Log
import com.varuna.rustify.platform.currentTimeMillis
import com.varuna.rustify.ui.AppFile
import com.varuna.rustify.ui.AppPlatform
import com.varuna.rustify.ui.child
import com.varuna.rustify.ui.readTextOrNull
import com.varuna.rustify.ui.writeTextAtomically
import com.varuna.rustify.util.parseArray
import com.varuna.rustify.util.parseObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Versioned backup/sync container.
 *
 * Aggregates into a SINGLE JSON all the local user data that already has its own export/import in
 * the app, so it can be uploaded to / downloaded from Google Drive (AppData) or exported to / imported
 * from a file:
 *
 *  - **mappings** — `youtube_mappings.json` (the Spotify→YouTube map written by the Rust core; the
 *    settings UI reads/writes it in `filesDir`).
 *  - **local**    — `local_playlists.json` + `local_favorites.json`
 *    (same `rustify-local-user-data` schema as the manual settings export).
 *  - **ytm**      — YouTube Music library ([YtMusicRepository.exportJson] → `ytm_library.json`).
 *  - **metrics**  — raw listening events (kept by the core since 4.0, via [ListeningTracker]).
 *  - **places**   — where you left off: "Continue listening" and long tracks, with what was dismissed
 *    or finished (5.x). The core builds, merges and applies it (`player::places`); a local file
 *    travels as its tags and only lands on a device whose library has the same song.
 *
 * Format:
 * ```jsonc
 * {
 *   "schema": "rustify-backup",
 *   "version": 1,
 *   "updatedAt": 1720000000000,
 *   "device": "<androidId>",
 *   "deviceName": "Galaxy Tab S8",        // 4.7; the user's name for the device, for display only
 *   "mappings": { "<spotifyId>": "<youtubeId>", ... },
 *   "local":    { "playlists": [...], "favorites": ["id", ...] },
 *   "ytm":      { "version": 1, "favorites": [...], "playlists": [...], ... },
 *   "metrics":  [ { event }, ... ],
 *   "places":   { "sessions": [...], "bookmarks": [...], "forgotten": [...] }
 * }
 * ```
 *
 * The `local` and `ytm` blocks reuse verbatim the same arrays as the existing exports, so import can
 * delegate to the already-tested paths (`reloadLocalUserData`, `reloadLibrary`,
 * `ListeningTracker.importHistory`). Moved from the app (5.x) so the desktop and iOS sync the same
 * container; the JSON is the one the phone has always written.
 */
object RustifyBackup {

    const val SCHEMA = "rustify-backup"
    const val VERSION = 1

    /** Name of the single file stored in Drive's `appDataFolder`. */
    const val DRIVE_FILE_NAME = "rustify-backup.json"

    private const val F_MAPPINGS = "youtube_mappings.json"
    // Which mappings the user picked by hand. Without it a restored device keeps the right
    // alternatives but labels every one of them "auto", because the flag lives in a separate file
    // from the mapping itself.
    private const val F_USER_ALTS = "user_alternatives.json"
    private const val F_LOCAL_PLAYLISTS = "local_playlists.json"
    private const val F_LOCAL_FAVORITES = "local_favorites.json"
    private const val F_YTM = "ytm_library.json"

    private const val TAG = "RustifyBackup"

    /** Which device wrote a container: a stable id (for merging, not a secret) and the user's name for it. */
    data class Device(val id: String, val name: String)

    // ---------------------------------------------------------------------
    // EXPORT — build the container from disk (filesDir).
    // ---------------------------------------------------------------------

    /** Reads the local files and produces the JSON container. Does not touch the network. */
    suspend fun build(platform: AppPlatform, device: Device): JsonObject {
        val dir = platform.filesDir
        // places: the core's block, with local songs described by their tags.
        SpotifyRepository.registerLocalLibrary(dir)
        val places = runCatching { parseObject(NativeEngine.placesExport()) }.getOrDefault(JsonObject(emptyMap()))
        val metrics = runCatching { parseArray(ListeningTracker.loadEventsJson(platform)) }.getOrDefault(JsonArray(emptyList()))
        return buildJsonObject {
            put("schema", SCHEMA)
            put("version", VERSION)
            put("updatedAt", currentTimeMillis())
            put("device", device.id)
            put("deviceName", device.name)

            // mappings: flat map { spotifyId: youtubeId }
            put("mappings", readObject(dir.child(F_MAPPINGS)) ?: JsonObject(emptyMap()))

            // userAlternatives: ids of the mappings the user chose explicitly.
            put("userAlternatives", readArray(dir.child(F_USER_ALTS)) ?: JsonArray(emptyList()))

            // local: playlists[] + favorites[]
            put("local", buildJsonObject {
                put("playlists", readArray(dir.child(F_LOCAL_PLAYLISTS)) ?: JsonArray(emptyList()))
                put("favorites", readArray(dir.child(F_LOCAL_FAVORITES)) ?: JsonArray(emptyList()))
            })

            // ytm: full library object (favorites/playlists/savedAlbums/savedArtists)
            put("ytm", readObject(dir.child(F_YTM)) ?: JsonObject(emptyMap()))

            // metrics: array of raw events
            put("metrics", metrics)

            put("places", places)
        }
    }

    /** What a backup category is, for the "what gets synced" screen to name it in its language. */
    enum class Kind {
        MAPPINGS, USER_ALTERNATIVES, LOCAL_PLAYLISTS, LOCAL_FAVORITES,
        YTM_FAVORITES, YTM_PLAYLISTS, YTM_ALBUMS, YTM_ARTISTS, METRICS, PLACES
    }

    /** A backup category with its element count (for the "what gets synced" screen). */
    data class Category(val kind: Kind, val count: Int)

    /** Per-category count of what would be uploaded to Drive NOW (reads disk, no network). */
    suspend fun summarize(platform: AppPlatform, device: Device): List<Category> {
        val c = build(platform, device)
        val local = c.obj("local")
        val ytm = c.obj("ytm")
        val places = c.obj("places")
        return listOf(
            Category(Kind.MAPPINGS, c.obj("mappings").size),
            Category(Kind.USER_ALTERNATIVES, c.arr("userAlternatives").size),
            Category(Kind.LOCAL_PLAYLISTS, local.arr("playlists").size),
            Category(Kind.LOCAL_FAVORITES, local.arr("favorites").size),
            Category(Kind.YTM_FAVORITES, ytm.arr("favorites").size),
            Category(Kind.YTM_PLAYLISTS, ytm.arr("playlists").size),
            Category(Kind.YTM_ALBUMS, ytm.arr("savedAlbums").size),
            Category(Kind.YTM_ARTISTS, ytm.arr("savedArtists").size),
            Category(Kind.METRICS, c.arr("metrics").size),
            Category(Kind.PLACES, places.arr("sessions").size + places.arr("bookmarks").size),
        )
    }

    // ---------------------------------------------------------------------
    // IMPORT — write each block to disk and reload via the existing paths.
    // ---------------------------------------------------------------------

    /**
     * Applies a container to disk and reloads the repos. Overwrites the `mappings`/`local`/`ytm`
     * files with the container's contents (for metrics it uses [ListeningTracker]'s dedupe import,
     * which does NOT delete local data).
     *
     * @param spotifyRepo for `reloadLocalUserData()` after writing the local data.
     * @param ytmRepo for `reloadLibrary()` after writing the YTM data.
     */
    suspend fun apply(
        platform: AppPlatform,
        container: JsonObject,
        spotifyRepo: SpotifyRepository?,
        ytmRepo: YtMusicRepository?,
    ) {
        require(container.text("schema") == SCHEMA) {
            "Not a rustify-backup container (schema=${container.text("schema")})"
        }
        val dir = platform.filesDir

        // mappings
        (container["mappings"] as? JsonObject)?.let {
            dir.child(F_MAPPINGS).writeTextAtomically(it.toString())
        }

        // userAlternatives — the "chosen by me" flags that go with those mappings.
        (container["userAlternatives"] as? JsonArray)?.let {
            dir.child(F_USER_ALTS).writeTextAtomically(it.toString())
            // The set is cached in memory on first use, so force a re-read.
            UserAlternatives.reload(dir)
        }

        // local — write files and reload in-memory state
        (container["local"] as? JsonObject)?.let { local ->
            dir.child(F_LOCAL_PLAYLISTS).writeTextAtomically(local.arr("playlists").toString())
            dir.child(F_LOCAL_FAVORITES).writeTextAtomically(local.arr("favorites").toString())
            spotifyRepo?.reloadLocalUserData()
        }

        // ytm — write the file and reload in-memory state
        (container["ytm"] as? JsonObject)?.let { ytm ->
            dir.child(F_YTM).writeTextAtomically(ytm.toString())
            ytmRepo?.reloadLibrary()
        }

        // places — merged with this device's by the core, in this device's ids.
        (container["places"] as? JsonObject)?.let { places ->
            SpotifyRepository.registerLocalLibrary(dir)
            runCatching { NativeEngine.placesApply(places.toString()) }
                .onFailure { Log.w(TAG, "places not applied", it) }
        }

        // metrics — dedupe import (union, not replace)
        (container["metrics"] as? JsonArray)?.let { metrics ->
            runCatching { ListeningTracker.importEvents(platform, metrics.toString()) }
        }
    }

    // ---------------------------------------------------------------------
    // MERGE — set union + last-write-wins by `updatedAt`.
    // ---------------------------------------------------------------------

    /**
     * Merges two containers into a new one. Strategy:
     *  - **mappings**: union of keys; on conflict the container with the higher `updatedAt` wins
     *    (global last-write-wins, since a mapping has no timestamp of its own).
     *  - **local.playlists / ytm.playlists**: union by id; on conflict the entity with the higher
     *    `updatedAt` wins.
     *  - **local.favorites / ytm.favorites / savedAlbums / savedArtists**: set union by id (a
     *    favorite is never lost to a sync).
     *  - **metrics**: union with dedupe by (trackId, startedAt, listenedMs).
     *  - **places**: newest per place, removals respected (the core's rule, `player::places`).
     *
     * Deletions are NOT propagated (no tombstones in v1, open decision), except in **places**,
     * whose removals are dated and travel (`player::places`).
     */
    fun merge(a: JsonObject, b: JsonObject): JsonObject {
        val aNewer = a.long("updatedAt") >= b.long("updatedAt")
        val newer = if (aNewer) a else b
        val older = if (aNewer) b else a
        val aLocal = a.obj("local")
        val bLocal = b.obj("local")
        val aYtm = a.obj("ytm")
        val bYtm = b.obj("ytm")
        return buildJsonObject {
            put("schema", SCHEMA)
            put("version", VERSION)
            put("updatedAt", maxOf(a.long("updatedAt"), b.long("updatedAt")))
            put("device", newer.text("device"))
            put("deviceName", newer.text("deviceName"))

            // mappings — union; the newer container wins on conflict.
            put("mappings", JsonObject(older.obj("mappings") + newer.obj("mappings")))

            // userAlternatives — plain set union: an explicit choice is never lost to a sync.
            put("userAlternatives", unionStrings(a.arr("userAlternatives"), b.arr("userAlternatives")))

            put("local", buildJsonObject {
                put("playlists", mergePlaylists(aLocal.arr("playlists"), bLocal.arr("playlists"), "id"))
                put("favorites", unionStrings(aLocal.arr("favorites"), bLocal.arr("favorites")))
            })

            put("ytm", buildJsonObject {
                put("version", 1)
                // Field names as serialized by the YTM models (SpotifyModels.kt):
                // YtmTrack→"video_id", YtmAlbumSlim→"browse_id", YtmArtistRef→"id".
                put("favorites", unionByKey(aYtm.arr("favorites"), bYtm.arr("favorites"), "video_id"))
                put("playlists", mergePlaylists(aYtm.arr("playlists"), bYtm.arr("playlists"), "localId"))
                put("savedAlbums", unionByKey(aYtm.arr("savedAlbums"), bYtm.arr("savedAlbums"), "browse_id"))
                put("savedArtists", unionByKey(aYtm.arr("savedArtists"), bYtm.arr("savedArtists"), "id"))
            })

            // metrics — union with dedupe
            put("metrics", mergeMetrics(a.arr("metrics"), b.arr("metrics")))

            put(
                "places",
                runCatching { parseObject(NativeEngine.placesMerge(a.obj("places").toString(), b.obj("places").toString())) }
                    .getOrDefault(JsonObject(emptyMap()))
            )
        }
    }

    // ---------------------------------------------------------------------
    // Merge helpers
    // ---------------------------------------------------------------------

    /** Playlists: union by [idKey]; on conflict the one with the higher `updatedAt` wins. */
    private fun mergePlaylists(a: JsonArray, b: JsonArray, idKey: String): JsonArray {
        val byId = LinkedHashMap<String, JsonObject>()
        for (o in (a + b).filterIsInstance<JsonObject>()) {
            val id = o.text(idKey)
            if (id.isBlank()) continue
            val prev = byId[id]
            if (prev == null || o.long("updatedAt") >= prev.long("updatedAt")) byId[id] = o
        }
        return JsonArray(byId.values.toList())
    }

    /** Union of an object array by the first [idKeys] that is present and non-empty. */
    private fun unionByKey(a: JsonArray, b: JsonArray, vararg idKeys: String): JsonArray {
        val byId = LinkedHashMap<String, JsonObject>()
        fun keyOf(o: JsonObject): String {
            for (k in idKeys) { val v = o.text(k); if (v.isNotBlank()) return "$k:$v" }
            return o.toString() // fallback: the whole object
        }
        for (o in (a + b).filterIsInstance<JsonObject>()) byId.getOrPut(keyOf(o)) { o }
        return JsonArray(byId.values.toList())
    }

    /** Union of a string array (local favorites are plain ids). */
    private fun unionStrings(a: JsonArray, b: JsonArray): JsonArray {
        val set = LinkedHashSet<String>()
        for (e in a + b) (e as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { set.add(it) }
        return buildJsonArray { set.forEach { add(JsonPrimitive(it)) } }
    }

    /** Metrics: union with dedupe by (trackId|trackName, startedAt, listenedMs). */
    private fun mergeMetrics(a: JsonArray, b: JsonArray): JsonArray {
        val seen = HashSet<String>()
        return JsonArray((a + b).filterIsInstance<JsonObject>().filter { seen.add(metricKey(it)) })
    }

    /** Same criterion as [ListeningTracker]'s private `dedupeKey`. */
    private fun metricKey(e: JsonObject): String {
        val id = e.text("trackId")
        val idPart = if (id.isNotBlank()) id else "n:${e.text("trackName")}"
        return "$idPart|${e.long("startedAt")}|${e.long("listenedMs")}"
    }

    // ---------------------------------------------------------------------
    // JSON and I/O helpers — org.json's opt* semantics, which the phone's container was written with.
    // ---------------------------------------------------------------------

    private fun JsonObject.obj(key: String): JsonObject = this[key] as? JsonObject ?: JsonObject(emptyMap())

    private fun JsonObject.arr(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())

    private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull ?: ""

    private fun JsonObject.long(key: String): Long =
        (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong() } ?: 0L

    private fun readObject(f: AppFile): JsonObject? =
        f.readTextOrNull()?.let { runCatching { parseObject(it) }.getOrNull() }

    private fun readArray(f: AppFile): JsonArray? =
        f.readTextOrNull()?.let { runCatching { parseArray(it) }.getOrNull() }

    /** The container as text, for Drive or a file. */
    fun encode(container: JsonObject): String = container.toString()

    /** A container read back, or null when [text] is not one. */
    fun decode(text: String): JsonObject? = runCatching { parseObject(text) }.getOrNull()

}
