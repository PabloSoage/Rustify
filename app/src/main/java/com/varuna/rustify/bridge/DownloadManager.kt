@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@file:android.annotation.SuppressLint("Recycle")
package com.varuna.rustify.bridge

import android.content.Context
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class DownloadStatus {
    QUEUED, RESOLVING, DOWNLOADING, COMPLETE, ERROR
}

data class DownloadTask(
    val id: String,
    val title: String,
    val artist: String,
    val status: DownloadStatus,
    val progress: Int = 0,
    val errorMessage: String? = null
)

object DownloadManager {
    private val _downloads = MutableStateFlow<List<DownloadTask>>(emptyList())
    val downloads: StateFlow<List<DownloadTask>> = _downloads.asStateFlow()

    private val _activeDownloadCount = MutableStateFlow(0)
    val activeDownloadCount: StateFlow<Int> = _activeDownloadCount.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val initDeferred = kotlinx.coroutines.CompletableDeferred<Unit>()

    fun enqueueDownload(
        context: Context,
        trackId: String,
        trackName: String,
        trackArtist: String,
        spotifyRepo: SpotifyRepository,
        downloadUriStr: String
    ) {
        val currentList = _downloads.value
        if (currentList.any { it.id == trackId && it.status in listOf(DownloadStatus.QUEUED, DownloadStatus.RESOLVING, DownloadStatus.DOWNLOADING) }) {
            return
        }

        _downloads.update { list ->
            val existing = list.find { it.id == trackId }
            if (existing != null) {
                list.map { if (it.id == trackId) it.copy(status = DownloadStatus.QUEUED, progress = 0, errorMessage = null) else it }
            } else {
                list + DownloadTask(trackId, trackName, trackArtist, DownloadStatus.QUEUED)
            }
        }
        updateActiveCount()

        scope.launch {
            processDownload(context.applicationContext, trackId, trackName, trackArtist, spotifyRepo, downloadUriStr)
        }
    }

    fun clearCompleted() {
        _downloads.update { list ->
            list.filter { it.status != DownloadStatus.COMPLETE && it.status != DownloadStatus.ERROR }
        }
        updateActiveCount()
    }

    private fun updateActiveCount() {
        _activeDownloadCount.value = _downloads.value.count { it.status in listOf(DownloadStatus.QUEUED, DownloadStatus.RESOLVING, DownloadStatus.DOWNLOADING) }
    }

    private fun updateStatus(trackId: String, status: DownloadStatus, progress: Int = 0, error: String? = null) {
        _downloads.update { list ->
            list.map { if (it.id == trackId) it.copy(status = status, progress = progress, errorMessage = error) else it }
        }
        updateActiveCount()
    }

    private suspend fun processDownload(
        context: Context,
        trackId: String,
        trackName: String,
        trackArtist: String,
        spotifyRepo: SpotifyRepository,
        downloadUriStr: String
    ) {
        updateStatus(trackId, DownloadStatus.RESOLVING)
        
        try {
            var resolvedId: String? = null
            try {
                val artistsJson = "[" + trackArtist.split(",").joinToString(",") {
                    "\"" + it.trim().replace("\"", "\\\"") + "\""
                } + "]"
                NativeEngine.registerTrackMetadataNative(trackId, trackName, artistsJson, 0, "")
                resolvedId = NativeEngine.resolveYouTubeIdNative(trackId, "")
            } catch (e: Exception) {
                e.printStackTrace()
            }
            if (resolvedId.isNullOrBlank()) {
                updateStatus(trackId, DownloadStatus.ERROR, error = context.getString(com.varuna.rustify.R.string.track_menu_url_not_found))
                return
            }

            updateStatus(trackId, DownloadStatus.DOWNLOADING)

            val safeName = "${trackArtist.replace("/", "_")} - ${trackName.replace("/", "_")}"
            
            withContext(Dispatchers.IO) {
                // Wait for yt-dlp update
                try {
                    initDeferred.await()
                } catch (e: Exception) {}

                val tempDir = java.io.File(context.cacheDir, "downloads")
                tempDir.mkdirs()
                val tempFile = java.io.File(tempDir, "$safeName.mp3")
                if (tempFile.exists()) tempFile.delete()

                try {
                    val request = YoutubeDLRequest("https://music.youtube.com/watch?v=$resolvedId")
                    request.addOption("-f", "bestaudio")
                    request.addOption("-x")
                    request.addOption("--audio-format", "mp3")
                    request.addOption("--audio-quality", "320K")
                    request.addOption("--embed-thumbnail")
                    request.addOption("--add-metadata")
                    request.addOption("-o", tempFile.absolutePath)
                    request.addOption("--no-check-certificate")
                    request.addOption("--no-warnings")

                    YoutubeDL.getInstance().execute(request, trackId) { progress, etaInSeconds, line ->
                        if (scope.isActive) {
                            scope.launch {
                                updateStatus(trackId, DownloadStatus.DOWNLOADING, progress = progress.toInt())
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("DownloadManager", "FFmpeg mp3 extraction failed, falling back to m4a", e)
                    if (tempFile.exists()) tempFile.delete()
                    
                    val fallbackRequest = YoutubeDLRequest("https://music.youtube.com/watch?v=$resolvedId")
                    fallbackRequest.addOption("-f", "bestaudio[ext=m4a]/bestaudio")
                    fallbackRequest.addOption("-o", tempFile.absolutePath)
                    fallbackRequest.addOption("--no-check-certificate")
                    fallbackRequest.addOption("--no-warnings")
                    
                    YoutubeDL.getInstance().execute(fallbackRequest, trackId) { progress, etaInSeconds, line ->
                        if (scope.isActive) {
                            scope.launch {
                                updateStatus(trackId, DownloadStatus.DOWNLOADING, progress = progress.toInt())
                            }
                        }
                    }
                }

                if (tempFile.exists()) {
                    val treeUri = downloadUriStr.toUri()
                    val docFile = DocumentFile.fromTreeUri(context, treeUri)
                    val newFile = docFile?.createFile("audio/mpeg", "$safeName.mp3")
                    if (newFile != null) {
                        context.contentResolver.openOutputStream(newFile.uri)?.use { outputStream ->
                            java.io.FileInputStream(tempFile).use { inputStream ->
                                inputStream.copyTo(outputStream)
                            }
                        }
                    } else {
                        throw Exception("Failed to create SAF output file")
                    }
                    tempFile.delete()
                } else {
                    throw Exception("Temporary file not found after download")
                }
            }

            if (currentCoroutineContext().isActive) {
                updateStatus(trackId, DownloadStatus.COMPLETE, progress = 100)
                val toastMsg = context.getString(com.varuna.rustify.R.string.track_menu_downloaded_toast, trackName)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(context, toastMsg, android.widget.Toast.LENGTH_SHORT).show()
                }
            } else {
                updateStatus(trackId, DownloadStatus.ERROR, error = context.getString(com.varuna.rustify.R.string.track_menu_download_error))
            }

        } catch (e: Exception) {
            e.printStackTrace()
            updateStatus(trackId, DownloadStatus.ERROR, error = e.localizedMessage ?: "Unknown error")
        }
    }
}
