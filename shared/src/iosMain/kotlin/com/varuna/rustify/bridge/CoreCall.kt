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

/**
 * The core on iOS: `rustify_call` from `core_engine/include/rustify.h`, through the `rustify` cinterop.
 *
 * It answers exactly what the JNI bridge of the same name answers on Android (`src/ffi.rs`), so the
 * decoders in `SpotifyModels.kt` read it unchanged. Like a JNI call, a call blocks its thread until
 * the core answers, so [call] runs on `Dispatchers.IO`; [callBlocking] is for memory-only methods
 * (`parseLink`, `searchQuery`, `playerReduce`) and for callers already off the main thread.
 *
 * Code that is not iOS-only calls [NativeEngine], which is built on this; what calls it directly is
 * what `NativeEngine` does not cover, the audio engine (`engine*`) and the version.
 */
@OptIn(ExperimentalForeignApi::class)
actual object CoreCall {
    private const val FAILURE = """{"success":false,"error":"no answer from the core","kind":"permanent"}"""

    actual fun callBlocking(method: String, args: JsonObject): String {
        val answer = rustify_call(method, args.toString()) ?: return FAILURE
        return try {
            answer.toKString()
        } finally {
            rustify_free(answer)
        }
    }

    actual suspend fun call(method: String, args: JsonObject): String =
        withContext(Dispatchers.IO) { callBlocking(method, args) }

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
