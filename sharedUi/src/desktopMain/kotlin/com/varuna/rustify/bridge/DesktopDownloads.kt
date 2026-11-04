// sharedUi/src/desktopMain/kotlin/com/varuna/rustify/bridge/DesktopDownloads.kt
package com.varuna.rustify.bridge

import com.varuna.rustify.engine.JvmPlayerBackend
import com.varuna.rustify.engine.StreamResolver
import com.varuna.rustify.platform.Log
import com.varuna.rustify.ui.AppPlatform
import com.varuna.rustify.ui.appString
import com.varuna.rustify.ui.resources.Res
import com.varuna.rustify.ui.resources.track_menu_download_error
import com.varuna.rustify.ui.resources.track_menu_downloaded_toast
import com.varuna.rustify.ui.resources.track_menu_url_not_found
import com.varuna.rustify.ui.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Downloads on the desktop, for the shared Downloads screen and the track menus: the phone's
 * `DownloadManager`, over the desktop's own pieces.
 *
 * A track is resolved by the player's [StreamResolver], so the file is the recording that plays, and
 * fetched in ranges of 1 MiB, as the engine reads it: googlevideo sends a whole-file request at about
 * twice the bitrate and a range at full speed (`engine/source.rs`). The bytes are saved as they come,
 * in the container the stream has (M4A for the AAC the engine asks for), into the folder chosen in
 * Settings (`download_directory`, a path here), with the track's tags and cover written in by the
 * core. The folder is added to the local music folders if it is not under one already, so what was
 * downloaded is in the Library's local tab next time it is scanned, which this starts.
 *
 * The queue, its states and their order are the phone's: [DownloadTask] in [downloads], one entry per
 * track, re-queued when asked again after finishing or failing.
 */
