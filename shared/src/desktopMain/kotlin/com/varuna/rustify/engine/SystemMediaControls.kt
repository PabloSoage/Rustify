// shared/src/desktopMain/kotlin/com/varuna/rustify/engine/SystemMediaControls.kt
package com.varuna.rustify.engine

import com.varuna.rustify.bridge.NativeLibrary
import com.varuna.rustify.platform.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The system's media controls (`core_engine/src/media_controls.rs`, feature `media-controls`): the
 * Windows overlay and media keys, MPRIS on Linux, Now Playing on macOS.
 *
 * Optional by construction. A core built without the feature, or a system with nothing to register
 * with (a Linux box without a session bus), answers `false` from [start] and the app plays on
 * without it.
 */
object SystemMediaControls {
    private external fun startNative(displayName: String, hwnd: Long): String
    private external fun updateNative(updateJson: String): String
    private external fun pollNative(timeoutMs: Int): String
    private external fun stopNative(): String

    /** [windowHandle] is the native window (Windows needs it; 0 is fine elsewhere). */
    suspend fun start(displayName: String, windowHandle: Long): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            NativeLibrary.load()
            startNative(displayName, windowHandle)
        }.fold(
            onSuccess = { answer ->
                answer.contains("\"success\":true").also { ok ->
                    if (!ok) Log.w(TAG, "media controls not started: $answer")
                }
            },
            // UnsatisfiedLinkError when the core was built without `media-controls`.
            onFailure = {
                Log.w(TAG, "media controls unavailable", it)
                false
            },
        )
    }

    fun metadata(title: String, artist: String, album: String, coverUrl: String?, durationMs: Long?) {
        send(buildJsonObject {
            put("type", "metadata")
            put("title", title)
            put("artist", artist)
            put("album", album)
            coverUrl?.let { put("coverUrl", it) }
            durationMs?.let { put("durationMs", it) }
        }.toString())
    }

    fun playback(playing: Boolean, positionMs: Long?) {
        send(buildJsonObject {
            put("type", "playback")
            put("playing", playing)
            positionMs?.let { put("positionMs", it) }
        }.toString())
    }

    fun stopped() = send("""{"type":"stopped"}""")

    private fun send(json: String) {
        runCatching { updateNative(json) }.onFailure { Log.w(TAG, "update failed", it) }
    }

    /** What the system asks for, for as long as it is collected. */
    fun requests(pollMs: Int = 250): Flow<MediaRequest> = flow {
        while (currentCoroutineContext().isActive) {
            val raw = pollNative(pollMs)
            if (raw != "null") {
                runCatching { EngineJson.decodeFromString(MediaRequest.serializer(), raw) }
                    .getOrNull()
                    ?.let { emit(it) }
            }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun stop() {
        withContext(Dispatchers.IO) { runCatching { stopNative() } }
    }

    private const val TAG = "SystemMediaControls"
}

/** `media_controls::Request`, the same names. */
@Serializable
sealed class MediaRequest {
    @Serializable @SerialName("play") data object Play : MediaRequest()
    @Serializable @SerialName("pause") data object Pause : MediaRequest()
    @Serializable @SerialName("toggle") data object Toggle : MediaRequest()
    @Serializable @SerialName("next") data object Next : MediaRequest()
    @Serializable @SerialName("previous") data object Previous : MediaRequest()
    @Serializable @SerialName("stop") data object Stop : MediaRequest()
    @Serializable @SerialName("seekBy") data class SeekBy(val offsetMs: Long) : MediaRequest()
    @Serializable @SerialName("setPosition") data class SetPosition(val positionMs: Long) : MediaRequest()
    @Serializable @SerialName("raise") data object Raise : MediaRequest()
    @Serializable @SerialName("quit") data object Quit : MediaRequest()
}
