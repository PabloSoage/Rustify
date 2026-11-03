// shared/src/commonMain/kotlin/com/varuna/rustify/engine/QueuePlayer.kt
package com.varuna.rustify.engine

import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.TrackRef
import com.varuna.rustify.platform.KeyValueStore
import com.varuna.rustify.platform.Log
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
    private data class ReduceAnswer(val state: QueueState = QueueState(), val effects: List<JsonObject> = emptyList())

    /** What `resolveInnertubeAudio` answers (`youtube::innertube::InnertubeAudio`), the fields used here. */
    @Serializable
    private data class ResolvedAudio(
        val url: String = "",
        val mimeType: String = "",
        val loudnessDb: Double? = null,
        val success: Boolean? = null,
        val error: String? = null,
    )

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
            backend.events().collect { onEvent(it) }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // What the window asks for
    // ---------------------------------------------------------------------------------------------

    /**
     * Plays [list] from [start]. Spotify and YouTube Music tracks mix freely: a `ytm:` id is played
     * as that video, anything else goes through the core's resolver, as on the phone. Tracks with no
     * id (a local file on Android) cannot play here and are dropped.
     */
    fun playAll(list: List<FullTrack>, start: Int) {
        val playable = list.filter { it.id != null }
        val first = list.getOrNull(start)?.let { playable.indexOf(it) }?.takeIf { it >= 0 } ?: 0
        tracks = playable
        failuresInARow = 0
        _ui.update { it.copy(queue = playable, error = null) }
        reduce(buildJsonObject {
            put("type", "set_queue")
            put("tracks", JsonArray(playable.map { JsonPrimitive(it.id.orEmpty()) }))
            put("start", first)
        })
    }

    fun next() = reduce(buildJsonObject { put("type", "next") })

    fun previous() = reduce(buildJsonObject { put("type", "previous") })

    fun togglePause() {
        when {
            // A restored queue has nothing loaded yet: play starts it where it was left.
            currentToken == 0L && tracks.isNotEmpty() -> load(state.index, state.positionMs)
            _ui.value.playing -> {
                backend.send(EngineCommand.Pause)
                save()
            }
            else -> backend.send(EngineCommand.Play)
        }
    }

    fun seek(positionMs: Long) = reduce(buildJsonObject {
        put("type", "seek")
        put("ms", positionMs)
    })

    fun setVolume(volume: Float) {
        _ui.update { it.copy(volume = volume) }
        backend.send(EngineCommand.SetVolume(volume))
    }

    fun setRepeat(mode: String) = reduce(buildJsonObject {
        put("type", "set_repeat")
        put("repeat", mode)
    })

    fun shutdown() {
        save()
        backend.send(EngineCommand.Stop)
    }

    // ---------------------------------------------------------------------------------------------
    // The reducer
    // ---------------------------------------------------------------------------------------------

    /** Runs [action] through `player::reduce` and applies its effects. [skipPlay] drops a `Play`. */
    private fun reduce(action: JsonObject, skipPlay: Boolean = false) {
        val answer = runReducer(state, action) ?: return
        state = answer.state
        _ui.update { it.copy(index = state.index) }
        for (effect in answer.effects) {
            when (effect["type"]?.jsonPrimitive?.content) {
                "play" -> if (!skipPlay) {
                    load(state.index, effect["positionMs"]?.jsonPrimitive?.long ?: 0)
                }
                "seek" -> backend.send(EngineCommand.Seek(effect["positionMs"]?.jsonPrimitive?.long ?: 0))
                "stop" -> {
                    backend.send(EngineCommand.Stop)
                    _ui.update { it.copy(playing = false) }
                }
                // No radio on the desktop yet (Android extends the queue here): the queue just ends.
                "queue_exhausted" -> Unit
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

    private fun load(index: Int, startMs: Long) {
        val track = tracks.getOrNull(index) ?: return
        val token = nextToken++
        indexOfToken[token] = index
        currentToken = token
        preloadedToken = 0
        preloadedIndex = -1
        backend.send(EngineCommand.ClearNext)
        _ui.update { it.copy(loading = true, positionMs = startMs, durationMs = null, error = null) }
        scope.launch {
            val audio = resolve(track)
            if (token != currentToken) return@launch // Skipped while resolving.
            if (audio == null) {
                failed("Could not resolve \"${track.name}\"")
                return@launch
            }
            backend.send(EngineCommand.Load(token, audio.url, startMs, gainOf(audio), play = true))
        }
    }

    /** The index the reducer would move to when the current track ends, if any. */
    private fun upcomingIndex(): Int? {
        val answer = runReducer(state, buildJsonObject { put("type", "track_ended") }) ?: return null
        return if (answer.effects.any { it["type"]?.jsonPrimitive?.content == "play" }) answer.state.index else null
    }

    private fun preloadNext() {
        val index = upcomingIndex() ?: return
        val track = tracks.getOrNull(index) ?: return
        val token = nextToken++
        indexOfToken[token] = index
        preloadedToken = token
        preloadedIndex = index
        scope.launch {
            val audio = resolve(track) ?: return@launch
            if (token != preloadedToken) return@launch
            backend.send(EngineCommand.Preload(token, audio.url, gainOf(audio)))
        }
    }

    private fun onEvent(event: EngineEvent) {
        when (event) {
            is EngineEvent.Ready -> if (event.token == currentToken) {
                failuresInARow = 0
                _ui.update { it.copy(loading = false, durationMs = event.durationMs) }
                preloadNext()
            }
            is EngineEvent.State -> _ui.update { it.copy(playing = event.playing) }
            is EngineEvent.Position -> if (event.token == currentToken) {
                state = state.copy(positionMs = event.positionMs)
                _ui.update { it.copy(positionMs = event.positionMs) }
            }
            is EngineEvent.Transitioned -> if (event.to == preloadedToken) {
                currentToken = event.to
                preloadedToken = 0
                // The engine is already playing it: tell the reducer, but do not load it again.
                reduce(buildJsonObject { put("type", "track_ended") }, skipPlay = true)
                _ui.update { it.copy(positionMs = 0, durationMs = null) }
                preloadNext()
            }
            is EngineEvent.Ended -> if (event.token == currentToken) {
                reduce(buildJsonObject { put("type", "track_ended") })
            }
            is EngineEvent.Error -> if (event.token == currentToken) {
                failed(event.message)
            }
            is EngineEvent.Loading, is EngineEvent.Preloaded -> Unit
        }
    }

    private fun failed(message: String) {
        failuresInARow++
        Log.w(TAG, "playback failed ($failuresInARow in a row): $message")
        _ui.update { it.copy(loading = false, error = message) }
        if (failuresInARow < 3) next() else backend.send(EngineCommand.Stop)
    }

    private suspend fun resolve(track: FullTrack): ResolvedAudio? {
        val id = track.id ?: return null
        val videoId = TrackRef.youtubeVideoIdOf(id) ?: run {
            // A Spotify track: the same resolver the phone uses, which needs the metadata first.
            backend.registerTrackMetadata(track)
            VIDEO_ID.find(backend.resolveYouTubeId(id))?.value
        } ?: return null
        // AAC (itag 140): the engine's decoder has no Opus (core_engine/src/engine/decode.rs).
        val raw = backend.resolveAudio(videoId, """{"prefer":"aac","maxKbps":0}""")
        val audio = runCatching { json.decodeFromString(ResolvedAudio.serializer(), raw) }.getOrNull()
        return audio?.takeIf { it.success != false && it.url.isNotBlank() }
    }

    /** YouTube's own player turns a loud recording down by its `loudnessDb`, and never up. */
    private fun gainOf(audio: ResolvedAudio): Float {
        val louder = (audio.loudnessDb ?: 0.0).coerceAtLeast(0.0)
        return (-louder).toFloat()
    }

    private companion object {
        const val TAG = "QueuePlayer"
        // v2: the queue became FullTrack (v1 held YtmTrack and was never released).
        const val STORE_KEY = "queue_v2"
        /** A YouTube video id inside whatever the resolver answered. */
        val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
    }
}
