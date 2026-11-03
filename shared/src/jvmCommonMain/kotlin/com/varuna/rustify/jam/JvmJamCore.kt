// shared/src/jvmCommonMain/kotlin/com/varuna/rustify/jam/JvmJamCore.kt
package com.varuna.rustify.jam

import com.varuna.rustify.bridge.NativeEngine

/** [JamCore] over JNI: the same bridges Android's Jam calls. */
object JvmJamCore : JamCore {
    override fun newCode(): String = NativeEngine.jamNewCode()
    override suspend fun join(configJson: String): String = NativeEngine.jamJoin(configJson)
    override suspend fun poll(timeoutMs: Int): String = NativeEngine.jamPoll(timeoutMs)
    override suspend fun publishState(stateJson: String): String = NativeEngine.jamPublishState(stateJson)
    override suspend fun send(commandJson: String): String = NativeEngine.jamSend(commandJson)
    override suspend fun kick(id: String): String = NativeEngine.jamKick(id)
    override suspend fun leave(): String = NativeEngine.jamLeave()
}
