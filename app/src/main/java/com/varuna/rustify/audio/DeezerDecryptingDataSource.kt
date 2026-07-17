package com.varuna.rustify.audio

import android.net.Uri
import android.util.Base64
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream

/**
 * E62 — DataSource de Media3 que **reproduce en streaming** un track de Deezer **descifrando al vuelo**.
 *
 * La URI es `deezer://<sngId>?u=<base64(url_cifrada)>`. Se abre el CDN cifrado (con `Range` para seeks
 * alineado a 2048), y en cada `read` se sirve el resultado en claro: 1 de cada 3 bloques de 2048B se
 * descifra con Blowfish ([DeezerCrypto]). Evita bajar el archivo entero antes de sonar (clave porque la
 * cadena de streaming tiene timeout de 15s).
 */
@UnstableApi
class DeezerDecryptingDataSource : BaseDataSource(true) {

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = DeezerDecryptingDataSource()
    }

    private var uri: Uri? = null
    private var key: ByteArray? = null
    private var response: Response? = null
    private var input: InputStream? = null
    private var blockIndex: Long = 0
    private var skip = 0
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
    private var buf: ByteArray = ByteArray(0)
    private var bufPos = 0

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val u = dataSpec.uri
        uri = u
        val sngId = u.host ?: u.getQueryParameter("s") ?: throw IOException("deezer uri missing sngId")
        val encUrl = u.getQueryParameter("u")?.let { String(Base64.decode(it, Base64.URL_SAFE or Base64.NO_WRAP)) }
            ?: throw IOException("deezer uri missing url")
        key = DeezerCrypto.blowfishKey(sngId)

        val position = dataSpec.position
        val aligned = position - (position % DeezerCrypto.CHUNK)
        blockIndex = aligned / DeezerCrypto.CHUNK
        skip = (position - aligned).toInt()

        val reqB = Request.Builder().url(encUrl).header("User-Agent", "Rustify/1.0")
        if (aligned > 0) reqB.header("Range", "bytes=$aligned-")
        val r = AudioHttp.client.newCall(reqB.build()).execute()
        if (!r.isSuccessful && r.code != 206) { r.close(); throw IOException("Deezer CDN HTTP ${r.code}") }
        response = r
        val body = r.body ?: throw IOException("Deezer CDN empty body")
        input = body.byteStream()
        val cl = body.contentLength()
        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            cl >= 0 -> cl - skip
            else -> C.LENGTH_UNSET.toLong()
        }
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        if (bufPos >= buf.size) {
            if (!fillNextChunk()) return C.RESULT_END_OF_INPUT
        }
        val toCopy = minOf(buf.size - bufPos, length)
        System.arraycopy(buf, bufPos, buffer, offset, toCopy)
        bufPos += toCopy
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= toCopy
        bytesTransferred(toCopy)
        return toCopy
    }

    private fun fillNextChunk(): Boolean {
        val ins = input ?: return false
        val chunk = ByteArray(DeezerCrypto.CHUNK)
        var readTotal = 0
        while (readTotal < DeezerCrypto.CHUNK) {
            val n = ins.read(chunk, readTotal, DeezerCrypto.CHUNK - readTotal)
            if (n == -1) break
            readTotal += n
        }
        if (readTotal == 0) return false
        val actual = if (readTotal == DeezerCrypto.CHUNK) chunk else chunk.copyOf(readTotal)
        buf = if (blockIndex % 3 == 0L && readTotal == DeezerCrypto.CHUNK) DeezerCrypto.decryptChunk(key!!, actual) else actual
        blockIndex++
        bufPos = 0
        if (skip > 0) { bufPos = minOf(skip, buf.size); skip = 0 }
        return bufPos < buf.size
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        runCatching { input?.close() }
        runCatching { response?.close() }
        input = null; response = null; buf = ByteArray(0); bufPos = 0
        transferEnded()
    }
}
