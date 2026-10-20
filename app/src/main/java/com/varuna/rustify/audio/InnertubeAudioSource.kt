package com.varuna.rustify.audio

import android.content.Context
import android.util.Log
import com.varuna.rustify.R
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.NativeEngine
import com.varuna.rustify.bridge.RustifyJson
import com.varuna.rustify.bridge.TrackRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** A YouTube audio stream as the core resolves it (`youtube::innertube::InnertubeAudio`). */
@Serializable
data class InnertubeAudio(
    val url: String = "",
    val mimeType: String = "",
    val itag: Int = 0,
    val bitrate: Long = 0,
    val contentLength: Long? = null,
    /** How much louder than YouTube's reference this recording is, in dB. For normalisation. */
    val loudnessDb: Double? = null,
    val approxDurationMs: Long? = null,
    val expiresAtMs: Long? = null,
    /** Which impersonated client produced it, for the log. */
    val client: String = ""
)

/**
 * YouTube audio from one request to `/youtubei/v1/player`, in the core — no yt-dlp, no Python (4.1).
 *
 * Measured before writing it: ~0.5 s for the request against 35.8 s of yt-dlp for the same song on
 * the emulator, and the stream downloads at over 100× real time. The clients it impersonates, and
 * why only visionOS, are in `core_engine/src/youtube/innertube.rs`.
 *
 * Streaming only. Downloads stay on yt-dlp, which converts to the MP3 the download pipeline names
 * its files after; this hands back WebM/MP4 as YouTube serves it.
 */
class InnertubeAudioSource(private val appContext: Context) : AudioSourceProvider {

    override val capabilities = AudioSourceCapabilities(
        id = ID,
        displayNameRes = R.string.backend_innertube,
        canStream = true,
        canDownload = false,
        requiresToken = false,
        maxQualityKbps = 160,
        // Finding the video (YouTube Music search, when not mapped yet) plus one or two requests.
        // Short on purpose: if this is slow, something is wrong, and yt-dlp is right behind it.
        resolveTimeoutMs = 12_000,
        // It fetches exactly the video the id names.
        honoursYoutubeHint = true
    )

    override suspend fun resolveStreamUrl(track: FullTrack, hint: String?): Result<StreamInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                val trackId = track.id ?: error("track has no id")
                val videoId = TrackRef.youtubeVideoIdOf(trackId)
                    ?: NativeEngine.resolveYouTubeId(trackId, hint ?: "")
                require(videoId.isNotBlank()) { "no YouTube id" }

                val answer = NativeEngine.resolveInnertubeAudio(videoId, prefsJson())
                failure(answer)?.let { error(it) }
                val audio = RustifyJson.decodeFromString(InnertubeAudio.serializer(), answer)
                Log.d(TAG, "$videoId via ${audio.client}: itag ${audio.itag} ${audio.mimeType} ${audio.bitrate / 1000} kbps")
                StreamInfo(
                    uri = audio.url,
                    expiresAtMs = audio.expiresAtMs ?: (System.currentTimeMillis() + 6 * 60 * 60 * 1000L),
                    mimeType = audio.mimeType,
                    // The URL expires; the audio behind it does not.
                    cache = CacheHint(upstreamUrl = audio.url),
                    loudnessDb = audio.loudnessDb
                )
            }
        }

    override suspend fun downloadTo(track: FullTrack, dst: File, onProgress: (Int) -> Unit): Result<File> =
        Result.failure(UnsupportedOperationException("innertube streams only; downloads use yt-dlp"))

    private fun prefsJson(): String {
        val prefs = appContext.getSharedPreferences("rustify_settings", Context.MODE_PRIVATE)
        val prefer = prefs.getString(PREF_FORMAT, "opus") ?: "opus"
        return """{"prefer":"$prefer","maxKbps":0}"""
    }

    private fun failure(json: String): String? {
        val obj = runCatching { RustifyJson.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return null
        if (obj["success"]?.jsonPrimitive?.booleanOrNull != false) return null
        return obj["error"]?.jsonPrimitive?.contentOrNull ?: "innertube failed"
    }

    companion object {
        const val ID = "innertube"
        private const val TAG = "InnertubeAudioSource"
        /** `"opus"` (WebM) or `"aac"` (MP4); AAC is the safer one for DLNA receivers. */
        const val PREF_FORMAT = "innertube_format"
    }
}
