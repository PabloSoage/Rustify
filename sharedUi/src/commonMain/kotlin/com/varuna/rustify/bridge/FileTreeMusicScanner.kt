// sharedUi/src/commonMain/kotlin/com/varuna/rustify/bridge/FileTreeMusicScanner.kt
@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package com.varuna.rustify.bridge

import com.varuna.rustify.platform.Log
import com.varuna.rustify.ui.AppFile
import com.varuna.rustify.ui.appFileAt
import com.varuna.rustify.ui.appFileOfUri
import com.varuna.rustify.ui.child
import com.varuna.rustify.ui.fileExists
import com.varuna.rustify.ui.fileName
import com.varuna.rustify.ui.fileUri
import com.varuna.rustify.ui.fullPath
import com.varuna.rustify.ui.isFolder
import com.varuna.rustify.ui.isPlainFile
import com.varuna.rustify.ui.listChildren
import com.varuna.rustify.ui.modifiedMillis
import com.varuna.rustify.ui.resolvedPath
import com.varuna.rustify.ui.writeAllBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64

/**
 * The local scan where folders are real paths (the desktop, iOS): the folders the user added are
 * walked here, and each file's tags come from the core (`readTags`, over lofty) instead of Android's
 * `MediaMetadataRetriever`.
 *
 * What it answers is what `AndroidLocalMusicScanner` answers, field for field, so the Library's
 * local tab, the folder view and the local matcher work on it unchanged: the id is `local:` and the
 * file's URI, the title falls back to the file name, the artists are split by the user's separators,
 * `addedAt` is the modification time that decides whether a file is read again, and the cover is one
 * file per album in [LocalScanRequest.coversDir], its URI in the album's image and in `externalUri`.
 * Two things the phone's retriever does not give, and the core does, go where the model has room for
 * them: the ISRC (the matcher's first rule) and the year.
 *
 * [folderOf] is a stored folder as a file, null when it is no folder here. [scaleCover] answers a
 * picture at most [COVER_MAX_PX] on its longer side, as a JPEG at quality 80, as the phone writes it.
 */
