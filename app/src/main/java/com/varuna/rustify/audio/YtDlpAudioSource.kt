package com.varuna.rustify.audio

import android.content.Context
import android.util.Log
import com.varuna.rustify.R
import com.varuna.rustify.bridge.DownloadManager
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.NativeEngine
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Default provider: wraps yt-dlp for streaming and downloading YouTube audio.
 *
 * The bootstrap (init + auto-update per channel) lives here so the app does not need to know
 * about the concrete provider: [AudioSourceRegistry] calls [initialize].
 */
class YtDlpAudioSource(private val appContext: Context) : AudioSourceProvider {

    override val capabilities = AudioSourceCapabilities(
        id = ID,
        displayNameRes = R.string.backend_ytdlp,
        canStream = true,
        canDownload = true,
        requiresToken = false,
        maxQualityKbps = 256
    )

    override fun initialize() {
        // Synchronous init on the caller's thread: it must complete before any execute() to avoid a
        // race that would occur if it were moved to a coroutine.
        try {
            YoutubeDL.getInstance().init(appContext)
            com.yausername.ffmpeg.FFmpeg.getInstance().init(appContext)
            Log.d(TAG, "YoutubeDL and FFmpeg initialized successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize YoutubeDL", e)
            // Even if init fails, unblock downloads waiting on initDeferred.
            DownloadManager.initDeferred.complete(Unit)
            return
        }
        // Auto-update in the background (does not block startup).
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = appContext.getSharedPreferences("rustify_settings", Context.MODE_PRIVATE)
                val channelStr = prefs.getString("ytdlp_channel", "NIGHTLY")
                val channel = if (channelStr == "STABLE") YoutubeDL.UpdateChannel.STABLE
                              else YoutubeDL.UpdateChannel.NIGHTLY
                YoutubeDL.getInstance().updateYoutubeDL(appContext, channel)
                Log.d(TAG, "YoutubeDL updated successfully.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update YoutubeDL", e)
            }
            // Notify pending downloads that yt-dlp is ready (or at least attempted).
            DownloadManager.initDeferred.complete(Unit)
        }
    }

    /** YouTube video id carried directly by a "ytm:" track id, or null for Spotify/local ids. */
    private fun youtubeIdOf(trackId: String): String? =
        trackId.removePrefix("ytm:").takeIf { trackId.startsWith("ytm:") && it.isNotBlank() }

    override suspend fun resolveStreamUrl(track: FullTrack, hint: String?): Result<StreamInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                val trackId = track.id ?: error("track has no id")
                // Resolve Spotify id -> YouTube id (a non-empty hint is returned as-is,
                // parity with server.rs::resolve_youtube_id_direct youtube_id_opt branch).
                // A "ytm:" id already carries its YouTube video id, so skip the Spotify resolver.
                val ytId = youtubeIdOf(trackId) ?: NativeEngine.resolveYouTubeIdNative(trackId, hint ?: "")
                require(ytId.isNotBlank()) { "resolver returned empty YouTube id" }
                val url = extractStreamUrlWithRetry(ytId)
                    ?: error("yt-dlp returned no url")
                StreamInfo(
                    uri = url,
                    // googlevideo URLs expire in ~6h; advisory value for invalidations.
                    expiresAtMs = System.currentTimeMillis() + 6 * 60 * 60 * 1000L
                )
            }
        }

    override suspend fun downloadTo(
        track: FullTrack,
        dst: File,
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val trackId = track.id ?: error("track has no id")
            // Make the track resolvable in the Rust resolver.
            registerMetadata(track)
            // A "ytm:" id already carries its YouTube video id, so skip the Spotify resolver.
            val ytId = youtubeIdOf(trackId) ?: NativeEngine.resolveYouTubeIdNative(trackId, "")
            require(ytId.isNotBlank()) { "resolver returned empty YouTube id" }

            if (dst.exists()) dst.delete()
            val downloadId = trackId // for YoutubeDL cancellation

            try {
                val request = YoutubeDLRequest("https://music.youtube.com/watch?v=$ytId").apply {
                    addOption("-f", "bestaudio")
                    addOption("-x")
                    addOption("--audio-format", "mp3")
                    addOption("--audio-quality", "320K")
                    addOption("--embed-thumbnail")
                    addOption("--add-metadata")
                    addOption("-o", dst.absolutePath)
                    addOption("--no-check-certificate")
                    addOption("--no-warnings")
                }
                YoutubeDL.getInstance().execute(request, downloadId) { progress, _, _ ->
                    onProgress(progress.toInt())
                }
            } catch (e: Exception) {
                Log.e(TAG, "FFmpeg mp3 extraction failed, falling back to m4a", e)
                if (dst.exists()) dst.delete()
                val fallback = YoutubeDLRequest("https://music.youtube.com/watch?v=$ytId").apply {
                    addOption("-f", "bestaudio[ext=m4a]/bestaudio")
                    addOption("-o", dst.absolutePath)
                    addOption("--no-check-certificate")
                    addOption("--no-warnings")
                }
                YoutubeDL.getInstance().execute(fallback, downloadId) { progress, _, _ ->
                    onProgress(progress.toInt())
                }
            }
            require(dst.exists()) { "Temporary file not found after download" }
            dst
        }
    }

    // ───────────────────────────────────────────────────────────────────
    // Internals
    // ───────────────────────────────────────────────────────────────────

    private fun registerMetadata(track: FullTrack) {
        val trackId = track.id ?: return
        val artistsJson = "[" + track.artists.joinToString(",") {
            "\"" + it.name.replace("\"", "\\\"") + "\""
        } + "]"
        NativeEngine.registerTrackMetadataNative(
            trackId, track.name, artistsJson, track.durationMs, track.isrc
        )
    }

    private suspend fun extractStreamUrlWithRetry(youtubeId: String, maxAttempts: Int = 3): String? {
        var lastError: Exception? = null
        for (attempt in 0 until maxAttempts) {
            try {
                val request = YoutubeDLRequest("https://music.youtube.com/watch?v=$youtubeId").apply {
                    addOption("-g")
                    addOption("-f", "bestaudio[ext=webm]/bestaudio[ext=m4a]/bestaudio")
                    addOption("--no-check-certificate")
                    addOption("--no-warnings")
                }
                val response = YoutubeDL.getInstance().execute(request)
                val url = response.out.trim().lines().firstOrNull()?.trim()
                if (!url.isNullOrBlank()) return url
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "yt-dlp attempt ${attempt + 1} failed: ${e.message}")
            }
            if (attempt < maxAttempts - 1) {
                try {
                    delay((500L shl attempt).coerceAtMost(4000L))
                } catch (_: kotlinx.coroutines.CancellationException) {
                    throw kotlinx.coroutines.CancellationException()
                }
            }
        }
        if (lastError != null) {
            Log.e(TAG, "YoutubeDL extraction failed after $maxAttempts attempts", lastError)
        }
        return null
    }

    companion object {
        const val ID = "ytdlp"
        private const val TAG = "YtDlpAudioSource"
    }
}