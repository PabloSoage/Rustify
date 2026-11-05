package com.varuna.rustify.metrics

/**
 * A year in listening, told as a few cards (4.7) — Spotify's "Wrapped", from the history this
 * phone keeps, for any year and on any day rather than once in December.
 *
 * Everything here is arithmetic over [PlayStats], which the core already computes; this only picks
 * what to say. Pure, so it is tested on the JVM (`RecapTest`).
 */
data class Recap(
    val year: Int,
    val minutes: Long,
    val plays: Int,
    val distinctArtists: Int,
    val topArtist: TopRow?,
    val topTrack: TopRow?,
    val topAlbum: TopRow?,
    val topArtists: List<TopRow>,
    val topTracks: List<TopRow>,
    /** 0 = January. Null with no plays. */
    val peakMonth: Int?,
    /** 0 = Monday. Null with no plays. */
    val peakWeekday: Int?,
    val listener: ListenerType,
    val longestStreakDays: Int,
)

/** When you listen most, as one word. */
enum class ListenerType { NIGHT_OWL, EARLY_BIRD, DAYTIME, EVENING, NONE }

object RecapBuilder {

    /** Null when the year has nothing to tell: no counted plays. */
    fun build(year: Int, s: PlayStats): Recap? {
        if (s.totalPlays == 0) return null
        return Recap(
            year = year,
            minutes = s.totalMs / 60_000L,
            plays = s.totalPlays,
            distinctArtists = s.distinctArtists,
            topArtist = s.topArtists.firstOrNull(),
            topTrack = s.topTracks.firstOrNull(),
            topAlbum = s.topAlbums.firstOrNull(),
            topArtists = s.topArtists.take(5),
            topTracks = s.topTracks.take(5),
            peakMonth = s.playsByMonth.peakIndex(),
            peakWeekday = s.playsByWeekday.peakIndex(),
            listener = listenerType(s.playsByHour),
            longestStreakDays = s.longestStreakDays,
        )
    }

    /**
     * The part of the day with the most plays: night 22–5, early 5–10, daytime 10–18, evening
     * 18–22. Ties go to the earlier-listed type, which is arbitrary but stable.
     */
    fun listenerType(playsByHour: List<Int>): ListenerType {
        if (playsByHour.size != 24 || playsByHour.sum() == 0) return ListenerType.NONE
        fun sum(hours: Iterable<Int>) = hours.sumOf { playsByHour[it] }
        val buckets = linkedMapOf(
            ListenerType.NIGHT_OWL to sum((22..23) + (0..4)),
            ListenerType.EARLY_BIRD to sum(5..9),
            ListenerType.DAYTIME to sum(10..17),
            ListenerType.EVENING to sum(18..21),
        )
        return buckets.maxByOrNull { it.value }!!.key
    }

    private fun List<Int>.peakIndex(): Int? =
        if (isEmpty() || max() == 0) null else indexOf(max())
}
