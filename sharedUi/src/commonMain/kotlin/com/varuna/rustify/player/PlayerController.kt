// sharedUi/src/commonMain/kotlin/com/varuna/rustify/player/PlayerController.kt
package com.varuna.rustify.player

import androidx.compose.runtime.staticCompositionLocalOf
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.LyricsResult
import kotlinx.coroutines.flow.StateFlow

/**
 * The player as the screens see it (docs serie-4.x/26): what they read and what they ask for, and
 * nothing of how it plays. Android's `AudioPlayerService` (Media3) implements it as it was; the desktop
 * implements it over the core's engine. The signatures are the service's own, so moving a screen to
 * the shared interface changes its imports and nothing else.
 *
 * Something a platform cannot do is answered by [can] rather than left out: a screen asks before it
 * shows the control, so the feature is explained where it is missing instead of vanishing.
 */
interface PlayerController {
    val state: StateFlow<AudioPlayerState>

    /** The current track's lyrics, fetched ahead so the player opens with them. */
    val preloadedLyrics: StateFlow<LyricsResult?>
    val preloadedLyricsTrackId: String?

    /** The track and position playback resumed at after a restart, until dismissed. */
    val resumedAt: StateFlow<Pair<String, Long>?>
    val abRepeat: StateFlow<AbRepeat>
    val savedQueues: StateFlow<List<SavedQueues.SavedQueue>>
    val activeSavedQueue: StateFlow<String?>
    val sleepTracks: StateFlow<Int>
    val sleepTimer: StateFlow<Long?>
    val isWebPlayerMode: Boolean

    fun can(feature: PlayerFeature): Boolean

    fun loadPlaylist(
        tracks: List<FullTrack>,
        initialIndex: Int = 0,
        keepShuffle: Boolean = false,
        context: PlaybackContext? = null,
        startPositionMs: Long = 0L,
    )
    fun loadAndPlay(track: FullTrack)
    fun shufflePlay(tracks: List<FullTrack>)
    fun playPreview(track: FullTrack, youtubeVideoId: String)
    fun playSpecificTrackInQueue(trackId: String, youtubeId: String? = null, queueIndex: Int? = null)
    fun retryCurrentTrack(youtubeId: String? = null, fallbackTrackId: String? = null, isAutoRetry: Boolean = false)

    fun play()
    fun pause(immediate: Boolean = false)
    fun togglePlayPause()
    fun seekTo(positionMs: Long)
    fun skipToNext()
    fun skipToPrevious()
    fun cyclePlaybackMode()
    fun applyPlaybackSpeed()
    fun currentQueuePosition(st: AudioPlayerState): Int

    fun enqueue(track: FullTrack)
    fun enqueueAll(tracks: List<FullTrack>)
    fun moveQueueItem(fromIndex: Int, toIndex: Int)
    fun removeFromQueue(index: Int)
    fun removeCachedStreamUrl(trackId: String)

    fun startOver()
    fun dismissResumed()
    fun pressAbRepeat()

    fun refreshSavedQueues()
    fun saveQueueAs(name: String, defaultPrefix: String)
    fun switchToSavedQueue(name: String)
    fun deleteSavedQueue(name: String)

    fun setSleepTimer(minutes: Int)
    fun setSleepAfterTracks(songs: Int)

    fun switchToVideoStream(videoUrl: String, audioUrl: String? = null)
    fun switchToAudioStream()
    fun setWebPlayerMode(enabled: Boolean)

    /** Writes the session to disk now (the app is going to the background). */
    fun saveNow()

    companion object {
        /** [setSleepTimer] argument: stop when the current track ends. */
        const val SLEEP_MINUTES_TRACK_END = -1
    }
}

/** What not every platform's player can do; see [PlayerController.can]. */
enum class PlayerFeature {
    /** The music video in the player (Media3's video surface). */
    VIDEO,
    /** Spotify's own web player as the audio source. */
    WEB_PLAYER,
    /** Speed and pitch. */
    SPEED,
    /** The system equaliser, AutoEq, bit-perfect and the other Android audio effects. */
    AUDIO_EFFECTS,
}

/** The player of the running app, provided at the root of the interface. */
val LocalPlayer = staticCompositionLocalOf<PlayerController> { error("no PlayerController provided") }
