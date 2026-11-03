// shared/src/iosMain/kotlin/com/varuna/rustify/bridge/CoreCall.kt
package com.varuna.rustify.bridge

import com.varuna.rustify.native.rustify_call
import com.varuna.rustify.native.rustify_free
import com.varuna.rustify.native.rustify_version
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject

/**
 * The core on iOS: `rustify_call` from `core_engine/include/rustify.h`, through the `rustify` cinterop.
 *
 * It answers exactly what the JNI bridge of the same name answers on Android (`src/ffi.rs`), so the
 * decoders in `SpotifyModels.kt` read it unchanged. Like a JNI call, a call blocks its thread until
 * the core answers, so [call] runs on `Dispatchers.IO`; [callBlocking] is for memory-only methods
 * (`parseLink`, `searchQuery`, `playerReduce`) and for callers already off the main thread.
 */
@OptIn(ExperimentalForeignApi::class)
object CoreCall {
    private const val FAILURE = """{"success":false,"error":"no answer from the core","kind":"permanent"}"""

    fun callBlocking(method: String, args: JsonObject = JsonObject(emptyMap())): String {
        val answer = rustify_call(method, args.toString()) ?: return FAILURE
        return try {
            answer.toKString()
        } finally {
            rustify_free(answer)
        }
    }

    suspend fun call(method: String, args: JsonObject = JsonObject(emptyMap())): String =
        withContext(Dispatchers.IO) { callBlocking(method, args) }

    suspend fun call(method: String, build: JsonObjectBuilder.() -> Unit): String =
        call(method, buildJsonObject(build))

    /** The core's version, to compare with the app's: a framework built from another commit says so. */
    fun version(): String {
        val v = rustify_version() ?: return ""
        return try {
            v.toKString()
        } finally {
            rustify_free(v)
        }
    }
}
