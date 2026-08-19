package com.varuna.rustify.audio

import android.content.Context
import com.varuna.rustify.R
import com.varuna.rustify.bridge.FullTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Deezer provider (HiFi/FLAC using the user's ARL).
 *
 * Both `resolveStreamUrl` and `downloadTo` go through the local player server, which decrypts the
 * stripes as they come off the CDN. That means an ordinary `http://127.0.0.1` URL for playback —
 * with real `Range` support, so seeking works — and an ordinary download for the rest.
 *
 * **The server is a hard dependency of this backend since 3.3.** The Kotlin decrypting `DataSource`
 * that used to be the alternative was deleted once this replaced it; see
 * `docs/stremio-core/DEEZER-CRYPTO.md` §5.
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
            val mime = if (media.format.contains("FLAC", true)) "audio/flac" else "audio/mpeg"

            // The local server decrypts as the bytes arrive, so the track is an ordinary http://
            // URL from the first play: no custom `DataSource`, and seeking is a real HTTP `Range`.
            //
            // **This is now the only path.** The Kotlin decrypting `DataSource` was deleted in 3.3
            // once this replaced it everywhere, so a Deezer track needs the server. That is a real
            // dependency rather than a preference, and it is stated here rather than discovered: if
            // loopback cannot be bound, this backend cannot play, and the chain falls through to
            // the next one.
            //
            // Storing is separate, and is what the "Stored songs" switch governs. With it off the
            // track still plays — nothing is kept.
            val trackId = track.id
            val keepACopy = StreamRouting.isEnabled(appContext) && !trackId.isNullOrBlank()

            val proxied = com.varuna.rustify.bridge.LocalStreamServer.registerDeezerProxy(
                upstreamUrl = media.url,
                sngId = media.sngId,
                mime = mime,
                cacheKey = if (keepACopy) StreamRouting.keyFor(appContext, trackId!!) else null,
                cacheRoot = if (keepACopy) StreamRouting.cacheRoot(appContext) else null
            ) ?: error("the local player server is not running, so Deezer cannot be decrypted")

            StreamInfo(
                uri = proxied,
                mimeType = mime,
                // No cache hint: the copy is written **as it plays**, by the server. Asking for a
                // background fill as well is what made 3.2 fetch every Deezer track twice.
                cache = null
            )
        }
    }

    /**
     * Downloads through the same server that plays it.
     *
     * Until 3.3 this had its own copy of the stripe loop, so downloading and playing decrypted the
     * same format by two different routes. They are one route now: the proxy already serves plain
     * audio over HTTP, so a download is a download.
     */
    override suspend fun downloadTo(track: FullTrack, dst: File, onProgress: (Int) -> Unit): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val arl = DeezerArl.ensureArl(appContext) ?: error("no working Deezer ARL")
            val media = DeezerClient().resolve(track, arl, DeezerSettings.formatChain(appContext))
                ?: error("Deezer resolve failed")

            // No cache key: a download is going to the user's own storage, and keeping a second
            // copy in the app's cache for a track they have just taken out of it is waste.
            val decrypted = com.varuna.rustify.bridge.LocalStreamServer.registerDeezerProxy(
                upstreamUrl = media.url,
                sngId = media.sngId,
                mime = if (media.format.contains("FLAC", true)) "audio/flac" else "audio/mpeg"
            ) ?: error("the local player server is not running, so Deezer cannot be decrypted")

            if (dst.exists()) dst.delete()
            AudioHttp.download(appContext, decrypted, dst, onProgress)
            require(dst.exists() && dst.length() > 0) { "empty Deezer download" }
            dst
        }
    }

    companion object { const val ID = "deezer" }
}
