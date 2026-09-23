package com.varuna.rustify.audio

import android.content.Context
import android.util.Log
import com.varuna.rustify.R
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.NativeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Invidious provider. Given the YouTube videoId (already resolved by the core via ISRC/name), it asks
 * a healthy instance `/api/v1/videos/{id}` and picks the best audio-only format. No yt-dlp.
 * Effectively cross-platform (HTTP+JSON only), though it currently lives on Android.
 */
class InvidiousAudioSource(private val appContext: Context) : AudioSourceProvider {

    override val capabilities = AudioSourceCapabilities(
        id = ID,
        displayNameRes = R.string.backend_invidious,
        canStream = true,
        canDownload = true,
        requiresToken = false,
        maxQualityKbps = 160,
        // Same YouTube video, fetched through an Invidious instance instead of yt-dlp.
        honoursYoutubeHint = true
    )

    override suspend fun isAvailableFor(track: FullTrack): Boolean =
        InvidiousInstances.selected(appContext).isNotEmpty()

    override suspend fun resolveStreamUrl(track: FullTrack, hint: String?): Result<StreamInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val trackId = track.id ?: error("track has no id")
            val videoId = (if (!hint.isNullOrBlank()) hint
                           else NativeEngine.resolveYouTubeId(trackId, "")).trim()
            require(videoId.isNotBlank()) { "no YouTube id" }
            val instances = InvidiousInstances.selected(appContext)
            require(instances.isNotEmpty()) { "no Invidious instances configured" }
            for (inst in InvidiousInstances.inTryOrder(instances).take(6)) {
                val found = InvidiousInstances.resolveAudio(appContext, inst, videoId) ?: continue
                Log.d(TAG, "$videoId via ${inst.baseUrl} (${found.via})")
                return@runCatching StreamInfo(
                    uri = found.url,
                    expiresAtMs = System.currentTimeMillis() + 6 * 60 * 60 * 1000L,
                    mimeType = found.mimeType,
                    // Worth caching: the URL dies in six hours, the audio does not.
                    cache = CacheHint(upstreamUrl = found.url)
                )
            }
            error("no instance returned audio for $videoId (tried ${instances.take(6).size})")
        }
    }

    override suspend fun downloadTo(track: FullTrack, dst: File, onProgress: (Int) -> Unit): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val info = resolveStreamUrl(track, null).getOrThrow()
            if (dst.exists()) dst.delete()
            AudioHttp.download(appContext, info.uri, dst, onProgress)
            require(dst.exists() && dst.length() > 0) { "download produced empty file" }
            dst
        }
    }

    companion object {
        const val ID = "invidious"
        private const val TAG = "InvidiousAudioSource"
    }
}
