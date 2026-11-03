// sharedUi/src/desktopMain/kotlin/com/varuna/rustify/player/DesktopPlayerController.kt
package com.varuna.rustify.player

import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.LyricsResult
import com.varuna.rustify.engine.PlayerUi
import com.varuna.rustify.engine.QueuePlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * [PlayerController] on the desktop, over [QueuePlayer] (the core's engine; the queue is still
 * `player::reduce`). What the engine does not do yet — shuffle, saved queues, A-B, the sleep timer,
 * video, speed — answers [can] with false or keeps its state empty, so the shared screens show the
 * control as unavailable rather than calling into nothing.
 */
class DesktopPlayerController(private val player: QueuePlayer, scope: CoroutineScope) : PlayerController {
    override val state: StateFlow<AudioPlayerState> =
        player.ui.map { it.toState() }.stateIn(scope, SharingStarted.Eagerly, player.ui.value.toState())

    private fun PlayerUi.toState() = AudioPlayerState(
        currentTrack = current,
        isPlaying = playing,
        isBuffering = loading,
        isError = error != null,
        errorMessage = error.orEmpty(),
        queue = queue,
        originalQueue = queue,
        positionMs = positionMs,
        durationMs = durationMs ?: current?.durationMs?.toLong() ?: 0L,
    )

    override val preloadedLyrics: StateFlow<LyricsResult?> = MutableStateFlow(null)
    override val preloadedLyricsTrackId: String? = null
    override val resumedAt: StateFlow<Pair<String, Long>?> = MutableStateFlow(null)
    override val abRepeat: StateFlow<AbRepeat> = MutableStateFlow(AbRepeat())
    override val savedQueues: StateFlow<List<SavedQueues.SavedQueue>> = MutableStateFlow(emptyList())
    override val activeSavedQueue: StateFlow<String?> = MutableStateFlow(null)
    override val sleepTracks: StateFlow<Int> = MutableStateFlow(0)
    override val sleepTimer: StateFlow<Long?> = MutableStateFlow(null)
    override val isWebPlayerMode: Boolean = false

    override fun can(feature: PlayerFeature): Boolean = false

    private var repeat = "off"

    override fun loadPlaylist(tracks: List<FullTrack>, initialIndex: Int, keepShuffle: Boolean, context: PlaybackContext?, startPositionMs: Long) =
        player.playAll(tracks, initialIndex, startPositionMs)

    override fun loadAndPlay(track: FullTrack) = player.playAll(listOf(track), 0)
    override fun shufflePlay(tracks: List<FullTrack>) = player.playAll(tracks.shuffled(), 0)

    // An audition of one recording is not something the engine can hint yet; play the track.
    override fun playPreview(track: FullTrack, youtubeVideoId: String) = loadAndPlay(track)

    override fun playSpecificTrackInQueue(trackId: String, youtubeId: String?, queueIndex: Int?) {
        val i = queueIndex ?: player.ui.value.queue.indexOfFirst { it.id == trackId }
        if (i >= 0) player.jumpTo(i)
    }

    override fun retryCurrentTrack(youtubeId: String?, fallbackTrackId: String?, isAutoRetry: Boolean) {
        val i = player.ui.value.index
        if (i >= 0) player.jumpTo(i)
    }

    override fun play() = player.play()
    override fun pause(immediate: Boolean) = player.pause()
    override fun togglePlayPause() = player.togglePause()
    override fun seekTo(positionMs: Long) = player.seek(positionMs)
    override fun skipToNext() = player.next()
    override fun skipToPrevious() = player.previous()

    /** Off → all → one, as the phone's button cycles. */
    override fun cyclePlaybackMode() {
        repeat = when (repeat) { "off" -> "all"; "all" -> "one"; else -> "off" }
        player.setRepeat(repeat)
    }

    override fun applyPlaybackSpeed() = Unit
    override fun currentQueuePosition(st: AudioPlayerState): Int = player.ui.value.index

    override fun enqueue(track: FullTrack) = enqueueAll(listOf(track))

    override fun enqueueAll(tracks: List<FullTrack>) {
        val ui = player.ui.value
        if (ui.current == null) {
            player.playAll(tracks, 0)
        } else {
            // The engine's queue is replaced whole: the same queue with these after it, at the same place.
            player.playAll(ui.queue + tracks, ui.index, ui.positionMs)
        }
    }

    override fun moveQueueItem(fromIndex: Int, toIndex: Int) = Unit
    override fun removeFromQueue(index: Int) = Unit
    override fun removeCachedStreamUrl(trackId: String) = Unit
    override fun startOver() = player.seek(0)
    override fun dismissResumed() = Unit
    override fun pressAbRepeat() = Unit
    override fun refreshSavedQueues() = Unit
    override fun saveQueueAs(name: String, defaultPrefix: String) = Unit
    override fun switchToSavedQueue(name: String) = Unit
    override fun deleteSavedQueue(name: String) = Unit
    override fun setSleepTimer(minutes: Int) = Unit
    override fun setSleepAfterTracks(songs: Int) = Unit
    override fun switchToVideoStream(videoUrl: String, audioUrl: String?) = Unit
    override fun switchToAudioStream() = Unit
    override fun setWebPlayerMode(enabled: Boolean) = Unit
    override fun saveNow() = Unit
}
