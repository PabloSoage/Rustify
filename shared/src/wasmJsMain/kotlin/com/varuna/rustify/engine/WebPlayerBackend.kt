// shared/src/wasmJsMain/kotlin/com/varuna/rustify/engine/WebPlayerBackend.kt
package com.varuna.rustify.engine

import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.NativeEngine
import com.varuna.rustify.platform.throughServer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

private external class HTMLAudioElement : JsAny {
    var src: String
    var currentTime: Double
    var volume: Double
    var preload: String
    var playbackRate: Double
    val duration: Double
    val paused: Boolean
    fun pause()
    fun load()
    fun addEventListener(type: String, listener: (JsAny) -> Unit)
}

private fun newAudio(): HTMLAudioElement = js("new Audio()")

private fun startPlaying(audio: HTMLAudioElement, onRefused: (String) -> Unit): Unit =
    js("audio.play().catch(function(e) { onRefused(String(e.name)); })")

private fun errorText(audio: HTMLAudioElement): String =
    js("audio.error ? ('media error ' + audio.error.code + ': ' + audio.error.message) : 'media error'")

/**
 * [PlayerBackend] in the browser: the song plays in an `<audio>`, on the listener's device, and everything
 * else (the queue's decisions, finding the audio) goes to the core on the server. A second element holds
 * the next song ready, which is what makes the change between two songs gapless, or nearly so.
 *
 * Effects go through Web Audio ([WebAudioGraph]): the equaliser, AutoEq, the stereo tools, the fades
 * and the speed. What a page cannot do (normalisation, ReplayGain, skipping silence, pitch) the web's
 * Settings do not offer.
 */
object WebPlayerBackend : PlayerBackend {
    private val events = Channel<EngineEvent>(Channel.UNLIMITED)
    private var current = element()
    private var next: HTMLAudioElement? = null
    private var currentToken = 0L
    private var nextToken = 0L
    private var wanted = false
    private val graph = WebAudioGraph()
    private var effects = EffectsConfig()

    /** The song playing now is fading out at its end ("fade between songs"), so it is not asked twice. */
    private var fadingOut = false

    /** The song was paused by a Pause, and the next Play resumes it (with the pause fade). */
    private var resumingFromPause = false

    private fun element(): HTMLAudioElement = newAudio().also { audio ->
        audio.preload = "auto"
        audio.addEventListener("loadedmetadata") { onMetadata(audio) }
        audio.addEventListener("timeupdate") {
            if (audio === current) {
                emit(EngineEvent.Position(currentToken, (audio.currentTime * 1000).toLong()))
                fadeOutAtEnd(audio)
            }
        }
        audio.addEventListener("playing") { if (audio === current) emit(EngineEvent.State(true)) }
        audio.addEventListener("pause") { if (audio === current && !wanted) emit(EngineEvent.State(false)) }
        audio.addEventListener("ended") { if (audio === current) onEnded() }
        audio.addEventListener("error") { onError(audio) }
    }

    private fun emit(event: EngineEvent) {
        events.trySend(event)
    }

    private fun durationOf(audio: HTMLAudioElement): Long? =
        audio.duration.takeIf { it.isFinite() && it > 0 }?.let { (it * 1000).toLong() }

    private fun onMetadata(audio: HTMLAudioElement) {
        when {
            audio === current -> emit(EngineEvent.Ready(currentToken, durationOf(audio)))
            audio === next -> emit(EngineEvent.Preloaded(nextToken, durationOf(audio)))
        }
    }

    /** "Fade between songs": the last seconds of each fade out (the next one fades in as it starts). */
    private fun fadeOutAtEnd(audio: HTMLAudioElement) {
        val between = effects.fades.betweenSongsMs
        if (between <= 0 || fadingOut) return
        val left = durationOf(audio)?.minus((audio.currentTime * 1000).toLong()) ?: return
        if (left > between) return
        fadingOut = true
        graph.fade(graph.attach(audio), 0.0, left.toInt().coerceAtLeast(0))
    }

    /** A song starting: through the effects, faded in when "fade between songs" asks for it. */
    private fun startSong(audio: HTMLAudioElement) {
        fadingOut = false
        val own = graph.attach(audio)
        val between = effects.fades.betweenSongsMs
        if (between > 0) {
            graph.set(own, 0.0)
            graph.fade(own, 1.0, between)
        } else {
            graph.set(own, 1.0)
        }
    }

    private fun onEnded() {
        val following = next
        if (following == null) {
            emit(EngineEvent.Ended(currentToken))
            return
        }
        val from = currentToken
        current = following
        currentToken = nextToken
        next = null
        emit(EngineEvent.Transitioned(from, currentToken))
        startSong(current)
        play()
    }

