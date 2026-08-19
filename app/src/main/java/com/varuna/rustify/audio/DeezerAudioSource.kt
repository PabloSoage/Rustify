package com.varuna.rustify.audio

import android.content.Context
import android.util.Base64
import com.varuna.rustify.R
import com.varuna.rustify.bridge.FullTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/**
 * Deezer provider (HiFi/FLAC source using the user's ARL). `resolveStreamUrl` returns a `deezer://…`
 * URI that [DeezerDecryptingDataSource] plays while decrypting on the fly (fast, respects the chain's
 * 15s timeout). `downloadTo` fetches the encrypted CDN and decrypts it to a file.
 */
class DeezerAudioSource(private val appContext: Context) : AudioSourceProvider {

    override val capabilities = AudioSourceCapabilities(
        id = ID,
        displayNameRes = R.string.backend_deezer,
        canStream = true,
        canDownload = true,
        requiresToken = true,
        maxQualityKbps = 1411 // FLAC
    )

    override suspend fun isAvailableFor(track: FullTrack): Boolean {
        // Cheap check: it is enough to have an ARL configured (own / cached / source). No auth here.
        return DeezerSettings.workingArl(appContext).isNotBlank() ||
            DeezerSettings.arl(appContext).isNotBlank() ||
            DeezerSettings.sourceUrl(appContext).isNotBlank()
    }

    override suspend fun resolveStreamUrl(track: FullTrack, hint: String?): Result<StreamInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val arl = DeezerArl.ensureArl(appContext) ?: error("no working Deezer ARL")
            val media = DeezerClient().resolve(track, arl, DeezerSettings.formatChain(appContext))
                ?: error("Deezer resolve failed (track not on Deezer or format unavailable)")
            val b64 = Base64.encodeToString(media.url.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP)
            StreamInfo(
                uri = "deezer://${media.sngId}?u=$b64",
                mimeType = if (media.format.contains("FLAC", true)) "audio/flac" else "audio/mpeg",
                requiresProxy = true,
                // The reason the local server exists. The bytes go into the cache **decrypted**, by
                // the Rust side, so the second play is an ordinary file behind an ordinary URL and
                // needs no custom `DataSource` at all — no ARL, no re-resolve, and seeking works
                // because it is a real HTTP `Range` over a real file.
                cache = CacheHint(upstreamUrl = media.url, deezerSngId = media.sngId)
            )
        }
    }

    override suspend fun downloadTo(track: FullTrack, dst: File, onProgress: (Int) -> Unit): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val arl = DeezerArl.ensureArl(appContext) ?: error("no working Deezer ARL")
            val media = DeezerClient().resolve(track, arl, DeezerSettings.formatChain(appContext))
                ?: error("Deezer resolve failed")
            val key = DeezerCrypto.blowfishKey(media.sngId)
            if (dst.exists()) dst.delete()
            val req = Request.Builder().url(media.url).header("User-Agent", "Rustify/1.0").build()
            AudioHttp.client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) error("Deezer CDN HTTP ${r.code}")
                val body = r.body ?: error("empty body")
                val total = body.contentLength()
                body.byteStream().use { ins ->
                    dst.outputStream().use { out ->
                        var blockIndex = 0L; var done = 0L
                        val chunk = ByteArray(DeezerCrypto.CHUNK)
                        while (true) {
                            var readTotal = 0
                            while (readTotal < DeezerCrypto.CHUNK) {
                                val n = ins.read(chunk, readTotal, DeezerCrypto.CHUNK - readTotal)
                                if (n == -1) break
                                readTotal += n
                            }
                            if (readTotal == 0) break
                            val outBytes = if (blockIndex % 3 == 0L && readTotal == DeezerCrypto.CHUNK)
                                DeezerCrypto.decryptChunk(key, chunk) else chunk.copyOf(readTotal)
                            out.write(outBytes, 0, readTotal)
                            blockIndex++; done += readTotal
                            if (total > 0) onProgress(((done * 100) / total).toInt().coerceIn(0, 100))
                            if (readTotal < DeezerCrypto.CHUNK) break
                        }
                    }
                }
            }
            require(dst.exists() && dst.length() > 0) { "empty Deezer download" }
            dst
        }
    }

    companion object { const val ID = "deezer" }
}
