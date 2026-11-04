// sharedUi/src/desktopMain/kotlin/com/varuna/rustify/ui/DesktopLocalMusicScanner.kt
package com.varuna.rustify.ui

import com.varuna.rustify.bridge.ArtistSeparators
import com.varuna.rustify.bridge.DesktopLocalFiles
import com.varuna.rustify.bridge.FullTrack
import com.varuna.rustify.bridge.LocalCover
import com.varuna.rustify.bridge.LocalFolders
import com.varuna.rustify.bridge.LocalMusicScanner
import com.varuna.rustify.bridge.LocalScanRequest
import com.varuna.rustify.bridge.LocalTags
import com.varuna.rustify.bridge.M3u
import com.varuna.rustify.bridge.NativeEngine
import com.varuna.rustify.bridge.RustifyJson
import com.varuna.rustify.bridge.SimpleAlbum
import com.varuna.rustify.bridge.SimpleArtist
import com.varuna.rustify.bridge.SpotifyImage
import com.varuna.rustify.platform.Log
import kotlinx.coroutines.Dispatchers
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
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * The desktop's local scan: the folders the user added are real paths, walked here, and each file's
 * tags come from the core (`readTags`, over lofty) instead of Android's `MediaMetadataRetriever`.
 *
 * What it answers is what `AndroidLocalMusicScanner` answers, field for field, so the Library's
 * local tab, the folder view and the local matcher work on it unchanged: the id is `local:` and the
 * file's URI ([DesktopLocalFiles]), the title falls back to the file name, the artists are split by
 * the user's separators, `addedAt` is the modification time that decides whether a file is read
 * again, and the cover is one file per album in [LocalScanRequest.coversDir], its URI in the album's
 * image and in `externalUri`. Two things the phone's retriever does not give, and the core does, go
 * where the model has room for them: the ISRC (the matcher's first rule) and the year.
 */
class DesktopLocalMusicScanner : LocalMusicScanner {
    override suspend fun scan(request: LocalScanRequest): List<FullTrack> = withContext(Dispatchers.IO) {
        val files = LinkedHashMap<String, File>()
        val visited = HashSet<String>()
        for (dir in request.dirs) {
            // A folder from another device (a phone's tree URI in restored settings) is no path here.
            val root = File(dir)
            if (root.isDirectory) walk(root, request.excludedFolders, visited, files)
        }
        val covers = ConcurrentHashMap<String, Mutex>()
        val reading = Semaphore(PARALLEL_READS)
        val tracks = coroutineScope {
            files.map { (id, file) ->
                async { reading.withPermit { trackOf(id, file, request, covers) } }
            }.awaitAll()
        }.filterNotNull()
        Log.i(TAG, "${tracks.size} local tracks in ${request.dirs.size} folders")
        tracks
    }

    /** Collects the audio files under [dir] by track id, skipping excluded folders and links back up. */
    private fun walk(dir: File, excluded: Set<String>, visited: MutableSet<String>, out: MutableMap<String, File>) {
        val canonical = runCatching { dir.canonicalPath }.getOrNull() ?: return
        if (!visited.add(canonical)) return
        val entries = dir.listFiles() ?: return
        for (entry in entries) {
            if (entry.name.startsWith(".")) continue
            if (entry.isDirectory) {
                val path = M3u.pathOf(DesktopLocalFiles.trackIdOf(entry))?.trimEnd('/')
                if (path == null || excluded.none { LocalFolders.isUnder(path, it) }) walk(entry, excluded, visited, out)
            } else if (entry.isFile && entry.extension.lowercase() in EXTENSIONS) {
                out.putIfAbsent(DesktopLocalFiles.trackIdOf(entry), entry)
            }
        }
    }

    private suspend fun trackOf(trackId: String, file: File, request: LocalScanRequest, covers: ConcurrentHashMap<String, Mutex>): FullTrack? {
        val lastModified = file.lastModified().toString()

        // An unchanged file whose cover is still there is taken as the last scan read it.
        val existing = request.existing[trackId]
        if (existing != null && existing.addedAt == lastModified && !request.resplit) {
            val cover = existing.externalUri.ifBlank { null }?.let { DesktopLocalFiles.fileOfUri(it) }
            if (existing.externalUri.isBlank() || cover?.exists() == true) return existing
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
            name = tags.title ?: file.name,
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

    private suspend fun readTags(file: File): LocalTags? = runCatching {
        val json = NativeEngine.readTags(file.absolutePath)
        if (failed(json)) null else RustifyJson.decodeFromString(LocalTags.serializer(), json)
    }.getOrNull()

    /** Every bridge answers a failure as `{"success":false,…}`, which would otherwise decode as empty tags. */
    private fun failed(json: String): Boolean =
        (RustifyJson.parseToJsonElement(json) as? JsonObject)?.get("success")?.jsonPrimitive?.booleanOrNull == false

    /**
     * The album's cover, one file per album (keyed like the phone's, by album and artist): the
     * existing one, or the file's embedded picture written as it is or scaled to 512 px. "" for none.
     */
    private suspend fun coverFor(
        file: File,
        tags: LocalTags,
        album: String,
        artist: String,
        request: LocalScanRequest,
        covers: ConcurrentHashMap<String, Mutex>,
    ): String {
        val key = "$album|$artist".lowercase().hashCode().toString()
        val coverFile = File(request.coversDir, "$key.jpg")
        // Tracks of one album are read side by side: one of them writes the cover, the rest find it.
        return covers.computeIfAbsent(key) { Mutex() }.withLock {
            if (!coverFile.exists() && tags.hasCover) {
                val bytes = readCover(file)
                if (bytes != null && bytes.isNotEmpty()) {
                    val out = if (request.coversFullRes) bytes else scaled(bytes) ?: bytes
                    runCatching { coverFile.writeBytes(out) }.onFailure { Log.w(TAG, "could not write a cover", it) }
                }
            }
            if (coverFile.exists()) DesktopLocalFiles.uriOf(coverFile) else ""
        }
    }

    private suspend fun readCover(file: File): ByteArray? = runCatching {
        val json = NativeEngine.readCover(file.absolutePath)
        if (json == "null" || failed(json)) return@runCatching null
        Base64.getDecoder().decode(RustifyJson.decodeFromString(LocalCover.serializer(), json).data)
    }.getOrNull()

    /** The picture at most 512 px on its longer side, as a JPEG at quality 80, as the phone writes it. */
    private fun scaled(bytes: ByteArray): ByteArray? = runCatching {
        val src = ImageIO.read(ByteArrayInputStream(bytes)) ?: return@runCatching null
        val scale = minOf(1.0, COVER_MAX_PX.toDouble() / maxOf(src.width, src.height))
        val w = maxOf(1, (src.width * scale).toInt())
        val h = maxOf(1, (src.height * scale).toInt())
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(src, 0, 0, w, h, null)
        g.dispose()
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val param = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = 0.8f
        }
        val buffer = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(buffer).use { stream ->
            writer.output = stream
            writer.write(null, IIOImage(out, null, null), param)
        }
        writer.dispose()
        buffer.toByteArray()
    }.getOrNull()

    private companion object {
        const val TAG = "LocalMusic"
        /** What the phone's scan takes by extension. */
        val EXTENSIONS = setOf("mp3", "m4a", "flac", "wav", "ogg", "opus", "webm", "aac")
        const val UNKNOWN_ARTIST = "Unknown Artist"
        const val UNKNOWN_ALBUM = "Unknown Album"
        const val COVER_MAX_PX = 512
        /** Files read at once: the reads are disk-bound, and a library is thousands of them. */
        const val PARALLEL_READS = 8
    }
}
