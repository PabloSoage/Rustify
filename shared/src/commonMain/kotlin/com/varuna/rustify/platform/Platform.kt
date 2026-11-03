// shared/src/commonMain/kotlin/com/varuna/rustify/platform/Platform.kt
package com.varuna.rustify.platform

/*
 * The handful of things common code needs that the Kotlin standard library does not have on every
 * target. Five, and each one is here because a file moved out of the app used the JVM's version of it
 * (docs 25 §3). Adding one is a decision: if what you want is in kotlin.*, use that instead.
 *
 * Implemented in jvmCommonMain (Android and the desktop share the JVM's) and iosMain.
 */

/** Milliseconds since the Unix epoch — `System.currentTimeMillis()` on the JVM. */
expect fun currentTimeMillis(): Long

/** The device's language as a two-letter code (`en`, `es`, `ja`). */
expect fun defaultLanguage(): String

/** Unicode NFC, so `é` typed and `é` decomposed compare equal. */
expect fun normalizeNfc(s: String): String

/** Whether [t] is a transport failure (the JVM's `IOException`), which a retry may fix. */
expect fun isIoException(t: Throwable): Boolean

/** The English name of a language code (`ja` → "Japanese"), or "" when the platform has none. */
expect fun languageNameInEnglish(code: String): String
