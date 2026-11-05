package com.varuna.rustify.bridge

import android.content.Context
import com.varuna.rustify.R
import com.varuna.rustify.platform.SharedPreferencesStore

/**
 * The phone's lyrics: the shared [LyricsChain] (providers, order, the per-song choice) over the app's
 * settings file, with the file's own lyrics read through Android's documents ([LocalLyrics]). One chain
 * for the process, so the caches outlive a screen.
 */
object LyricsRepository {
    /** The id of the track's own lyrics (a .lrc beside the file, or its tag). */
    const val LOCAL = LyricsChain.LOCAL

    @Volatile private var chain: LyricsChain? = null

    /** The process's chain; also what the shared screens read, through AppServices.lyrics. */
    fun chain(context: Context): LyricsChain = chain ?: synchronized(this) {
        chain ?: context.applicationContext.let { app ->
            LyricsChain(
                SharedPreferencesStore(app.getSharedPreferences("rustify_settings", Context.MODE_PRIVATE)),
                readLocal = { LocalLyrics.read(app, it) },
            )
        }.also { chain = it }
    }

    /** The ids to swipe between for [trackId]: the file's own first for a local track, then the enabled providers in order. */
    fun providersFor(context: Context, trackId: String): List<String> = chain(context).providersFor(trackId)

    /** The provider the user picked for [trackId], if any. */
    fun chosen(context: Context, trackId: String): String? = chain(context).chosen(trackId)

    /** Remembers [providerId] for [trackId], see [LyricsChain.choose]. */
    fun choose(context: Context, trackId: String, providerId: String) = chain(context).choose(trackId, providerId)

    /** What [providerId] alone answers for the track; null when it has nothing. Cached per provider. */
    suspend fun fetchFrom(
        context: Context,
        trackId: String,
        providerId: String,
        artist: String,
        title: String,
        durationSec: Int
    ): LyricsResult? = chain(context).fetchFrom(trackId, providerId, artist, title, durationSec)

    suspend fun getLyrics(
        context: Context,
        trackId: String,
        artist: String,
        title: String,
        durationSec: Int
    ): LyricsResult? = chain(context).getLyrics(trackId, artist, title, durationSec)

    fun invalidateLyrics(trackId: String) {
        chain?.invalidateLyrics(trackId)
    }

    /** The settings store the chain reads, for the Settings screen's order and switches. */
    fun store(context: Context) =
        SharedPreferencesStore(context.applicationContext.getSharedPreferences("rustify_settings", Context.MODE_PRIVATE))

    /** A provider's name as the phone shows it; the ids are [LyricsProviders]'. */
    fun nameRes(id: String): Int? = when (id) {
        SpotifyLyricsProvider.id -> R.string.lyrics_provider_spotify
        LrcLibProvider.id -> R.string.lyrics_provider_lrclib
        MusixmatchProvider.id -> R.string.lyrics_provider_musixmatch
        GeniusProvider.id -> R.string.lyrics_provider_genius
        PetitLyricsProvider.id -> R.string.lyrics_provider_petitlyrics
        LOCAL -> R.string.lyrics_provider_file
        else -> null
    }
}
