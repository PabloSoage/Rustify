package com.varuna.rustify.metrics

import android.content.Context
import com.varuna.rustify.bridge.FullTrack
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * E70 — Rastreador de sesión de escucha con umbral 30s / 50% duración.
 * Persiste eventos crudos como JSON en `filesDir/metrics.json` (sin Room,
 * paridad con E30: la app no añade dependencias de BD).
 *
 * Hook en [AudioPlayerService]: onTrackStarted, onProgress, onEnded, onError, flush.
 */
class ListeningTracker(private val appContext: Context) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var cur: Session? = null

    private data class Session(
        val track: FullTrack, val startedAt: Long,
        var lastPos: Long = 0, var listenedMs: Long = 0,
        var completed: Boolean = false, var errored: Boolean = false
    )

    fun onTrackStarted(track: FullTrack) {
        if (track.id == null) return
        if (cur?.track?.id == track.id) return
        flush()
        cur = Session(track, System.currentTimeMillis())
    }

    fun onProgress(posMs: Long) {
        val s = cur ?: return
        val delta = posMs - s.lastPos
        if (delta in 1..2000) s.listenedMs += delta
        s.lastPos = posMs
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
            // Rotación: mantener las últimas ~5000 entradas
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
         * E70 — Exporta las métricas a formato Streaming History de Spotify (compatible con
         * stats.fm) para que el usuario pueda importarlas manualmente en stats.fm u otra app.
         * Devuelve los bytes del JSON (UTF-8).
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
         * E70 — Importa un historial de escucha. Acepta tres formatos:
         *  1. JSONArray de eventos Rustify (el mismo formato de metrics.json).
         *  2. Streaming History de Spotify (array de objetos con `ts`/`ms_played`/`master_metadata_*`).
         *  3. Contenedor Rustify `{ "events": [...] }`.
         *
         * Devuelve el número de eventos añadidos. Updates metrics.json on disk.
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
            var added = 0
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val event = normalizeToRustifyEvent(o) ?: continue
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
         * Si el JSONObject ya tiene el campo `trackName` de Rustify, se considera Rustify y se
         * acepta tal cual. Si no, se intenta parsear como entrada de Spotify Streaming History
         * (campos `ts`, `ms_played`, `master_metadata_*`, `spotify_track_uri`).
         */
        private fun normalizeToRustifyEvent(o: JSONObject): JSONObject? {
            val nameR = o.optString("trackName", "")
            if (nameR.isNotBlank()) return o // ya es Rustify
            val name = o.optString("master_metadata_track_name", "")
            if (name.isBlank()) return null
            val ms = o.optLong("ms_played", 0)
            val startedAt = runCatching {
                java.time.Instant.parse(o.optString("ts", "")).toEpochMilli()
            }.getOrDefault(0L)
            val trackName = o.optString("master_metadata_track_name", "")
            val albumName = o.optString("master_metadata_album_album_name", "")
            val artistName = o.optString("master_metadata_artist_name", "")
            val uri = o.optString("spotify_track_uri", "")
            val trackId = if (uri.startsWith("spotify:track:")) uri.removePrefix("spotify:track:") else ""
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
    }
}
