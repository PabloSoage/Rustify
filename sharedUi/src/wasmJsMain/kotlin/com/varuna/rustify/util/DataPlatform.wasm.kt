// sharedUi/src/wasmJsMain/kotlin/com/varuna/rustify/util/DataPlatform.wasm.kt
package com.varuna.rustify.util

import com.varuna.rustify.ui.WebLocale
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

actual fun defaultLanguageTag(): String = WebLocale.tag

private fun regionName(code: String): String =
    js("(function() { try { return new Intl.DisplayNames(['en'], { type: 'region' }).of(code) || ''; } catch (e) { return ''; } })()")

actual fun countryNameInEnglish(regionCode: String): String =
    regionName(regionCode).takeIf { it.isNotEmpty() && it != regionCode } ?: throw IllegalArgumentException("not a region: $regionCode")

/** The zone's offset in minutes behind UTC at [epochMs], the sign `Date` gives it. */
private fun offsetMinutesAt(epochMs: Double): Int = js("new Date(epochMs).getTimezoneOffset()")

private fun nowMs(): Double = js("Date.now()")

// The same shape as the JVMs' zoneOffsetsJson. A browser lists no transitions: the offset is looked at day
// by day over the time the history can cover, and each change narrowed to the minute.
actual fun deviceZoneOffsetsJson(): String {
    val day = 86_400_000.0
    val end = nowMs() + 2 * 366 * day
    var at = 0.0
    var offset = offsetMinutesAt(at)
    return buildJsonArray {
        add(buildJsonArray {
            add(JsonPrimitive(Long.MIN_VALUE))
            add(JsonPrimitive(-offset * 60_000L))
        })
        while (at < end) {
            val next = at + day
            if (offsetMinutesAt(next) != offset) {
                var low = at
                var high = next
                while (high - low > 60_000) {
                    val middle = kotlin.math.floor((low + high) / 2 / 60_000) * 60_000
                    if (offsetMinutesAt(middle) == offset) low = middle else high = middle
                }
                offset = offsetMinutesAt(high)
                add(buildJsonArray {
                    add(JsonPrimitive(high.toLong()))
                    add(JsonPrimitive(-offset * 60_000L))
                })
            }
            at = next
        }
    }.toString()
}

actual fun coverDownloadUserAgent(): String = "Rustify"