class FileTreeMusicScanner(
    private val scaleCover: (ByteArray) -> ByteArray?,
    private val folderOf: (String) -> AppFile? = { dir -> appFileAt(dir).takeIf { it.isFolder() } },
) : LocalMusicScanner {
    override suspend fun scan(request: LocalScanRequest): List<FullTrack> = withContext(Dispatchers.IO) {
        val files = LinkedHashMap<String, AppFile>()
        val visited = HashSet<String>()
        for (dir in request.dirs) {
            // A folder from another device (a phone's tree URI in restored settings) is no path here.
            folderOf(dir)?.let { walk(it, request.excludedFolders, visited, files) }
        }
        val covers = Covers()
        val reading = Semaphore(PARALLEL_READS)
        val tracks = coroutineScope {
            files.map { (id, file) ->
                async { reading.withPermit { trackOf(id, file, request, covers) } }
            }.awaitAll()
        }.filterNotNull()
        Log.i(TAG, "${tracks.size} local tracks in ${request.dirs.size} folders")
        tracks
    }

    /** One lock per album: tracks of one album are read side by side, and one of them writes the cover. */
    private class Covers {
        private val guard = Mutex()
        private val locks = HashMap<String, Mutex>()
        suspend fun lockOf(key: String): Mutex = guard.withLock { locks.getOrPut(key) { Mutex() } }
    }

    /** Collects the audio files under [dir] by track id, skipping excluded folders and links back up. */
    private fun walk(dir: AppFile, excluded: Set<String>, visited: MutableSet<String>, out: MutableMap<String, AppFile>) {
        val canonical = dir.resolvedPath() ?: return
        if (!visited.add(canonical)) return
        for (entry in dir.listChildren()) {
            val name = entry.fileName
            if (name.startsWith(".")) continue
            if (entry.isFolder()) {
                val path = M3u.pathOf(trackIdOf(entry))?.trimEnd('/')
                if (path == null || excluded.none { LocalFolders.isUnder(path, it) }) walk(entry, excluded, visited, out)
            } else if (entry.isPlainFile() && name.substringAfterLast('.', "").lowercase() in EXTENSIONS) {
                out.getOrPut(trackIdOf(entry)) { entry }
            }
        }
    }

    private fun trackIdOf(file: AppFile): String = TrackRef.Local(file.fileUri()).raw

    private suspend fun trackOf(trackId: String, file: AppFile, request: LocalScanRequest, covers: Covers): FullTrack? {
        val lastModified = file.modifiedMillis().toString()

        // An unchanged file whose cover is still there is taken as the last scan read it.
        val existing = request.existing[trackId]
        if (existing != null && existing.addedAt == lastModified && !request.resplit) {
            val cover = existing.externalUri.ifBlank { null }?.let { appFileOfUri(it) }
            if (existing.externalUri.isBlank() || cover?.fileExists() == true) return existing
        }

        // A file lofty cannot read (WebM, or one that is not audio after all) keeps its file name,
        // as the phone's does when the retriever finds no title.
        val tags = readTags(file) ?: LocalTags()
        val rawArtist = tags.artists.joinToString("; ").ifBlank { UNKNOWN_ARTIST }
        val artists = tags.artists
            .flatMap { ArtistSeparators.split(it, request.separators) }
            .distinct()
            .ifEmpty { listOf(rawArtist) }
        val album = tags.album ?: UNKNOWN_ALBUM

        val coverUri = coverFor(file, tags, album, tags.albumArtist ?: rawArtist, request, covers)
        val year = tags.year?.takeIf { it > 0 }?.toString()
        return FullTrack(
            id = trackId,
            name = tags.title ?: file.fileName,
            artists = artists.map { SimpleArtist("local_artist:$it", it, "", emptyList()) },
            album = SimpleAlbum(
                "local_album:$album", album, "", year, year?.let { "year" },
                if (coverUri.isNotBlank()) listOf(SpotifyImage(coverUri, null, null)) else emptyList(),
                emptyList(), null
            ),
            durationMs = tags.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            explicit = false,
            isrc = tags.isrc.orEmpty(),
            addedAt = lastModified,
            externalUri = coverUri,
        )
    }

    private suspend fun readTags(file: AppFile): LocalTags? = runCatching {
        val json = NativeEngine.readTags(file.fullPath)
        if (failed(json)) null else RustifyJson.decodeFromString(LocalTags.serializer(), json)
    }.getOrNull()

    /** Every bridge answers a failure as `{"success":false,…}`, which would otherwise decode as empty tags. */
    private fun failed(json: String): Boolean =
        (RustifyJson.parseToJsonElement(json) as? JsonObject)?.get("success")?.jsonPrimitive?.booleanOrNull == false

    /**
     * The album's cover, one file per album (keyed like the phone's, by album and artist): the
     * existing one, or the file's embedded picture written as it is or scaled. "" for none.
     */
    private suspend fun coverFor(
        file: AppFile,
        tags: LocalTags,
        album: String,
        artist: String,
        request: LocalScanRequest,
        covers: Covers,
    ): String {
        val key = "$album|$artist".lowercase().hashCode().toString()
        val coverFile = request.coversDir.child("$key.jpg")
        return covers.lockOf(key).withLock {
            if (!coverFile.fileExists() && tags.hasCover) {
                val bytes = readCover(file)
                if (bytes != null && bytes.isNotEmpty()) {
                    val out = if (request.coversFullRes) bytes else scaleCover(bytes) ?: bytes
                    runCatching { coverFile.writeAllBytes(out) }.onFailure { Log.w(TAG, "could not write a cover", it) }
                }
            }
            if (coverFile.fileExists()) coverFile.fileUri() else ""
        }
    }

    private suspend fun readCover(file: AppFile): ByteArray? = runCatching {
        val json = NativeEngine.readCover(file.fullPath)
        if (json == "null" || failed(json)) return@runCatching null
        Base64.decode(RustifyJson.decodeFromString(LocalCover.serializer(), json).data)
    }.getOrNull()

    companion object {
        private const val TAG = "LocalMusic"
        /** What the phone's scan takes by extension. */
        private val EXTENSIONS = setOf("mp3", "m4a", "flac", "wav", "ogg", "opus", "webm", "aac")
        private const val UNKNOWN_ARTIST = "Unknown Artist"
        private const val UNKNOWN_ALBUM = "Unknown Album"
        const val COVER_MAX_PX = 512
        /** Files read at once: the reads are disk-bound, and a library is thousands of them. */
        private const val PARALLEL_READS = 8
    }
}
