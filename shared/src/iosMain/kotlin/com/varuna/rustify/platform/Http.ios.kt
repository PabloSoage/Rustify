// shared/src/iosMain/kotlin/com/varuna/rustify/platform/Http.ios.kt
package com.varuna.rustify.platform

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.darwin.Darwin

actual fun httpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(Darwin) { config(this) }

/** No `HttpURLConnection` to match here: the app's name. */
actual fun systemHttpUserAgent(): String = "Rustify"