    private fun onError(audio: HTMLAudioElement) {
        val token = if (audio === current) currentToken else if (audio === next) nextToken else return
        // What the browser cannot read is for the next source to answer, as the engine's `unsupported`.
        emit(EngineEvent.Error(token, code = "unsupported", kind = "permanent", message = errorText(audio)))
    }

    private fun play() {
        wanted = true
        graph.resume()
        val own = graph.attach(current)
        // Resuming after a faded pause comes back up the same way.
        if (effects.fades.pauseMs > 0 && resumingFromPause) {
            graph.set(own, 0.0)
            graph.fade(own, 1.0, effects.fades.pauseMs)
        }
        resumingFromPause = false
        startPlaying(current) { reason ->
            wanted = false
            // A browser refuses to start sound no gesture asked for: the button is there, it says paused.
            if (reason == "NotAllowedError") emit(EngineEvent.State(false))
        }
    }

    /** What an `<audio>` can read from anywhere: the core's own server, and any other host, through this one. */
    private fun playable(uri: String): String = when {
        uri.startsWith("http://127.0.0.1") || uri.startsWith("http://localhost") ->
            "api/stream/" + uri.substringAfter("/stream/")
        uri.startsWith("http") -> throughServer(uri)
        uri.startsWith("file://") -> com.varuna.rustify.platform.rawUrlOf(uri.removePrefix("file://"))
        else -> uri
    }

    override suspend fun startEngine(): String = """{"success":true}"""

    override fun send(command: EngineCommand) {
        when (command) {
            is EngineCommand.Load -> {
                current.pause()
                current = element()
                currentToken = command.token
                next = null
                emit(EngineEvent.Loading(command.token))
                current.src = playable(command.uri)
                current.playbackRate = speedOf(effects)
                if (command.startMs > 0) current.currentTime = command.startMs / 1000.0
                current.load()
                startSong(current)
                resumingFromPause = false
                if (command.play) play() else wanted = false
            }
            is EngineCommand.Preload -> {
                nextToken = command.token
                next = element().also {
                    it.src = playable(command.uri)
                    it.playbackRate = speedOf(effects)
                    it.load()
                }
            }
            EngineCommand.ClearNext -> next = null
            EngineCommand.Play -> play()
            EngineCommand.Pause -> {
                wanted = false
                val pausing = current
                resumingFromPause = true
                val fade = effects.fades.pauseMs
                if (fade > 0) graph.fade(graph.attach(pausing), 0.0, fade) { if (!wanted) pausing.pause() }
                else pausing.pause()
            }
            is EngineCommand.Seek -> {
                current.currentTime = command.positionMs / 1000.0
                emit(EngineEvent.Position(currentToken, command.positionMs))
            }
            EngineCommand.Stop -> {
                wanted = false
                current.pause()
                next = null
                emit(EngineEvent.State(false))
            }
            is EngineCommand.SetVolume -> graph.setVolume(command.volume.toDouble().coerceIn(0.0, 1.0))
            is EngineCommand.SetEffects -> {
                effects = command.effects
                current.playbackRate = speedOf(effects)
                next?.playbackRate = speedOf(effects)
                graph.apply(effects)
            }
            is EngineCommand.SetFadeMs, EngineCommand.ReopenOutput -> Unit
        }
    }

    private fun speedOf(effects: EffectsConfig): Double = effects.speed.toDouble().takeIf { it > 0 } ?: 1.0

    override fun events(): Flow<EngineEvent> = events.receiveAsFlow()

    override fun reduce(stateJson: String, actionJson: String): String =
        NativeEngine.playerReduceNative(stateJson, actionJson)

    override fun registerTrackMetadata(track: FullTrack) {
        val id = track.id ?: return
        val artists = JsonArray(track.artists.map { JsonPrimitive(it.name) }).toString()
        NativeEngine.registerTrackMetadataNative(id, track.name, artists, track.durationMs, track.isrc)
    }

    override suspend fun resolveYouTubeId(spotifyId: String): String = NativeEngine.resolveYouTubeId(spotifyId, "")

    override suspend fun recordPlay(eventJson: String): String = NativeEngine.recordPlay(eventJson)

    override suspend fun resolveAudio(videoId: String, prefsJson: String): String =
        NativeEngine.resolveInnertubeAudio(videoId, prefsJson)

    override suspend fun trackPlace(trackId: String): Long = NativeEngine.trackPlace(trackId)

    override suspend fun recordTrackPlace(trackId: String, positionMs: Long, durationMs: Long): String =
        NativeEngine.recordTrackPlace(trackId, positionMs, durationMs)

    override suspend fun forgetTrackPlace(trackId: String): String = NativeEngine.forgetTrackPlace(trackId)
}
