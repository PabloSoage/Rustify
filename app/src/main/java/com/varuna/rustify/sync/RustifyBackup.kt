package com.varuna.rustify.sync

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import com.varuna.rustify.bridge.SpotifyRepository
import com.varuna.rustify.bridge.YtMusicRepository
import com.varuna.rustify.metrics.ListeningTracker
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File

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
 *  - **metrics**  — raw listening events ([ListeningTracker], `metrics.json`).
 *
 * Format:
 * ```jsonc
 * {
 *   "schema": "rustify-backup",
 *   "version": 1,
 *   "updatedAt": 1720000000000,
 *   "device": "<androidId>",
 *   "mappings": { "<spotifyId>": "<youtubeId>", ... },
 *   "local":    { "playlists": [...], "favorites": ["id", ...] },
 *   "ytm":      { "version": 1, "favorites": [...], "playlists": [...], ... },
 *   "metrics":  [ { event }, ... ]
 * }
 * ```
 *
 * The `local` and `ytm` blocks reuse verbatim the same arrays as the existing exports, so import can
 * delegate to the already-tested paths (`reloadLocalUserData`, `reloadLibrary`,
 * `ListeningTracker.importHistory`).
 */
object RustifyBackup {

    const val SCHEMA = "rustify-backup"
    const val VERSION = 1

    /** Name of the single file stored in Drive's `appDataFolder`. */
    const val DRIVE_FILE_NAME = "rustify-backup.json"

    private const val F_MAPPINGS = "youtube_mappings.json"
    private const val F_LOCAL_PLAYLISTS = "local_playlists.json"
    private const val F_LOCAL_FAVORITES = "local_favorites.json"
    private const val F_YTM = "ytm_library.json"
    private const val F_METRICS = "metrics.json"

    /** Stable device id (for diagnostics/merge only, not a secret). */
    @SuppressLint("HardwareIds")
    fun deviceId(ctx: Context): String = runCatching {
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
    }.getOrDefault("unknown")

    // ---------------------------------------------------------------------
    // EXPORT — build the container from disk (filesDir).
    // ---------------------------------------------------------------------

    /** Reads the local files and produces the JSON container. Does not touch the network. */
    fun build(ctx: Context): JSONObject {
        val dir = ctx.filesDir
        val root = JSONObject()
        root.put("schema", SCHEMA)
        root.put("version", VERSION)
        root.put("updatedAt", System.currentTimeMillis())
        root.put("device", deviceId(ctx))

        // mappings: flat map { spotifyId: youtubeId }
        root.put("mappings", readObject(File(dir, F_MAPPINGS)) ?: JSONObject())

        // local: playlists[] + favorites[]
        root.put("local", JSONObject().apply {
            put("playlists", readArray(File(dir, F_LOCAL_PLAYLISTS)) ?: JSONArray())
            put("favorites", readArray(File(dir, F_LOCAL_FAVORITES)) ?: JSONArray())
        })

        // ytm: full library object (favorites/playlists/savedAlbums/savedArtists)
        root.put("ytm", readObject(File(dir, F_YTM)) ?: JSONObject())

        // metrics: array of raw events
        root.put("metrics", readArray(File(dir, F_METRICS)) ?: JSONArray())

        return root
    }

    /** A backup category with its element count (for the "what gets synced" screen). */
    data class Category(val key: String, val label: String, val count: Int)

