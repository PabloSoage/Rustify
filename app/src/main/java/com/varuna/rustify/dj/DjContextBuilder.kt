package com.varuna.rustify.dj

import android.content.Context
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.metrics.ListeningTracker
import org.json.JSONObject

/**
 * E90 — Construye el [DjContext] a partir de señales que YA existen, solo LEYENDO:
 *  - Top artistas / canciones de las métricas ([ListeningTracker.loadEvents]), con la misma
 *    lógica de agregación que MetricsScreen (agrupar por id, caer a nombre si no hay id).
 *  - Estado del player (pista actual + preview de la cola) que pasa el llamador.
 *
 * No modifica nada; no depende de MetricsScreen (privado), reimplementa la agregación mínima aquí.
 */
object DjContextBuilder {

    fun build(
        context: Context,
        nowPlaying: FullTrack?,
        queue: List<FullTrack>,
        topN: Int = 10
    ): DjContext {
        val events = runCatching {
            ListeningTracker.loadEvents(context).filter { it.optBoolean("counted") }
        }.getOrDefault(emptyList())

        val topArtists = aggregateArtists(events).take(topN)
        val topTracks = aggregateTracks(events).take(topN)

        val language = runCatching {
            java.util.Locale.getDefault().language
        }.getOrDefault("en")

        val nowStr = nowPlaying?.let { fmt(it) }
        // Preview de la cola: hasta 10 tras la actual, para que el DJ no repita lo ya encolado.
        val queuePreview = queue.take(10).map { fmt(it) }

        return DjContext(
            topArtists = topArtists,
            topTracks = topTracks,
            nowPlaying = nowStr,
            queuePreview = queuePreview,
            language = language
        )
    }

    private fun fmt(t: FullTrack): String {
        val artist = t.artists.firstOrNull()?.name.orEmpty()
        return if (artist.isNotBlank()) "$artist — ${t.name}" else t.name
    }

    /** Top nombres de artista por nº de reproducciones (paridad con MetricsScreen.aggregateByArtist). */
    private fun aggregateArtists(events: List<JSONObject>): List<String> {
        data class Acc(var plays: Int, var name: String, var nameAt: Long)
        val map = HashMap<String, Acc>()
        events.forEach { e ->
            val ids = e.optJSONArray("artistIds")
            val names = e.optJSONArray("artistNames") ?: return@forEach
            val startedAt = e.optLong("startedAt")
            for (i in 0 until names.length()) {
                val name = names.optString(i)
                if (name.isBlank()) continue
                val id = ids?.optString(i).orEmpty().ifEmpty { "n:$name" }
                val acc = map.getOrPut(id) { Acc(0, name, startedAt) }
                acc.plays += 1
                if (startedAt >= acc.nameAt) { acc.name = name; acc.nameAt = startedAt }
            }
        }
        return map.values.sortedByDescending { it.plays }.map { it.name }
    }

    /** Top canciones "Artista — Título" por reproducciones (paridad con aggregateBy trackId). */
    private fun aggregateTracks(events: List<JSONObject>): List<String> {
        data class Acc(var plays: Int, var name: String, var artist: String, var at: Long)
        val map = HashMap<String, Acc>()
        events.forEach { e ->
            val name = e.optString("trackName")
            if (name.isBlank()) return@forEach
            val id = e.optString("trackId").ifEmpty { "n:$name" }
            val artist = e.optJSONArray("artistNames")?.optString(0).orEmpty()
            val at = e.optLong("startedAt")
            val acc = map.getOrPut(id) { Acc(0, name, artist, at) }
            acc.plays += 1
            if (at >= acc.at) { acc.name = name; acc.artist = artist; acc.at = at }
        }
        return map.values.sortedByDescending { it.plays }.map {
            if (it.artist.isNotBlank()) "${it.artist} — ${it.name}" else it.name
        }
    }
}
