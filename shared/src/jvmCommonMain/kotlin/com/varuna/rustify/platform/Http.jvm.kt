// shared/src/jvmCommonMain/kotlin/com/varuna/rustify/platform/Http.jvm.kt
package com.varuna.rustify.platform

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.okhttp.OkHttp

actual fun httpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(OkHttp) { config(this) }

actual fun httpClientOverSocks(host: String, port: Int, config: HttpClientConfig<*>.() -> Unit): HttpClient =
    HttpClient(OkHttp) {
        engine { proxy = ProxyBuilder.socks(host, port) }
        config(this)
    }
