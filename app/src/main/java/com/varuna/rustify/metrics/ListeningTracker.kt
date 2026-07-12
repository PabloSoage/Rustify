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
        fun loadEvents(context: Context): List<JSONObject> {
            val file = File(context.filesDir, "metrics.json")
            if (!file.exists()) return emptyList()
            return runCatching {
                val arr = JSONArray(file.readText())
                (0 until arr.length()).map { arr.getJSONObject(it) }
            }.getOrDefault(emptyList())
        }
    }
}
