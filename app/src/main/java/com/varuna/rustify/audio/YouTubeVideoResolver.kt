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
    suspend fun resolve(youtubeId: String, maxQuality: Boolean = true): Pair<String, String?>? = withContext(Dispatchers.IO) {
        runCatching {
            val req = YoutubeDLRequest("https://www.youtube.com/watch?v=$youtubeId").apply {
                addOption("-g")
                if (maxQuality) {
                    // bestvideo+bestaudio returns two lines if it's a DASH format (like 1080p).
                    // Fallback to progressive (best) if DASH isn't available.
                    //
                    // Capped and codec-pinned on purpose. A bare `bestvideo[ext=mp4]` is unbounded:
                    // for a lot of music videos that is 4K (sometimes 8K) AV1 or VP9, and handing a
                    // phone's vendor decoder a profile it cannot really do is a good way to take the
                    // whole process down — natively, so nothing on the Kotlin side can catch it. That
                    // matches a crash that is reproducible on *particular* songs and absent on the
                    // rest: the deciding factor is that video's codec and resolution, not the app's
                    // state. On a phone 1080p AVC is visually indistinguishable and decodes in
                    // hardware everywhere, so the cap costs nothing.
                    addOption(
                        "-f",
                        "bestvideo[ext=mp4][vcodec^=avc1][height<=1080]+bestaudio[ext=m4a]/" +
                            "bestvideo[ext=mp4][height<=1080]+bestaudio[ext=m4a]/" +
                            "best[ext=mp4][height<=1080]/best[ext=mp4]/best"
                    )
                } else {
                    addOption("-f", "best[height<=720][ext=mp4]/best[ext=mp4]/18/best")
                }
            }
            val lines = YoutubeDL.getInstance().execute(req).out
                .trim().lines().filter { it.startsWith("http") }
            if (lines.isEmpty()) return@runCatching null
            Pair(lines[0], lines.getOrNull(1))
        }.getOrNull()
    }
}
