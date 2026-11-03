package com.varuna.rustify.player

/**
 * Keeps the player's clock honest after audio offload loses track of it (5.0.1).
 *
 * Reported on the POCO F8 Ultra on 5.0: after a phone call, or after pausing right at the start and
 * leaving the phone locked for a while, the progress bar looped near the start and did not advance
 * properly; one song showed 1:43 of 1:29; and songs moved on half-way through. Seeking back and
 * forth put everything right again.
 *
 * All of it fits one cause. With offload the DSP holds minutes of the song; a call changes the audio
 * route and a long pause lets the system close the offload output, and either tears down the
 * AudioTrack. When media3 rebuilds it, the position it reports can jump back to near zero (the
 * loop), or run ahead of what is heard (1:43 of 1:29). Running ahead is what skips songs: the early
 * end then looks like a real one at the end of the song, so [PlaybackRecovery.isPrematureEnd] does
 * not see the cut. A seek rebuilds the output at a known position, which is why it helped.
 *
 * So the app does that seek itself, in the two places it can know better than the player:
 * - **On resume** after a long pause or a focus loss, while offloaded: back to where the pause
 *   happened, which was read before anything was torn down ([shouldResyncOnResume]).
 * - **While playing**: a position that moves backwards, past the song's end, or ahead of the clock,
 *   without the player having reported a discontinuity (a seek, a new item), is not real
 *   ([check]); the expected position is.
 *
 * Free of Android so it runs on the JVM; [AudioPlayerService] feeds it and does the seek.
 */
object PositionSanity {
    /** A pause at least this long, with offload, rebuilds the output on resume. */
    const val RESYNC_AFTER_PAUSE_MS = 30_000L

    /** How far the position may stray from the clock before it is called a desync. */
    const val TOLERANCE_MS = 3_000L

    fun shouldResyncOnResume(offloaded: Boolean, pausedForMs: Long, focusLostWhilePaused: Boolean): Boolean =
        offloaded && (pausedForMs >= RESYNC_AFTER_PAUSE_MS || focusLostWhilePaused)

    /**
     * Where playback should be, if [positionMs] is not believable; null when it is.
     *
     * [previousMs] was read [elapsedMs] ago with nothing in between that moves the position (the
     * caller resets its baseline on every discontinuity and every pause). [speed] is the playback
     * rate. [durationMs] is the container's; 0 or less when unknown.
     */
    fun check(previousMs: Long, elapsedMs: Long, positionMs: Long, durationMs: Long, speed: Float = 1f): Long? {
        if (elapsedMs < 0) return null
        val expected = previousMs + (elapsedMs * speed).toLong()
        val backwards = positionMs < previousMs - TOLERANCE_MS
        val pastTheEnd = durationMs > 0 && positionMs > durationMs + TOLERANCE_MS
        val ahead = positionMs > expected + TOLERANCE_MS
        if (!backwards && !pastTheEnd && !ahead) return null
        return if (durationMs > 0) expected.coerceIn(0L, durationMs) else expected.coerceAtLeast(0L)
    }
}
