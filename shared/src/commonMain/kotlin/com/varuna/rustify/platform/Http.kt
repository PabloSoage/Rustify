// shared/src/commonMain/kotlin/com/varuna/rustify/platform/Http.kt
package com.varuna.rustify.platform

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig

/**
 * A Ktor client over the platform's engine — OkHttp on Android and the desktop, NSURLSession on
 * iOS — for the few requests common code makes itself (lyrics, the DJ's API, a cover to share).
 * Everything else goes through the core.
 *
 * The engine is named here rather than found on the classpath: Ktor finds it through a
 * `ServiceLoader`, which a shrunk APK does not keep.
 */
expect fun httpClient(config: HttpClientConfig<*>.() -> Unit = {}): HttpClient

/** [httpClient] through the SOCKS proxy on [host]:[port]. */
expect fun httpClientOverSocks(host: String, port: Int, config: HttpClientConfig<*>.() -> Unit = {}): HttpClient

/**
 * The `User-Agent` the platform's own HTTP stack sends when the caller sets none — what a request
 * made with `HttpURLConnection` carried before it moved to Ktor, which would otherwise send its own.
 */
expect fun systemHttpUserAgent(): String

/**
 * [value] as `application/x-www-form-urlencoded`, byte for byte what `URLEncoder.encode(value,
 * "UTF-8")` gives: letters, digits and `.-*_` as they are, a space as `+`, every other UTF-8 byte as
 * `%XX` in capitals.
 */
fun formUrlEncode(value: String): String = buildString {
    for (byte in value.encodeToByteArray()) {
        val b = byte.toInt() and 0xFF
        val c = b.toChar()
        when {
            c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '-' || c == '*' || c == '_' -> append(c)
            c == ' ' -> append('+')
            else -> {
                append('%')
                append(HEX[b shr 4])
                append(HEX[b and 0x0F])
            }
        }
    }
}

private const val HEX = "0123456789ABCDEF"
