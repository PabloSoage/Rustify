// shared/src/commonMain/kotlin/com/varuna/rustify/engine/StreamResolver.kt
package com.varuna.rustify.engine

import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.TrackRef
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Where a track's audio comes from on the platforms that play through the core's engine (desktop,
 * iOS): a local file from its own path, a `ytm:` id as that video, a Spotify track through the
 * core's resolver, as on the phone. One answer for [QueuePlayer] and for the desktop's downloads, so
 * what is downloaded is the recording that plays.
 */
class StreamResolver(private val backend: PlayerBackend) {

    /** What `resolveInnertubeAudio` answers (`youtube::innertube::InnertubeAudio`), the fields used here. */
    @Serializable
    data class Stream(
        val url: String = "",
        val mimeType: String = "",
        val loudnessDb: Double? = null,
        val success: Boolean? = null,
        val error: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /**
     * The stream for [track], or null when there is none. [localCopy] is asked for a track that is
     * not a file of its own (the phone's "match local first"): its answer, a `file://` URI, wins.
     */
    suspend fun resolve(track: FullTrack, localCopy: (suspend (FullTrack) -> String?)? = null): Stream? {
        val id = track.id ?: return null
        val videoId = when (val ref = TrackRef.parse(id) ?: return null) {
            // A file on this computer is played from where it is: no resolver, and not through the
            // core's local server either, which would add a hop to something that has no problem.
            // Only a file:// URI names one; a phone's content:// track has nothing here to open.
            is TrackRef.Local -> return if (ref.uri.startsWith("file:")) Stream(url = ref.uri) else null
            is TrackRef.Ytm -> ref.videoId
            // A Spotify track: the same resolver the phone uses, which needs the metadata first.
            is TrackRef.Spotify, is TrackRef.SpotifyLocal -> {
                localCopy?.invoke(track)?.let { return Stream(url = it) }
                backend.registerTrackMetadata(track)
                VIDEO_ID.find(backend.resolveYouTubeId(id))?.value
            }
        } ?: return null
        // AAC (itag 140): the engine's decoder has no Opus (core_engine/src/engine/decode.rs).
        val raw = backend.resolveAudio(videoId, """{"prefer":"aac","maxKbps":0}""")
        val audio = runCatching { json.decodeFromString(Stream.serializer(), raw) }.getOrNull()
        return audio?.takeIf { it.success != false && it.url.isNotBlank() }
    }

    private companion object {
        /** A YouTube video id inside whatever the resolver answered. */
        val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
    }
}
