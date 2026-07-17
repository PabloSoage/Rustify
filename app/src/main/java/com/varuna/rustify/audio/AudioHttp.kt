package com.varuna.rustify.audio

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** Utilidades HTTP compartidas por los providers E61/E62 (descarga con progreso). */
object AudioHttp {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** Descarga [url] a [dst] reportando progreso 0..100 (o indeterminado si no hay Content-Length). */
    fun download(context: Context, url: String, dst: File, onProgress: (Int) -> Unit = {}, http: OkHttpClient = client) {
        val req = Request.Builder().url(url).header("User-Agent", "Rustify/1.0").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code} for download")
            val body = resp.body ?: throw java.io.IOException("empty body")
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
