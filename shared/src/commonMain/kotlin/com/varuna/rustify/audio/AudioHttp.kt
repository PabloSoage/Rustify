package com.varuna.rustify.audio

import com.varuna.rustify.platform.httpClient
import com.varuna.rustify.platform.httpClientOverSocks
import com.varuna.rustify.ui.AppFile
import com.varuna.rustify.ui.fullPath
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Shared HTTP utilities for the audio providers: the clients they ask through and a download with
 * progress. Suspending all the way down: a call cancelled by the chain's `withTimeout` stops there,
 * where a blocked thread would sit past it (measured on a real device with `Call.execute()`:
 * Invidious reported `25198 ms of 15000`, a dead instance holding the resolution ten seconds past its
 * budget). An unbounded read is point N no matter which side of JNI it is on.
 */
object AudioHttp {
    val client: HttpClient by lazy { httpClient { timeouts(connectMs = 15_000, readMs = 30_000) } }

    /** Downloads [url] to [dst] reporting progress 0..100 (indeterminate when there is no Content-Length). */
    suspend fun download(url: String, dst: AppFile, onProgress: (Int) -> Unit = {}, http: HttpClient = client) {
        http.prepareGet(url) { header(HttpHeaders.UserAgent, "Rustify/1.0") }.execute { resp ->
            if (!resp.status.isSuccess()) throw IOException("HTTP ${resp.status.value} for download")
            val total = resp.contentLength() ?: -1L
            val input = resp.bodyAsChannel()
            SystemFileSystem.sink(Path(dst.fullPath)).buffered().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val read = input.readAvailable(buf)
                    if (read == -1) break
                    if (read == 0) continue
                    out.write(buf, 0, read)
                    done += read
                    if (total > 0) onProgress(((done * 100) / total).toInt().coerceIn(0, 100))
                }
            }
        }
    }
}

/**
 * The two budgets every client here sets, and redirects as OkHttp follows them by itself: Ktor
 * refuses to follow one from https to http unless told, and Invidious hosts do send those.
 */
internal fun HttpClientConfig<*>.timeouts(connectMs: Long, readMs: Long) {
    install(HttpTimeout) {
        connectTimeoutMillis = connectMs
        socketTimeoutMillis = readMs
    }
    install(HttpRedirect) { allowHttpsDowngrade = true }
}

/** A client through a SOCKS proxy on [host]:[port] (Orbot, for .onion and .i2p). */
internal fun socksClient(host: String, port: Int, connectMs: Long, readMs: Long): HttpClient =
    httpClientOverSocks(host, port) { timeouts(connectMs, readMs) }
