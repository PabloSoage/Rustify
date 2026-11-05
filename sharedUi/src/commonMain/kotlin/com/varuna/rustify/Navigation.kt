// sharedUi/src/commonMain/kotlin/com/varuna/rustify/Navigation.kt
// The app's screens and how a link or a launcher shortcut lands on one: shared by the phone and the
// desktop, whose root is the same RustifyApp (docs serie-4.x/26).
package com.varuna.rustify

import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.SpotifyImage
import com.varuna.rustify.bridge.SpotifyRepository
import com.varuna.rustify.player.PlayerController
import com.varuna.rustify.ui.AppPlatform
import com.varuna.rustify.ui.appString
import com.varuna.rustify.ui.resources.Res
import com.varuna.rustify.ui.resources.home_error_loading
import com.varuna.rustify.ui.resources.home_offline
import kotlinx.coroutines.Dispatchers
import com.varuna.rustify.platform.IO
import kotlinx.coroutines.launch

sealed class Screen {
    object Home : Screen()
    object Search : Screen()
    object Library : Screen()
    object NewReleases : Screen()
    data class PlaylistDetail(val id: String, val name: String, val images: List<SpotifyImage>) : Screen()
    data class AlbumDetail(val id: String, val name: String, val images: List<SpotifyImage>) : Screen()
    data class ArtistDetail(val id: String) : Screen()
    data class ArtistAllSongs(val id: String, val name: String) : Screen()
    data class TrackDetail(val id: String, val openQueue: Boolean = false) : Screen()
    data class RadioDetail(val trackId: String, val trackName: String) : Screen()
    // YouTube Music first-class destinations (no more embedded tab screen).
    object YtmSearch : Screen()
    data class YtmAlbumDetail(val browseId: String, val title: String) : Screen()
    data class YtmArtistDetail(val channelId: String, val name: String) : Screen()
    data class YtmPlaylistDetail(val playlistId: String, val title: String) : Screen()
    data class YtmLocalPlaylistDetail(val localId: String) : Screen()
    object Settings : Screen()
    object WebPlayer : Screen()
    object Downloads : Screen()
    // Custom downloads (paste a URL, choose video/audio quality, separate folder).
    object CustomDownload : Screen()
    object LogViewer : Screen()
    // Settings tools (4.7.1): a chat with Gemini Nano, and the audio analyser.
    object NanoChat : Screen()
    object AudioLab : Screen()
    object Metrics : Screen()
    // YouTube match editor (list with names; edit/preview/delete, add manually).
    object MatchEditor : Screen()
    // AI DJ (automix / natural-language requests).
    object Dj : Screen()
    object Travel : Screen()
    /**
     * Travel with a preloaded initial destination (when opened from a shared Google Maps link or a
     * geo: link). [lat]/[lon] are reverse-geocoded to a label via Nominatim/Google.
     */
    data class TravelWithDestination(val lat: Double, val lon: Double, val label: String? = null) : Screen()
}

/** The launcher shortcuts' targets (Android's `AppShortcuts` publishes them). */
object ShortcutTarget {
    const val RESUME = "resume"
    const val SEARCH = "search"
    const val DJ = "dj"
}

/**
 * Route a deep-link token ("track:ID" / "album:ID" / "playlist:ID" / "artist:ID" /
 * "NOW_PLAYING", or a legacy bare track id) onto the navigation stack.
 */
fun navigateDeepLink(
    deepLink: String,
    navigationStack: androidx.compose.runtime.snapshots.SnapshotStateList<Screen>,
    audioPlayerService: PlayerController,
    /** Follows a Maps short link to its place (network; the platform's, Travel is Android's). */
    resolveMapsLink: suspend (String) -> Screen.TravelWithDestination? = { null },
) {
    if (deepLink == "NOW_PLAYING") {
        val currentTrackId = audioPlayerService.state.value.currentTrack?.id
        if (currentTrackId != null) {
            val last = navigationStack.lastOrNull()
            if (last !is Screen.TrackDetail || last.id != currentTrackId) {
                navigationStack.add(Screen.TrackDetail(currentTrackId))
            }
        }
        return
    }
    val parts = deepLink.split(":", limit = 2)
    if (parts.size != 2) {
        navigationStack.add(Screen.TrackDetail(deepLink))
        return
    }
    when (parts[0]) {
        "track" -> navigationStack.add(Screen.TrackDetail(parts[1]))
        "album" -> navigationStack.add(Screen.AlbumDetail(parts[1], "", emptyList()))
        "playlist" -> navigationStack.add(Screen.PlaylistDetail(parts[1], "", emptyList()))
"artist" -> navigationStack.add(Screen.ArtistDetail(parts[1]))
        // Shared Google Maps / geo: link.
        // Launcher shortcuts (4.9) — see AppShortcuts.
        "shortcut" -> when (parts[1]) {
            ShortcutTarget.RESUME -> {
                if (!audioPlayerService.state.value.isPlaying) audioPlayerService.play()
                navigateDeepLink("NOW_PLAYING", navigationStack, audioPlayerService, resolveMapsLink)
            }
            ShortcutTarget.SEARCH -> if (navigationStack.lastOrNull() != Screen.Search) navigationStack.add(Screen.Search)
            ShortcutTarget.DJ -> if (navigationStack.lastOrNull() != Screen.Dj) navigationStack.add(Screen.Dj)
        }
        "travel" -> {
            val inner = parts[1].split(",", limit = 3)
            if (inner.size >= 2) {
                val lat = inner[0].toDoubleOrNull() ?: return
                val lon = inner[1].toDoubleOrNull() ?: return
                val label = inner.getOrNull(2)?.replace("+", " ")
                navigationStack.add(Screen.TravelWithDestination(lat, lon, label))
            }
        }
        // Google Maps short link / place: resolve the redirect on IO (not on the main thread).
        "travelresolve" -> {
            val url = parts[1]
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                val target = resolveMapsLink(url)
                if (target != null) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    navigationStack.add(target)
                }
            }
        }
        // YouTube Music deep links.
        "ytmtrack" -> {
            // Build a minimal ytm: FullTrack and hand it to the existing player pipeline.
            val yt = FullTrack(
                id = "ytm:${parts[1]}",
                name = "YouTube Music",
                externalUri = "https://music.youtube.com/watch?v=${parts[1]}",
                explicit = false, durationMs = 0, isrc = "",
                artists = emptyList(), album = null
            )
            audioPlayerService.loadPlaylist(listOf(yt), 0)
        }
        "ytmalbum" -> navigationStack.add(Screen.YtmAlbumDetail(parts[1], ""))
        "ytmartist" -> navigationStack.add(Screen.YtmArtistDetail(parts[1], ""))
        "ytmplaylist" -> navigationStack.add(Screen.YtmPlaylistDetail(parts[1], ""))
        else -> navigationStack.add(Screen.TrackDetail(deepLink))
    }
}

