// shared/src/desktopMain/kotlin/com/varuna/rustify/audio/DesktopYtDlpAudioSource.kt
package com.varuna.rustify.audio

import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.NativeEngine
import com.varuna.rustify.bridge.TrackRef
import com.varuna.rustify.platform.Executables
import com.varuna.rustify.platform.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * yt-dlp on the desktop: the official `yt-dlp` program, when it is on `PATH`, in the role the phone's
 * youtubedl-android has — the slow, self-updating fallback behind InnerTube, under the same id
 * ([AudioSourceRegistry.YTDLP_ID]), so the order and switches in Settings are the phone's.
 *
 * Nothing is downloaded or installed for it: without the program the source answers "not available"
 * and the chain passes over it quietly, which is also what a fresh install with yt-dlp switched on
 * by default meets. It is looked for again until found, so installing it needs no restart.
 *
 * AAC first, because the core's engine has no Opus decoder (`engine/decode.rs`): the WebM audio
 * yt-dlp would otherwise pick is Opus.
 */
class DesktopYtDlpAudioSource : AudioSourceProvider {

    override val capabilities = AudioSourceCapabilities(
        id = AudioSourceRegistry.YTDLP_ID,
        canStream = true,
        canDownload = true,
        requiresToken = false,
        maxQualityKbps = 256,
        // A Python program after a network round trip, as on the phone (YtDlpAudioSource).
        resolveTimeoutMs = 40_000,
        // It fetches the video the id names, so a chosen alternative is exactly what it plays.
        honoursYoutubeHint = true
    )

    @Volatile private var found: String? = null
    @Volatile private var saidMissing = false

    /** The program's path, or null; a hit is remembered, a miss is looked for again next time. */
    private fun program(): String? {
        found?.let { return it }
        val path = Executables.onPath("yt-dlp")
        if (path != null) {
            found = path
            Log.i(TAG, "yt-dlp at $path")
        } else if (!saidMissing) {
            saidMissing = true
            Log.i(TAG, "no yt-dlp on PATH; the yt-dlp source is passed over until one is installed")
        }
        return path
    }

    override suspend fun isAvailableFor(track: FullTrack): Boolean =
        withContext(Dispatchers.IO) { program() != null }

    /** The video for [track]: a `ytm:` id's own, else [hint] or the core's match. */
    private suspend fun videoIdOf(track: FullTrack, hint: String?): String {
        val trackId = track.id ?: error("track has no id")
        val ytId = TrackRef.youtubeVideoIdOf(trackId) ?: NativeEngine.resolveYouTubeId(trackId, hint ?: "")
        require(ytId.isNotBlank()) { "resolver returned empty YouTube id" }
        return ytId
    }

    override suspend fun resolveStreamUrl(track: FullTrack, hint: String?): Result<StreamInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                val exe = program() ?: error("yt-dlp is not installed (not on PATH)")
                val ytId = videoIdOf(track, hint)
                val (code, out) = Executables.run(
                    listOf(exe, "-g", "-f", FORMAT, "--no-warnings", watchUrl(ytId)),
                    timeoutSeconds = RESOLVE_TIMEOUT_S,
                ) ?: error("yt-dlp did not finish")
                val url = out.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("http") }
                if (code != 0 || url == null) error("yt-dlp: ${lastLine(out)}")
                StreamInfo(
                    uri = url,
                    // googlevideo URLs expire in ~6h; advisory value for invalidations.
                    expiresAtMs = System.currentTimeMillis() + 6 * 60 * 60 * 1000L,
                    mimeType = "audio/mp4",
                    cache = CacheHint(upstreamUrl = url)
                )
            }
        }

    override suspend fun downloadTo(track: FullTrack, dst: File, onProgress: (Int) -> Unit): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val exe = program() ?: error("yt-dlp is not installed (not on PATH)")
                val ytId = videoIdOf(track, null)
                // yt-dlp picks the extension, so it gets a template and the file it wrote is looked
                // up afterwards, as on the phone.
                val dir = dst.parentFile ?: error("download target has no folder")
                val base = dst.nameWithoutExtension
                fun produced() = dir.listFiles()
                    ?.filter { it.name.startsWith("$base.") && it.extension !in setOf("part", "ytdl") }
                    ?.maxByOrNull { it.lastModified() }
                val (code, out) = Executables.run(
                    listOf(exe, "-f", FORMAT, "--no-warnings", "--no-progress", "-o", "${dir.absolutePath}/$base.%(ext)s", watchUrl(ytId)),
                    timeoutSeconds = DOWNLOAD_TIMEOUT_S,
                ) ?: error("yt-dlp did not finish")
                val file = produced()?.takeIf { code == 0 && it.length() > 0 } ?: error("yt-dlp: ${lastLine(out)}")
                onProgress(100)
                file
            }
        }

    private fun watchUrl(videoId: String) = "https://music.youtube.com/watch?v=$videoId"

    private fun lastLine(out: String) = out.lineSequence().lastOrNull { it.isNotBlank() }?.trim()?.take(200).orEmpty()

    private companion object {
        const val TAG = "DesktopYtDlpAudioSource"

        /** AAC (itag 140, or any M4A audio), never Opus: the engine cannot decode it. */
        const val FORMAT = "140/bestaudio[ext=m4a]/bestaudio[acodec^=mp4a]"

        /** Under the chain's 40 s, so the process is gone before the budget is. */
        const val RESOLVE_TIMEOUT_S = 38L
        const val DOWNLOAD_TIMEOUT_S = 20 * 60L
    }
}
