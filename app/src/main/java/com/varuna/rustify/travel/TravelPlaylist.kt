package com.varuna.rustify.travel

import com.varuna.rustify.bridge.FullTrack

/**
 * E99 — builds a playlist that fills a target duration (the trip ETA + a small buffer for traffic).
 * Keyless / offline: just sums [FullTrack.durationMs] from a pool. If the pool is shorter than the
 * target it loops (long trips), otherwise it stops once the target is covered.
 */
object TravelPlaylist {
    fun build(targetMs: Long, pool: List<FullTrack>): List<FullTrack> {
        val usable = pool.filter { it.durationMs > 0 && it.id != null }
        if (targetMs <= 0 || usable.isEmpty()) return emptyList()
        val shuffled = usable.shuffled()
        val result = ArrayList<FullTrack>()
        var total = 0L
        var i = 0
        // Cover the target; loop the pool if needed. Cap to avoid a runaway on tiny pools.
        while (total < targetMs && result.size < 500) {
            val t = shuffled[i % shuffled.size]
            result.add(t)
            total += t.durationMs
            i++
        }
        return result
    }

    /** Total duration of a track list, in ms. */
    fun totalMs(tracks: List<FullTrack>): Long = tracks.sumOf { it.durationMs.toLong() }
}
