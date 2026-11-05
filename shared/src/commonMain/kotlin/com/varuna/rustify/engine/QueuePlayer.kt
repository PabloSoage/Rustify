// shared/src/commonMain/kotlin/com/varuna/rustify/engine/QueuePlayer.kt
package com.varuna.rustify.engine

import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.metrics.PlayEvent
import com.varuna.rustify.metrics.creditedListenMs
import com.varuna.rustify.metrics.playEventOf
import com.varuna.rustify.platform.KeyValueStore
import com.varuna.rustify.platform.Log
import com.varuna.rustify.platform.currentTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * What a [QueuePlayer] needs from the platform: the core's reducer and resolver, and its audio engine.
 *
 * Two implementations, one per way of reaching the core: `JvmPlayerBackend` (desktop, JNI) and
 * `IosPlayerBackend` (C ABI). Everything they answer is the core's own JSON.
 */
interface PlayerBackend {
    /** Opens the output device; `{"success":true}` or a failure. */
    suspend fun startEngine(): String

    /** Queues a command; never waits. */
    fun send(command: EngineCommand)

    /** The engine's events, for as long as they are collected. */
    fun events(): Flow<EngineEvent>

    /** `player::reduce` over JSON: `{"state":…,"effects":[…]}`. Memory only, fast. */
    fun reduce(stateJson: String, actionJson: String): String

    /** `youtube::innertube::resolve_audio` for [videoId] with [prefsJson]; the core's JSON. */
    suspend fun resolveAudio(videoId: String, prefsJson: String): String

    /** Makes a Spotify track matchable by the core's resolver (`registerTrackMetadata`). Memory only. */
    fun registerTrackMetadata(track: FullTrack)

    /** The YouTube video for a Spotify track id, by the core's resolver; "" when there is none. */
    suspend fun resolveYouTubeId(spotifyId: String): String

    /** Stores one play in the core's listening history (`metrics::record`); [eventJson] is a `PlayEvent`. */
    suspend fun recordPlay(eventJson: String): String

    /** The place kept for a long track (`player::bookmark`), or -1 when there is none. */
    suspend fun trackPlace(trackId: String): Long

    /** Keeps the place in a long track; the core removes it when barely started or finished. */
    suspend fun recordTrackPlace(trackId: String, positionMs: Long, durationMs: Long): String

    /** Drops the place in a long track, because the person chose to start it over. */
    suspend fun forgetTrackPlace(trackId: String): String
}

/** What a screen shows. Top-level rather than nested so Swift sees it as `PlayerUi`. */
data class PlayerUi(
    val queue: List<FullTrack> = emptyList(),
    val index: Int = -1,
    val playing: Boolean = false,
    val loading: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val volume: Float = 1f,
    val error: String? = null,
) {
    val current: FullTrack? get() = queue.getOrNull(index)
}

/**
 * A player for the platforms that play through the core's engine (desktop, iOS): `player::reduce`
 * decides, the engine plays.
 *
 * The same split Android has between `PlayerQueue` and ExoPlayer, without the Android: every
 * "what next" goes through the core's reducer, so repeat, "previous restarts after ten seconds" and
 * the end of the queue behave exactly as on the phone. This class only turns the reducer's effects
 * into engine commands and the engine's events back into actions.
 *
 * Gapless: when a track is ready, the one the reducer would play next is resolved and preloaded.
 * When the engine moves on to it by itself (`Transitioned`), the reducer is told the track ended and
 * the `Play` it answers is not sent — the engine is already playing it.
 *
 * Runs on one thread — the scope's, which must be single-threaded (the desktop's Swing main thread,
 * iOS's main queue): no locks, and events and clicks cannot interleave in the middle of a reduce.
 *
 * **Persisted** in [store], when there is one: the queue, where it is and the position, written on the
 * reducer's `Persist` and on pause and shutdown, and read back by [restore] — loaded, not played, so
 * opening the app does not start the music. One key, one JSON document, versioned in its name.
 */
