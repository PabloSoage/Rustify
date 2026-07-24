package com.varuna.rustify.dj

import android.content.Context
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.SpotifyRepository

/**
 * DJ engine. Orchestrates the full flow:
 *  1. Builds the [DjContext] (metrics + player state) via [DjContextBuilder].
 *  2. Picks the [DjProvider] according to [DjSettings.mode] and asks it for a [DjPlan].
 *  3. Resolves the plan's seeds to real tracks via [SpotifyRepository] (search for
 *     artists/tracks/queries + radio of the best seed), deduplicates, and spreads by artist for
 *     smooth transitions.
 *
 * Does not touch AudioPlayerService: it returns the result and the caller (DjScreen) enqueues with
 * the public `loadPlaylist` / `enqueueAll` methods.
 */
class DjEngine(
    private val context: Context,
    private val repo: SpotifyRepository
) {
    data class Result(val intro: String, val tracks: List<FullTrack>)

    /**
     * Generates a DJ session. An empty [request] ⇒ automix from the seed (now playing / top).
     */
    suspend fun run(
        request: String,
        nowPlaying: FullTrack?,
        queue: List<FullTrack>,
        targetCount: Int = 30
    ): Result {
        val djContext = DjContextBuilder.build(context, nowPlaying, queue)
        val provider = providerFor()
        val plan = provider.plan(djContext, request)

        // If the provider already returned resolved tracks, keep them; otherwise resolve the seeds.
        val resolved = if (plan.tracks.isNotEmpty()) {
            plan.tracks
        } else {
            resolveSeeds(plan.seeds, nowPlaying, targetCount)
        }

        val alreadyQueued = queue.mapNotNull { it.id }.toHashSet()
        val ordered = spreadByArtist(
            resolved.filter { it.id != null && it.id !in alreadyQueued }.distinctBy { it.id }
        ).take(targetCount)

        return Result(intro = plan.intro, tracks = ordered)
    }

    private fun providerFor(): DjProvider = when (DjSettings.mode(context)) {
        DjMode.HEURISTIC -> HeuristicDjProvider()
        DjMode.LOCAL -> LocalDjProvider()
        DjMode.API -> ApiDjProvider(
            baseUrl = DjSettings.apiBaseUrl(context),
            model = DjSettings.apiModel(context),
            apiKey = DjSettings.apiKey(context)
        )
    }

    /**
     * Resolves soft seeds to tracks. Strategy:
     *  - Each seed becomes tracks by searching or by resolving an artist → top tracks.
     *  - The best seed (the first resolved track) feeds [SpotifyRepository.getTrackRadio], which is
     *    the primary similarity signal (Spotify computes the affinity).
     *  - If the pool is short, it is expanded with the seed's related artists.
     */
    private suspend fun resolveSeeds(
        seeds: List<DjSeed>,
        nowPlaying: FullTrack?,
        targetCount: Int
    ): List<FullTrack> {
        val pool = LinkedHashMap<String, FullTrack>()
        fun add(tracks: List<FullTrack>) {
            tracks.forEach { t -> t.id?.let { id -> if (!pool.containsKey(id)) pool[id] = t } }
        }

        // The radio seed must come from the REQUEST's resolved seed, not from the currently-playing
        // track. Seeding from now-playing made every session re-seed off whatever was on (so asking
        // "energetic" while a chill track played kept returning chill). Fall back to now-playing only
        // if the request produced no usable seed (e.g. a blank automix request that resolved nothing).
        var radioSeedTrackId: String? = null

        for (seed in seeds) {
            if (pool.size >= targetCount * 2) break
            runCatching {
                when (seed.type) {
                    DjSeed.Type.ARTIST -> {
                        val artist = repo.searchArtists(seed.value, limit = 1).items.firstOrNull()
                        if (artist != null) {
                            val top = repo.getArtistTopTracks(artist.id, limit = 10).items
                            add(top)
                            if (radioSeedTrackId == null) radioSeedTrackId = top.firstOrNull()?.id
                        }
                    }
                    DjSeed.Type.TRACK, DjSeed.Type.QUERY -> {
                        val found = repo.searchTracks(seed.value, limit = 8).items
                        add(found)
                        if (radioSeedTrackId == null) radioSeedTrackId = found.firstOrNull()?.id
                    }
                }
            }
        }

        // Fall back to the now-playing track only if the request produced no usable seed.
        if (radioSeedTrackId == null) radioSeedTrackId = nowPlaying?.id

        // Primary signal: radio of the best seed (≈50 similar tracks).
        radioSeedTrackId?.let { id ->
            runCatching { add(repo.getTrackRadio(id)) }
        }

        // Reinforcement when there are few: related artists of the first artist seed.
        if (pool.size < targetCount) {
            val artistSeed = seeds.firstOrNull { it.type == DjSeed.Type.ARTIST }?.value
                ?: nowPlaying?.artists?.firstOrNull()?.name
            if (artistSeed != null) {
                runCatching {
                    val artist = repo.searchArtists(artistSeed, limit = 1).items.firstOrNull()
                    if (artist != null) {
                        val related = repo.getRelatedArtists(artist.id, limit = 5).items
                        related.forEach { ra ->
                            if (pool.size >= targetCount * 2) return@forEach
                            runCatching { add(repo.getArtistTopTracks(ra.id, limit = 5).items) }
                        }
                    }
                }
            }
        }

        return pool.values.toList()
    }

    /**
     * Avoids two consecutive songs from the same artist (smooth transition). Distributes round-robin
     * by primary artist; deterministic given the same input (no randomness).
     */
    private fun spreadByArtist(tracks: List<FullTrack>): List<FullTrack> {
        if (tracks.size <= 2) return tracks
        val byArtist = LinkedHashMap<String, ArrayDeque<FullTrack>>()
        tracks.forEach { t ->
            val key = t.artists.firstOrNull()?.id?.ifBlank { null }
                ?: t.artists.firstOrNull()?.name?.lowercase()
                ?: "?"
            byArtist.getOrPut(key) { ArrayDeque() }.add(t)
        }
        val result = ArrayList<FullTrack>(tracks.size)
        var lastKey: String? = null
        while (result.size < tracks.size) {
            var progressed = false
            for ((key, deque) in byArtist) {
                if (deque.isEmpty()) continue
                if (key == lastKey && byArtist.count { it.value.isNotEmpty() } > 1) continue
                result.add(deque.removeFirst())
                lastKey = key
                progressed = true
            }
            // Safeguard: if everything remaining is from the same artist, drain it to avoid an infinite loop.
            if (!progressed) {
                byArtist.values.forEach { d -> while (d.isNotEmpty()) result.add(d.removeFirst()) }
            }
        }
        return result
    }
}
