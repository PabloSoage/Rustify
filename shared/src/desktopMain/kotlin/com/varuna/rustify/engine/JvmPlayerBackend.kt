// shared/src/desktopMain/kotlin/com/varuna/rustify/engine/JvmPlayerBackend.kt
package com.varuna.rustify.engine

import com.varuna.rustify.bridge.NativeEngine
import kotlinx.coroutines.flow.Flow

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
}