    /** Per-category count of what would be uploaded to Drive NOW (reads disk, no network). */
    fun summarize(ctx: Context): List<Category> {
        val c = build(ctx)
        val local = c.optJSONObject("local") ?: JSONObject()
        val ytm = c.optJSONObject("ytm") ?: JSONObject()
        return listOf(
            Category("mappings", "YouTube matches", c.optJSONObject("mappings")?.length() ?: 0),
            Category("local_playlists", "Listas locales", local.optJSONArray("playlists")?.length() ?: 0),
            Category("local_favorites", "Favoritos locales", local.optJSONArray("favorites")?.length() ?: 0),
            Category("ytm_favorites", "YT Music: favoritos", ytm.optJSONArray("favorites")?.length() ?: 0),
            Category("ytm_playlists", "YT Music: listas", ytm.optJSONArray("playlists")?.length() ?: 0),
            Category("ytm_albums", "YT Music: álbumes", ytm.optJSONArray("savedAlbums")?.length() ?: 0),
            Category("ytm_artists", "YT Music: artistas", ytm.optJSONArray("savedArtists")?.length() ?: 0),
            Category("metrics", "Eventos de escucha", c.optJSONArray("metrics")?.length() ?: 0),
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
    fun apply(
        ctx: Context,
        container: JSONObject,
        spotifyRepo: SpotifyRepository?,
        ytmRepo: YtMusicRepository?,
    ) {
        require(container.optString("schema") == SCHEMA) {
            "Not a rustify-backup container (schema=${container.optString("schema")})"
        }
        val dir = ctx.filesDir

        // mappings
        container.optJSONObject("mappings")?.let {
            atomicWrite(File(dir, F_MAPPINGS), it.toString())
        }

        // local — write files and reload in-memory state
        container.optJSONObject("local")?.let { local ->
            atomicWrite(File(dir, F_LOCAL_PLAYLISTS), (local.optJSONArray("playlists") ?: JSONArray()).toString())
            atomicWrite(File(dir, F_LOCAL_FAVORITES), (local.optJSONArray("favorites") ?: JSONArray()).toString())
            spotifyRepo?.reloadLocalUserData()
        }

        // ytm — write the file and reload in-memory state
        container.optJSONObject("ytm")?.let { ytm ->
            atomicWrite(File(dir, F_YTM), ytm.toString())
            ytmRepo?.reloadLibrary()
        }

        // metrics — dedupe import (union, not replace)
        container.optJSONArray("metrics")?.let { metrics ->
            runCatching {
                ListeningTracker.importHistory(ctx, ByteArrayInputStream(metrics.toString().toByteArray(Charsets.UTF_8)))
            }
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
     *
     * Deletions are NOT propagated (no tombstones in v1, open decision).
     */
    fun merge(a: JSONObject, b: JSONObject): JSONObject {
        val aNewer = a.optLong("updatedAt") >= b.optLong("updatedAt")
        val newer = if (aNewer) a else b
        val older = if (aNewer) b else a

        val out = JSONObject()
        out.put("schema", SCHEMA)
        out.put("version", VERSION)
        out.put("updatedAt", maxOf(a.optLong("updatedAt"), b.optLong("updatedAt")))
        out.put("device", newer.optString("device"))

        // mappings — union; the newer container wins on conflict.
        out.put("mappings", mergeMappings(older.optJSONObject("mappings"), newer.optJSONObject("mappings")))

        // local
        val aLocal = a.optJSONObject("local") ?: JSONObject()
        val bLocal = b.optJSONObject("local") ?: JSONObject()
        out.put("local", JSONObject().apply {
            put("playlists", mergePlaylists(aLocal.optJSONArray("playlists"), bLocal.optJSONArray("playlists"), "id"))
            put("favorites", unionStrings(aLocal.optJSONArray("favorites"), bLocal.optJSONArray("favorites")))
        })

        // ytm
        val aYtm = a.optJSONObject("ytm") ?: JSONObject()
        val bYtm = b.optJSONObject("ytm") ?: JSONObject()
        out.put("ytm", JSONObject().apply {
            put("version", 1)
            // Field names as serialized by the YTM models (SpotifyModels.kt):
            // YtmTrack→"video_id", YtmAlbumSlim→"browse_id", YtmArtistRef→"id".
            put("favorites", unionByKey(aYtm.optJSONArray("favorites"), bYtm.optJSONArray("favorites"), "video_id"))
            put("playlists", mergePlaylists(aYtm.optJSONArray("playlists"), bYtm.optJSONArray("playlists"), "localId"))
            put("savedAlbums", unionByKey(aYtm.optJSONArray("savedAlbums"), bYtm.optJSONArray("savedAlbums"), "browse_id"))
            put("savedArtists", unionByKey(aYtm.optJSONArray("savedArtists"), bYtm.optJSONArray("savedArtists"), "id"))
        })

        // metrics — union with dedupe
        out.put("metrics", mergeMetrics(a.optJSONArray("metrics"), b.optJSONArray("metrics")))

        return out
    }

    // ---------------------------------------------------------------------
    // Merge helpers
    // ---------------------------------------------------------------------

    private fun mergeMappings(older: JSONObject?, newer: JSONObject?): JSONObject {
        val out = JSONObject()
        older?.keys()?.forEach { k -> out.put(k, older.opt(k)) }
        newer?.keys()?.forEach { k -> out.put(k, newer.opt(k)) } // newer overwrites
        return out
    }

    /** Playlists: union by [idKey]; on conflict the one with the higher `updatedAt` wins. */
    private fun mergePlaylists(a: JSONArray?, b: JSONArray?, idKey: String): JSONArray {
        val byId = LinkedHashMap<String, JSONObject>()
        fun ingest(arr: JSONArray?) {
            arr ?: return
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString(idKey, "")
                if (id.isBlank()) continue
                val prev = byId[id]
                if (prev == null || o.optLong("updatedAt") >= prev.optLong("updatedAt")) byId[id] = o
            }
        }
        ingest(a); ingest(b)
        return JSONArray().apply { byId.values.forEach { put(it) } }
    }

    /** Union of an object array by the first [idKeys] that is present and non-empty. */
    private fun unionByKey(a: JSONArray?, b: JSONArray?, vararg idKeys: String): JSONArray {
        val byId = LinkedHashMap<String, JSONObject>()
        fun keyOf(o: JSONObject): String {
            for (k in idKeys) { val v = o.optString(k, ""); if (v.isNotBlank()) return "$k:$v" }
            return o.toString() // fallback: the whole object
        }
        fun ingest(arr: JSONArray?) {
            arr ?: return
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                byId.putIfAbsent(keyOf(o), o)
            }
        }
        ingest(a); ingest(b)
        return JSONArray().apply { byId.values.forEach { put(it) } }
    }

    /** Union of a string array (local favorites are plain ids). */
    private fun unionStrings(a: JSONArray?, b: JSONArray?): JSONArray {
        val set = LinkedHashSet<String>()
        fun ingest(arr: JSONArray?) {
            arr ?: return
            for (i in 0 until arr.length()) arr.optString(i)?.takeIf { it.isNotBlank() }?.let { set.add(it) }
        }
        ingest(a); ingest(b)
        return JSONArray().apply { set.forEach { put(it) } }
    }

    /** Metrics: union with dedupe by (trackId|trackName, startedAt, listenedMs). */
    private fun mergeMetrics(a: JSONArray?, b: JSONArray?): JSONArray {
        val seen = HashSet<String>()
        val out = JSONArray()
        fun ingest(arr: JSONArray?) {
            arr ?: return
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (seen.add(metricKey(o))) out.put(o)
            }
        }
        ingest(a); ingest(b)
        return out
    }

    /** Same criterion as [ListeningTracker]'s private `dedupeKey`. */
    private fun metricKey(e: JSONObject): String {
        val id = e.optString("trackId", "")
        val idPart = if (id.isNotBlank()) id else "n:${e.optString("trackName", "")}"
        return "$idPart|${e.optLong("startedAt")}|${e.optLong("listenedMs")}"
    }

    // ---------------------------------------------------------------------
    // I/O helpers
    // ---------------------------------------------------------------------

    private fun readObject(f: File): JSONObject? =
        if (f.exists()) runCatching { JSONObject(f.readText()) }.getOrNull() else null

    private fun readArray(f: File): JSONArray? =
        if (f.exists()) runCatching { JSONArray(f.readText()) }.getOrNull() else null

    private fun atomicWrite(dst: File, content: String) {
        val tmp = File(dst.parentFile, dst.name + ".tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(dst)) { dst.writeText(content); tmp.delete() }
    }
}
