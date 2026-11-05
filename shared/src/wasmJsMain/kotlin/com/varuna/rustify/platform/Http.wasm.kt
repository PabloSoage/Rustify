// shared/src/wasmJsMain/kotlin/com/varuna/rustify/platform/Http.wasm.kt
package com.varuna.rustify.platform

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.takeFrom
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

private fun pageOrigin(): String = js("window.location.origin + window.location.pathname.replace(/[^/]*$/, '')")

/** What the browser will not let a page set on a request, and would refuse the whole request over if it could. */
private val NOT_ON_A_PAGE = setOf(HttpHeaders.Accept, HttpHeaders.ContentType)

/**
 * The URL of [url] through the server's `/api/fetch`, which a page can play or draw from anywhere: most
 * of what the other platforms ask directly (lyrics, covers, the sources) sends no CORS headers, and a
 * browser refuses to read such an answer.
 */
fun throughServer(url: String): String = pageOrigin() + "api/fetch?url=" + encodeUri(url)

private fun encodeUri(s: String): String = js("encodeURIComponent(s)")

/** A file on the server (`/music/0/a b.flac`, a cover under `/cache`) as what an `<audio>` or a picture can load. */
fun rawUrlOf(path: String): String = pageOrigin() + "api/raw" + path.split('/').joinToString("/") { encodeUri(it) }

/**
 * A Ktor client whose requests leave through the server (`/api/fetch`), with the headers the caller set
 * passed along for it to send, since a page cannot set `User-Agent` or `Cookie` by itself.
 */
actual fun httpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient {
    val client = HttpClient(Js) { config(this) }
    val origin = pageOrigin()
    client.plugin(HttpSend).intercept { request ->
        val target = request.url.buildString()
        if (!target.startsWith(origin)) {
            val headers = buildJsonObject {
                request.headers.entries().forEach { (name, values) -> put(name, JsonPrimitive(values.joinToString(","))) }
            }
            val kept = request.headers.entries().filter { (name, _) -> name in NOT_ON_A_PAGE }
            request.headers.clear()
            kept.forEach { (name, values) -> request.headers.appendAll(name, values) }
            request.headers.append("X-Rustify-Headers", headers.toString())
            request.url.takeFrom(URLBuilder(throughServer(target)))
        }
        execute(request)
    }
    return client
}

/** The server can go through a proxy of its own; a page cannot name one. */
actual fun httpClientOverSocks(host: String, port: Int, config: HttpClientConfig<*>.() -> Unit): HttpClient =
    httpClient(config)

actual fun systemHttpUserAgent(): String = "Rustify"
