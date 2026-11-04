// sharedUi/src/commonMain/kotlin/com/varuna/rustify/util/DataPlatform.kt
package com.varuna.rustify.util

/*
 * What the stores and the repositories ask the platform that Kotlin's standard library does not
 * answer on every target. Each names the JVM call it made before it moved to common code; on Android
 * and the desktop that call is what runs.
 */

/** The default locale as a BCP 47 tag ("es-ES"): `Locale.getDefault().toLanguageTag()`. */
expect fun defaultLanguageTag(): String

/**
 * The English name of the country [regionCode] names ("ES" → "Spain"):
 * `Locale.Builder().setRegion(code).build().getDisplayCountry(Locale.ENGLISH)`. Throws for a code
 * that is not a region.
 */
expect fun countryNameInEnglish(regionCode: String): String

/**
 * The device's time zone as the offsets it has had, `[[fromMs, offsetMs], …]`, from 1970 to two
 * years ahead — `zoneOffsetsJson()` in the shared module over `java.time`'s zone rules. kotlinx's
 * time zones do not list their transitions, so each platform reads its own.
 */
expect fun deviceZoneOffsetsJson(): String

/**
 * The `User-Agent` a cover shared with its link is fetched with: OkHttp's own (`okhttp/<version>`)
 * on Android and the desktop, as when [ShareUtils] called OkHttp directly.
 */
expect fun coverDownloadUserAgent(): String
