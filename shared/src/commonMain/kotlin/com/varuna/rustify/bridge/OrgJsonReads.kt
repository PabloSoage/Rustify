// shared/src/commonMain/kotlin/com/varuna/rustify/bridge/OrgJsonReads.kt
package com.varuna.rustify.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// org.json's `opt…` reads over kotlinx.serialization's tree, for the code that moved out of the app
// with them (the audio sources, the add-ons, the local server; docs serie-4.x/27 §2.5). org.json is
// part of Android and does not exist on the desktop's JVM or on iOS, and those reads were written
// against its forgiveness, so it is kept rather than tightened on the way: a missing or `null` field
// is the default, a number read as text is its text, and text read as a number is parsed.

/** Lenient like org.json's parser: what came back from a server is read, not judged. */
internal val OrgJson: Json = Json { isLenient = true }

/** [raw] as a JSON object, or null when it is not one (org.json's `JSONObject(raw)` in a `runCatching`). */
internal fun parseJsonObject(raw: String?): JsonObject? =
    raw?.let { runCatching { OrgJson.parseToJsonElement(it) as? JsonObject }.getOrNull() }

/** [raw] as a JSON array, or null when it is not one. */
internal fun parseJsonArray(raw: String?): JsonArray? =
    raw?.let { runCatching { OrgJson.parseToJsonElement(it) as? JsonArray }.getOrNull() }

/** `optString`: absent or `null` is [default], a number or a boolean is its text, a tree its JSON. */
internal fun JsonElement?.optText(default: String = ""): String = when (this) {
    null, JsonNull -> default
    is JsonPrimitive -> content
    else -> toString()
}

internal fun JsonObject.optString(key: String, default: String = ""): String = this[key].optText(default)

/** `optBoolean`: a boolean, or the text `true`/`false` in any case; anything else is [default]. */
internal fun JsonObject.optBoolean(key: String, default: Boolean = false): Boolean =
    (this[key] as? JsonPrimitive)?.content?.lowercase()?.toBooleanStrictOrNull() ?: default

/** `optLong`: a number (a fraction truncated) or text that parses as one; anything else is [default]. */
internal fun JsonObject.optLong(key: String, default: Long = 0L): Long =
    (this[key] as? JsonPrimitive)?.content?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() } ?: default

internal fun JsonObject.optInt(key: String, default: Int = 0): Int =
    (this[key] as? JsonPrimitive)?.content?.let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() } ?: default

internal fun JsonObject.optDouble(key: String, default: Double = Double.NaN): Double =
    (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: default

internal fun JsonObject.optObject(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.optArray(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonArray.optObject(index: Int): JsonObject? = getOrNull(index) as? JsonObject

internal fun JsonArray.optArray(index: Int): JsonArray? = getOrNull(index) as? JsonArray

internal fun JsonArray.optString(index: Int): String = getOrNull(index).optText()
