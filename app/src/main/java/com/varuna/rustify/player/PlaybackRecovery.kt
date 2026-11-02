package com.varuna.rustify.player

/**
 * What to do when a track fails or stops early (4.9) — and above all, when *not* to move on.
 *
 * Reported from the car on 4.8.2: press play in a garage with no signal, and by the way out the
 * queue had jumped three or four songs. Every failure got two quick retries (half a second, then a
 * second) and then a skip, and with no network the next song failed the same way, so twenty seconds
 * underground cost four songs. A failure caused by the network says nothing about the song; the
 * answer to it is to wait where we are, and the network callback resumes from the same place.
 *
 * The same trip showed the quieter half: songs ending a little before their end and the next one
 * starting. A stream cut mid-way can look like a clean end of file to the player, which then
 * reports ENDED. When the container declared a longer duration than what was reached, it was a cut,
 * not an ending, and the track is picked up where it stopped.
 *
 * Free of Android so it runs on the JVM; [AudioPlayerService] feeds it and carries out the answer.
 */
object PlaybackRecovery {
    sealed interface Decision {
        /** Try the same track again after [delayMs], counting it as one of the [MAX_RETRIES]. */
        data class Retry(val delayMs: Long) : Decision
        /** Stay on this track, at this position, until the network is back. Nothing is counted. */
        data object WaitForNetwork : Decision
        /** The track itself is the problem: move on. */
        data object Skip : Decision
    }

    const val MAX_RETRIES = 2

    /** A stop this far before the declared end is a cut, not an ending. */
    const val PREMATURE_END_MARGIN_MS = 5_000L

    /** Resumes after a premature end, per track, before believing the end is real. */
    const val MAX_PREMATURE_RESUMES = 2

    /**
     * @param networkUp whether the device has a validated network right now.
     * @param networkError whether the failure itself was a connection failure or a timeout, which
     *   happens with the network nominally up — a garage with one bar.
     * @param retries how many retries this track has already had.
     */
    fun onFailure(networkUp: Boolean, networkError: Boolean, retries: Int, expiredUrl: Boolean = false): Decision = when {
        !networkUp -> Decision.WaitForNetwork
        // Retried for longer than a song's own failure, but never skipped for it.
        networkError -> Decision.Retry(backoff(retries, capMs = 30_000L))
        retries < MAX_RETRIES -> Decision.Retry(if (expiredUrl) 100L else backoff(retries, capMs = 8_000L))
        else -> Decision.Skip
    }

    /**
     * Tracks in a row that may fail before playback stops on the last one instead of skipping on.
     *
     * Seen on WSA in 4.10: googlevideo answered 403 to every URL from that IP, so every song was a
     * "the track is the problem" failure, and two retries and a skip each emptied the queue in
     * seconds. Three songs failing in a row is not three bad songs; it is the source, and the rest of
     * the queue would fail the same way. Any track that reaches READY resets the count.
     */
    const val MAX_FAILED_IN_A_ROW = 3

    /** After the [failedInARow]-th consecutive failed track: stop here rather than skip again? */
    fun shouldStopSkipping(failedInARow: Int): Boolean = failedInARow >= MAX_FAILED_IN_A_ROW

    private fun backoff(retries: Int, capMs: Long): Long =
        (500L shl retries.coerceAtMost(10)).coerceAtMost(capMs)

    /**
     * Did the player stop short of what the file said it holds?
     *
     * Only the container's own duration counts. The catalogue's length is not used: a YouTube
     * recording can legitimately be shorter than the Spotify one, and trusting it would replay the
     * last seconds of such a song forever.
     */
    fun isPrematureEnd(positionMs: Long, containerDurationMs: Long, resumesSoFar: Int): Boolean =
        containerDurationMs > 0 &&
            positionMs > 0 &&
            positionMs < containerDurationMs - PREMATURE_END_MARGIN_MS &&
            resumesSoFar < MAX_PREMATURE_RESUMES
}
