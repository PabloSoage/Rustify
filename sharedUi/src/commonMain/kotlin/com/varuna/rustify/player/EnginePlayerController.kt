// sharedUi/src/commonMain/kotlin/com/varuna/rustify/player/EnginePlayerController.kt
package com.varuna.rustify.player

import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.LyricsResult
import com.varuna.rustify.engine.EffectsConfig
import com.varuna.rustify.engine.PlayerUi
import com.varuna.rustify.engine.QueuePlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import com.varuna.rustify.platform.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import com.varuna.rustify.bridge.LocalStreamServer
import com.varuna.rustify.bridge.SpotifyRepository
import com.varuna.rustify.bridge.TrackRef
import com.varuna.rustify.cast.CastDiscovery
import com.varuna.rustify.cast.CastSession
import com.varuna.rustify.cast.DlnaController
import com.varuna.rustify.ui.appFileOfUri
import com.varuna.rustify.ui.appString
import com.varuna.rustify.ui.child
import com.varuna.rustify.ui.fileExists
import com.varuna.rustify.ui.fullPath
import com.varuna.rustify.ui.resources.Res
import com.varuna.rustify.ui.resources.cast_track_not_on_device

/**
 * [PlayerController] where the core's engine plays (the desktop and iOS), over [QueuePlayer] (the core's engine; the queue is still
 * `player::reduce`). Shuffle, repeat, editing the queue, A-B, the sleep timer and saved queues work
 * as on the phone; what the engine does not do yet — video, and the Android-only audio settings —
 * answers [can] with false, so the shared screens show the control as unavailable rather than calling
 * into nothing.
 *
 * The audio effects the engine does have (equaliser, ReplayGain, stereo tools, fades, speed and pitch,
 * skipping silence) follow the phone's settings, by the phone's keys: read at start and sent again
 * whenever one of them is written.
 */
