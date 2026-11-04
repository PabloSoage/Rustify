// sharedUi/src/commonMain/kotlin/com/varuna/rustify/bridge/LocalMusicScanner.kt
package com.varuna.rustify.bridge

import com.varuna.rustify.ui.AppFile

/**
 * Reads the user's music folders into tracks: what [SpotifyRepository.scanLocalMusic] needs from the
 * platform. Android lists them through the Storage Access Framework (a folder is a tree URI the user
 * granted) and reads tags with `MediaMetadataRetriever`; the desktop walks real paths.
 *
 * The repository keeps everything else — the cache, the duration floor, dropping orphaned covers —
 * so those rules are written once.
 */
interface LocalMusicScanner {
    suspend fun scan(request: LocalScanRequest): List<FullTrack>
}

data class LocalScanRequest(
    /** The folders to read: Android tree URIs, or paths on the desktop. */
    val dirs: Set<String>,
    /** The last scan's tracks by id: an unchanged file is taken from here, not read again. */
    val existing: Map<String?, FullTrack>,
    /** Absolute folder paths left out, with everything below them. */
    val excludedFolders: Set<String>,
    /** How a tag's artist field is split into artists ([ArtistSeparators]). */
    val separators: String,
    /** The separators changed since the cache was written: every file is read again. */
    val resplit: Boolean,
    /** Where covers go, one per album (`java.io.File` on Android and the desktop, where AppFile is it). */
    val coversDir: AppFile,
    /** Keep the embedded art as it is (true) or scaled to 512 px. */
    val coversFullRes: Boolean,
)
