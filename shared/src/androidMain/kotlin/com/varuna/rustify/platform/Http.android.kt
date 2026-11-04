// shared/src/androidMain/kotlin/com/varuna/rustify/platform/Http.android.kt
package com.varuna.rustify.platform

/** What Android's `HttpURLConnection` sends: the `http.agent` property, printable ASCII only. */
actual fun systemHttpUserAgent(): String =
    (System.getProperty("http.agent") ?: "").map { if (it in ' '..'~') it else '?' }.joinToString("")
