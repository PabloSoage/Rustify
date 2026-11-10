// sharedUi/src/commonMain/kotlin/com/varuna/rustify/player/PlayerModels.kt
package com.varuna.rustify.player

import com.varuna.rustify.bridge.FullTrack

// What the player shows the screens, moved out of AudioPlayerService.kt unchanged so the shared
// interface can read it on both platforms (docs serie-4.x/26).

data class PlaybackContext(
    val id: String,
    val label: String,
    val subtitle: String = "",
    val imageUrl: String = ""
) {
    // The org.json forms the saved playback state reads and writes (`toJson()`,
    // `PlaybackContext.fromJson(obj)`) are the app's, in bridge/SpotifyModelsJson.kt.
    companion object {
        /**
         * Your Spotify liked songs, as a context: one per account, so it has no id of its own. Kind
         * `liked`, which is what [com.varuna.rustify.resumeListening] reads back.
         */
        const val LIKED_SONGS_ID = "liked:spotify"
    }
}

data class AudioPlayerState(
    val currentTrack: FullTrack? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,  // true while resolving OR while ExoPlayer is buffering
    val isError: Boolean = false,       // true when stream could not be resolved/played
    val errorMessage: String = "",
    val queue: List<FullTrack> = emptyList(),
    val originalQueue: List<FullTrack> = emptyList(),
    val isShuffle: Boolean = false,
    val isRepeat: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferPercent: Int = 0,
    val isVideoMode: Boolean = false,
    val videoSizeRatio: Float? = null,
    // true when the current track resolved to a local file (local match or a "local:" track).
    // The UI uses it to turn the alternatives button green and indicate the current match.
    val isLocalSource: Boolean = false
)
