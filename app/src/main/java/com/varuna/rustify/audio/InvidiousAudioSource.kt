package com.varuna.rustify.audio

import android.content.Context
import com.varuna.rustify.R
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.NativeEngine
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * E61 — Provider Invidious. Dado el **videoId de YouTube** (que ya resuelve el core con ISRC/nombre),
 * pide a una instancia sana `/api/v1/videos/{id}` y elige el mejor formato **audio-only**. Sin yt-dlp.
 * Cross-platform de facto (solo HTTP+JSON), aunque de momento vive en Android.
 */
class InvidiousAudioSource(private val appContext: Context) : AudioSourceProvider {

    override val capabilities = AudioSourceCapabilities(
        id = ID,
        displayNameRes = R.string.backend_invidious,
        canStream = true,
        canDownload = true,
        requiresToken = false,
        maxQualityKbps = 160
    )

    override suspend fun isAvailableFor(track: FullTrack): Boolean =
        InvidiousInstances.selected(appContext).isNotEmpty()

    override suspend fun resolveStreamUrl(track: FullTrack, hint: String?): Result<StreamInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val trackId = track.id ?: error("track has no id")
            val videoId = (if (!hint.isNullOrBlank()) hint
                           else NativeEngine.resolveYouTubeIdNative(trackId, "")).trim()
            require(videoId.isNotBlank()) { "no YouTube id" }
            val instances = InvidiousInstances.selected(appContext)
            require(instances.isNotEmpty()) { "no Invidious instances configured" }
            for (inst in instances.take(6)) {
                val url = fetchAudioUrl(inst, videoId, local = false)
                    ?: fetchAudioUrl(inst, videoId, local = true)
                if (url != null) {
                    return@runCatching StreamInfo(
                        uri = url,
                        expiresAtMs = System.currentTimeMillis() + 6 * 60 * 60 * 1000L,
                        mimeType = null
                    )
                }
            }
            error("all Invidious instances failed for $videoId")
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

    /** GET /api/v1/videos/{id} → mejor `adaptiveFormats` de audio (o `formatStreams` como fallback). */
    private fun fetchAudioUrl(inst: InvidiousInstances.Instance, videoId: String, local: Boolean): String? = runCatching {
        val url = "${inst.baseUrl}/api/v1/videos/$videoId?fields=adaptiveFormats,formatStreams&local=$local"
        val req = Request.Builder().url(url).header("User-Agent", "Rustify/1.0").build()
        val body = InvidiousInstances.clientFor(appContext, inst).newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            r.body?.string() ?: return null
        }
        val obj = JSONObject(body)
        val adaptive = obj.optJSONArray("adaptiveFormats")
        var bestUrl: String? = null; var bestBitrate = -1L
        if (adaptive != null) {
            for (i in 0 until adaptive.length()) {
                val f = adaptive.optJSONObject(i) ?: continue
                val type = f.optString("type")
                if (!type.startsWith("audio", true)) continue
                val br = f.optString("bitrate").toLongOrNull() ?: f.optLong("bitrate", 0)
                val u = f.optString("url")
                if (u.isNotBlank() && br > bestBitrate) { bestBitrate = br; bestUrl = u }
            }
        }
        if (bestUrl != null) return bestUrl
        // Fallback: primer formatStream muxed (tiene audio).
        obj.optJSONArray("formatStreams")?.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() }
    }.getOrNull()

    companion object { const val ID = "invidious" }
}
