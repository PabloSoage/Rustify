// sharedUi/src/androidMain/kotlin/com/varuna/rustify/bridge/AndroidLocalMusicScanner.kt
package com.varuna.rustify.bridge

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * The phone's local scan, moved out of `SpotifyRepository` unchanged: folders are tree URIs granted
 * through the Storage Access Framework, tags come from `MediaMetadataRetriever`, and the embedded cover
 * is written once per album.
 */
class AndroidLocalMusicScanner(context: Context) : LocalMusicScanner {
    private val appCtx = context.applicationContext

    override suspend fun scan(request: LocalScanRequest): List<FullTrack> {
        val supportedExtensions = setOf("mp3", "m4a", "flac", "wav", "ogg", "opus", "webm", "aac")
        val coversDir = request.coversDir
        val coversFullRes = request.coversFullRes
        val separators = request.separators
        val newLocalTracks = java.util.Collections.synchronizedList(mutableListOf<FullTrack>())
        val totalFileCount = java.util.concurrent.atomic.AtomicInteger(0)

        // Extract metadata + optional cover for a single file
        fun extractTrack(file: DocumentFile, existingTrack: FullTrack?): FullTrack? {
            val trackId = "local:${file.uri}"
            val lastModifiedStr = file.lastModified().toString()
            val name = file.name ?: return null

            // Fast path: unchanged existing track with valid cover
            if (existingTrack != null && existingTrack.addedAt == lastModifiedStr && !request.resplit) {
                val existingCoverPath = existingTrack.externalUri.removePrefix("file://")
                if (existingCoverPath.isBlank() || java.io.File(existingCoverPath).exists()) {
                    return existingTrack
                }
            }

            val retriever = android.media.MediaMetadataRetriever()
            return try {
                retriever.setDataSource(appCtx, file.uri)
                val title = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE) ?: name
                val artist = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "Unknown Artist"
                val album = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "Unknown Album"
                val durationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                val durationMs = durationStr?.toLongOrNull() ?: 0L

                // Cover art: ONE file per ALBUM (deduplicated) + downscaled/recompressed, so a
                // large local library doesn't bloat User Data. Key by album+artist so all tracks
                // of an album share a single ~512px JPEG instead of each storing full-res art.
                val coverKey = "$album|$artist".lowercase().hashCode()
                val coverFile = java.io.File(coversDir, "$coverKey.jpg")
                var coverUri = if (coverFile.exists()) "file://${coverFile.absolutePath}" else ""
                if (coverUri.isBlank()) {
                    try {
                        val coverBytes = retriever.embeddedPicture
                        if (coverBytes != null && coverBytes.isNotEmpty()) {
                            if (coversFullRes) {
                                // Full resolution (default): keep the original embedded art — sharpest.
                                // Dedup-by-album already keeps this from bloating User Data.
                                coverFile.writeBytes(coverBytes)
                                coverUri = "file://${coverFile.absolutePath}"
                            } else {
                                val bmp = android.graphics.BitmapFactory.decodeByteArray(coverBytes, 0, coverBytes.size)
                                if (bmp != null) {
                                    val maxPx = 512
                                    val scale = minOf(1f, maxPx.toFloat() / maxOf(bmp.width, bmp.height))
                                    val out = if (scale < 1f)
                                        android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
                                    else bmp
                                    java.io.FileOutputStream(coverFile).use { out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) }
                                    if (out !== bmp) out.recycle()
                                    bmp.recycle()
                                    coverUri = "file://${coverFile.absolutePath}"
                                } else {
                                    coverFile.writeBytes(coverBytes)
                                    coverUri = "file://${coverFile.absolutePath}"
                                }
                            }
                        }
                    } catch (_: Exception) { }
                }

                FullTrack(
                    id = trackId,
                    name = title,
                    artists = ArtistSeparators.split(artist, separators).ifEmpty { listOf(artist) }
                        .map { SimpleArtist("local_artist:$it", it, "", emptyList()) },
                    album = SimpleAlbum(
                        "local_album:$album", album, "", null, null,
                        if (coverUri.isNotBlank()) listOf(SpotifyImage(coverUri, null, null)) else emptyList(),
                        emptyList(), null
                    ),
                    durationMs = durationMs.toInt(),
                    explicit = false,
                    isrc = "",
                    addedAt = lastModifiedStr,
                    externalUri = coverUri
                )
            } catch (e: Exception) {
                null
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }
        }

        // Process directories in parallel: one coroutine per root directory
        val scanDispatcher = Dispatchers.IO.limitedParallelism(32)
        coroutineScope {
            request.dirs.map { dirUriStr ->
                async(Dispatchers.IO) {
                    val dirTracks = java.util.Collections.synchronizedList(mutableListOf<FullTrack>())
                    try {
                        val treeUri = android.net.Uri.parse(dirUriStr)
                        val dirFile = DocumentFile.fromTreeUri(appCtx, treeUri)

                        // Recursive directory scanner
                        suspend fun processDirectory(df: DocumentFile?) {
                            if (df == null) return
                            val files = df.listFiles()

                            val audioFiles = mutableListOf<DocumentFile>()
                            val subdirs = mutableListOf<DocumentFile>()

                            for (file in files) {
                                if (file.isDirectory) {
                                    val path = M3u.pathOf("local:${file.uri}")
                                    if (path == null || request.excludedFolders.none { LocalFolders.isUnder(path, it) }) subdirs.add(file)
                                } else {
                                    val mime = file.type
                                    val fname = file.name?.lowercase() ?: ""
                                    val hasAudioExtension = supportedExtensions.any { fname.endsWith(".$it") }
                                    if (mime?.startsWith("audio/") == true || hasAudioExtension) {
                                        audioFiles.add(file)
                                    }
                                }
                            }

                            coroutineScope {
                                val fileJob = async(scanDispatcher) {
                                    for (file in audioFiles) {
                                        if (totalFileCount.incrementAndGet() % 16 == 0) {
                                            delay(1.milliseconds)
                                        }
                                        val trackId = "local:${file.uri}"
                                        val existing = request.existing[trackId]
                                        val track = extractTrack(file, existing)
                                        if (track != null) dirTracks.add(track)
                                    }
                                }

                                val subdirJobs = subdirs.map { subdir ->
                                    async(scanDispatcher) {
                                        processDirectory(subdir)
                                    }
                                }

                                fileJob.await()
                                subdirJobs.awaitAll()
                            }
                        }
                        processDirectory(dirFile)
                    } catch (_: Exception) { }
                    dirTracks
                }
            }.awaitAll().forEach { newLocalTracks.addAll(it) }
        }
        return newLocalTracks.toList()
    }
}
