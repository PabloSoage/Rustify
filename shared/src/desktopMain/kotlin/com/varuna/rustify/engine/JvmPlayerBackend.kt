// shared/src/desktopMain/kotlin/com/varuna/rustify/engine/JvmPlayerBackend.kt
package com.varuna.rustify.engine

import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.NativeEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** [PlayerBackend] on the desktop JVM: the core through JNI, the engine through [RustAudioEngine]. */
object JvmPlayerBackend : PlayerBackend {
    override suspend fun startEngine(): String = RustAudioEngine.start()

    override fun send(command: EngineCommand) {
        RustAudioEngine.send(command)
    }

    override fun events(): Flow<EngineEvent> = RustAudioEngine.events()

    override fun reduce(stateJson: String, actionJson: String): String =
        NativeEngine.playerReduceNative(stateJson, actionJson)

    override suspend fun resolveAudio(videoId: String, prefsJson: String): String =
        NativeEngine.resolveInnertubeAudio(videoId, prefsJson)

    override fun registerTrackMetadata(track: FullTrack) {
        val id = track.id ?: return
        val artists = JsonArray(track.artists.map { JsonPrimitive(it.name) }).toString()
        NativeEngine.registerTrackMetadataNative(id, track.name, artists, track.durationMs, track.isrc)
    }

    override suspend fun resolveYouTubeId(spotifyId: String): String = NativeEngine.resolveYouTubeId(spotifyId, "")
}
