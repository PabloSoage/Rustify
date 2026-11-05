// shared/src/coreCallMain/kotlin/com/varuna/rustify/bridge/CoreCall.kt
package com.varuna.rustify.bridge

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject

/**
 * One call to the core's JSON dispatcher (`ffi::dispatch`): the answer is what the JNI bridge of the same
 * name answers. [call] is for whatever may touch the network or the disk; [callBlocking] for memory-only
 * methods (`parseLink`, `searchQuery`, `playerReduce`), and it blocks its caller until the core answers.
 */
expect object CoreCall {
    fun callBlocking(method: String, args: JsonObject = JsonObject(emptyMap())): String

    suspend fun call(method: String, args: JsonObject = JsonObject(emptyMap())): String
}

suspend fun CoreCall.call(method: String, build: JsonObjectBuilder.() -> Unit): String =
    call(method, buildJsonObject(build))