class EnginePlayerController(
    private val player: QueuePlayer,
    private val scope: CoroutineScope,
    private val platform: com.varuna.rustify.ui.AppPlatform,
    /** The platform's chain of audio sources, in the order and with the settings chosen in Settings. */
    sources: com.varuna.rustify.engine.StreamResolver.Sources,
    /** The name of the output playing now, which AutoEq keeps its profile under. */
    private val output: StateFlow<String?> = MutableStateFlow(null),
    /** Asks the system which output that is; [output] follows. */
    private val refreshOutput: suspend () -> Unit = {},
) : PlayerController {
    /** Off, shuffle, repeat one: the phone's single button cycles through them in that order. */
    private enum class Mode { OFF, SHUFFLE, REPEAT }

    private val mode = MutableStateFlow(Mode.OFF)

    private data class CastUiOverride(
        val isPlaying: Boolean = false,
        val isBuffering: Boolean = false,
        val positionMs: Long = 0L,
        val currentTrack: FullTrack? = null,
        val currentIndex: Int? = null,
        val errorMessage: String? = null,
        val isError: Boolean = false,
    )

    private val _castOverride = MutableStateFlow<CastUiOverride?>(null)

    override val state: StateFlow<AudioPlayerState> =
        combine(player.ui, mode, _castOverride) { ui, m, cast -> ui.toState(m, cast) }
            .stateIn(scope, SharingStarted.Eagerly, player.ui.value.toState(mode.value, null))

    private fun PlayerUi.toState(m: Mode, cast: CastUiOverride?) = AudioPlayerState(
        currentTrack = cast?.currentTrack ?: current,
        isPlaying = cast?.isPlaying ?: playing,
        isBuffering = cast?.isBuffering ?: loading,
        isError = (cast?.isError ?: false) || error != null,
        errorMessage = cast?.errorMessage ?: error.orEmpty(),
        queue = queue,
        originalQueue = beforeShuffle ?: queue,
        isShuffle = m == Mode.SHUFFLE,
        isRepeat = m == Mode.REPEAT,
        positionMs = cast?.positionMs ?: positionMs,
        durationMs = (cast?.currentTrack ?: current)?.durationMs?.toLong() ?: durationMs ?: 0L,
    )

    override val preloadedLyrics: StateFlow<LyricsResult?> = MutableStateFlow(null)
    override val preloadedLyricsTrackId: String? = null
    override val resumedAt: StateFlow<Pair<String, Long>?> = player.resumedAt
    override val isWebPlayerMode: Boolean = false

    override fun can(feature: PlayerFeature): Boolean = when (feature) {
        PlayerFeature.SPEED, PlayerFeature.CAST -> true
        else -> false
    }

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
        player.playAll(inShuffledOrder(tracks, current = null), 0)
        beforeShuffle = tracks
        mode.value = Mode.SHUFFLE
    }

    // An audition of one recording, as the phone's (AudioPlayerService.playPreview): that video, out
    // of the queue, and nothing written down — no listen, no position, nothing kept for the track.
    override fun playPreview(track: FullTrack, youtubeVideoId: String) = player.audition(track, youtubeVideoId)

    // [youtubeId], when given, is the recording to play: an alternative just chosen.
    override fun playSpecificTrackInQueue(trackId: String, youtubeId: String?, queueIndex: Int?) {
        if (isCasting) {
            val q = state.value.queue
            val i = queueIndex?.takeIf { it in q.indices && q[it].id == trackId } ?: q.indexOfFirst { it.id == trackId }
            if (i >= 0) castTrack(q[i], i)
        } else {
            val queue = player.ui.value.queue
            // The row asked for, when it holds that track: a playlist can repeat a song.
            val i = queueIndex?.takeIf { it in queue.indices && queue[it].id == trackId } ?: queue.indexOfFirst { it.id == trackId }
            if (i >= 0) player.jumpTo(i, youtubeId = youtubeId)
        }
    }

    /**
     * Resolves the song again, from the sources: the phone's retry. [youtubeId] is the recording to
     * play (an alternative just chosen); [fallbackTrackId] the song, when it is not the one playing.
     * The same song resumes where it was; another starts from the beginning.
     */
    override fun retryCurrentTrack(youtubeId: String?, fallbackTrackId: String?, isAutoRetry: Boolean) {
        val ui = player.ui.value
        val i = fallbackTrackId?.let { id -> ui.queue.indexOfFirst { it.id == id }.takeIf { it >= 0 } } ?: ui.index
        val track = ui.queue.getOrNull(i) ?: return
        // A forced re-resolution: the source that served it last is not trusted as the good one.
        track.id?.let { com.varuna.rustify.audio.AudioSourceRegistry.invalidateLastGood(it) }
        val sameTrack = fallbackTrackId == null || fallbackTrackId == ui.current?.id
        player.jumpTo(i, startMs = if (sameTrack) ui.positionMs.coerceAtLeast(0) else 0, youtubeId = youtubeId, isAutoRetry = isAutoRetry)
    }

    override fun play() {
        if (isCasting) {
            scope.launch { com.varuna.rustify.cast.CastSession.resume() }
        } else {
            player.play()
        }
    }
    override fun pause(immediate: Boolean) {
        if (isCasting) {
            scope.launch { com.varuna.rustify.cast.CastSession.pause() }
        } else {
            player.pause()
        }
    }
    override fun togglePlayPause() {
        if (isCasting) {
            scope.launch {
                val state = com.varuna.rustify.cast.CastSession.device?.let {
                    com.varuna.rustify.cast.DlnaController.transportState(it.controlUrl)
                }
                if (state == "PLAYING") com.varuna.rustify.cast.CastSession.pause()
                else com.varuna.rustify.cast.CastSession.resume()
            }
        } else {
            player.togglePause()
        }
    }
    override fun seekTo(positionMs: Long) {
        if (isCasting) {
            scope.launch { com.varuna.rustify.cast.CastSession.seekTo(positionMs) }
        } else {
            player.seek(positionMs)
        }
    }
    override fun skipToNext() {
        if (isCasting) {
            val q = state.value.queue
            val curTrack = state.value.currentTrack
            val curIdx = _castOverride.value?.currentIndex
                ?: q.indexOfFirst { it.id == curTrack?.id }.takeIf { it >= 0 }
                ?: player.ui.value.index
            val nextIdx = curIdx + 1
            if (nextIdx in q.indices) {
                castTrack(q[nextIdx], nextIdx)
            } else {
                endCastSession("Queue ended")
            }
        } else {
            player.next()
        }
    }

    override fun skipToPrevious() {
        if (isCasting) {
            val q = state.value.queue
            val curTrack = state.value.currentTrack
            val curIdx = _castOverride.value?.currentIndex
                ?: q.indexOfFirst { it.id == curTrack?.id }.takeIf { it >= 0 }
                ?: player.ui.value.index
            val prevIdx = (curIdx - 1).coerceAtLeast(0)
            if (prevIdx in q.indices) {
                castTrack(q[prevIdx], prevIdx)
            }
        } else {
            player.previous()
        }
    }

    override fun startOver() = player.startOver()

    // Speed and pitch are two of the effects: all of them go again, from the settings.
    override fun applyPlaybackSpeed() = applyEffects()

    // ── Audio effects ───────────────────────────────────────────────────────────────────────────

    private val settings = platform.prefs()

    /** The phone's audio settings to the engine, with AutoEq's profile for the output playing now. */
    private fun applyEffects() = player.setEffects(EffectsConfig.fromSettings(settings, output.value))
    override fun currentQueuePosition(st: AudioPlayerState): Int =
        if (isCasting) (_castOverride.value?.currentIndex ?: player.ui.value.index)
        else player.ui.value.index

    // ── Shuffle and repeat ──────────────────────────────────────────────────────────────────────

    /** The queue as it was before shuffling, to go back to; null when not shuffled or edited since. */
    private var beforeShuffle: List<FullTrack>? = null

    private fun forgetShuffle() {
        beforeShuffle = null
    }

    /**
     * [tracks] shuffled song by song, or album by album when that is chosen ([AlbumShuffle]): then
     * what is left of [current]'s album plays on before the rest.
     */
    private fun inShuffledOrder(tracks: List<FullTrack>, current: FullTrack?): List<FullTrack> {
        if (!AlbumShuffle.enabled(settings)) return tracks.shuffled()
        val albumOf: (FullTrack) -> String? = { t -> t.album?.id?.takeIf { it.isNotBlank() } ?: t.album?.name }
        return AlbumShuffle.arrange(tracks, albumOf, firstAlbum = current?.let(albumOf))
    }

    /** The song playing stays where it is, first; the rest come after it in a random order. */
    private fun shuffleRest() {
        val ui = player.ui.value
        val current = ui.current ?: return
        beforeShuffle = ui.queue
        val rest = inShuffledOrder(ui.queue.filterIndexed { i, _ -> i != ui.index }, current)
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

    // The DJ's blocks are the whole of what follows the song playing (the phone's, without its
    // separate queue of songs added by hand, which the desktop does not keep apart yet).
    override fun replaceAutoQueueAfterCurrent(tracks: List<FullTrack>) {
        if (tracks.isEmpty()) return
        val ui = player.ui.value
        if (ui.current == null || ui.index !in ui.queue.indices) {
            loadPlaylist(tracks, 0)
            return
        }
        player.editQueue(ui.queue.subList(0, ui.index + 1) + tracks, ui.index)
        forgetShuffle()
    }

    // The core keeps the alternative and resolves through it. Of what is keyed by the track, the
    // desktop has the source that served it last (no persisted URL and no stream cache): forgotten,
    // as on the phone, so the next play is not handed back to it.
    override fun chooseRecording(trackId: String, youtubeId: String) {
        com.varuna.rustify.bridge.NativeEngine.setAlternativeTrackNative(trackId, youtubeId)
        com.varuna.rustify.bridge.UserAlternatives.add(platform.filesDir, trackId)
        com.varuna.rustify.audio.AudioSourceRegistry.invalidateLastGood(trackId)
    }

    override fun clearRecording(trackId: String) {
        com.varuna.rustify.bridge.UserAlternatives.remove(platform.filesDir, trackId)
    }

    override fun dismissResumed() = player.dismissResumed()

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

    // On a scope of its own, off the window's thread: quitting blocks that thread while it waits for
    // the last song's submits, which could not run there meanwhile.
    private val scrobbler = com.varuna.rustify.metrics.Scrobbler(
        platform,
        CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO),
    )

    /** Returns once the scrobbles already launched have been sent (or failed): for quitting. */
    suspend fun awaitScrobbles() = scrobbler.awaitSubmits()

    init {
        // Counted where a song ends by itself, so skipping through the queue does not use it up.
        player.onTrackEnded = {
            if (_sleepTracks.value > 0) setSleepTracks(_sleepTracks.value - 1)
            if (_sleepTimer.value == SLEEP_AT_TRACK_END && _sleepTracks.value == 0) _sleepTimer.value = null
        }
        // "Match local first", by the phone's rule (AudioPlayerService.localStreamFor): a recording
        // the user chose beats the local copy, and the copy is the core's match, never a name
        // compared here.
        player.localCopy = { track -> localCopyOf(platform, track) }
        // The phone's audio sources, in the order and with the settings chosen in Settings.
        player.sources = sources
        // ListenBrainz and Last.fm, by the phone's rule; the history itself the player keeps.
        player.listenObserver = object : QueuePlayer.ListenObserver {
            override fun started(track: FullTrack, startedAt: Long) = scrobbler.started(track, startedAt)
            override fun finished(track: FullTrack, startedAt: Long, listenedMs: Long, errored: Boolean) =
                scrobbler.finished(track, startedAt, listenedMs, errored)
        }
        // How the queue moves, by the phone's settings: read each time, so a change counts at once.
        val audio = AudioSettings(settings)
        player.previousAlways = { audio.previousAlways() }
        player.blocked = { Blocklist.current(settings).blocks(it) }
        player.radio = { seed -> if (audio.autoplayRadio()) radioAfter(seed) else emptyList() }
        player.onRadioAdded = { added -> beforeShuffle = beforeShuffle?.plus(added) }
        // The stored settings now (the player holds them until its engine runs), and again on every
        // write of one, wherever it comes from.
        applyEffects()
        scope.launch {
            EffectsConfig.SETTINGS_KEYS.map { settings.changes(it) }.merge().collect { applyEffects() }
        }
        // AutoEq's profile is the output's: asked again each song, and sent again when it moved.
        scope.launch {
            player.ui.map { it.current?.id }.distinctUntilChanged().collect { refreshOutput() }
        }
        scope.launch { output.collect { applyEffects() } }
    }

    /** The autoplay radio after [seed], as on the phone: nothing after a local file. */
    private suspend fun radioAfter(seed: FullTrack): List<FullTrack> {
        val id = seed.id ?: return emptyList()
        if (com.varuna.rustify.bridge.TrackRef.isLocal(id)) return emptyList()
        val answer = com.varuna.rustify.bridge.NativeEngine.getSpotifyTrackRadio(id)
        return com.varuna.rustify.bridge.RustifyJson.decodeFromString(ListSerializer(FullTrack.serializer()), answer)
    }

    private suspend fun localCopyOf(platform: com.varuna.rustify.ui.AppPlatform, track: FullTrack): String? {
        val trackId = track.id ?: return null
        val prefs = platform.prefs()
        if (!prefs.getBoolean("settings_match_local_first", false)) return null
        if (prefs.getStringSet("local_music_directories", emptySet()).isEmpty()) return null
        return withContext(Dispatchers.IO) {
            if (com.varuna.rustify.bridge.UserAlternatives.isUserSet(platform.filesDir, trackId)) return@withContext null
            val match = com.varuna.rustify.bridge.SpotifyRepository.findLocalMatch(platform.filesDir, track)
            com.varuna.rustify.bridge.TrackRef.localUriOf(match?.id)?.takeIf { it.startsWith("file:") }
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
                val endsAt = com.varuna.rustify.platform.currentTimeMillis() + minutes * 60_000L
                _sleepTimer.value = endsAt
                sleepJob = scope.launch {
                    delay(endsAt - com.varuna.rustify.platform.currentTimeMillis())
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
        scope.launch { _savedQueues.value = withContext(Dispatchers.IO) { SavedQueues.load(platform.filesDir) } }
    }

    private fun snapshot(name: String): SavedQueues.SavedQueue? {
        val ui = player.ui.value
        if (ui.queue.isEmpty()) return null
        return SavedQueues.SavedQueue(
            name = name,
            tracks = ui.queue,
            index = ui.index.coerceAtLeast(0),
            positionMs = ui.positionMs,
            savedAt = com.varuna.rustify.platform.currentTimeMillis(),
        )
    }

    private suspend fun persistSaved(change: (List<SavedQueues.SavedQueue>) -> List<SavedQueues.SavedQueue>) {
        val next = change(_savedQueues.value)
        _savedQueues.value = next
        withContext(Dispatchers.IO) { SavedQueues.store(platform.filesDir, next) }
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

    // ── What the engine has nothing for yet ─────────────────────────────────────────────────

    override fun switchToVideoStream(videoUrl: String, audioUrl: String?) = Unit
    override fun switchToAudioStream() = Unit
    override fun setWebPlayerMode(enabled: Boolean) = Unit

    override val isCasting: Boolean
        get() = CastSession.device != null

    private var castPollJob: Job? = null
    private var castStartConfirmed = false
    private var lastCastEndedTrackId: String? = null

    private suspend fun localFilePathFor(track: FullTrack): String? {
        val id = track.id ?: return null
        if (TrackRef.isLocal(id)) {
            val uri = TrackRef.localUriOf(id) ?: return null
            val file = appFileOfUri(uri)
            if (file != null && file.fileExists()) return file.fullPath
        }
        val match = localCopyOf(platform, track)
        if (match != null) {
            val file = appFileOfUri(match)
            if (file != null && file.fileExists()) return file.fullPath
        }
        val localMatch = withContext(Dispatchers.IO) {
            SpotifyRepository.findLocalMatch(platform.filesDir, track)
        }
        if (localMatch != null) {
            val uri = TrackRef.localUriOf(localMatch.id)
            if (uri != null) {
                val file = appFileOfUri(uri)
                if (file != null && file.fileExists()) return file.fullPath
            }
        }
        return null
    }

    private suspend fun castHandleFor(track: FullTrack): String? {
        val path = localFilePathFor(track)
        if (path != null) {
            return LocalStreamServer.registerFile(path)
        }
        val id = track.id
        if (!id.isNullOrBlank()) {
            val cacheRoot = platform.cacheDir.child("stream-cache").fullPath
            val cachedUrl = LocalStreamServer.registerReady(
                cacheKey = id,
                cacheRoot = cacheRoot
            )
            if (cachedUrl != null) {
                return cachedUrl.substringAfterLast("/stream/", "").substringBefore('?').takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    override suspend fun startCastingTo(device: CastDiscovery.Device): String? {
        val currentTrack = state.value.currentTrack ?: return "nothing is playing"
        val position = state.value.positionMs
        val handle = castHandleFor(currentTrack)
            ?: return appString(Res.string.cast_track_not_on_device, currentTrack.name)

        pause()
        val store = platform.prefs("rustify_settings")
        val failure = CastSession.start(
            store = store,
            target = device,
            handle = handle,
            title = currentTrack.name,
            artist = currentTrack.artists.joinToString(", ") { it.name },
            durationMs = currentTrack.durationMs.toLong()
        )
        if (failure != null) {
            return failure
        }
        _castOverride.value = CastUiOverride(
            isPlaying = true,
            isBuffering = false,
            positionMs = position,
            currentTrack = currentTrack,
            currentIndex = player.ui.value.index
        )
        if (position > 1_000L) runCatching { CastSession.seekTo(position) }
        startCastPolling(device)
        return null
    }

    override fun stopCasting() {
        castPollJob?.cancel()
        castPollJob = null
        castStartConfirmed = false
        lastCastEndedTrackId = null
        _castOverride.value = null
        scope.launch {
            CastSession.stop()
        }
    }

    private fun endCastSession(reason: String) {
        castPollJob?.cancel()
        castPollJob = null
        castStartConfirmed = false
        lastCastEndedTrackId = null
        scope.launch {
            CastSession.stop()
        }
        _castOverride.value = CastUiOverride(
            isPlaying = false,
            isBuffering = false,
            isError = true,
            errorMessage = reason,
            currentTrack = state.value.currentTrack,
            positionMs = state.value.positionMs,
            currentIndex = _castOverride.value?.currentIndex ?: player.ui.value.index
        )
    }

    private fun castTrack(track: FullTrack, newIndex: Int? = null) {
        val device = CastSession.device ?: return
        castStartConfirmed = false
        _castOverride.value = _castOverride.value?.copy(
            isPlaying = false,
            isBuffering = true,
            currentTrack = track,
            positionMs = 0L,
            currentIndex = newIndex
        ) ?: CastUiOverride(
            isPlaying = false,
            isBuffering = true,
            currentTrack = track,
            positionMs = 0L,
            currentIndex = newIndex
        )
        scope.launch {
            val handle = castHandleFor(track)
            if (handle == null) {
                endCastSession(appString(Res.string.cast_track_not_on_device, track.name))
                return@launch
            }
            val store = platform.prefs("rustify_settings")
            val failure = CastSession.start(
                store = store,
                target = device,
                handle = handle,
                title = track.name,
                artist = track.artists.joinToString(", ") { it.name },
                durationMs = track.durationMs.toLong()
            )
            if (failure != null) {
                endCastSession(failure)
            }
        }
    }

    private fun startCastPolling(device: CastDiscovery.Device) {
        castPollJob?.cancel()
        castStartConfirmed = false
        lastCastEndedTrackId = null
        castPollJob = scope.launch {
            var unanswered = 0
            while (isCasting) {
                val currentDevice = CastSession.device ?: break
                val transport = DlnaController.transportState(currentDevice.controlUrl)
                if (transport == null) {
                    if (++unanswered >= 5) {
                        endCastSession("${currentDevice.friendlyName} stopped answering")
                        return@launch
                    }
                    delay(1000)
                    continue
                }
                unanswered = 0
                if (transport == "PLAYING") castStartConfirmed = true

                val pos = CastSession.positionMs()
                _castOverride.value = _castOverride.value?.copy(
                    isPlaying = transport == "PLAYING",
                    isBuffering = transport == "TRANSITIONING",
                    positionMs = pos ?: _castOverride.value?.positionMs ?: 0L
                )

                val finished = castStartConfirmed && (transport == "STOPPED" || transport == "NO_MEDIA_PRESENT")
                val playingId = state.value.currentTrack?.id
                if (finished && playingId != null && playingId != lastCastEndedTrackId) {
                    lastCastEndedTrackId = playingId
                    skipToNext()
                }
                delay(1000)
            }
        }
    }

    // QueuePlayer stores the queue on every change that matters and on close.
    override fun saveNow() = Unit

    private companion object {
        /** [sleepTimer] for "when this song ends", as the phone's. */
        const val SLEEP_AT_TRACK_END = -1L
    }
}
