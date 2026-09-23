package com.varuna.rustify.audio

import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/**
 * Runs this call without blocking a thread, and **cancels it** when the coroutine is cancelled.
 *
 * `Call.execute()` is the wrong tool inside a provider: it blocks, and a blocked thread does not see
 * coroutine cancellation, so the chain's `withTimeout` only takes effect once the socket gives up on
 * its own. Measured on a real device: Invidious reported `25198 ms of 15000`, a dead instance holding
 * the resolution ten seconds past its budget. An unbounded read is point N no matter which side of
 * JNI it is on.
 *
 * The response is the caller's to close.
 */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, value, _ -> value.close() }
        }

        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
}

/** Shared HTTP utilities for the audio providers (download with progress). */
object AudioHttp {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** Downloads [url] to [dst] reporting progress 0..100 (indeterminate when there is no Content-Length). */
    fun download(context: Context, url: String, dst: File, onProgress: (Int) -> Unit = {}, http: OkHttpClient = client) {
        val req = Request.Builder().url(url).header("User-Agent", "Rustify/1.0").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code} for download")
            val body = resp.body
            val total = body.contentLength()
            body.byteStream().use { input ->
                dst.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int; var done = 0L
                    while (input.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read); done += read
                        if (total > 0) onProgress(((done * 100) / total).toInt().coerceIn(0, 100))
                    }
                }
            }
        }
    }
}
