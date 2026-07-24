package com.varuna.rustify.metrics

import android.content.Context
import com.varuna.rustify.bridge.FullTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Tracks listening sessions with a 30s / 50%-of-duration threshold.
 * Persists raw events as JSON in `filesDir/metrics.json` (no Room; the app
 * avoids adding a database dependency).
 *
 * Hooked from [AudioPlayerService]: onTrackStarted, onProgress, onEnded, onError, flush.
 */
class ListeningTracker(private val appContext: Context) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var cur: Session? = null

    private data class Session(
        val track: FullTrack, val startedAt: Long,
        var lastPos: Long = 0, var listenedMs: Long = 0,
        var lastTickAt: Long = 0,
        var completed: Boolean = false, var errored: Boolean = false
    )

    fun onTrackStarted(track: FullTrack) {
        if (track.id == null) return
        if (cur?.track?.id == track.id) return
        flush()
        cur = Session(track, System.currentTimeMillis())
    }

    /**
     * Accumulates time actually listened by counting `min(posDelta, wallDelta)`:
     *  - normal playback: posDelta≈wallDelta≈500 ms → counts real time, including late ticks;
     *  - forward seek: large posDelta but small wallDelta → counts only what was played (no inflation);
     *  - backward seek / stall (buffering): posDelta ≤ 0 → not counted;
     *  - large gap (>10 s, app suspended without audio): ignored so non-played time isn't counted.
     */
    fun onProgress(posMs: Long) {
        val s = cur ?: return
        val now = System.currentTimeMillis()
        val posDelta = posMs - s.lastPos
        val wallDelta = if (s.lastTickAt > 0L) now - s.lastTickAt else 0L
        if (posDelta > 0L && wallDelta in 1L..10_000L) {
            s.listenedMs += minOf(posDelta, wallDelta)
        }
        s.lastPos = posMs
        s.lastTickAt = now
    }

    fun onEnded() { cur?.completed = true; flush() }
    fun onError() { cur?.errored = true; flush() }

    fun flush() {
        val s = cur ?: return; cur = null
        val tid = s.track.id ?: return
        val counted = !s.errored &&
            (s.listenedMs >= 30_000 || (s.track.durationMs > 0 && s.listenedMs >= s.track.durationMs / 2))
        val source = when {
            tid.startsWith("local:") -> "local"
            tid.startsWith("ytm:") -> "ytm"
            else -> "spotify"
        }
        val json = JSONObject().apply {
            put("trackId", tid)
            put("isrc", s.track.isrc)
            put("trackName", s.track.name)
            val aIds = JSONArray(); s.track.artists.forEach { aIds.put(it.id) }
            put("artistIds", aIds)
            val aNms = JSONArray(); s.track.artists.forEach { aNms.put(it.name) }
            put("artistNames", aNms)
            put("albumId", s.track.album?.id ?: "")
            put("albumName", s.track.album?.name ?: "")
            // Album artwork, used to show covers on the metrics screen (older events lack it, so the
            // UI falls back to a placeholder). Adding this extra field doesn't break the format.
            put("imageUrl", s.track.album?.images?.firstOrNull()?.url ?: "")
            put("durationMs", s.track.durationMs)
            put("startedAt", s.startedAt)
            put("listenedMs", s.listenedMs)
            put("counted", counted)
            put("completed", s.completed)
            put("source", source)
        }
        scope.launch { appendEvent(json) }
    }

    private fun appendEvent(json: JSONObject) {
        runCatching {
            val file = File(appContext.filesDir, "metrics.json")
            val arr = if (file.exists()) JSONArray(file.readText()) else JSONArray()
            arr.put(json)
            // Rotation: keep the most recent ~5000 entries
            val trimmed = if (arr.length() > 5000) {
                val fresh = JSONArray()
                for (i in arr.length() - 5000 until arr.length()) fresh.put(arr.getJSONObject(i.toInt()))
                fresh
            } else arr
            file.writeText(trimmed.toString())
        }
    }

    companion object {
        private const val MAX_EVENTS = 5000

        fun loadEvents(context: Context): List<JSONObject> {
            val file = File(context.filesDir, "metrics.json")
            if (!file.exists()) return emptyList()
            return runCatching {
                val arr = JSONArray(file.readText())
                (0 until arr.length()).map { arr.getJSONObject(it) }
            }.getOrDefault(emptyList())
        }

        /**
         * Exports the metrics to Spotify's Streaming History format (compatible with stats.fm)
         * so the user can manually import them into stats.fm or another app.
         * Returns the JSON bytes (UTF-8).
         */
        fun exportSpotifyHistoryBytes(context: Context): ByteArray {
            val events = loadEvents(context)
            val arr = JSONArray()
            events.forEach { e ->
                val ts = e.optLong("startedAt")
                val iso = runCatching {
                    java.time.Instant.ofEpochMilli(ts)
                        .toString() // ISO-8601, e.g. 2024-09-08T15:32:45Z
                }.getOrDefault("")
                val artistName = runCatching {
                    val a = e.optJSONArray("artistNames")
                    if (a != null && a.length() > 0) a.getString(0) else ""
                }.getOrDefault("")
                val trackId = e.optString("trackId", "")
                val uri = if (trackId.isNotBlank()) "spotify:track:$trackId" else null
                arr.put(JSONObject().apply {
                    put("ts", iso)
                    put("username", "")
                    put("platform", "rustify")
                    put("ms_played", e.optLong("listenedMs"))
                    put("conn_country", "")
                    if (uri != null) put("spotify_track_uri", uri)
                    put("master_metadata_track_name", e.optString("trackName", ""))
                    put("master_metadata_album_album_name", e.optString("albumName", ""))
                    put("master_metadata_artist_name", artistName)
                })
            }
            return arr.toString().toByteArray(Charsets.UTF_8)
        }

        /**
         * Imports a listening history. Accepts three formats:
         *  1. JSONArray of Rustify events (same format as metrics.json).
         *  2. Spotify Streaming History (array of objects with `ts`/`ms_played`/`master_metadata_*`).
         *  3. Rustify container `{ "events": [...] }`.
         *
         * Returns the number of events added. Updates metrics.json on disk.
         */
        fun importHistory(context: Context, inputStream: java.io.InputStream): Int {
            val text = inputStream.bufferedReader().use { it.readText() }
            val arr: JSONArray? = try {
                JSONArray(text)
            } catch (_: org.json.JSONException) {
                runCatching {
                    val obj = JSONObject(text)
                    if (obj.has("events")) obj.optJSONArray("events") else null
                }.getOrNull()
            }
            if (arr == null) throw IllegalArgumentException("Unrecognized JSON format")
            val file = File(context.filesDir, "metrics.json")
            val existing = if (file.exists()) JSONArray(file.readText()) else JSONArray()
            // Dedupe by (trackId|trackName, startedAt, listenedMs). Re-importing the same export
            // must not duplicate events. Seeded with what's already persisted and with entries added
            // in this same pass (in case the file itself contains duplicates).
            val seen = HashSet<String>()
            for (i in 0 until existing.length()) {
                existing.optJSONObject(i)?.let { seen.add(dedupeKey(it)) }
            }
            var added = 0
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val event = normalizeToRustifyEvent(o) ?: continue
                // Skip if it already exists (same track + startedAt + listenedMs).
                if (!seen.add(dedupeKey(event))) continue
                existing.put(event)
                added++
            }
            val trimmed = if (existing.length() > MAX_EVENTS) {
                val fresh = JSONArray()
                for (i in existing.length() - MAX_EVENTS until existing.length()) {
                    fresh.put(existing.getJSONObject(i))
                }
                fresh
            } else existing
            file.writeText(trimmed.toString())
            return added
        }

        /**
         * Normalizes a history JSON object into a Rustify event. Accepts three sources:
         *  1. Rustify event (already has `trackName`) → accepted as-is.
         *  2. Spotify **Extended** Streaming History (newer GDPR export):
         *     `ts` (ISO-8601), `ms_played`, `master_metadata_track_name`,
         *     `master_metadata_album_album_name`, `master_metadata_artist_name`, `spotify_track_uri`.
         *  3. Spotify **legacy** `StreamingHistory*.json`:
         *     `endTime` ("yyyy-MM-dd HH:mm", export's local time zone), `msPlayed`, `trackName`,
         *     `artistName` (no album or uri).
         */
        /**
         * Dedupe key for an already-normalized Rustify event. Uses `trackId` when present (new
         * imports with a URI or app events), and falls back to `trackName` for the legacy format
         * without a URI.
         */
        private fun dedupeKey(e: JSONObject): String {
            val id = e.optString("trackId", "")
            val idPart = if (id.isNotBlank()) id else "n:${e.optString("trackName", "")}"
            return "$idPart|${e.optLong("startedAt")}|${e.optLong("listenedMs")}"
        }

        private fun normalizeToRustifyEvent(o: JSONObject): JSONObject? {
            // Rustify format: `trackName` plus one of its own fields (avoids collision with the
            // legacy Spotify format, which also uses `trackName` but lacks `startedAt`/`counted`/`source`).
            val nameR = o.optString("trackName", "")
            if (nameR.isNotBlank() && (o.has("startedAt") || o.has("counted") || o.has("source"))) {
                return o // already Rustify
            }

            // --- Spotify format detection ---
            val isOld = o.has("endTime") || o.has("msPlayed")

            val trackName: String
            val artistName: String
            val albumName: String
            val ms: Long
            val startedAt: Long
            val trackId: String

            if (isOld) {
                // Legacy format: StreamingHistory*.json
                trackName = o.optString("trackName", "")
                if (trackName.isBlank()) return null
                artistName = o.optString("artistName", "")
                albumName = "" // the legacy format has no album
                ms = o.optLong("msPlayed", 0)
                startedAt = parseOldEndTime(o.optString("endTime", ""))
                trackId = "" // the legacy format has no URI/ID
            } else {
                // Newer format: Extended Streaming History
                trackName = o.optString("master_metadata_track_name", "")
                if (trackName.isBlank()) return null
                artistName = o.optString("master_metadata_artist_name", "")
                albumName = o.optString("master_metadata_album_album_name", "")
                ms = o.optLong("ms_played", 0)
                startedAt = runCatching {
                    java.time.Instant.parse(o.optString("ts", "")).toEpochMilli()
                }.getOrDefault(0L)
                val uri = o.optString("spotify_track_uri", "")
                trackId = if (uri.startsWith("spotify:track:")) uri.removePrefix("spotify:track:") else ""
            }

            return JSONObject().apply {
                put("trackId", trackId)
                put("isrc", "")
                put("trackName", trackName)
                put("artistIds", JSONArray())
                put("artistNames", JSONArray().apply { if (artistName.isNotBlank()) put(artistName) })
                put("albumId", "")
                put("albumName", albumName)
                put("durationMs", 0)
                put("startedAt", startedAt)
                put("listenedMs", ms)
                put("counted", ms >= 30_000)
                put("completed", false)
                put("source", "spotify")
            }
        }

        /**
         * Parses the `endTime` from Spotify's legacy format ("yyyy-MM-dd HH:mm"), given in the
         * user's local time zone at export time. Returns epoch millis, or 0 on failure.
         */
        private fun parseOldEndTime(endTime: String): Long {
            if (endTime.isBlank()) return 0L
            return runCatching {
                val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                java.time.LocalDateTime.parse(endTime, fmt)
                    .atZone(java.time.ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            }.getOrDefault(0L)
        }
    }
}
