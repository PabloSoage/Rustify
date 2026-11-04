// sharedUi/src/androidMain/kotlin/com/varuna/rustify/util/DataPlatform.android.kt
package com.varuna.rustify.util

import com.varuna.rustify.metrics.zoneOffsetsJson
import java.util.Locale

actual fun defaultLanguageTag(): String = Locale.getDefault().toLanguageTag()

actual fun countryNameInEnglish(regionCode: String): String =
    Locale.Builder().setRegion(regionCode).build().getDisplayCountry(Locale.ENGLISH)

actual fun deviceZoneOffsetsJson(): String = zoneOffsetsJson()
