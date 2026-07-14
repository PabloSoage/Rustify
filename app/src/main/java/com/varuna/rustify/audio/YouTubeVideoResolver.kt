package com.varuna.rustify.audio

import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Resolves a *video* (progressive audio+video) stream URL for a YouTube id via yt-dlp, so the track
 * screen's "Video" tab can play the matched YouTube video. Separate from [YtDlpAudioSource], which
 * requests bestaudio; here we ask for a single progressive stream (audio+video) capped at 720p so
 * ExoPlayer can play it directly without a DASH merge.
 */
object YouTubeVideoResolver {
    suspend fun resolve(youtubeId: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val req = YoutubeDLRequest("https://www.youtube.com/watch?v=$youtubeId").apply {
                addOption("-g")
                addOption("-f", "best[height<=720][ext=mp4]/best[ext=mp4]/18/best")
            }
            YoutubeDL.getInstance().execute(req).out
                .trim().lines().firstOrNull { it.startsWith("http") }
        }.getOrNull()
    }
}
