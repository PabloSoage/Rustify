package com.varuna.rustify.bridge

import android.content.Context

/** A single synchronized lyric line. */
data class LyricLine(
    val timeMs: Long,
    val text: String
)

/** Lyrics result: synced lines (empty if none) + a plain-text fallback, and who answered. */
data class LyricsResult(
    val synced: List<LyricLine>,
    val plain: String?,
    /** The provider's id ([LyricsProviders]), or [LyricsRepository.LOCAL] for the file's own. */
    val providerId: String? = null
)

/**
 * Lyrics fetcher — now a **provider chain** (see [LyricsProviders] / [LyricsSettings]). The enabled
 * providers are tried in the user's configured order and the first non-empty result wins; if an upper
 * provider resolves, the lower ones are not queried. LRCLIB is the default (enabled) provider.
 */
object LyricsRepository {
    /** The id of the track's own lyrics (a .lrc beside the file, or its tag). */
    const val LOCAL = "local"

    private const val PREFS = "rustify_settings"
    /** trackId → providerId the user picked by swiping, as a JSON object. */
    private const val KEY_CHOICE = "lyrics_provider_choice"

    // trackId -> result. null means "tried and failed" is NOT cached, so it retries next time.
    private val cache = mutableMapOf<String, LyricsResult>()
    // "trackId|providerId" -> one provider's answer, for swiping between them without refetching.
    private val byProvider = mutableMapOf<String, LyricsResult>()

    private fun hasLines(r: LyricsResult?) = r != null && (r.synced.isNotEmpty() || !r.plain.isNullOrBlank())

    /** The ids to swipe between for [trackId]: the file's own first for a local track, then the enabled providers in order. */
    fun providersFor(context: Context, trackId: String): List<String> = buildList {
        if (TrackRef.isLocal(trackId)) add(LOCAL)
        LyricsSettings.load(context).filter { it.enabled && LyricsProviders.byId(it.id) != null }.forEach { add(it.id) }
    }

    /** The provider the user picked for [trackId], if any. */
    fun chosen(context: Context, trackId: String): String? = runCatching {
        org.json.JSONObject(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CHOICE, "{}") ?: "{}")
            .optString(trackId).ifBlank { null }
    }.getOrNull()

    /**
     * Remembers [providerId] for [trackId]: the lyrics shown were the wrong song's, and the one that
     * got it right is what this song should open with next time. The newest 500 choices are kept.
     */
    fun choose(context: Context, trackId: String, providerId: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val map = runCatching { org.json.JSONObject(prefs.getString(KEY_CHOICE, "{}") ?: "{}") }.getOrDefault(org.json.JSONObject())
        map.remove(trackId)
        map.put(trackId, providerId)
        while (map.length() > 500) map.remove(map.keys().next())
        prefs.edit().putString(KEY_CHOICE, map.toString()).apply()
        byProvider["$trackId|$providerId"]?.let { cache[trackId] = it }
    }

    /** What [providerId] alone answers for the track; null when it has nothing. Cached per provider. */
    suspend fun fetchFrom(
        context: Context,
        trackId: String,
        providerId: String,
        artist: String,
        title: String,
        durationSec: Int
    ): LyricsResult? {
        byProvider["$trackId|$providerId"]?.let { return it }
        val res = if (providerId == LOCAL) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { LocalLyrics.read(context, trackId) }.getOrNull()
            }
        } else {
            // Clean the query the same way for every provider (strip feat/parenthetical, first artist).
            val cleanTitle = title.replace(Regex("\\(.*?\\)|\\[.*?]"), "").trim()
            val cleanArtist = artist.split(",").firstOrNull()?.trim() ?: artist
            val provider = LyricsProviders.byId(providerId) ?: return null
            runCatching { provider.fetchFor(trackId, cleanArtist, cleanTitle, durationSec) }.getOrNull()
        }?.takeIf { hasLines(it) }?.copy(providerId = providerId) ?: return null
        byProvider["$trackId|$providerId"] = res
        return res
    }

    suspend fun getLyrics(
        context: Context,
        trackId: String,
        artist: String,
        title: String,
        durationSec: Int
    ): LyricsResult? {
        cache[trackId]?.let { return it }
        val order = providersFor(context, trackId)
        // The one the user picked for this song first, then the usual order (the file's own first).
        val chosen = chosen(context, trackId)?.takeIf { it in order }
        for (id in listOfNotNull(chosen) + order.filter { it != chosen }) {
            val res = fetchFrom(context, trackId, id, artist, title, durationSec) ?: continue
            cache[trackId] = res
            return res
        }
        return null
    }

    fun invalidateLyrics(trackId: String) {
        cache.remove(trackId)
        byProvider.keys.removeAll { it.startsWith("$trackId|") }
    }
}
