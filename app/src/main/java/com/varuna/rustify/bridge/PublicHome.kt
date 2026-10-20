package com.varuna.rustify.bridge

import android.content.Context
import com.varuna.rustify.R
import com.varuna.rustify.metrics.ListeningTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Home without a Spotify account (4.7). It used to be one card asking to connect, and nothing else.
 *
 * Built from what needs no account: your own listening history (albums you played lately) and
 * Spotify's public catalogue, which the anonymous token reads — the charts (Top 50 for the world and
 * for your country) and Spotify's own mood playlists. Same [BrowseSection] shape as the personal
 * Home, so the screen draws them the same way.
 *
 * Kept for the process: the charts change weekly, and Home is drawn often.
 */
object PublicHome {
    @Volatile private var cached: List<BrowseSection>? = null

    suspend fun sections(context: Context, repo: SpotifyRepository, refresh: Boolean = false): List<BrowseSection> =
        withContext(Dispatchers.IO) {
            if (!refresh) cached?.let { return@withContext it }
            val built = coroutineScope {
                val recent = async { recentAlbums(context) }
                val charts = async { charts(context, repo) }
                val moods = async { moods(context, repo) }
                listOfNotNull(recent.await(), charts.await(), moods.await())
            }
            if (built.isNotEmpty()) cached = built
            built
        }

    /** Albums of the songs you played lately, newest first, one per album. Spotify ones only. */
    private suspend fun recentAlbums(context: Context): BrowseSection? = runCatching {
        val now = System.currentTimeMillis()
        val plays = ListeningTracker.stats(context, now - 90L * 86_400_000L, now).recent
        val albums = plays
            .filter { TrackRef.parse(it.trackId)?.spotifyId != null && it.albumId.isNotBlank() }
            .distinctBy { it.albumId }
            .take(10)
            .map { e ->
                BrowseSectionItem.AlbumItem(
                    SimpleAlbum(
                        id = e.albumId,
                        name = e.albumName,
                        images = listOfNotNull(e.imageUrl.takeIf { it.isNotBlank() }?.let { SpotifyImage(it) }),
                        artists = e.artistNames.zip(e.artistIds) { n, id -> SimpleArtist(id = id, name = n) }
                    )
                )
            }
        if (albums.isEmpty()) null
        else BrowseSection(id = "public-recent", title = context.getString(R.string.home_recent_albums), items = albums)
    }.getOrNull()

    /** Spotify's own playlist for [query], if there is one: the editorial ones are owned by "spotify". */
    private suspend fun editorial(repo: SpotifyRepository, query: String): SimplePlaylist? = runCatching {
        repo.searchPlaylists(query, limit = 10).items.firstOrNull { it.owner?.id == "spotify" }
    }.getOrNull()

    private suspend fun charts(context: Context, repo: SpotifyRepository): BrowseSection? = coroutineScope {
        // The country's name in English is what the chart playlists are called ("Top 50 - Spain").
        val country = Locale.getDefault().getDisplayCountry(Locale.ENGLISH).takeIf { it.isNotBlank() }
        val queries = listOfNotNull("Top 50 Global", country?.let { "Top 50 $it" }, "Viral 50 Global", country?.let { "Viral 50 $it" })
        val found = queries.map { q -> async { editorial(repo, q) } }.awaitAll().filterNotNull().distinctBy { it.id }
        if (found.isEmpty()) null
        else BrowseSection(id = "public-charts", title = context.getString(R.string.home_charts), items = found.map { BrowseSectionItem.PlaylistItem(it) })
    }

    private suspend fun moods(context: Context, repo: SpotifyRepository): BrowseSection? = coroutineScope {
        val queries = listOf("chill", "focus", "workout", "party", "sleep", "happy")
        val found = queries.map { q -> async { editorial(repo, q) } }.awaitAll().filterNotNull().distinctBy { it.id }
        if (found.isEmpty()) null
        else BrowseSection(id = "public-moods", title = context.getString(R.string.home_moods), items = found.map { BrowseSectionItem.PlaylistItem(it) })
    }
}
