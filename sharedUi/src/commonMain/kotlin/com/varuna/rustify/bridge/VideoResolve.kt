// sharedUi/src/commonMain/kotlin/com/varuna/rustify/bridge/VideoResolve.kt
package com.varuna.rustify.bridge

import com.varuna.rustify.platform.Log
import kotlinx.serialization.Serializable

/** What `resolveInnertubeVideo` answers (`youtube::innertube::InnertubeVideo`), the field used here. */
@Serializable
private data class ResolvedVideo(val url: String = "")

/**
 * The video and its sound in one stream, from the core with no yt-dlp, for a player that reads it as it
 * is (AVPlayer, ffmpeg): there is no second URL, as `AppServices.resolveVideo` allows for.
 */
suspend fun resolveVideoFromCore(youtubeId: String): Pair<String, String?>? {
    val answer = NativeEngine.resolveInnertubeVideo(youtubeId)
    val url = runCatching { RustifyJson.decodeFromString(ResolvedVideo.serializer(), answer).url }.getOrDefault("")
    if (url.isEmpty()) Log.w("VideoResolve", "no video for $youtubeId: ${answer.take(200)}")
    return url.takeIf { it.isNotEmpty() }?.let { it to null }
}
