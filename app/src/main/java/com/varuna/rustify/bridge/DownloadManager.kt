@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@file:android.annotation.SuppressLint("Recycle")
package com.varuna.rustify.bridge

import android.content.Context
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
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
            // E60: spotifyRepo is no longer used inside processDownload (resolution+download now live
            // in AudioSourceRegistry.downloadChain). Kept on enqueueDownload's public signature for callers.
            processDownload(context.applicationContext, trackId, trackName, trackArtist, downloadUriStr)
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
        downloadUriStr: String
    ) {
        updateStatus(trackId, DownloadStatus.RESOLVING)

        try {
            // E60: la resolución + descarga vive ahora en la cadena de backends
            // (AudioSourceRegistry.downloadChain). yt-dlp es el provider por defecto.
            try {
                initDeferred.await()
            } catch (_: Exception) {}

            val safeName = "${trackArtist.replace("/", "_")} - ${trackName.replace("/", "_")}"
            val tempDir = java.io.File(context.cacheDir, "downloads")
            tempDir.mkdirs()
            val tempFile = java.io.File(tempDir, "$safeName.mp3")

            updateStatus(trackId, DownloadStatus.DOWNLOADING)

            // Reconstruye un FullTrack para el contrato del provider a partir de los
            // primitivos que maneja DownloadManager (artist como string separado por comas).
            val track = FullTrack(
                id = trackId, name = trackName, externalUri = "", explicit = false,
                durationMs = 0, isrc = "",
                artists = if (trackArtist.isBlank()) emptyList()
                          else trackArtist.split(",").map { SimpleArtist("", it.trim(), "", null) },
                album = null
            )

            val res = com.varuna.rustify.audio.AudioSourceRegistry.downloadChain(context)
                .downloadTo(track, tempFile) { progress ->
                    if (scope.isActive) {
                        scope.launch {
                            updateStatus(trackId, DownloadStatus.DOWNLOADING, progress = progress)
                        }
                    }
                }

            if (res.isSuccess) {
                val f = res.getOrNull()!!.second
                if (!f.exists()) throw Exception("Temporary file not found after download")

                // Copia el temporal a un árbol SAF (sin cambios respecto al flujo previo).
                val treeUri = downloadUriStr.toUri()
                val docFile = DocumentFile.fromTreeUri(context, treeUri)
                val newFile = docFile?.createFile("audio/mpeg", "$safeName.mp3")
                if (newFile != null) {
                    context.contentResolver.openOutputStream(newFile.uri)?.use { outputStream ->
                        java.io.FileInputStream(f).use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                } else {
                    throw Exception("Failed to create SAF output file")
                }
                f.delete()

                if (currentCoroutineContext().isActive) {
                    updateStatus(trackId, DownloadStatus.COMPLETE, progress = 100)
                    val toastMsg = context.getString(com.varuna.rustify.R.string.track_menu_downloaded_toast, trackName)
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(context, toastMsg, android.widget.Toast.LENGTH_SHORT).show()
                    }
                } else {
                    updateStatus(trackId, DownloadStatus.ERROR, error = context.getString(com.varuna.rustify.R.string.track_menu_download_error))
                }
            } else {
                val err = res.exceptionOrNull()
                err?.printStackTrace()
                // Distinción de mensajes (paridad con el flujo previo):
                //  - "resolver returned empty YouTube id" → URL no encontrada
                //  - resto de fallos de cadena → error genérico de descarga
                val isResolveFail = err is com.varuna.rustify.audio.AudioSourceChainException &&
                    err.errors.any { it.message?.contains("resolver returned empty") == true }
                val msg = when {
                    isResolveFail -> context.getString(com.varuna.rustify.R.string.track_menu_url_not_found)
                    err is com.varuna.rustify.audio.AudioSourceChainException ->
                        err.errors.firstOrNull()?.localizedMessage
                            ?: context.getString(com.varuna.rustify.R.string.track_menu_download_error)
                    else -> err?.localizedMessage ?: context.getString(com.varuna.rustify.R.string.track_menu_download_error)
                }
                updateStatus(trackId, DownloadStatus.ERROR, error = msg)
            }

        } catch (e: Exception) {
            e.printStackTrace()
            updateStatus(trackId, DownloadStatus.ERROR, error = e.localizedMessage ?: "Unknown error")
        }
    }
}
