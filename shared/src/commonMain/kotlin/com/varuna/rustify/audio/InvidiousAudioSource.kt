package com.varuna.rustify.audio

import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.NativeEngine
import com.varuna.rustify.platform.Log
import com.varuna.rustify.platform.currentTimeMillis
import com.varuna.rustify.ui.AppFile
import com.varuna.rustify.ui.byteSize
import com.varuna.rustify.ui.deleteFile
import com.varuna.rustify.ui.fileExists
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/**
 * Invidious provider. Given the YouTube videoId (already resolved by the core via ISRC/name), it asks
 * a healthy instance `/api/v1/videos/{id}` and picks the best audio-only format. No yt-dlp.
 * Cross-platform (HTTP+JSON only): the phone and the desktop run this same class.
 */
class InvidiousAudioSource(private val env: AudioSourceEnvironment) : AudioSourceProvider {

    override val capabilities = AudioSourceCapabilities(
        id = ID,
        canStream = true,
        canDownload = true,
        requiresToken = false,
        maxQualityKbps = 160,
        // Same YouTube video, fetched through an Invidious instance instead of yt-dlp.
        honoursYoutubeHint = true
    )

    override suspend fun isAvailableFor(track: FullTrack): Boolean =
        InvidiousInstances.selected(env).isNotEmpty()

    override suspend fun resolveStreamUrl(track: FullTrack, hint: String?): Result<StreamInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val trackId = track.id ?: error("track has no id")
            val videoId = (if (!hint.isNullOrBlank()) hint
                           else NativeEngine.resolveYouTubeId(trackId, "")).trim()
            require(videoId.isNotBlank()) { "no YouTube id" }
            val instances = InvidiousInstances.selected(env)
            require(instances.isNotEmpty()) { "no Invidious instances configured" }
            for (inst in InvidiousInstances.inTryOrder(instances).take(6)) {
                val found = InvidiousInstances.resolveAudio(env, inst, videoId) ?: continue
                Log.d(TAG, "$videoId via ${inst.baseUrl} (${found.via})")
                return@runCatching StreamInfo(
                    uri = found.url,
                    expiresAtMs = currentTimeMillis() + 6 * 60 * 60 * 1000L,
                    mimeType = found.mimeType,
                    // Worth caching: the URL dies in six hours, the audio does not.
                    cache = CacheHint(upstreamUrl = found.url)
                )
            }
            error("no instance returned audio for $videoId (tried ${instances.take(6).size})")
        }
    }

    override suspend fun downloadTo(track: FullTrack, dst: AppFile, onProgress: (Int) -> Unit): Result<AppFile> = withContext(Dispatchers.IO) {
        runCatching {
            val info = resolveStreamUrl(track, null).getOrThrow()
            if (dst.fileExists()) dst.deleteFile()
            AudioHttp.download(info.uri, dst, onProgress)
            require(dst.fileExists() && dst.byteSize() > 0) { "download produced empty file" }
            dst
        }
    }

    companion object {
        const val ID = "invidious"
        private const val TAG = "InvidiousAudioSource"
    }
}
