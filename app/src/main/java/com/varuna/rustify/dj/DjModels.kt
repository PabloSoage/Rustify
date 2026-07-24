package com.varuna.rustify.dj

import com.varuna.rustify.bridge.FullTrack

/**
 * AI DJ. Models shared between the providers and the engine.
 *
 * A [DjProvider] receives the [DjContext] (user context: top artists/tracks from metrics + current
 * queue) and a natural-language request, and returns a [DjPlan] with an intro phrase and seeds
 * (artists/tracks/queries) — never final URIs. The [DjEngine] resolves those seeds to real tracks
 * via SpotifyRepository (search/radio) and builds the queue.
 */

/** DJ provider mode, persisted in `rustify_settings` under the [DjSettings.KEY_MODE] key. */
enum class DjMode { HEURISTIC, API, LOCAL }

/**
 * A seed that the [DjEngine] resolves to tracks. The LLM/heuristic returns "soft" seeds
 * (names/queries), not ids or URIs — the app materializes them against Spotify.
 */
data class DjSeed(
    val type: Type,
    /** Seed text: an artist name, "Artist - Song", or a free-form search query. */
    val value: String
) {
    enum class Type { ARTIST, TRACK, QUERY }
}

/**
 * Context passed to the provider. Everything is derived solely from public reads
 * (ListeningTracker.loadEvents + player state). See [DjContextBuilder].
 */
data class DjContext(
    val topArtists: List<String>,
    val topTracks: List<String>,
    /** "Artist — Song" of the current track, or null. */
    val nowPlaying: String?,
    /** Names of the upcoming queued tracks (so the DJ does not repeat them). */
    val queuePreview: List<String>,
    /** App language (es/en/ja/…), so the LLM writes the intro in that language. */
    val language: String
)

/**
 * Provider result. [intro] is the DJ's spoken/displayed phrase; either [tracks] (already resolved)
 * or [seeds] (to be resolved by the engine) is populated depending on the provider.
 */
data class DjPlan(
    val intro: String,
    val seeds: List<DjSeed> = emptyList(),
    val tracks: List<FullTrack> = emptyList()
)

/**
 * Contract for a DJ provider. Pure: it does not touch the player or the UI; it only produces a plan.
 * It must degrade gracefully (without throwing) by returning a plan with seeds derived from the
 * context when an external call fails.
 */
interface DjProvider {
    suspend fun plan(context: DjContext, request: String): DjPlan
}
