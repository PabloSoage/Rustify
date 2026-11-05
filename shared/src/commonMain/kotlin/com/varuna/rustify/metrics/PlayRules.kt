// shared/src/commonMain/kotlin/com/varuna/rustify/metrics/PlayRules.kt
package com.varuna.rustify.metrics

import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.TrackRef

/*
 * What a play *is*, once, for every platform: Android's `ListeningTracker` and the desktop's and iOS's
 * `QueuePlayer` both count with these, and the core stores what they produce (`metrics::record`).
 * Moved out of `ListeningTracker.kt` in 5.x unchanged.
 */

/**
 * How much of a tick counts as listening: the position advanced [posDelta], the clock [wallDelta].
 *
 * Ticks are half a second apart while the app is awake, and the old rule — count only gaps up to
 * 10 s — was written for that. With audio offload (4.7.1) the DSP plays while the CPU sleeps, and
 * the next tick can come minutes later; dropping those gaps lost whole songs from the stats. A long
 * gap counts when the position moved about as much as the clock did — playback went on unattended.
 * A seek (position far ahead of the clock) and a pause (position still) still count nothing extra.
 */
fun creditedListenMs(posDelta: Long, wallDelta: Long): Long = when {
    posDelta <= 0L || wallDelta <= 0L -> 0L
    wallDelta <= 10_000L -> minOf(posDelta, wallDelta)
    kotlin.math.abs(posDelta - wallDelta) <= maxOf(2_000L, wallDelta / 20) -> minOf(posDelta, wallDelta)
    else -> 0L
}

/**
 * The event for one listen of [track], or null for a track with no id (nothing to key it on).
 *
 * Counted (it shows in the stats) after 30 s or half the song, unless playback failed; completed when
 * it played to its end. The source is the kind of id: a local file, a YouTube Music video, Spotify.
 */
fun playEventOf(track: FullTrack, startedAt: Long, listenedMs: Long, completed: Boolean, errored: Boolean): PlayEvent? {
    val tid = track.id ?: return null
    val counted = !errored &&
        (listenedMs >= 30_000 || (track.durationMs > 0 && listenedMs >= track.durationMs / 2))
    val source = when {
        TrackRef.isLocal(tid) -> "local"
        TrackRef.isYtm(tid) -> "ytm"
        else -> "spotify"
    }
    return PlayEvent(
        trackId = tid,
        isrc = track.isrc,
        trackName = track.name,
        artistIds = track.artists.map { it.id },
        artistNames = track.artists.map { it.name },
        albumId = track.album?.id.orEmpty(),
        albumName = track.album?.name.orEmpty(),
        // Album artwork, used to show covers on the metrics screen.
        imageUrl = track.album?.images?.firstOrNull()?.url.orEmpty(),
        durationMs = track.durationMs.toLong(),
        startedAt = startedAt,
        listenedMs = listenedMs,
        counted = counted,
        completed = completed,
        source = source,
    )
}
