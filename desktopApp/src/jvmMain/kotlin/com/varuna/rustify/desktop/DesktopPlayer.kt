// desktopApp/src/jvmMain/kotlin/com/varuna/rustify/desktop/DesktopPlayer.kt
package com.varuna.rustify.desktop

import com.varuna.rustify.bridge.NativeEngine
import com.varuna.rustify.bridge.YtmTrack
import com.varuna.rustify.engine.EngineCommand
import com.varuna.rustify.engine.EngineEvent
import com.varuna.rustify.engine.RustAudioEngine
import com.varuna.rustify.platform.Log
import kotlinx.coroutines.CoroutineScope
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
 * The desktop's player: `player::reduce` decides, the core's engine plays.
 *
 * The same split Android has between `PlayerQueue` and ExoPlayer, without the Android: every
 * "what next" goes through `NativeEngine.playerReduceNative`, so repeat, "previous restarts after
 * ten seconds" and the end of the queue behave exactly as on the phone. This class only turns the
 * reducer's effects into engine commands and the engine's events back into actions.
 *
 * Gapless: when a track is ready, the one the reducer would play next is resolved and preloaded.
 * When the engine moves on to it by itself (`Transitioned`), the reducer is told the track ended and
 * the `Play` it answers is not sent — the engine is already playing it.
 *
 * Runs on one thread (the scope's, the Swing main thread): no locks, and events and clicks cannot
 * interleave in the middle of a reduce.
 */
class DesktopPlayer(private val scope: CoroutineScope) {

    data class Ui(
        val queue: List<YtmTrack> = emptyList(),
        val index: Int = -1,
        val playing: Boolean = false,
        val loading: Boolean = false,
        val positionMs: Long = 0,
        val durationMs: Long? = null,
        val volume: Float = 1f,
        val error: String? = null,
    ) {
        val current: YtmTrack? get() = queue.getOrNull(index)
    }

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    /** The reducer's state, as `player::PlayerState` serialises it. */
    @Serializable
    private data class QueueState(
        val queue: List<String> = emptyList(),
        val index: Int = 0,
        @SerialName("position_ms") val positionMs: Long = 0,
        val repeat: String = "off",
        val shuffle: Boolean = false,
    )

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
    private var tracks: List<YtmTrack> = emptyList()

    private var nextToken = 1L
    private var currentToken = 0L
    private var preloadedToken = 0L
    private var preloadedIndex = -1
    private val indexOfToken = mutableMapOf<Long, Int>()

    /** Failures in a row; at three the player stops instead of running through the queue (as on Android). */
    private var failuresInARow = 0

    fun start() {
        scope.launch {
            val started = RustAudioEngine.start()
            if (!started.contains("\"success\":true")) {
                _ui.update { it.copy(error = "Audio output: $started") }
                return@launch
            }
            RustAudioEngine.events().collect { onEvent(it) }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // What the window asks for
    // ---------------------------------------------------------------------------------------------

    fun playAll(list: List<YtmTrack>, start: Int) {
        tracks = list
        failuresInARow = 0
        _ui.update { it.copy(queue = list, error = null) }
        reduce(buildJsonObject {
            put("type", "set_queue")
            put("tracks", JsonArray(list.map { JsonPrimitive("ytm:${it.videoId}") }))
            put("start", start)
        })
    }

    fun next() = reduce(buildJsonObject { put("type", "next") })

    fun previous() = reduce(buildJsonObject { put("type", "previous") })

    fun togglePause() {
        RustAudioEngine.send(if (_ui.value.playing) EngineCommand.Pause else EngineCommand.Play)
    }

    fun seek(positionMs: Long) = reduce(buildJsonObject {
        put("type", "seek")
        put("ms", positionMs)
    })

    fun setVolume(volume: Float) {
        _ui.update { it.copy(volume = volume) }
        RustAudioEngine.send(EngineCommand.SetVolume(volume))
    }

    fun setRepeat(mode: String) = reduce(buildJsonObject {
        put("type", "set_repeat")
        put("repeat", mode)
    })

    fun shutdown() {
        RustAudioEngine.send(EngineCommand.Stop)
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
                "seek" -> RustAudioEngine.send(EngineCommand.Seek(effect["positionMs"]?.jsonPrimitive?.long ?: 0))
                "stop" -> {
                    RustAudioEngine.send(EngineCommand.Stop)
                    _ui.update { it.copy(playing = false) }
                }
                // No radio on the desktop yet (Android extends the queue here): the queue just ends.
                "queue_exhausted" -> Unit
                // Not persisted yet: docs 25 lists saving the queue through KeyValueStore.
                "persist" -> Unit
            }
        }
    }

    private fun runReducer(from: QueueState, action: JsonObject): ReduceAnswer? = runCatching {
        val raw = NativeEngine.playerReduceNative(json.encodeToString(QueueState.serializer(), from), action.toString())
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
        RustAudioEngine.send(EngineCommand.ClearNext)
        _ui.update { it.copy(loading = true, positionMs = startMs, durationMs = null, error = null) }
        scope.launch {
            val audio = resolve(track)
            if (token != currentToken) return@launch // Skipped while resolving.
            if (audio == null) {
                failed("Could not resolve \"${track.title}\"")
                return@launch
            }
            RustAudioEngine.send(EngineCommand.Load(token, audio.url, startMs, gainOf(audio), play = true))
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
            RustAudioEngine.send(EngineCommand.Preload(token, audio.url, gainOf(audio)))
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
        if (failuresInARow < 3) next() else RustAudioEngine.send(EngineCommand.Stop)
    }

    private suspend fun resolve(track: YtmTrack): ResolvedAudio? {
        // AAC (itag 140): the engine's decoder has no Opus (core_engine/src/engine/decode.rs).
        val raw = NativeEngine.resolveInnertubeAudio(track.videoId, """{"prefer":"aac","maxKbps":0}""")
        val audio = runCatching { json.decodeFromString(ResolvedAudio.serializer(), raw) }.getOrNull()
        return audio?.takeIf { it.success != false && it.url.isNotBlank() }
    }

    /** YouTube's own player turns a loud recording down by its `loudnessDb`, and never up. */
    private fun gainOf(audio: ResolvedAudio): Float {
        val louder = (audio.loudnessDb ?: 0.0).coerceAtLeast(0.0)
        return (-louder).toFloat()
    }

    private companion object {
        const val TAG = "DesktopPlayer"
    }
}
