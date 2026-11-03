// sharedUi/src/commonMain/kotlin/com/varuna/rustify/player/PlayerModels.kt
package com.varuna.rustify.player

import com.varuna.rustify.bridge.FullTrack

// What the player shows the screens, moved out of AudioPlayerService.kt unchanged so the shared
// interface can read it on both platforms (docs serie-4.x/26).

data class PlaybackContext(
    val id: String,
    val label: String,
    val subtitle: String = "",
    val imageUrl: String = ""
) {
    fun toJson(): org.json.JSONObject = org.json.JSONObject().apply {
        put("id", id)
        put("label", label)
        put("subtitle", subtitle)
        put("imageUrl", imageUrl)
    }

    companion object {
        /**
         * Reads a context back out of the saved playback state.
         *
         * It is saved at all because it was not, and the queue was: after the process was killed the
         * queue came back and the context did not, so playback carried on into a `null` context and
         * both the things keyed on one went quiet — no ✓ on the tracks being finished, and no update
         * to "pick up where you left off". Nothing looked broken, which is why it took a while to
         * notice: the music played fine.
         *
         * Null for a state file written before this field existed, which is the behaviour that was
         * there anyway.
         */
        fun fromJson(json: org.json.JSONObject?): PlaybackContext? {
            val id = json?.optString("id").orEmpty()
            if (id.isBlank()) return null
            return PlaybackContext(
                id = id,
                label = json!!.optString("label"),
                subtitle = json.optString("subtitle"),
                imageUrl = json.optString("imageUrl")
            )
        }
    }
}

data class AudioPlayerState(
    val currentTrack: FullTrack? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,  // true while resolving OR while ExoPlayer is buffering
    val isError: Boolean = false,       // true when stream could not be resolved/played
    val errorMessage: String = "",
    val queue: List<FullTrack> = emptyList(),
    val originalQueue: List<FullTrack> = emptyList(),
    val isShuffle: Boolean = false,
    val isRepeat: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferPercent: Int = 0,
    val isVideoMode: Boolean = false,
    val videoSizeRatio: Float? = null,
    // true when the current track resolved to a local file (local match or a "local:" track).
    // The UI uses it to turn the alternatives button green and indicate the current match.
    val isLocalSource: Boolean = false
)
