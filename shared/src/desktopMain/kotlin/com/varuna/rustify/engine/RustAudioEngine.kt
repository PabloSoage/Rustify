// shared/src/desktopMain/kotlin/com/varuna/rustify/engine/RustAudioEngine.kt
package com.varuna.rustify.engine

import com.varuna.rustify.bridge.NativeLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * The core's audio engine on the desktop JVM (`core_engine/src/engine/`, JNI bridges at the end of
 * `lib.rs`, built with the `desktop-audio` feature).
 *
 * Same discipline as [com.varuna.rustify.bridge.NativeEngine]: the `external fun`s are private and
 * the blocking ones are reached only through `suspend` wrappers on `Dispatchers.IO`. [send] is plain
 * because the bridge only queues the command.
 */
object RustAudioEngine {
    init {
        NativeLibrary.load()
    }

    private external fun startNative(): String
    private external fun sendNative(commandJson: String): String
    private external fun pollNative(timeoutMs: Int): String
    private external fun stopNative(): String

    /** Opens the default output device. Idempotent. Answers the usual `{"success":…}`. */
    suspend fun start(): String = withContext(Dispatchers.IO) { startNative() }

    /** Queues [command]; never waits for it to happen. Memory only, so callable from the UI thread. */
    fun send(command: EngineCommand): String = sendNative(command.toJson())

    /**
     * Every event, for as long as the collector is active. One collector at a time: the engine has a
     * single event queue and two readers would split it between them.
     */
    fun events(pollMs: Int = 250): Flow<EngineEvent> = flow {
        while (currentCoroutineContext().isActive) {
            EngineEvent.parse(pollNative(pollMs))?.let { emit(it) }
        }
    }.flowOn(Dispatchers.IO)

    /** Releases the device. */
    suspend fun stop(): String = withContext(Dispatchers.IO) { stopNative() }
}
