package com.varuna.rustify.audio

import com.varuna.rustify.bridge.TrackRef
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
        maxQualityKbps = 256,
        // The generic 15 s ceiling never fitted what happens inside here, and the arithmetic says
        // so: matching the song is a network round trip, and then `extractStreamUrlWithRetry` runs
        // up to three yt-dlp invocations with 0.5 s + 1 s of backoff between them. That is 1.5 s of
        // the budget spent asleep before a single extraction has finished, leaving roughly four
        // seconds each — less than one cold Python start.
        //
        // So the retry loop and the timeout were fighting: instead of one attempt that would have
        // worked, you got three that were all killed. The loop is now deadline-aware as well (see
        // `extractStreamUrlWithRetry`), and this is the budget it is aware of.
        resolveTimeoutMs = 40_000,
        // It fetches the video the id names, so a chosen alternative is exactly what it plays.
        honoursYoutubeHint = true
    )

    /**
     * Completes once yt-dlp is unpacked and usable: `true`, or `false` when init failed.
     *
     * Init used to run synchronously on the caller's thread "so it completes before any execute()".
     * The caller is `MainActivity.onCreate`, and init unpacks a Python runtime: measured at ~3 s of
     * main thread on a cold start in 4.0, which is most of the "app isn't responding" dialog. The
     * ordering it protected is kept by awaiting this instead of by blocking the UI.
     */
    private val ready get() = Companion.ready

    private suspend fun awaitReady() = awaitInitialized()

    override fun initialize() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                YoutubeDL.getInstance().init(appContext)
                dropUnpackedFfmpeg()
                Log.d(TAG, "YoutubeDL initialized successfully.")
                ready.complete(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize YoutubeDL", e)
                ready.complete(false)
                // Even if init fails, unblock downloads waiting on initDeferred.
                DownloadManager.initDeferred.complete(Unit)
                return@launch
            }
            autoUpdate()
        }
    }

    /**
     * ffmpeg and ffprobe are now Rustify's own static audio-only build (`tools/ffmpeg/build.sh`,
     * 4.9), run straight from `nativeLibraryDir` as before. The AAR they replace shipped 184 shared
     * libraries in a zip that its init unpacked into `no_backup` — 78 MB that nothing deletes once
     * the AAR is gone, so it is deleted here, once.
     */
    private fun dropUnpackedFfmpeg() {
        val dir = java.io.File(appContext.noBackupFilesDir, "youtubedl-android/packages/ffmpeg")
        if (dir.exists() && dir.deleteRecursively()) Log.i(TAG, "Removed the old unpacked FFmpeg libraries")
    }

    /** Auto-update in the background; downloads wait for it (see [DownloadManager.initDeferred]). */
    private suspend fun autoUpdate() {
        withContext(Dispatchers.IO) {
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

    /**
     * YouTube video id carried directly by a "ytm:" track id, or null for Spotify/local ids.
     *
     * This is the helper v2.11.8 had to add by hand, in two places, after a "ytm:" id reached the
     * Spotify resolver. It is now one line delegating to the shared codec, which is where the next
     * kind of id will be taught about exactly once.
     */
    private fun youtubeIdOf(trackId: String): String? = TrackRef.youtubeVideoIdOf(trackId)

    override suspend fun resolveStreamUrl(track: FullTrack, hint: String?): Result<StreamInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                val trackId = track.id ?: error("track has no id")
                // Resolve Spotify id -> YouTube id (a non-empty hint is returned as-is,
                // parity with server.rs::resolve_youtube_id_direct youtube_id_opt branch).
                // A "ytm:" id already carries its YouTube video id, so skip the Spotify resolver.
                val ytId = youtubeIdOf(trackId) ?: NativeEngine.resolveYouTubeId(trackId, hint ?: "")
                require(ytId.isNotBlank()) { "resolver returned empty YouTube id" }
                // After the id: finding the video overlaps unpacking yt-dlp on a cold start.
                awaitReady()
                val url = extractStreamUrlWithRetry(ytId)
                    ?: error("yt-dlp returned no url")
                StreamInfo(
                    uri = url,
                    // googlevideo URLs expire in ~6h; advisory value for invalidations.
                    expiresAtMs = System.currentTimeMillis() + 6 * 60 * 60 * 1000L,
                    // The URL expires; the audio behind it does not. Cache the bytes and the next
                    // play needs neither yt-dlp nor the network.
                    cache = CacheHint(upstreamUrl = url)
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
            val ytId = youtubeIdOf(trackId) ?: NativeEngine.resolveYouTubeId(trackId, "")
            require(ytId.isNotBlank()) { "resolver returned empty YouTube id" }

            awaitReady()
            val downloadId = trackId // for YoutubeDL cancellation
            // yt-dlp picks the extension (`.opus`, `.m4a`, `.mp3`), so it gets a template and the
            // file it produced is looked up afterwards. Writing to `dst` as given is what saved an
            // m4a under a `.mp3` name whenever the conversion failed.
            val dir = dst.parentFile ?: error("download target has no folder")
            val base = dst.nameWithoutExtension
            fun produced() = dir.listFiles()
                ?.filter { it.name.startsWith("$base.") && it.extension !in setOf("part", "ytdl", "webp", "jpg", "png") }
                ?.maxByOrNull { it.lastModified() }
            fun clear() = dir.listFiles()?.filter { it.name.startsWith("$base.") }?.forEach { it.delete() }
            clear()

            val mp3 = DownloadFormat.convertToMp3(appContext)
            // Each attempt asks for less: the chosen format with cover and tags, then without the
            // cover (embedding one into Ogg needs a module the bundled Python may lack), then the
            // audio as served.
            val attempts = listOf(true, false).map { cover ->
                YoutubeDLRequest("https://music.youtube.com/watch?v=$ytId").apply {
                    addOption("-f", "bestaudio")
                    addOption("-x")
                    if (mp3) {
                        addOption("--audio-format", "mp3")
                        addOption("--audio-quality", "320K")
                    }
                    if (cover) addOption("--embed-thumbnail")
                    addOption("--add-metadata")
                    addOption("-o", "${dir.absolutePath}/$base.%(ext)s")
                    addOption("--no-check-certificate")
                    addOption("--no-warnings")
                }
            } + YoutubeDLRequest("https://music.youtube.com/watch?v=$ytId").apply {
                addOption("-f", "bestaudio[ext=m4a]/bestaudio")
                addOption("-o", "${dir.absolutePath}/$base.%(ext)s")
                addOption("--no-check-certificate")
                addOption("--no-warnings")
            }

            var lastError: Exception? = null
            for ((i, request) in attempts.withIndex()) {
                try {
                    YoutubeDL.getInstance().execute(request, downloadId) { progress, _, _ ->
                        onProgress(progress.toInt())
                    }
                    produced()?.takeIf { it.length() > 0 }?.let { return@runCatching it }
                } catch (e: Exception) {
                    Log.w(TAG, "yt-dlp download attempt ${i + 1}/${attempts.size} failed", e)
                    lastError = e
                }
                clear()
            }
            throw lastError ?: IllegalStateException("yt-dlp produced no file")
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

    /**
     * Runs yt-dlp, retrying, **within the budget the chain gave this provider**.
     *
     * The deadline is the point. Without it the loop spends the budget on backoff and on starting
     * attempts it has no time to finish, so a track that would have resolved on a slow first try
     * comes back as "timed out" — which reads as the song being unavailable rather than as the
     * stopwatch being wrong.
     *
     * `coroutineContext.isActive` is checked rather than trusted to throw: the work inside is a
     * blocking process call, and a blocking call does not notice cancellation until it returns.
     */
    private suspend fun extractStreamUrlWithRetry(youtubeId: String, maxAttempts: Int = 3): String? {
        var lastError: Exception? = null
        val deadline = System.currentTimeMillis() +
            (capabilities.resolveTimeoutMs ?: AudioSourceChain.DEFAULT_TIMEOUT_MS)
        for (attempt in 0 until maxAttempts) {
            // Starting an attempt there is no time to finish wastes the little budget that is left
            // and turns a clean failure into a cancellation.
            val remaining = deadline - System.currentTimeMillis()
            if (attempt > 0 && remaining < MIN_ATTEMPT_MS) {
                Log.w(TAG, "not retrying $youtubeId: ${remaining} ms left, an attempt needs more")
                break
            }
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
                    // Backoff, but never more than the budget can afford: sleeping through the
                    // deadline is the worst possible use of it.
                    val left = deadline - System.currentTimeMillis() - MIN_ATTEMPT_MS
                    val backoff = (500L shl attempt).coerceAtMost(4000L).coerceAtMost(left)
                    if (backoff > 0) delay(backoff)
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
        const val ID = AudioSourceRegistry.YTDLP_ID
        private const val TAG = "YtDlpAudioSource"

        /**
         * Below this much budget left, another attempt is not worth starting.
         *
         * A yt-dlp extraction is a Python process that fetches the watch page and runs the player's
         * JavaScript. Six seconds is optimistic for a cold one and generous for a warm one, which is
         * what makes it a reasonable floor rather than a guess.
         */
        private const val MIN_ATTEMPT_MS = 6_000L

        /** See the instance `ready`: yt-dlp unpacked and usable (`true`) or failed (`false`). */
        internal val ready = kotlinx.coroutines.CompletableDeferred<Boolean>()

        /** For anything that calls `YoutubeDL` directly: waits for init instead of racing it. */
        suspend fun awaitInitialized() {
            if (!ready.await()) error("yt-dlp failed to initialise")
        }
    }
}