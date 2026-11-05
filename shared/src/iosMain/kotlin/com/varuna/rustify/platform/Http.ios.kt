// shared/src/iosMain/kotlin/com/varuna/rustify/platform/Http.ios.kt
package com.varuna.rustify.platform

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.engine.darwin.DarwinClientEngineConfig

actual fun httpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(Darwin) {
    engine { noCookieJar() }
    config(this)
}

actual fun httpClientOverSocks(host: String, port: Int, config: HttpClientConfig<*>.() -> Unit): HttpClient =
    HttpClient(Darwin) {
        engine {
            noCookieJar()
            proxy = ProxyBuilder.socks(host, port)
        }
        config(this)
    }

// NSURLSession keeps cookies and sends them by itself, over the `Cookie` header a request sets (Deezer's
// `arl`). The JVMs' OkHttp keeps none unless asked to.
private fun DarwinClientEngineConfig.noCookieJar() = configureSession {
    setHTTPCookieStorage(null)
    setHTTPShouldSetCookies(false)
}

/** No `HttpURLConnection` to match here: the app's name. */
actual fun systemHttpUserAgent(): String = "Rustify"
