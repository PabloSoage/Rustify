package com.varuna.rustify.audio

import com.varuna.rustify.bridge.FullTrack
import java.io.File

/**
 * Audio backend abstraction.
 *
 * Information about a stream playable by ExoPlayer, produced by an
 * [AudioSourceProvider]. [uri] is the only thing ExoPlayer consumes; the rest is
 * advisory metadata (expiry, mime) so the engine can decide when to re-resolve.
 */
data class StreamInfo(
    val uri: String,                  // http(s):// | file:// | content://
    val expiresAtMs: Long? = null,    // googlevideo ~6h; null = unknown
    val mimeType: String? = null,     // "audio/webm", "audio/mp4"... optional
    val requiresProxy: Boolean = false // e.g. encrypted Deezer -> custom datasource
)

/**
 * Capabilities declared by a provider: filters the chain (only canStream providers
 * take part in resolveStreamUrl; only canDownload in downloadTo) and feeds the
 * settings UI (name, requirements, quality).
 */
data class AudioSourceCapabilities(
    val id: String,                   // stable key: "ytdlp" | "invidious" | "deezer"
    val displayNameRes: Int,          // R.string.* for the UI
    val canStream: Boolean,
    val canDownload: Boolean,
    val requiresToken: Boolean = false,
    val maxQualityKbps: Int? = null
)

/**
 * The single contract consumed by the player ([com.varuna.rustify.player.AudioPlayerService])
 * and by downloads ([com.varuna.rustify.bridge.DownloadManager]). Decouples fetching the
 * URL/bytes from any specific backend, allowing multiple chained providers with fallback.
 *
 * The [hint] of [resolveStreamUrl] is an explicit `youtubeId` (a manually chosen
 * alternative): when provided, the YouTube provider uses it directly without re-resolving
 * from metadata (parity with `NativeEngine.resolveYouTubeIdNative(id, hint)`).
 */
interface AudioSourceProvider {
    val capabilities: AudioSourceCapabilities

    /** Lazy bootstrap (yt-dlp init/update, Invidious health-check, Deezer token validation). Non-blocking. */
    fun initialize() {}

    /** Is it ready to serve this track right now? (valid token, healthy instance...). */
    suspend fun isAvailableFor(track: FullTrack): Boolean = capabilities.canStream

    /** Resolves a playable URL/URI. Cancelable (coroutine) and respects an external timeout. */
    suspend fun resolveStreamUrl(track: FullTrack, hint: String? = null): Result<StreamInfo>

    /** Downloads to a temporary file (the caller copies it to SAF). onProgress 0..100. */
    suspend fun downloadTo(
        track: FullTrack,
        dst: File,
        onProgress: (Int) -> Unit = {}
    ): Result<File>
}

/**
 * Thrown by [AudioSourceChain] when ALL providers in the chain fail.
 * Carries the per-provider error list for diagnostics.
 */
class AudioSourceChainException(val errors: List<Throwable>) : Exception(
    "All audio providers failed: " + errors.joinToString { it.message ?: it::class.simpleName.orEmpty() }
)