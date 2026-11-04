// sharedUi/src/iosMain/kotlin/com/varuna/rustify/util/DataPlatform.ios.kt
package com.varuna.rustify.util

import com.varuna.rustify.ui.IosLocale
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
// A star import, as in IosLocale: Foundation declares much of these in categories.
import platform.Foundation.*

actual fun defaultLanguageTag(): String = IosLocale.current.localeIdentifier.replace('_', '-')

actual fun countryNameInEnglish(regionCode: String): String =
    NSLocale(localeIdentifier = "en").displayNameForKey(NSLocaleCountryCode, value = regionCode)
        ?: throw IllegalArgumentException("not a region: $regionCode")

// The same shape as the JVMs' zoneOffsetsJson, from NSTimeZone's transitions.
actual fun deviceZoneOffsetsJson(): String {
    val zone = NSTimeZone.localTimeZone
    val epoch = NSDate.dateWithTimeIntervalSince1970(0.0)
    val end = NSDate.dateWithTimeIntervalSinceNow(2.0 * 366 * 86_400)
    return buildJsonArray {
        add(buildJsonArray {
            add(JsonPrimitive(Long.MIN_VALUE))
            add(JsonPrimitive(zone.secondsFromGMTForDate(epoch) * 1000L))
        })
        var t = zone.nextDaylightSavingTimeTransitionAfterDate(epoch)
        while (t != null && t.timeIntervalSince1970 < end.timeIntervalSince1970) {
            val change = t
            add(buildJsonArray {
                add(JsonPrimitive((change.timeIntervalSince1970 * 1000).toLong()))
                add(JsonPrimitive(zone.secondsFromGMTForDate(change) * 1000L))
            })
            t = zone.nextDaylightSavingTimeTransitionAfterDate(change)
        }
    }.toString()
}

// No OkHttp here to name: the app's.
actual fun coverDownloadUserAgent(): String = "Rustify"
