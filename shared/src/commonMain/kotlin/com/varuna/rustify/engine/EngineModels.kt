// shared/src/commonMain/kotlin/com/varuna/rustify/engine/EngineModels.kt
package com.varuna.rustify.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * The Rust audio engine's commands and events (`core_engine/src/engine/mod.rs`), for the platforms
 * that play through it: the desktop (JNI, `RustAudioEngine`) and iOS (C ABI, `engineSend`).
 *
 * Same contract on both sides, pinned the same way as the Spotify models: the Rust test
 * `events_serialise_in_the_shape_the_bridges_send` and `commands_deserialise_from_the_shape_the_app_sends`
 * spell out the JSON, and `EngineModelsTest` here decodes and encodes those very strings.
 */

/** Tolerant like `RustifyJson`: a newer core may add fields. Unknown event types decode to null. */
val EngineJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    classDiscriminator = "type"
}

@Serializable
sealed class EngineCommand {
    @Serializable
    @SerialName("load")
    data class Load(
        val token: Long,
        val uri: String,
        val startMs: Long = 0,
        val gainDb: Float = 0f,
        val play: Boolean = true,
    ) : EngineCommand()

    @Serializable
    @SerialName("preload")
    data class Preload(val token: Long, val uri: String, val gainDb: Float = 0f) : EngineCommand()

    @Serializable
    @SerialName("clearNext")
    data object ClearNext : EngineCommand()

    @Serializable
    @SerialName("play")
    data object Play : EngineCommand()

    @Serializable
    @SerialName("pause")
    data object Pause : EngineCommand()

    @Serializable
    @SerialName("seek")
    data class Seek(val positionMs: Long) : EngineCommand()

    @Serializable
    @SerialName("stop")
    data object Stop : EngineCommand()

    @Serializable
    @SerialName("setVolume")
    data class SetVolume(val volume: Float) : EngineCommand()

    @Serializable
    @SerialName("setFadeMs")
    data class SetFadeMs(val ms: Int) : EngineCommand()

    fun toJson(): String = EngineJson.encodeToString(serializer(), this)
}

@Serializable
sealed class EngineEvent {
    @Serializable
    @SerialName("loading")
    data class Loading(val token: Long) : EngineEvent()

    @Serializable
    @SerialName("ready")
    data class Ready(val token: Long, val durationMs: Long? = null) : EngineEvent()

    @Serializable
    @SerialName("preloaded")
    data class Preloaded(val token: Long, val durationMs: Long? = null) : EngineEvent()

    @Serializable
    @SerialName("state")
    data class State(val playing: Boolean) : EngineEvent()

    @Serializable
    @SerialName("position")
    data class Position(val token: Long, val positionMs: Long) : EngineEvent()

    /** The device went from [from] to the preloaded [to] with no gap. */
    @Serializable
    @SerialName("transitioned")
    data class Transitioned(val from: Long, val to: Long) : EngineEvent()

    /** [token] was heard to its last sample and nothing followed it. */
    @Serializable
    @SerialName("ended")
    data class Ended(val token: Long) : EngineEvent()

    /**
     * [code] is what failed (`open`, `http`, `unsupported`, `decode`, `output`); [kind] whether
     * trying again could help (`auth`, `rateLimited`, `transient`, `permanent`). An `unsupported`
     * stream means "ask the next source", not "the track is broken".
     */
    @Serializable
    @SerialName("error")
    data class Error(
        val token: Long? = null,
        val code: String = "",
        val kind: String = "permanent",
        val message: String = "",
    ) : EngineEvent()

    companion object {
        /** One event from the bridge's answer; `null` for "no event" or a type this build does not know. */
        fun parse(json: String): EngineEvent? {
            if (json == "null" || json.isBlank()) return null
            return runCatching { EngineJson.decodeFromString(serializer(), json) }.getOrNull()
        }
    }
}