class DesktopDownloads(
    private val platform: AppPlatform,
    // The player's sources, the phone's chain (DesktopAudioSources), so the file is what plays.
    private val resolver: StreamResolver =
        StreamResolver(JvmPlayerBackend, com.varuna.rustify.audio.DesktopAudioSources.install(platform.prefs())),
) {
    private val _downloads = MutableStateFlow<List<DownloadTask>>(emptyList())
    val downloads: StateFlow<List<DownloadTask>> = _downloads.asStateFlow()

    private val _activeDownloadCount = MutableStateFlow(0)
    val activeDownloadCount: StateFlow<Int> = _activeDownloadCount.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Downloads at once; the rest wait their turn, queued. */
    private val running = Semaphore(PARALLEL)

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private fun isActive(status: DownloadStatus) =
        status == DownloadStatus.QUEUED || status == DownloadStatus.RESOLVING || status == DownloadStatus.DOWNLOADING

    fun enqueue(trackId: String, trackName: String, trackArtist: String, folder: String, track: FullTrack?) {
        if (_downloads.value.any { it.id == trackId && isActive(it.status) }) return
        _downloads.update { list ->
            if (list.any { it.id == trackId }) {
                list.map { if (it.id == trackId) it.copy(status = DownloadStatus.QUEUED, progress = 0, errorMessage = null) else it }
            } else {
                list + DownloadTask(trackId, trackName, trackArtist, DownloadStatus.QUEUED)
            }
        }
        updateActiveCount()
        scope.launch { running.withPermit { process(trackId, trackName, trackArtist, folder, track) } }
    }

    fun clearCompleted() {
        _downloads.update { list -> list.filter { it.status != DownloadStatus.COMPLETE && it.status != DownloadStatus.ERROR } }
        updateActiveCount()
    }

    private fun updateActiveCount() {
        _activeDownloadCount.value = _downloads.value.count { isActive(it.status) }
    }

    private fun updateStatus(trackId: String, status: DownloadStatus, progress: Int = 0, error: String? = null) {
        _downloads.update { list ->
            list.map { if (it.id == trackId) it.copy(status = status, progress = progress, errorMessage = error) else it }
        }
        updateActiveCount()
    }

    private suspend fun process(trackId: String, trackName: String, trackArtist: String, folder: String, full: FullTrack?) {
        updateStatus(trackId, DownloadStatus.RESOLVING)
        var partial: File? = null
        try {
            // As the phone rebuilds it from the primitives when the screen had no track to hand over.
            val track = full?.takeIf { it.id == trackId } ?: FullTrack(
                id = trackId, name = trackName, externalUri = "", explicit = false,
                durationMs = 0, isrc = "",
                artists = if (trackArtist.isBlank()) emptyList()
                else trackArtist.split(",").map { SimpleArtist("", it.trim(), "", null) },
                album = null
            )
            val dir = File(folder)
            // A folder named in another device's settings (a phone's tree URI) is no folder here.
            if (!dir.isAbsolute || !(dir.isDirectory || dir.mkdirs())) {
                updateStatus(trackId, DownloadStatus.ERROR, error = appString(Res.string.track_menu_download_error))
                Log.w(TAG, "the download folder is not a folder on this computer: $folder")
                return
            }
            // A local file is already one; the menus do not offer it, and nothing is resolved for it.
            val stream = if (TrackRef.isLocal(trackId)) null else resolver.resolve(track)
            if (stream == null) {
                updateStatus(trackId, DownloadStatus.ERROR, error = appString(Res.string.track_menu_url_not_found))
                return
            }

            updateStatus(trackId, DownloadStatus.DOWNLOADING)
            val baseName = safeName("$trackArtist - $trackName")
            val temp = File(dir, "$baseName.download").also { partial = it }
            var lastPercent = -1
            fetchTo(stream.url, temp, stream.contentLength) { percent ->
                if (percent != lastPercent) {
                    lastPercent = percent
                    updateStatus(trackId, DownloadStatus.DOWNLOADING, progress = percent)
                }
            }

            // The bytes decide the extension, as on the phone; the stream's type is the fallback.
            val head = temp.inputStream().use { input -> ByteArray(16).let { buf -> buf.copyOf(input.read(buf).coerceAtLeast(0)) } }
            val target = freeName(dir, baseName, extensionOf(head, stream.mimeType))
            if (!temp.renameTo(target)) throw IOException("could not move the download to ${target.name}")
            partial = null

            writeTags(target, track)
            updateStatus(trackId, DownloadStatus.COMPLETE, progress = 100)
            platform.toast(Res.string.track_menu_downloaded_toast, trackName)
            intoLocalMusic(dir)
        } catch (e: Exception) {
            Log.w(TAG, "download failed for $trackId", e)
            updateStatus(trackId, DownloadStatus.ERROR, error = e.message ?: appString(Res.string.track_menu_download_error))
        } finally {
            partial?.let { runCatching { it.delete() } }
        }
    }

    /** One range's outcome: bytes appended (with the total, when the server says it), or the end. */
    private sealed class Range {
        data class Got(val bytes: Long, val total: Long?, val whole: Boolean) : Range()
        data object End : Range()
    }

    /**
     * Fetches [url] into [out] in ranges. A server that ignores `Range` answers the whole body at
     * once, which is taken as it is. [knownTotal] is the size YouTube gave, when it gave one.
     */
    private fun fetchTo(url: String, out: File, knownTotal: Long?, onProgress: (Int) -> Unit) {
        var total = knownTotal?.takeIf { it > 0 }
        var have = 0L
        FileOutputStream(out).use { sink ->
            while (true) {
                val size = total
                if (size != null && have >= size) break
                val end = have + CHUNK - 1
                val to = if (size != null) minOf(end, size - 1) else end
                when (val r = range(url, have, to, sink)) {
                    Range.End -> break
                    is Range.Got -> {
                        have += r.bytes
                        if (total == null) total = r.total
                        // Without a total, a range shorter than asked was the last one.
                        if (r.whole || r.bytes == 0L || (r.total == null && r.bytes < to - (have - r.bytes) + 1)) break
                    }
                }
                total?.let { onProgress((have * 100 / it).toInt().coerceIn(0, 99)) }
            }
        }
        val size = total
        if (have == 0L) throw IOException("the stream was empty")
        if (size != null && have < size) throw IOException("the download stopped at $have of $size bytes")
    }

    /**
     * Asks for bytes [from]..[to] and appends them to [sink], trying [RANGE_TRIES] times. A range is
     * read whole before it is written, so a try that broke off halfway leaves nothing to undo.
     */
    private fun range(url: String, from: Long, to: Long, sink: OutputStream): Range {
        var last: Exception? = null
        repeat(RANGE_TRIES) {
            try {
                val request = Request.Builder().url(url).header("Range", "bytes=$from-$to").build()
                http.newCall(request).execute().use { response ->
                    when {
                        response.code == 206 -> {
                            val total = response.header("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull()
                            val bytes = response.body.bytes()
                            sink.write(bytes)
                            return Range.Got(bytes.size.toLong(), total, whole = false)
                        }
                        // Past the end, when the end was not known.
                        response.code == 416 -> return Range.End
                        response.isSuccessful && from == 0L -> {
                            val bytes = response.body.bytes()
                            sink.write(bytes)
                            return Range.Got(bytes.size.toLong(), bytes.size.toLong(), whole = true)
                        }
                        else -> {
                            last = IOException("range answered HTTP ${response.code}")
                        }
                    }
                }
            } catch (e: IOException) {
                last = e
            }
        }
        throw last ?: IOException("the download failed")
    }

    /** The track's tags and its album's cover, written into the file by the core. Best effort. */
    private suspend fun writeTags(file: File, track: FullTrack) {
        val cover = track.album?.images?.largest()?.url?.takeIf { it.startsWith("http") }?.let { coverBytes(it) }
        val tags = TagsToWrite(
            title = track.name.ifBlank { null },
            artists = track.artists.map { it.name }.filter { it.isNotBlank() },
            album = track.album?.name?.ifBlank { null },
            albumArtist = track.album?.artists?.firstOrNull()?.name?.ifBlank { null },
            isrc = track.isrc.ifBlank { null },
            year = track.album?.releaseDate?.take(4)?.toIntOrNull(),
            cover = cover?.let { Base64.getEncoder().encodeToString(it) },
        )
        val answer = NativeEngine.writeTags(file.absolutePath, RustifyJson.encodeToString(TagsToWrite.serializer(), tags))
        if (!answer.contains("\"success\":true")) Log.w(TAG, "tags not written into ${file.name}: $answer")
    }

    private fun coverBytes(url: String): ByteArray? = runCatching {
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (response.isSuccessful) response.body.bytes().takeIf { it.size in 1..MAX_COVER_BYTES } else null
        }
    }.getOrNull()

    /** Adds [dir] to the local music folders unless one already holds it, and rescans. */
    private fun intoLocalMusic(dir: File) {
        val prefs = platform.prefs()
        val dirs = prefs.getStringSet(LOCAL_DIRS, emptySet())
        val path = dir.absolutePath
        val slashed = path.replace('\\', '/')
        if (dirs.none { LocalFolders.isUnder(slashed, it.replace('\\', '/')) }) {
            prefs.putStringSet(LOCAL_DIRS, dirs + path)
            Log.i(TAG, "added the download folder to the local music folders: $path")
        }
        SpotifyRepository.instance?.scanLocalMusic()
    }

    private companion object {
        const val TAG = "Downloads"
        const val LOCAL_DIRS = "local_music_directories"
        const val PARALLEL = 2
        const val CHUNK = 1L shl 20
        const val RANGE_TRIES = 3
        const val MAX_COVER_BYTES = 8 shl 20

        /** "Artist - Title" as a file name every system takes: no separators, no reserved characters. */
        fun safeName(name: String): String =
            name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trimEnd('.').take(180).ifBlank { "download" }

        /** [base].[ext] in [dir], or "base (2).ext" and on when that is taken, as the phone's picker names it. */
        fun freeName(dir: File, base: String, ext: String): File {
            var candidate = File(dir, "$base.$ext")
            var n = 2
            while (candidate.exists()) candidate = File(dir, "$base (${n++}).$ext")
            return candidate
        }

        /** What the first bytes say the file is; the stream's MIME type when they say nothing known. */
        fun extensionOf(head: ByteArray, mimeType: String): String {
            fun at(offset: Int, ascii: String) =
                head.size >= offset + ascii.length && ascii.indices.all { head[offset + it] == ascii[it].code.toByte() }
            val ebml = head.size >= 4 && head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() &&
                head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte()
            val mpegFrame = head.size >= 2 && head[0] == 0xFF.toByte() && (head[1].toInt() and 0xE0) == 0xE0
            return when {
                at(4, "ftyp") -> "m4a"
                ebml -> "webm"
                at(0, "fLaC") -> "flac"
                at(0, "OggS") -> "ogg"
                at(0, "ID3") || mpegFrame -> "mp3"
                at(0, "RIFF") -> "wav"
                else -> when (mimeType.substringBefore(';').trim().lowercase()) {
                    "audio/webm" -> "webm"
                    "audio/mpeg" -> "mp3"
                    "audio/flac" -> "flac"
                    "audio/ogg" -> "ogg"
                    else -> "m4a"
                }
            }
        }
    }
}
