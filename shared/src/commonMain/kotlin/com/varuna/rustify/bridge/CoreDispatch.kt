// shared/src/commonMain/kotlin/com/varuna/rustify/bridge/CoreDispatch.kt
package com.varuna.rustify.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * What a [NativeEngine] over a JSON dispatcher — the C ABI's `rustify_call` on iOS, and the same
 * calls over HTTP later — needs to keep the JNI bridge's shape on both sides of the call.
 *
 * The dispatcher takes one JSON object of arguments and answers JSON. The JNI bridges take some
 * arguments as a *string* of JSON (an id list, a session, a config) and answer a few things that are
 * not JSON. `core_engine/include/rustify.h` says how each of those crosses; this is the other half.
 */
internal object CoreDispatch {
    /**
     * A string of JSON as the value the dispatcher expects in its place.
     *
     * Text that does not parse becomes `null`, which the core then refuses or defaults exactly where
     * its JNI twin refuses or defaults a string that does not parse — a failure answer, not a throw.
     */
    fun value(json: String): JsonElement =
        runCatching { Json.parseToJsonElement(json) }.getOrDefault(JsonNull)

    /**
     * A bare string the JNI twin answers (`wrapLink`, `unwrapLink`, `resolveYouTubeId`,
     * `getAlternativeTrack`), which the dispatcher answers as a JSON string. Anything else — a
     * failure envelope included — reads as `""`, the twin's "nothing".
     */
    fun string(answer: String): String =
        runCatching { (Json.parseToJsonElement(answer) as? JsonPrimitive)?.takeIf { it.isString }?.content }
            .getOrNull() ?: ""

    /** A `long` the JNI twin answers, which the dispatcher answers as a JSON number; [default] if not one. */
    fun long(answer: String, default: Long): Long = answer.trim().toLongOrNull() ?: default

    /** A `boolean` the JNI twin answers, which the dispatcher answers as `true` or `false`. */
    fun boolean(answer: String): Boolean = answer.trim() == "true"
}
