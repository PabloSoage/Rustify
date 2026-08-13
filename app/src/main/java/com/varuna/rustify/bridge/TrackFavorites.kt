package com.varuna.rustify.bridge

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One answer to "is this song saved, and how do I flip it" for every kind of track Rustify plays.
 *
 * Saving a song was only reachable from the screens that list it, because each of those knows which
 * of the three stores the song belongs to and calls it directly: Spotify likes for a plain id, the
 * local favorites file for `local:`, the YouTube Music library for `ytm:`. Anywhere that holds a
 * track without knowing where it came from — the notification, the miniplayer — could not offer it at
 * all without repeating that fork.
 *
 * So the fork lives here once. Everything else asks for a track and gets a boolean.
 *
 * [SpotifyRepository] is read through its singleton rather than constructed: its `init` assigns
 * `instance`, so building one from a service would quietly steal the identity of the one the UI is
 * using, and take the loaded likes with it. When there is no instance yet nothing is saved and
 * nothing can be — reported as "not saved", which is the honest answer for a library that has not
 * been loaded.
 */
object TrackFavorites {

    /** Process-wide, like the state it reads: rebuilding one per recomposition would reload it. */
    @Volatile private var ytmRepo: YtMusicRepository? = null

    private fun ytm(context: Context): YtMusicRepository =
        ytmRepo ?: synchronized(this) {
            ytmRepo ?: YtMusicRepository(context.applicationContext).also { ytmRepo = it }
        }

    /**
     * Whether [track] is in the user's library.
     *
     * Reads Compose snapshot state (`likedTrackIds`, the local favorites map, the YTM favorites
     * list), so a composable calling this recomposes when the answer changes, and a
     * `snapshotFlow { }` outside composition emits — which is how the notification keeps its icon
     * honest while the song is hearted from somewhere else.
     */
    fun isSaved(context: Context, track: FullTrack?): Boolean {
        val id = track?.id ?: return false
        return when {
            id.startsWith("ytm:") -> ytm(context).isFavorite(id.removePrefix("ytm:"))
            id.startsWith("local:") -> SpotifyRepository.instance?.isLocalFavorite(id) == true
            else -> SpotifyRepository.instance?.isTrackLiked(id) == true
        }
    }

    /**
     * Flips it, and returns the state it ended up in — read back from the store rather than assumed,
     * so a Spotify call that failed reports the truth instead of an optimistic toggle that the next
     * refresh would undo.
     *
     * The two local stores are written on the main thread: they are Compose state that the UI is
     * observing, and that is the thread the screens already write them from. Only the Spotify path
     * touches the network, and it does its own dispatching.
     */
    suspend fun toggle(context: Context, track: FullTrack?): Boolean {
        val id = track?.id ?: return false
        return when {
            id.startsWith("ytm:") -> {
                val vid = id.removePrefix("ytm:")
                val repo = ytm(context)
                withContext(Dispatchers.Main) {
                    repo.toggleFavorite(
                        YtmTrack(
                            videoId = vid,
                            title = track.name,
                            artists = track.artists.map { YtmArtistRef(it.id, it.name) },
                            albumId = null,
                            durationSec = track.durationMs / 1000,
                            thumbnailUrl = track.album?.images?.firstOrNull()?.url ?: ""
                        )
                    )
                    repo.isFavorite(vid)
                }
            }
            id.startsWith("local:") -> {
                val repo = SpotifyRepository.instance ?: return false
                withContext(Dispatchers.Main) {
                    repo.toggleLocalFavorite(id)
                    repo.isLocalFavorite(id)
                }
            }
            else -> {
                val repo = SpotifyRepository.instance ?: return false
                repo.toggleLikeTrack(track)
                repo.isTrackLiked(id)
            }
        }
    }
}
