package com.varuna.rustify.metrics

import com.varuna.rustify.bridge.TrackRef
import com.varuna.rustify.ui.AppPlatform
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.NativeEngine
import com.varuna.rustify.bridge.RustifyJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * Tracks listening sessions with a 30s / 50%-of-duration threshold, and hands each finished play to
 * the core, which keeps the history (`core_engine/src/metrics/`).
 *
 * The history used to live here, as `filesDir/metrics.json`: rewritten whole on every play and cut to
 * the last 5 000 entries — after an import too, by insertion order, so importing an old Spotify
 * history deleted the newest plays. The core keeps every play in yearly append-only segments; this
 * class only decides what a play *is*. The old file is imported once and kept as
 * `metrics.json.migrated`.
 *
 * Hooked from [AudioPlayerService]: onTrackStarted, onProgress, onEnded, onError, flush.
 */
class ListeningTracker(private val platform: AppPlatform) {
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
        val startedAt = System.currentTimeMillis()
        cur = Session(track, startedAt)
        if (ListenBrainz.token(platform) != null) {
            scope.launch { ListenBrainz.playingNow(platform, ListenBrainz.listenOf(track, startedAt)) }
        }
        if (LastFm.credentials(platform) != null) {
            scope.launch { LastFm.nowPlaying(platform, ListenBrainz.listenOf(track, startedAt)) }
        }
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
        s.listenedMs += creditedListenMs(posDelta, wallDelta)
        s.lastPos = posMs
        s.lastTickAt = now
    }

    fun onEnded() { cur?.completed = true; flush() }
    fun onError() { cur?.errored = true; flush() }

    fun flush() {
        val s = cur ?: return; cur = null
        val event = playEventOf(s.track, s.startedAt, s.listenedMs, s.completed, s.errored) ?: return
        // ListenBrainz and Last.fm share their own, stricter rule (4 min or half); the core queues
        // each if offline.
        if (!s.errored && ListenBrainz.qualifies(s.listenedMs, s.track.durationMs.toLong())) {
            val listen = ListenBrainz.listenOf(s.track, s.startedAt)
            if (ListenBrainz.token(platform) != null) {
                scope.launch { runCatching { ListenBrainz.submit(platform, listen) } }
            }
            if (LastFm.credentials(platform) != null) {
                scope.launch { runCatching { LastFm.submit(platform, listen) } }
            }
        }
        scope.launch {
            migrateLegacyFile(platform)
            val answer = runCatching { NativeEngine.recordPlay(RustifyJson.encodeToString(PlayEvent.serializer(), event)) }
            if (answer.isFailure || failed(answer.getOrNull())) {
                com.varuna.rustify.platform.Log.w(TAG, "could not record a play: ${answer.exceptionOrNull()?.message ?: answer.getOrNull()}")
            }
        }
    }

    companion object {
        private const val TAG = "ListeningTracker"
        private const val LEGACY_FILE = "metrics.json"
        private const val MIGRATED_SUFFIX = ".migrated"
        private val migration = Mutex()

        /** Whether a bridge answer is the failure envelope rather than data. */
        private fun failed(json: String?): Boolean {
            if (json == null) return true
            val obj = runCatching { RustifyJson.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return false
            return obj["success"]?.jsonPrimitive?.booleanOrNull == false
        }

        private fun errorOf(json: String): String =
            runCatching { (RustifyJson.parseToJsonElement(json) as JsonObject)["error"]?.jsonPrimitive?.contentOrNull }
                .getOrNull() ?: "unknown engine error"

        /**
         * Hands the pre-4.0 `metrics.json` to the core, once. Renamed rather than deleted, so a
         * failed migration can be retried by renaming it back.
         */
        suspend fun migrateLegacyFile(platform: AppPlatform) = kotlinx.coroutines.withContext(Dispatchers.IO) { migration.withLock {
            val file = File(platform.filesDir, LEGACY_FILE)
            if (!file.exists()) return@withLock
            val answer = runCatching { NativeEngine.importPlayHistory(file.readText()) }.getOrNull()
            if (answer != null && !failed(answer)) {
                file.renameTo(File(platform.filesDir, LEGACY_FILE + MIGRATED_SUFFIX))
                com.varuna.rustify.platform.Log.i(TAG, "migrated metrics.json into the core: $answer")
            } else {
                com.varuna.rustify.platform.Log.w(TAG, "metrics.json migration failed, will retry: $answer")
            }
        } }

        /** Every play, oldest first. For export and backup — screens use [stats]. */
        suspend fun loadEvents(platform: AppPlatform): List<PlayEvent> {
            migrateLegacyFile(platform)
            val json = NativeEngine.allPlays()
            if (failed(json)) return emptyList()
            return runCatching {
                RustifyJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(PlayEvent.serializer()), json)
            }.getOrDefault(emptyList())
        }

        /** Every play as raw JSON, for the backup container (no per-event objects on the heap). */
        suspend fun loadEventsJson(platform: AppPlatform): JSONArray {
            migrateLegacyFile(platform)
            val json = NativeEngine.allPlays()
            return if (failed(json)) JSONArray() else runCatching { JSONArray(json) }.getOrDefault(JSONArray())
        }

        /** The Metrics screen's numbers for `[fromMs, toMs)`, in the device's time zone. */
        suspend fun stats(platform: AppPlatform, fromMs: Long, toMs: Long): PlayStats {
            migrateLegacyFile(platform)
            val json = NativeEngine.playStats(fromMs, toMs, zoneJson())
            if (failed(json)) throw IllegalStateException(errorOf(json))
            return RustifyJson.decodeFromString(PlayStats.serializer(), json)
        }

        /** Every row of one "top" list for `[fromMs, toMs)` as CSV — tracks, artists or albums. */
        suspend fun topCsv(platform: AppPlatform, fromMs: Long, toMs: Long, kind: String): String {
            migrateLegacyFile(platform)
            val json = NativeEngine.topCsv(fromMs, toMs, kind)
            if (failed(json)) throw IllegalStateException(errorOf(json))
            return RustifyJson.decodeFromString(kotlinx.serialization.serializer<String>(), json)
        }

        /**
         * The device zone as the offsets it has had, `[[fromMs, offsetMs], …]`, from 1970 to two
         * years ahead — what the core needs to put each play in its local hour and day, with DST.
         */
        fun zoneJson(zone: ZoneId = ZoneId.systemDefault()): String = zoneOffsetsJson(zone)

        /**
         * Exports the history in Spotify's Extended Streaming History format, which stats.fm and
         * Rustify itself import.
         *
         * `ts` is when the stream **ended**, as Spotify defines it; this used to write the start. And
         * only real Spotify tracks get a `spotify_track_uri` — a YouTube Music or local id is not one,
         * and `spotify:track:ytm:…` was what they used to get.
         */
        suspend fun exportSpotifyHistoryBytes(platform: AppPlatform): ByteArray {
            val arr = JSONArray()
            loadEvents(platform).forEach { e ->
                val spotifyId = TrackRef.parse(e.trackId)?.spotifyId
                arr.put(JSONObject().apply {
                    put("ts", Instant.ofEpochMilli(e.startedAt + e.listenedMs).toString())
                    put("username", "")
                    put("platform", "rustify")
                    put("ms_played", e.listenedMs)
                    put("conn_country", "")
                    if (spotifyId != null) put("spotify_track_uri", "spotify:track:$spotifyId")
                    put("master_metadata_track_name", e.trackName)
                    put("master_metadata_album_album_name", e.albumName)
                    put("master_metadata_album_artist_name", e.artistNames.firstOrNull().orEmpty())
                })
            }
            return arr.toString().toByteArray(Charsets.UTF_8)
        }

        /**
         * Imports a listening history: a Rustify export, Spotify's Extended Streaming History, or the
         * legacy `StreamingHistory*.json`. Duplicates are skipped and nothing is ever dropped.
         */
        suspend fun importHistory(platform: AppPlatform, inputStream: java.io.InputStream): PlayImportReport {
            migrateLegacyFile(platform)
            val text = inputStream.bufferedReader().use { it.readText() }
            val json = NativeEngine.importPlayHistory(text)
            if (failed(json)) throw IllegalArgumentException(errorOf(json))
            return RustifyJson.decodeFromString(PlayImportReport.serializer(), json)
        }

        /** [importHistory] for a backup container's raw events. */
        suspend fun importEvents(platform: AppPlatform, events: JSONArray): PlayImportReport =
            importHistory(platform, events.toString().byteInputStream())
    }
}

// creditedListenMs and playEventOf — what a play *is* — live in the shared module since 5.x
// (shared/.../metrics/PlayRules.kt), so the desktop and iOS count plays by the same rule.