/**
 * Puts a "continue listening" session back on, from the track and position it stored.
 *
 * The session keeps track **ids**, not tracks — ids are the only thing every platform agrees on —
 * so the queue is refetched from the context it came from. That is one request, because the context
 * id says which: `album:X` is one album fetch, `playlist:X` is one playlist fetch. Refetching also
 * means a playlist that changed since gets its current contents rather than a stale copy.
 *
 * If the track it left off on is no longer there, playback starts at the top rather than refusing:
 * the user asked for this album, and giving them the album is closer to right than giving them
 * nothing.
 */
suspend fun resumeListening(
    session: com.varuna.rustify.bridge.ListeningSession,
    spotifyRepo: SpotifyRepository,
    audioPlayerService: PlayerController,
    /**
     * Called the moment something is actually playing — which is now well before this function
     * returns, because the rest of the context is fetched afterwards. The spinner on the row keys on
     * this rather than on completion; leaving it spinning while music plays would say the opposite
     * of what is happening.
     */
    onPlaying: () -> Unit = {}
) {
    val (kind, id) = session.id.split(':', limit = 2).let {
        if (it.size == 2) it[0] to it[1] else return
    }
    val context = com.varuna.rustify.player.PlaybackContext(
        id = session.id,
        label = session.label,
        subtitle = session.subtitle,
        imageUrl = session.imageUrl
    )
    val target = session.currentTrackId

    // Play FIRST, fetch the list after.
    //
    // This used to be the other way round, and it is why resuming took several seconds even for a
    // track already sitting in the cache: the whole context was refetched before anything played,
    // which since T8 means up to twenty round trips for a long playlist. Nothing about that was
    // needed to start — the session already knows which track, at which second, with which title and
    // artwork, and `StreamRouting.cachedUrlFor` is asked before any backend is chosen, so a cached or
    // on-device track needs no network at all.
    //
    // The stand-in queue holds one track. The real one replaces it below, without interrupting it.
    val stub = session.stub()
    if (stub != null) {
        audioPlayerService.loadPlaylist(
            listOf(stub),
            0,
            context = context,
            startPositionMs = session.positionMs.coerceAtLeast(0L)
        )
        onPlaying()
    }

    val tracks = runCatching {
        when (kind) {
            // Every page, not just the first: a session left off at track 120 used to resume at
            // track 1 because the track it wanted was never fetched. That paging now lives in the
            // repository, because "the tracks it has scrolled to" being read as "the whole album"
            // turned out to be a bug three screens had as well.
            "album" -> spotifyRepo.getAllAlbumTracks(id)
            "playlist" -> spotifyRepo.getAllPlaylistTracks(id)
            // A radio is a generated list, so there is nothing to re-fetch by id: it is regenerated
            // from the track it was seeded on. Resuming it means the same seed, which gives a list
            // that starts where you were even when its tail differs.
            "radio" -> spotifyRepo.getTrackRadio(id)
            else -> emptyList()
        }
    }.getOrElse { emptyList() }

    if (tracks.isEmpty()) {
        // No network, or the context is gone. With a stand-in playing, that is a single track rather
        // than nothing — which is the whole point of starting before fetching.
        return
    }

    if (stub != null) {
        audioPlayerService.replaceQueuePreservingCurrent(tracks, target)
        return
    }

    // No stand-in: a session stored before the title and artist were kept. The old path, which is
    // slower but still correct.
    val index = tracks.indexOfFirst { it.id == target }.takeIf { it >= 0 } ?: 0
    // Only start mid-track when we landed on the track that was actually left off on -- dropping two
    // minutes into a different song is worse than starting it. The position goes IN, rather than a
    // seekTo() after the call: resolving a track is asynchronous, so by the time this returns the
    // player is not holding it yet and the start position has already been read as zero. That is why
    // resuming always began at the top of the song.
    val startAt = if (tracks[index].id == target) session.positionMs.coerceAtLeast(0L) else 0L
    audioPlayerService.loadPlaylist(tracks, index, context = context, startPositionMs = startAt)
    onPlaying()
}

/** What Home says when its sections did not load: the engine's words, unless there is no network. */
suspend fun homeError(platform: AppPlatform, engineMessage: String?): String =
    if (!platform.isOnline()) appString(Res.string.home_offline)
    else engineMessage ?: appString(Res.string.home_error_loading)
