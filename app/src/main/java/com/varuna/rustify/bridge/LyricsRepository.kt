package com.varuna.rustify.bridge

import android.content.Context

/** A single synchronized lyric line. */
data class LyricLine(
    val timeMs: Long,
    val text: String
)

/** Lyrics result: synced lines (empty if none) + a plain-text fallback. */
data class LyricsResult(
    val synced: List<LyricLine>,
    val plain: String?
)

/**
 * Lyrics fetcher — now a **provider chain** (see [LyricsProviders] / [LyricsSettings]). The enabled
 * providers are tried in the user's configured order and the first non-empty result wins; if an upper
 * provider resolves, the lower ones are not queried. LRCLIB is the default (enabled) provider.
 */
object LyricsRepository {

    // trackId -> result. null means "tried and failed" is NOT cached, so it retries next time.
    private val cache = mutableMapOf<String, LyricsResult>()

    suspend fun getLyrics(
        context: Context,
        trackId: String,
        artist: String,
        title: String,
        durationSec: Int
    ): LyricsResult? {
        cache[trackId]?.let { return it }

        // Clean the query the same way for every provider (strip feat/parenthetical, first artist).
        val cleanTitle = title.replace(Regex("\\(.*?\\)|\\[.*?]"), "").trim()
        val cleanArtist = artist.split(",").firstOrNull()?.trim() ?: artist

        for (entry in LyricsSettings.load(context)) {
            if (!entry.enabled) continue
            val provider = LyricsProviders.byId(entry.id) ?: continue
            val res = runCatching { provider.fetch(cleanArtist, cleanTitle, durationSec) }.getOrNull()
            if (res != null && (res.synced.isNotEmpty() || !res.plain.isNullOrBlank())) {
                cache[trackId] = res
                return res
            }
        }
        return null
    }

    fun invalidateLyrics(trackId: String) {
        cache.remove(trackId)
    }
}
