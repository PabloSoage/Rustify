// shared/src/jvmCommonMain/kotlin/com/varuna/rustify/platform/Http.jvm.kt
package com.varuna.rustify.platform

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp

actual fun httpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(OkHttp) { config(this) }
