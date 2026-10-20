package com.varuna.rustify.metrics

import kotlinx.serialization.Serializable

/**
 * The listening history's wire types, mirroring `core_engine/src/metrics/{mod,stats}.rs`.
 *
 * Pinned by the fixtures `PlayEvent`, `PlayImportReport` and `PlayStats` in
 * `core_engine/tests/fixtures/wire/`, which `SpotifyModelsContractTest` reads — the same contract
 * every other type crossing the bridge has.
 */
@Serializable
data class PlayEvent(
    val trackId: String = "",
    val isrc: String = "",
    val trackName: String = "",
    val artistIds: List<String> = emptyList(),
    val artistNames: List<String> = emptyList(),
    val albumId: String = "",
    val albumName: String = "",
    val imageUrl: String = "",
    val durationMs: Long = 0,
    val startedAt: Long = 0,
    val listenedMs: Long = 0,
    val counted: Boolean = false,
    val completed: Boolean = false,
    val source: String = "",
    /** Where an imported play came from (`spotify-extended`, `spotify-legacy`); null for the app's own. */
    val origin: String? = null
)

@Serializable
data class PlayImportReport(
    val read: Int = 0,
    val added: Int = 0,
    val duplicates: Int = 0
)

@Serializable
data class TopRow(
    val key: String = "",
    val name: String = "",
    val subtitle: String = "",
    val imageUrl: String = "",
    val plays: Int = 0,
    val ms: Long = 0
)

@Serializable
data class YearPlays(val year: Int = 0, val plays: Int = 0)

@Serializable
data class PlayStats(
    val totalEvents: Int = 0,
    val totalPlays: Int = 0,
    val distinctTracks: Int = 0,
    val distinctArtists: Int = 0,
    val distinctAlbums: Int = 0,
    val totalMs: Long = 0,
    val daysListened: Int = 0,
    val avgPerDayMin: Long = 0,
    val completionRate: Int = 0,
    val playsByHour: List<Int> = List(24) { 0 },
    val msByHour: List<Long> = List(24) { 0L },
    /** Monday first. */
    val playsByWeekday: List<Int> = List(7) { 0 },
    val playsByMonth: List<Int> = List(12) { 0 },
    val playsByYear: List<YearPlays> = emptyList(),
    val cumulativeMin: List<Long> = emptyList(),
    val cumulativePlays: List<Long> = emptyList(),
    val minutesPerDay: List<Long> = emptyList(),
    val topTracks: List<TopRow> = emptyList(),
    val topArtists: List<TopRow> = emptyList(),
    val topAlbums: List<TopRow> = emptyList(),
    val recent: List<PlayEvent> = emptyList()
)