class QueuePlayer(
    private val scope: CoroutineScope,
    private val backend: PlayerBackend,
    private val store: KeyValueStore? = null,
) {

    private val _ui = MutableStateFlow(PlayerUi())
    val ui: StateFlow<PlayerUi> = _ui.asStateFlow()

    private val _resumedAt = MutableStateFlow<Pair<String, Long>?>(null)
    val resumedAt: StateFlow<Pair<String, Long>?> = _resumedAt.asStateFlow()

    /** Below this the core keeps no place anyway (`player::bookmark::MIN_DURATION_MS`), as on the phone. */
    private fun isLong(track: FullTrack?) = (track?.durationMs ?: 0) >= 20 * 60_000

    /** The last place written, so the position events do not rewrite the file each time. */
    private var lastPlace: Pair<String, Long>? = null

    /**
     * [force] for the moments that matter: a pause, leaving the track, finishing it. Otherwise only
     * every 30 s of progress. The core removes a place below a minute, so a position nothing has
     * reported yet must never get here: it would read as zero and lose the place being resumed.
     */
    private fun rememberPlace(track: FullTrack?, positionMs: Long, force: Boolean = false) {
        val id = track?.id ?: return
        if (!isLong(track)) return
        val last = lastPlace
        if (!force && last != null && last.first == id && kotlin.math.abs(positionMs - last.second) < 30_000) return
        lastPlace = id to positionMs
        val duration = track.durationMs.toLong()
        scope.launch { runCatching { backend.recordTrackPlace(id, positionMs, duration) } }
    }

    /** "Start over" on the resumed notice: back to zero, and the place is forgotten. */
    fun startOver() {
        val (id, _) = _resumedAt.value ?: return
        _resumedAt.value = null
        seek(0L)
        scope.launch { runCatching { backend.forgetTrackPlace(id) } }
    }

    fun dismissResumed() {
        _resumedAt.value = null
    }

    /** For callers without coroutines (Swift): [onChange] on every change, until the job is cancelled. */
    fun observe(onChange: (PlayerUi) -> Unit): Job = scope.launch { ui.collect { onChange(it) } }

    /** The reducer's state, as `player::PlayerState` serialises it. */
    @Serializable
    private data class QueueState(
        val queue: List<String> = emptyList(),
        val index: Int = 0,
        @SerialName("position_ms") val positionMs: Long = 0,
        val repeat: String = "off",
        val shuffle: Boolean = false,
    )

    /** What [store] holds. A new shape is a new key (`queue_v2`), never this one changed in place. */
    @Serializable
    private data class Saved(val state: QueueState = QueueState(), val tracks: List<FullTrack> = emptyList())

    @Serializable
    private data class ReduceAnswer(val state: QueueState = QueueState(), val effects: List<JsonObject> = emptyList()) {
        val plays: Boolean get() = effects.any { it["type"]?.jsonPrimitive?.content == "play" }
    }

    /** Where each track's audio comes from; shared with the desktop's downloads. */
    private val resolver = StreamResolver(backend)

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    private var state = QueueState()
    private var tracks: List<FullTrack> = emptyList()

    private var nextToken = 1L
    private var currentToken = 0L
    private var preloadedToken = 0L
    private var preloadedIndex = -1
    private val indexOfToken = mutableMapOf<Long, Int>()

    /** Failures in a row; at three the player stops instead of running through the queue (as on Android). */
    private var failuresInARow = 0

    /**
     * The listen in progress, for the listening history: the same rules as Android's
     * `ListeningTracker` (`metrics/PlayRules.kt`), fed by the engine's events instead of ExoPlayer's.
     */
    private class Listening(val track: FullTrack, val startedAt: Long) {
        var lastPos = 0L
        var lastTickAt = 0L
        var listenedMs = 0L
    }

    private var listening: Listening? = null

    /** Told when a listen starts and how much of it was heard when it ends (the desktop scrobbles from it). */
    interface ListenObserver {
        fun started(track: FullTrack, startedAt: Long)
        fun finished(track: FullTrack, startedAt: Long, listenedMs: Long, errored: Boolean)
    }

    var listenObserver: ListenObserver? = null

    private fun startListening(token: Long) {
        finishListening(completed = false, errored = false)
        val index = indexOfToken[token] ?: return
        val track = tracks.getOrNull(index) ?: return
        val l = Listening(track, currentTimeMillis())
        listening = l
        listenObserver?.started(track, l.startedAt)
    }

    private fun progressListening(positionMs: Long) {
        val l = listening ?: return
        val now = currentTimeMillis()
        val wallDelta = if (l.lastTickAt > 0L) now - l.lastTickAt else 0L
        l.listenedMs += creditedListenMs(positionMs - l.lastPos, wallDelta)
        l.lastPos = positionMs
        l.lastTickAt = now
    }

    private fun finishListening(completed: Boolean, errored: Boolean) {
        if (completed) {
            listening?.let { rememberPlace(it.track, it.track.durationMs.toLong(), force = true) }
        } else {
            rememberHeardPlace()
        }
        val event = takeListening(completed, errored) ?: return
        scope.launch { record(event) }
    }

    /** The place the listen in progress has reached, once the engine has reported one. */
    private fun rememberHeardPlace() {
        listening?.takeIf { it.lastTickAt > 0L }?.let { rememberPlace(it.track, it.lastPos, force = true) }
    }

    /** Ends the listen in progress and answers its event, if it is one to store. */
    private fun takeListening(completed: Boolean, errored: Boolean): PlayEvent? {
        val l = listening ?: return null
        listening = null
        listenObserver?.finished(l.track, l.startedAt, l.listenedMs, errored)
        return playEventOf(l.track, l.startedAt, l.listenedMs, completed, errored)
    }

    private suspend fun record(event: PlayEvent) {
        runCatching { backend.recordPlay(json.encodeToString(PlayEvent.serializer(), event)) }
            .onFailure { Log.w(TAG, "could not record a play", it) }
    }

    /**
     * Brings back the last session's queue and position, paused. Call before [start]. Nothing saved, or
     * something unreadable (an older shape), leaves an empty player.
     */
    fun restore() {
        val raw = store?.getString(STORE_KEY) ?: return
        val saved = runCatching { json.decodeFromString(Saved.serializer(), raw) }
            .onFailure { Log.w(TAG, "saved queue unreadable; starting empty", it) }
            .getOrNull() ?: return
        if (saved.tracks.isEmpty() || saved.state.queue.size != saved.tracks.size) return
        tracks = saved.tracks
        state = saved.state
        _ui.update { it.copy(queue = tracks, index = state.index, positionMs = state.positionMs) }
    }

    private fun save() {
        val target = store ?: return
        runCatching { target.putString(STORE_KEY, json.encodeToString(Saved.serializer(), Saved(state, tracks))) }
            .onFailure { Log.w(TAG, "could not save the queue", it) }
    }

    fun start() {
        scope.launch {
            val started = backend.startEngine()
            if (!started.contains("\"success\":true")) {
                _ui.update { it.copy(error = "Audio output: $started") }
                return@launch
            }
            engineRunning = true
            // Set before the engine was there to take them.
            effects?.let { backend.send(EngineCommand.SetEffects(it)) }
            backend.events().collect { onEvent(it) }
        }
    }

    private var engineRunning = false
    private var effects: EffectsConfig? = null

    /**
     * The audio effects (equaliser, ReplayGain, stereo tools, fades, speed, …), all of them each time:
     * the engine works out what changed. Kept, so effects set before [start] reach the engine once it
     * runs.
     */
    fun setEffects(config: EffectsConfig) {
        effects = config
        if (engineRunning) backend.send(EngineCommand.SetEffects(config))
    }

    /** The system stopped the output behind the engine's back (an interruption on iOS). */
    fun reopenOutput() {
        if (engineRunning) backend.send(EngineCommand.ReopenOutput)
    }

    // ---------------------------------------------------------------------------------------------
    // What the window asks for
    // ---------------------------------------------------------------------------------------------

    /**
     * Plays [list] from [start]. Spotify, YouTube Music and local tracks mix freely: a `ytm:` id is
     * played as that video, a `local:` file from its path, anything else goes through the core's
     * resolver, as on the phone. Tracks with no id cannot play here and are dropped.
     */
    fun playAll(list: List<FullTrack>, start: Int, startMs: Long = 0, isAutoRetry: Boolean = false) {
        val playable = list.filter { it.id != null }
        // By position, not by equality: a playlist can hold the same song twice, and starting at the
        // second copy must not land on the first. Tracks dropped above shift the index down.
        val first = if (list.getOrNull(start)?.id != null) list.take(start).count { it.id != null } else 0
        tracks = playable
        failuresInARow = 0
        // A radio on its way was for the queue this one replaces.
        radioJob?.cancel()
        radioSeed = null
        _ui.update { it.copy(queue = playable, error = null) }
        reduce(buildJsonObject {
            put("type", "set_queue")
            put("tracks", JsonArray(playable.map { JsonPrimitive(it.id.orEmpty()) }))
            put("start", first)
        }, startMs = startMs, isAutoRetry = isAutoRetry)
    }

    /**
     * Asked before "next" and "previous" act; answering true means someone else took them — a Jam
     * guest sends them to the host instead of moving on alone.
     */
    var intercept: ((String) -> Boolean)? = null

    /** Whether "previous" always goes back a song, instead of restarting one well under way. */
    var previousAlways: () -> Boolean = { false }

    /**
     * What the queue steps over when it moves on by itself or with next/previous (the blocklist). A
     * song chosen by hand still plays: the user asked for that one.
     */
    var blocked: (FullTrack) -> Boolean = { false }

    /**
     * What plays after [FullTrack] when the queue runs out (the phone's autoplay radio); empty when
     * nothing does. Asked while the last song still plays, so the end of a queue is an ordinary
     * track change, and again if the queue ran out anyway.
     */
    var radio: (suspend (FullTrack) -> List<FullTrack>)? = null

    /** Told what the radio added at the end of the queue. */
    var onRadioAdded: ((List<FullTrack>) -> Unit)? = null

    private var radioJob: Job? = null
    private var radioSeed: String? = null

    fun next() {
        if (intercept?.invoke("next") == true) return
        reduce(buildJsonObject { put("type", "next") }, moving = true)
    }

    fun previous() {
        if (intercept?.invoke("previous") == true) return
        reduce(buildJsonObject {
            put("type", "previous")
            if (previousAlways()) put("always", true)
        }, moving = true)
    }

    /**
     * Adds the radio after [seed]. [thenPlay]: the queue already ran out, so it moves on into what
     * was added. Fetching is a round trip, so what is already queued is read after it, not before.
     */
    private fun extendWithRadio(seed: FullTrack, thenPlay: Boolean) {
        val source = radio ?: return
        // A guest's queue is the host's.
        if (intercept != null) return
        val id = seed.id ?: return
        if (!thenPlay && radioSeed == id) return
        radioSeed = id
        radioJob?.cancel()
        radioJob = scope.launch {
            val fetched = runCatching { source(seed) }
                .onFailure { if (it is CancellationException) throw it; Log.w(TAG, "the radio failed", it) }
                .getOrDefault(emptyList())
            val have = tracks.mapNotNull { it.id }.toSet()
            val fresh = fetched.filter { it.id != null && it.id !in have && !blocked(it) }
            if (fresh.isEmpty()) {
                // No network, or nothing new: the next pass can try again.
                radioSeed = null
                return@launch
            }
            editQueue(tracks + fresh, state.index)
            onRadioAdded?.invoke(fresh)
            if (thenPlay) reduce(buildJsonObject { put("type", "next") }, moving = true)
        }
    }

    /** The first song from [to] on, going the way the queue was going, that is not [blocked]; null if none. */
    private fun allowedFrom(from: Int, to: Int): Int? {
        val step = if (to >= from) 1 else -1
        var i = to
        while (i in tracks.indices) {
            if (!blocked(tracks[i])) return i
            i += step
        }
        return null
    }

    /**
     * Replaces the queue around the song playing, which carries on uninterrupted: songs added after
     * it, moved or removed. [currentIndex] is where the playing song now is in [list]. Tracks with no
     * id are dropped, as in [playAll].
     */
    fun editQueue(list: List<FullTrack>, currentIndex: Int) {
        val current = list.getOrNull(currentIndex)
        val playable = list.filter { it.id != null }
        if (playable.isEmpty()) return
        val index = if (current?.id != null) list.take(currentIndex).count { it.id != null } else 0
        val position = state.positionMs
        tracks = playable
        _ui.update { it.copy(queue = playable) }
        reduce(buildJsonObject {
            put("type", "set_queue")
            put("tracks", JsonArray(playable.map { JsonPrimitive(it.id.orEmpty()) }))
            put("start", index)
        }, skipPlay = true)
        // set_queue starts the song over; it has not, it is still playing where it was.
        state = state.copy(positionMs = position)
        if (currentToken != 0L && !auditioning) indexOfToken[currentToken] = index
        // What comes next may be another song now.
        dropPreload()
        if (currentToken != 0L) preloadNext()
        save()
    }

    /** Called when a song ends by itself (not a skip or a failure), before the queue moves on. */
    var onTrackEnded: (() -> Unit)? = null

    private var stopAtEnd = false

    /**
     * When set, the song playing is the last: when it ends by itself playback stops there, paused at
     * the next song, instead of playing on. The sleep timer's "at the end of this song". That end
     * uses it up: it reads false from [onTrackEnded] on.
     */
    var stopAtTrackEnd: Boolean
        get() = stopAtEnd
        set(value) {
            if (stopAtEnd == value) return
            stopAtEnd = value
            // Nothing preloaded behind a last song: the engine would move straight on into it.
            dropPreload()
            if (!value && currentToken != 0L) preloadNext()
        }

    private fun dropPreload() {
        preloadedToken = 0
        preloadedIndex = -1
        backend.send(EngineCommand.ClearNext)
    }

    /**
     * Plays the queue's [index]-th song from the start (or [startMs]). With [youtubeId], that load
     * plays *that* YouTube video — a chosen alternative, or a retry of one — rather than whatever the
     * song resolves to: the local copy is not asked, and only the sources that can honour a video id
     * are (see [StreamResolver.Sources]). Used once: the next load of the song resolves it as usual.
     */
    fun jumpTo(index: Int, startMs: Long = 0, youtubeId: String? = null, isAutoRetry: Boolean = false) {
        if (index !in tracks.indices) return
        hintFor = youtubeId?.takeIf { it.isNotBlank() }?.let { index to it }
        playAll(tracks, index, startMs, isAutoRetry = isAutoRetry)
    }

    /** The row and video [jumpTo] was given, for the load it causes; cleared by that load. */
    private var hintFor: Pair<Int, String>? = null

    /**
     * Plays [youtubeId] as [track], outside the queue: an audition of one recording (the play button
     * in the alternatives dialog), as the phone's preview.
     *
     * It writes nothing down. The queue, the song it is at and that song's position stay as they
     * were, and are what is saved; the audition is not a listen in the history; and nothing about the
     * track remembers the video. When it ends, or fails, playback stops there and play picks the
     * queue up where it was. Whatever the queue does next (next, a song chosen) replaces it.
     */
    fun audition(track: FullTrack, youtubeId: String) {
        if (track.id == null || youtubeId.isBlank()) return
        // The song that was playing stops here, heard as far as it got.
        finishListening(completed = false, errored = false)
        val token = nextToken++
        currentToken = token
        auditionToken = token
        hintFor = null
        dropPreload()
        _ui.update { it.copy(loading = true, positionMs = 0, durationMs = null, error = null) }
        scope.launch {
            // No local copy: that is the song's, and this is one recording of it.
            val audio = resolver.resolve(track, localCopy = null, hint = youtubeId)
            if (token != currentToken) return@launch // Something else started meanwhile.
            if (audio == null) {
                endAudition(error = "Could not resolve \"${track.name}\"")
                return@launch
            }
            backend.send(EngineCommand.Load(token, audio.url, 0, gainOf(audio), play = true))
        }
    }

    /** The token of the audition loaded, while it is the one playing; 0 otherwise. */
    private var auditionToken = 0L

    private val auditioning: Boolean get() = auditionToken != 0L && auditionToken == currentToken

    /** The audition is over: nothing is loaded, and play resumes the queue where it was. */
    private fun endAudition(error: String? = null) {
        auditionToken = 0
        currentToken = 0
        backend.send(EngineCommand.Stop)
        _ui.update {
            it.copy(playing = false, loading = false, positionMs = state.positionMs, durationMs = null, error = error)
        }
    }

    fun play() {
        if (!_ui.value.playing) togglePause()
    }

    fun pause() {
        if (_ui.value.playing) togglePause()
    }

    fun togglePause() {
        when {
            // A restored queue has nothing loaded yet: play starts it where it was left.
            currentToken == 0L && tracks.isNotEmpty() -> load(state.index, state.positionMs)
            _ui.value.playing -> {
                backend.send(EngineCommand.Pause)
                save()
                rememberHeardPlace()
            }
            else -> backend.send(EngineCommand.Play)
        }
    }

    fun seek(positionMs: Long) {
        // Within an audition, which is not the queue's song: the queue keeps its own position.
        if (auditioning) {
            backend.send(EngineCommand.Seek(positionMs))
            _ui.update { it.copy(positionMs = positionMs) }
            return
        }
        reduce(buildJsonObject {
            put("type", "seek")
            put("ms", positionMs)
        })
    }

    fun setVolume(volume: Float) {
        _ui.update { it.copy(volume = volume) }
        backend.send(EngineCommand.SetVolume(volume))
    }

    fun setRepeat(mode: String) = reduce(buildJsonObject {
        put("type", "set_repeat")
        put("repeat", mode)
    })

    fun shutdown() {
        finishListening(completed = false, errored = false)
        save()
        backend.send(EngineCommand.Stop)
    }

    /**
     * [shutdown] for a process about to end: the listen in progress is stored before this returns.
     * [shutdown] only launches that write, and an exit right after it loses the last song.
     */
    suspend fun close() {
        val last = takeListening(completed = false, errored = false)
        save()
        backend.send(EngineCommand.Stop)
        last?.let { record(it) }
    }

    // ---------------------------------------------------------------------------------------------
    // The reducer
    // ---------------------------------------------------------------------------------------------

    /**
     * Runs [action] through `player::reduce` and applies its effects. [skipPlay] drops a `Play`;
     * [startMs] is where a `Play` the action causes starts, when the caller knows better than 0.
     * [moving]: the queue is moving on by itself or with next/previous, so it steps over [blocked],
     * and when everything that way is blocked nothing more plays and it stays where it was.
     */
    private fun reduce(action: JsonObject, skipPlay: Boolean = false, startMs: Long = 0, moving: Boolean = false, isAutoRetry: Boolean = false) {
        val answer = runReducer(state, action) ?: return
        val allowed = if (moving && answer.plays) allowedFrom(state.index, answer.state.index) ?: return else answer.state.index
        state = answer.state.copy(index = allowed)
        _ui.update { it.copy(index = state.index) }
        for (effect in answer.effects) {
            when (effect["type"]?.jsonPrimitive?.content) {
                "play" -> if (!skipPlay) {
                    load(state.index, (effect["positionMs"]?.jsonPrimitive?.long ?: 0).takeIf { it > 0 } ?: startMs, isAutoRetry = isAutoRetry)
                }
                "seek" -> {
                    val at = effect["positionMs"]?.jsonPrimitive?.long ?: 0
                    // The reducer means the queue's song ("previous" restarting it), which an
                    // audition has unloaded: load it there instead of seeking the audition.
                    if (auditioning) load(state.index, at) else backend.send(EngineCommand.Seek(at))
                }
                "stop" -> {
                    backend.send(EngineCommand.Stop)
                    _ui.update { it.copy(playing = false) }
                }
                // The core says there is nothing after this, which is where the radio starts.
                "queue_exhausted" -> tracks.getOrNull(state.index)?.let { extendWithRadio(it, thenPlay = true) }
                "persist" -> save()
            }
        }
    }

    private fun runReducer(from: QueueState, action: JsonObject): ReduceAnswer? = runCatching {
        val raw = backend.reduce(json.encodeToString(QueueState.serializer(), from), action.toString())
        json.decodeFromString(ReduceAnswer.serializer(), raw)
    }.onFailure { Log.w(TAG, "reduce failed", it) }.getOrNull()

    // ---------------------------------------------------------------------------------------------
    // The engine
    // ---------------------------------------------------------------------------------------------

    private fun load(index: Int, startMs: Long, isAutoRetry: Boolean = false) {
        val track = tracks.getOrNull(index) ?: return
        val trackId = track.id
        if (_resumedAt.value?.first != trackId) {
            _resumedAt.value = null
        }
        val hint = hintFor?.takeIf { it.first == index }?.second
        hintFor = null
        // A skip, or a new queue: the track that was playing ends here, not completed.
        finishListening(completed = false, errored = false)
        val token = nextToken++
        indexOfToken[token] = index
        currentToken = token
        auditionToken = 0
        dropPreload()
        _ui.update { it.copy(loading = true, positionMs = startMs, durationMs = null, error = null) }
        scope.launch {
            // A long track started from the top picks up where it was left.
            var startAt = startMs
            if (startAt == 0L && !isAutoRetry && trackId != null) {
                val place = placeOf(track)
                if (token != currentToken) return@launch // Skipped while asking.
                if (place > 0L) {
                    startAt = place
                    _resumedAt.value = trackId to place
                    _ui.update { it.copy(positionMs = place) }
                }
            }
            val audio = resolve(track, hint)
            if (token != currentToken) return@launch // Skipped while resolving.
            if (audio == null) {
                failed("Could not resolve \"${track.name}\"")
                return@launch
            }
            backend.send(EngineCommand.Load(token, audio.url, startAt, gainOf(audio), play = true))
        }
    }

    /** The place kept for [track], or -1: a song has none, and asking would cost a call per song. */
    private suspend fun placeOf(track: FullTrack): Long {
        val id = track.id?.takeIf { isLong(track) } ?: return -1L
        return runCatching { backend.trackPlace(id) }.getOrDefault(-1L)
    }

    /** The index the queue would move to when the current track ends, if any. */
    private fun upcomingIndex(): Int? {
        val answer = runReducer(state, buildJsonObject { put("type", "track_ended") }) ?: return null
        return if (answer.plays) allowedFrom(state.index, answer.state.index) else null
    }

    private fun preloadNext() {
        // Nothing behind an audition: the engine would move on into it when the audition ends.
        if (stopAtTrackEnd || auditioning) return
        val index = upcomingIndex()
        if (index == null) {
            // The last song is playing: what follows it, ready before it ends.
            tracks.getOrNull(state.index)?.let { extendWithRadio(it, thenPlay = false) }
            return
        }
        val track = tracks.getOrNull(index) ?: return
        val token = nextToken++
        indexOfToken[token] = index
        preloadedToken = token
        preloadedIndex = index
        scope.launch {
            // Not behind a track with a place: the engine would run into it from the top, and its first
            // position would erase the place. Left out, the end of this song loads it, and load resumes.
            if (placeOf(track) > 0L) return@launch
            val audio = resolve(track) ?: return@launch
            if (token != preloadedToken) return@launch
            backend.send(EngineCommand.Preload(token, audio.url, gainOf(audio)))
        }
    }

    private fun onEvent(event: EngineEvent) {
        // An audition is not the queue's: no listen, no position saved, nothing preloaded behind
        // it, and its end or failure moves the queue nowhere.
        if (auditioning && auditionEvent(event)) return
        when (event) {
            is EngineEvent.Ready -> if (event.token == currentToken) {
                failuresInARow = 0
                startListening(event.token)
                _ui.update { it.copy(loading = false, durationMs = event.durationMs) }
                preloadNext()
            }
            is EngineEvent.State -> _ui.update { it.copy(playing = event.playing) }
            is EngineEvent.Position -> if (event.token == currentToken) {
                progressListening(event.positionMs)
                state = state.copy(positionMs = event.positionMs)
                _ui.update { it.copy(positionMs = event.positionMs) }
                tracks.getOrNull(state.index)?.let { rememberPlace(it, event.positionMs, force = false) }
            }
            is EngineEvent.Transitioned -> if (event.to == preloadedToken) {
                finishListening(completed = true, errored = false)
                onTrackEnded?.invoke()
                currentToken = event.to
                preloadedToken = 0
                startListening(event.to)
                // The engine is already playing it: tell the reducer, but do not load it again.
                reduce(buildJsonObject { put("type", "track_ended") }, skipPlay = true)
                // It is the row that was preloaded, past any blocked song the reducer knows nothing of.
                indexOfToken[event.to]?.let { to -> state = state.copy(index = to) }
                _ui.update { it.copy(index = state.index, positionMs = 0, durationMs = null) }
                preloadNext()
            }
            is EngineEvent.Ended -> if (event.token == currentToken) {
                finishListening(completed = true, errored = false)
                val last = stopAtEnd
                // Used up here, past the setter: turning it off there preloads what comes next,
                // which would reach the engine after the Stop below.
                stopAtEnd = false
                onTrackEnded?.invoke()
                if (last) stopAfterTrack() else reduce(buildJsonObject { put("type", "track_ended") }, moving = true)
            }
            is EngineEvent.Error -> if (event.token == currentToken) {
                finishListening(completed = false, errored = true)
                failed(event.message)
            }
            is EngineEvent.Loading, is EngineEvent.Preloaded -> Unit
        }
    }

    /** [event] while an audition plays; true when it was the audition's and is handled. */
    private fun auditionEvent(event: EngineEvent): Boolean {
        when (event) {
            is EngineEvent.Ready -> {
                if (event.token != auditionToken) return false
                _ui.update { it.copy(loading = false, durationMs = event.durationMs) }
            }
            is EngineEvent.Position -> {
                if (event.token != auditionToken) return false
                _ui.update { it.copy(positionMs = event.positionMs) }
            }
            is EngineEvent.Ended -> {
                if (event.token != auditionToken) return false
                endAudition()
            }
            is EngineEvent.Error -> {
                if (event.token != auditionToken) return false
                endAudition(error = event.message)
            }
            else -> return false
        }
        return true
    }

    /**
     * The song that just ended was the last ([stopAtTrackEnd]): the queue moves to the next one but
     * nothing is loaded, so play picks up there, as after [restore].
     */
    private fun stopAfterTrack() {
        reduce(buildJsonObject { put("type", "track_ended") }, skipPlay = true)
        state = state.copy(positionMs = 0)
        currentToken = 0
        backend.send(EngineCommand.Stop)
        _ui.update { it.copy(playing = false, positionMs = 0, durationMs = null) }
        save()
    }

    private fun failed(message: String) {
        failuresInARow++
        Log.w(TAG, "playback failed ($failuresInARow in a row): $message")
        _ui.update { it.copy(loading = false, error = message) }
        // Straight to the reducer, not next(): a failure is not someone pressing a button, and a Jam
        // guest must not send the host on because its own copy failed to load.
        if (failuresInARow < 3) reduce(buildJsonObject { put("type", "next") }, moving = true) else backend.send(EngineCommand.Stop)
    }

    /**
     * Where a track that is not a file of its own has a copy on this computer, when the user asked
     * for the local copy first: its `file://` URI, or null to stream it. Set by the platform, which
     * has the local library (the phone's "match local first").
     */
    var localCopy: (suspend (FullTrack) -> String?)? = null

    /**
     * The platform's chain of audio sources, when it has one (the desktop: the phone's chain, in the
     * order and with the settings chosen in Settings); null resolves through the core's resolver and
     * InnerTube. See [StreamResolver.sources].
     */
    var sources: StreamResolver.Sources?
        get() = resolver.sources
        set(value) {
            resolver.sources = value
        }

    private suspend fun resolve(track: FullTrack, hint: String? = null): StreamResolver.Stream? =
        resolver.resolve(track, localCopy, hint)

    /** YouTube's own player turns a loud recording down by its `loudnessDb`, and never up. */
    private fun gainOf(audio: StreamResolver.Stream): Float {
        val louder = (audio.loudnessDb ?: 0.0).coerceAtLeast(0.0)
        return (-louder).toFloat()
    }

    private companion object {
        const val TAG = "QueuePlayer"
        // v2: the queue became FullTrack (v1 held YtmTrack and was never released).
        const val STORE_KEY = "queue_v2"
    }
}
