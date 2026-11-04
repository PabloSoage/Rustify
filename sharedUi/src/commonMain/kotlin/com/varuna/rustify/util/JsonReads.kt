// sharedUi/src/commonMain/kotlin/com/varuna/rustify/util/JsonReads.kt
package com.varuna.rustify.util

import com.varuna.rustify.bridge.RustifyJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/*
 * org.json's opt* reads over kotlinx elements, for the files and the answers the stores read with
 * org.json before they moved to common code: absent or null is the fallback, a number or a boolean
 * read as text is its text, and text read as a number is parsed. What the app wrote reads back the
 * same; only something it never wrote could tell the two apart.
 */

/** `JSONObject(text)`: the object [text] holds. Throws when it is not one. */
internal fun parseObject(text: String): JsonObject = RustifyJson.parseToJsonElement(text) as JsonObject

/** `JSONArray(text)`: the array [text] holds. Throws when it is not one. */
internal fun parseArray(text: String): JsonArray = RustifyJson.parseToJsonElement(text) as JsonArray

/** `optJSONObject`. */
internal fun JsonElement?.optObject(): JsonObject? = this as? JsonObject

/** `optJSONArray`. */
internal fun JsonElement?.optArray(): JsonArray? = this as? JsonArray

/** `optString(key, fallback)`. */
internal fun JsonElement?.optString(fallback: String = ""): String = optStringOrNull() ?: fallback

/** `optString(key, null)`: absent or null is null. */
internal fun JsonElement?.optStringOrNull(): String? = when (this) {
    null, JsonNull -> null
    is JsonPrimitive -> content
    else -> toString()
}

/** `optInt(key, fallback)`: a fraction is cut, as `Number.intValue()` does. */
internal fun JsonElement?.optInt(fallback: Int = 0): Int =
    (this as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() } ?: fallback

/** `optLong(key, fallback)`. */
internal fun JsonElement?.optLong(fallback: Long = 0L): Long =
    (this as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() } ?: fallback

/** `optBoolean(key, fallback)`: `true` and `false`, or the same as text in any case. */
internal fun JsonElement?.optBoolean(fallback: Boolean = false): Boolean {
    if (this !is JsonPrimitive || this is JsonNull) return fallback
    return when (content.lowercase()) {
        "true" -> true
        "false" -> false
        else -> fallback
    }
}
