// shared/src/iosMain/kotlin/com/varuna/rustify/jam/IosJamCore.kt
package com.varuna.rustify.jam

import com.varuna.rustify.bridge.CoreCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

/** [JamCore] over the C ABI (`src/ffi.rs`, `jam*`). */
object IosJamCore : JamCore {
    private fun with(key: String, json: String) = JsonObject(mapOf(key to Json.parseToJsonElement(json)))

    override fun newCode(): String = CoreCall.callBlocking("jamNewCode")
    override suspend fun join(configJson: String): String = CoreCall.call("jamJoin", with("config", configJson))
    override suspend fun poll(timeoutMs: Int): String = CoreCall.call("jamPoll") { put("timeoutMs", timeoutMs) }
    override suspend fun publishState(stateJson: String): String = CoreCall.call("jamPublishState", with("state", stateJson))
    override suspend fun send(commandJson: String): String = CoreCall.call("jamSend", with("command", commandJson))
    override suspend fun kick(id: String): String = CoreCall.call("jamKick") { put("id", id) }
    override suspend fun leave(): String = CoreCall.call("jamLeave")
}
