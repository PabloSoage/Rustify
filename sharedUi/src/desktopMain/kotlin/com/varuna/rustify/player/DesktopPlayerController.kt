// sharedUi/src/desktopMain/kotlin/com/varuna/rustify/player/DesktopPlayerController.kt
package com.varuna.rustify.player

import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.LyricsResult
import com.varuna.rustify.engine.PlayerUi
import com.varuna.rustify.engine.QueuePlayer
import com.varuna.rustify.platform.AppDirs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [PlayerController] on the desktop, over [QueuePlayer] (the core's engine; the queue is still
 * `player::reduce`). Shuffle, repeat, editing the queue, A-B, the sleep timer and saved queues work
 * as on the phone; what the engine does not do yet — video, speed, audio effects — answers [can]
 * with false, so the shared screens show the control as unavailable rather than calling into nothing.
 */
class DesktopPlayerController(
    private val player: QueuePlayer,
    private val scope: CoroutineScope,
    platform: com.varuna.rustify.ui.AppPlatform,
) : PlayerController {
    /** Off, shuffle, repeat one: the phone's single button cycles through them in that order. */
    private enum class Mode { OFF, SHUFFLE, REPEAT }

    private val mode = MutableStateFlow(Mode.OFF)

    override val state: StateFlow<AudioPlayerState> =
        combine(player.ui, mode) { ui, m -> ui.toState(m) }
            .stateIn(scope, SharingStarted.Eagerly, player.ui.value.toState(mode.value))

    private fun PlayerUi.toState(m: Mode) = AudioPlayerState(
        currentTrack = current,
        isPlaying = playing,
        isBuffering = loading,
        isError = error != null,
        errorMessage = error.orEmpty(),
        queue = queue,
        originalQueue = beforeShuffle ?: queue,
        isShuffle = m == Mode.SHUFFLE,
        isRepeat = m == Mode.REPEAT,
        positionMs = positionMs,
        durationMs = durationMs ?: current?.durationMs?.toLong() ?: 0L,
    )

    override val preloadedLyrics: StateFlow<LyricsResult?> = MutableStateFlow(null)
    override val preloadedLyricsTrackId: String? = null
    override val resumedAt: StateFlow<Pair<String, Long>?> = MutableStateFlow(null)
    override val isWebPlayerMode: Boolean = false

    override fun can(feature: PlayerFeature): Boolean = false

    // ── Playing ─────────────────────────────────────────────────────────────────────────────────

    override fun loadPlaylist(tracks: List<FullTrack>, initialIndex: Int, keepShuffle: Boolean, context: PlaybackContext?, startPositionMs: Long) {
        forgetShuffle()
        player.playAll(tracks, initialIndex, startPositionMs)
        // Choosing a song from a list plays that list in its order, as on the phone: the opposite
        // of asking for a random one. Only [shufflePlay] keeps it.
        if (mode.value == Mode.SHUFFLE) {
            if (keepShuffle) shuffleRest() else mode.value = Mode.OFF
        }
    }

    override fun loadAndPlay(track: FullTrack) = loadPlaylist(listOf(track), 0)

    override fun shufflePlay(tracks: List<FullTrack>) {
        forgetShuffle()
        if (mode.value == Mode.REPEAT) player.setRepeat("off")
        player.playAll(tracks.shuffled(), 0)
        beforeShuffle = tracks
        mode.value = Mode.SHUFFLE
    }

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
    override fun startOver() = player.seek(0)

    override fun applyPlaybackSpeed() = Unit
    override fun currentQueuePosition(st: AudioPlayerState): Int = player.ui.value.index

    // ── Shuffle and repeat ──────────────────────────────────────────────────────────────────────

    /** The queue as it was before shuffling, to go back to; null when not shuffled or edited since. */
    private var beforeShuffle: List<FullTrack>? = null

    private fun forgetShuffle() {
        beforeShuffle = null
    }

    /** The song playing stays where it is, first; the rest come after it in a random order. */
    private fun shuffleRest() {
        val ui = player.ui.value
        val current = ui.current ?: return
        beforeShuffle = ui.queue
        val rest = ui.queue.filterIndexed { i, _ -> i != ui.index }.shuffled()
        player.editQueue(listOf(current) + rest, 0)
    }

    override fun cyclePlaybackMode() {
        when (mode.value) {
            Mode.OFF -> {
                shuffleRest()
                mode.value = Mode.SHUFFLE
            }
            Mode.SHUFFLE -> {
                // Back to the order it had, at the song playing, unless the queue changed since.
                val ui = player.ui.value
                val original = beforeShuffle
                val at = original?.indexOfFirst { it === ui.current } ?: -1
                if (original != null && at >= 0) player.editQueue(original, at)
                forgetShuffle()
                player.setRepeat("one")
                mode.value = Mode.REPEAT
            }
            Mode.REPEAT -> {
                player.setRepeat("off")
                mode.value = Mode.OFF
            }
        }
    }

    // ── Editing the queue ───────────────────────────────────────────────────────────────────────

    override fun enqueue(track: FullTrack) = enqueueAll(listOf(track))

    override fun enqueueAll(tracks: List<FullTrack>) {
        val ui = player.ui.value
        if (ui.current == null) {
            loadPlaylist(tracks, 0)
            return
        }
        // Right after the song playing and whatever was queued by hand before, as the phone does:
        // at the end of the queue here, since the desktop does not tell the two apart yet.
        player.editQueue(ui.queue + tracks, ui.index)
        beforeShuffle = beforeShuffle?.plus(tracks)
    }

    override fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        val ui = player.ui.value
        if (fromIndex !in ui.queue.indices || toIndex !in ui.queue.indices || fromIndex == toIndex) return
        val list = ui.queue.toMutableList()
        list.add(toIndex, list.removeAt(fromIndex))
        // Where the playing song went: itself if it was moved, else shifted by the move past it.
        val current = when {
            ui.index == fromIndex -> toIndex
            fromIndex < ui.index && toIndex >= ui.index -> ui.index - 1
            fromIndex > ui.index && toIndex <= ui.index -> ui.index + 1
            else -> ui.index
        }
        player.editQueue(list, current)
    }

    override fun removeFromQueue(index: Int) {
        val ui = player.ui.value
        if (index !in ui.queue.indices) return
        if (index == ui.index) {
            // The song playing: the next one plays in its place, as removing it on the phone does.
            val list = ui.queue.filterIndexed { i, _ -> i != index }
            if (list.isEmpty()) return
            player.playAll(list, index.coerceAtMost(list.lastIndex))
        } else {
            player.editQueue(ui.queue.filterIndexed { i, _ -> i != index }, if (index < ui.index) ui.index - 1 else ui.index)
        }
        beforeShuffle = null
    }

    override fun removeCachedStreamUrl(trackId: String) = Unit

    override fun replaceQueuePreservingCurrent(tracks: List<FullTrack>, keepPlayingId: String?) {
        if (tracks.isEmpty() || keepPlayingId == null || player.ui.value.current?.id != keepPlayingId) return
        val at = tracks.indexOfFirst { it.id == keepPlayingId }
        if (at < 0) return
        player.editQueue(tracks, at)
        forgetShuffle()
    }

    // The core keeps the alternative and resolves through it; the desktop has no other per-track cache.
    override fun chooseRecording(trackId: String, youtubeId: String) {
        com.varuna.rustify.bridge.NativeEngine.setAlternativeTrackNative(trackId, youtubeId)
        com.varuna.rustify.bridge.UserAlternatives.add(AppDirs.data, trackId)
    }

    override fun clearRecording(trackId: String) {
        com.varuna.rustify.bridge.UserAlternatives.remove(AppDirs.data, trackId)
    }

    override fun dismissResumed() = Unit

    // ── A-B repeat ──────────────────────────────────────────────────────────────────────────────

    private val _abRepeat = MutableStateFlow(AbRepeat())
    override val abRepeat: StateFlow<AbRepeat> = _abRepeat
    private var abWatcher: Job? = null

    /** The A-B button: mark A, mark B (the loop starts), clear. */
    override fun pressAbRepeat() {
        val ui = player.ui.value
        val next = _abRepeat.value.press(ui.current?.id, ui.positionMs)
        _abRepeat.value = next
        abWatcher?.cancel()
        if (!next.isLooping) return
        abWatcher = scope.launch {
            player.ui.collect { now ->
                val loop = _abRepeat.value.onTrack(now.current?.id)
                if (loop != _abRepeat.value) _abRepeat.value = loop
                loop.jumpFrom(now.current?.id, now.positionMs)?.let { player.seek(it) }
            }
        }
    }

    // ── Sleep timer ─────────────────────────────────────────────────────────────────────────────

    private val _sleepTimer = MutableStateFlow<Long?>(null)
    override val sleepTimer: StateFlow<Long?> = _sleepTimer
    private val _sleepTracks = MutableStateFlow(0)
    override val sleepTracks: StateFlow<Int> = _sleepTracks
    private var sleepJob: Job? = null

    init {
        // Counted where a song ends by itself, so skipping through the queue does not use it up.
        player.onTrackEnded = {
            if (_sleepTracks.value > 0) setSleepTracks(_sleepTracks.value - 1)
            if (_sleepTimer.value == SLEEP_AT_TRACK_END && _sleepTracks.value == 0) _sleepTimer.value = null
        }
        // ListenBrainz and Last.fm, by the phone's rule; the history itself the player keeps.
        val scrobbler = com.varuna.rustify.metrics.Scrobbler(platform, scope)
        player.listenObserver = object : QueuePlayer.ListenObserver {
            override fun started(track: FullTrack, startedAt: Long) = scrobbler.started(track, startedAt)
            override fun finished(track: FullTrack, startedAt: Long, listenedMs: Long, errored: Boolean) =
                scrobbler.finished(track, startedAt, listenedMs, errored)
        }
    }

    /** Pauses after [minutes]; `0` cancels; [PlayerController.SLEEP_MINUTES_TRACK_END] at the song's end. */
    override fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        setSleepTracks(0)
        when {
            minutes == 0 -> _sleepTimer.value = null
            minutes == PlayerController.SLEEP_MINUTES_TRACK_END -> setSleepAfterTracks(1)
            else -> {
                val endsAt = System.currentTimeMillis() + minutes * 60_000L
                _sleepTimer.value = endsAt
                sleepJob = scope.launch {
                    delay(endsAt - System.currentTimeMillis())
                    player.pause()
                    _sleepTimer.value = null
                }
            }
        }
    }

    /** Pauses when [songs] songs have ended, the current one counting as the first. */
    override fun setSleepAfterTracks(songs: Int) {
        sleepJob?.cancel()
        setSleepTracks(songs.coerceAtLeast(0))
        _sleepTimer.value = if (songs > 0) SLEEP_AT_TRACK_END else null
    }

    private fun setSleepTracks(n: Int) {
        _sleepTracks.value = n
        // The last song: the player stops when it ends instead of moving on.
        player.stopAtTrackEnd = n == 1
    }

    // ── Saved queues ────────────────────────────────────────────────────────────────────────────

    private val _savedQueues = MutableStateFlow<List<SavedQueues.SavedQueue>>(emptyList())
    override val savedQueues: StateFlow<List<SavedQueues.SavedQueue>> = _savedQueues
    private val _activeSavedQueue = MutableStateFlow<String?>(null)
    override val activeSavedQueue: StateFlow<String?> = _activeSavedQueue

    override fun refreshSavedQueues() {
        scope.launch { _savedQueues.value = withContext(Dispatchers.IO) { SavedQueues.load(AppDirs.data) } }
    }

    private fun snapshot(name: String): SavedQueues.SavedQueue? {
        val ui = player.ui.value
        if (ui.queue.isEmpty()) return null
        return SavedQueues.SavedQueue(
            name = name,
            tracks = ui.queue,
            index = ui.index.coerceAtLeast(0),
            positionMs = ui.positionMs,
            savedAt = System.currentTimeMillis(),
        )
    }

    private suspend fun persistSaved(change: (List<SavedQueues.SavedQueue>) -> List<SavedQueues.SavedQueue>) {
        val next = change(_savedQueues.value)
        _savedQueues.value = next
        withContext(Dispatchers.IO) { SavedQueues.store(AppDirs.data, next) }
    }

    override fun saveQueueAs(name: String, defaultPrefix: String) {
        scope.launch {
            val chosen = name.trim().ifEmpty { SavedQueues.defaultName(_savedQueues.value, defaultPrefix) }
            val snap = snapshot(chosen) ?: return@launch
            persistSaved { SavedQueues.upsert(it, snap) }
            _activeSavedQueue.value = chosen
        }
    }

    /** Plays saved queue [name] from where it was, keeping the one being left where it got to. */
    override fun switchToSavedQueue(name: String) {
        scope.launch {
            val leaving = _activeSavedQueue.value
            if (leaving != null && !leaving.equals(name, ignoreCase = true)) {
                snapshot(leaving)?.let { snap -> persistSaved { SavedQueues.upsert(it, snap) } }
            }
            val target = _savedQueues.value.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return@launch
            _activeSavedQueue.value = target.name
            loadPlaylist(target.tracks, target.index, startPositionMs = target.positionMs)
        }
    }

    override fun deleteSavedQueue(name: String) {
        scope.launch {
            persistSaved { SavedQueues.remove(it, name) }
            if (_activeSavedQueue.value.equals(name, ignoreCase = true)) _activeSavedQueue.value = null
        }
    }

    // ── What the desktop has no engine for yet ──────────────────────────────────────────────────

    override fun switchToVideoStream(videoUrl: String, audioUrl: String?) = Unit
    override fun switchToAudioStream() = Unit
    override fun setWebPlayerMode(enabled: Boolean) = Unit

    // QueuePlayer stores the queue on every change that matters and on close.
    override fun saveNow() = Unit

    private companion object {
        /** [sleepTimer] for "when this song ends", as the phone's. */
        const val SLEEP_AT_TRACK_END = -1L
    }
}
