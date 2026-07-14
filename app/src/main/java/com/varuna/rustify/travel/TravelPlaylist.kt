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

    /** Formato humano de duración: "1 h 23 min", "23 min 4 s" o "0 s" si 0. */
    fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return when {
            h > 0 && m > 0 -> "$h h $m min"
            h > 0 -> "$h h"
            m > 0 && s > 0 -> "$m min $s s"
            m > 0 -> "$m min"
            else -> "$s s"
        }
    }

    /**
     * Rellena la diferencia entre [manualTracks] (suministradas a mano) y [targetMs] usando
     * [pool]. Útil para el modo manual: el usuario selecciona algunas canciones obligatorias
     * y la app completa el resto con favoritas aleatorias hasta cubrir el trayecto.
     *
     * Devuelve la lista combinada (manual al frente, relleno detrás, sin duplicar las `id` de
     * las canciones manuales si éstas están en el pool).
     */
    fun fillRemaining(targetMs: Long, manualTracks: List<FullTrack>, pool: List<FullTrack>): List<FullTrack> {
        val base = manualTracks.filter { it.durationMs > 0 && it.id != null }
        val baseTotal = totalMs(base)
        if (baseTotal >= targetMs) return base
        val remaining = targetMs - baseTotal
        val usedIds = base.mapNotNull { it.id }.toHashSet()
        val fillPool = pool
            .filter { it.durationMs > 0 && it.id != null && it.id !in usedIds }
            .shuffled()
        val fillResult = ArrayList<FullTrack>()
        var fillTotal = 0L
        var i = 0
        while (fillTotal < remaining && fillResult.size < 500 && fillPool.isNotEmpty()) {
            val t = fillPool[i % fillPool.size]
            fillResult.add(t)
            fillTotal += t.durationMs
            i++
        }
        return base + fillResult
    }
}
