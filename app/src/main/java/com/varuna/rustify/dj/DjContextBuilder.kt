package com.varuna.rustify.dj

import android.content.Context
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.metrics.ListeningTracker

/**
 * Builds the [DjContext] from signals that already exist, read-only:
 *  - Top artists / tracks from the listening history, as the core ranks them for the Metrics screen
 *    ([ListeningTracker.stats] over all time). This used to be a second copy of that aggregation,
 *    over every event as a `JSONObject`; since 4.0 the ranking exists once.
 *  - Player state (current track + queue preview) passed in by the caller.
 */
object DjContextBuilder {

    suspend fun build(
        context: Context,
        nowPlaying: FullTrack?,
        queue: List<FullTrack>,
        topN: Int = 10
    ): DjContext {
        val stats = runCatching {
            ListeningTracker.stats(context, 0L, System.currentTimeMillis() + 1)
        }.getOrNull()

        val topArtists = stats?.topArtists.orEmpty().map { it.name }.filter { it.isNotBlank() }.take(topN)
        val topTracks = stats?.topTracks.orEmpty().take(topN).map {
            if (it.subtitle.isNotBlank()) "${it.subtitle.substringBefore(", ")} — ${it.name}" else it.name
        }

        val language = runCatching {
            java.util.Locale.getDefault().language
        }.getOrDefault("en")

        val nowStr = nowPlaying?.let { fmt(it) }
        // Queue preview: up to 10 tracks after the current one, so the DJ does not repeat what is already queued.
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
}
