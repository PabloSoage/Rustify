package com.varuna.rustify.player

/**
 * A-B repeat (4.10): loop one passage of the current track — a solo to learn, a line to catch.
 *
 * One button cycles it: the first press marks A where the song is, the second marks B and starts
 * the loop, the third clears it. A loop belongs to the track it was set on; changing track drops it.
 * Pure, so it is tested on the JVM; the service watches the position and seeks.
 */
data class AbRepeat(val trackId: String? = null, val a: Long? = null, val b: Long? = null) {
    val isLooping: Boolean get() = a != null && b != null

    /** The next state after a press at [positionMs] on [currentTrackId]. */
    fun press(currentTrackId: String?, positionMs: Long): AbRepeat = when {
        currentTrackId == null -> NONE
        trackId != currentTrackId || a == null -> AbRepeat(currentTrackId, positionMs.coerceAtLeast(0L), null)
        b == null -> {
            // A B at or before A, or too close to it, is a slip of the finger: start again from here.
            if (positionMs - a < MIN_LOOP_MS) AbRepeat(currentTrackId, positionMs.coerceAtLeast(0L), null)
            else copy(b = positionMs)
        }
        else -> NONE
    }

    /** Where to seek to, or null to carry on. */
    fun jumpFrom(currentTrackId: String?, positionMs: Long): Long? =
        if (isLooping && currentTrackId == trackId && positionMs >= b!!) a else null

    /** What is left after the track changed to [currentTrackId]. */
    fun onTrack(currentTrackId: String?): AbRepeat = if (currentTrackId == trackId) this else NONE

    companion object {
        val NONE = AbRepeat()
        const val MIN_LOOP_MS = 1_000L
    }
}
