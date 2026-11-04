// shared/src/desktopMain/kotlin/com/varuna/rustify/platform/Http.desktop.kt
package com.varuna.rustify.platform

/** What the JDK's `HttpURLConnection` sends: `Java/<version>`, after `http.agent` when one is set. */
actual fun systemHttpUserAgent(): String {
    val version = System.getProperty("java.version")
    val agent = System.getProperty("http.agent")
    return if (agent == null) "Java/$version" else "$agent Java/$version"
}
