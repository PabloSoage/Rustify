// shared/src/iosMain/kotlin/com/varuna/rustify/platform/Platform.ios.kt
package com.varuna.rustify.platform

// A star import on purpose: Foundation declares half of these in categories, which Kotlin/Native
// turns into extensions that need importing, and which half is which is not worth guessing blind.
import platform.Foundation.*

actual fun currentTimeMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

actual fun defaultLanguage(): String =
    (NSLocale.currentLocale.languageCode as String?)?.takeIf { it.isNotBlank() } ?: "en"

@Suppress("CAST_NEVER_SUCCEEDS") // Kotlin/Native bridges String and NSString; the cast is how.
actual fun normalizeNfc(s: String): String = (s as NSString).precomposedStringWithCanonicalMapping

/** No `java.io` here: transport failures from the core arrive as answers, not exceptions. */
actual fun isIoException(t: Throwable): Boolean = false
