package com.varuna.rustify.audio

import android.content.Context
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.varuna.rustify.bridge.DownloadManager
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * E103 — Descargas personalizadas: pega CUALQUIER URL soportada por yt-dlp (no solo YouTube), lista
 * las calidades de vídeo y de audio disponibles, y descarga la elegida a una carpeta aparte (SAF).
 * Reutiliza el yt-dlp ya inicializado por [YtDlpAudioSource] (mismo `initDeferred`).
 */
object CustomDownload {

    /** Un formato descargable. [isAudio] = solo-audio (sin vídeo). */
    data class Fmt(
        val id: String,
        val isAudio: Boolean,
        val height: Int,
        val fps: Int,
        val ext: String,
        val codec: String,
        val abr: Int,
        val tbr: Float,
        val sizeBytes: Long,
        val note: String
    ) {
        /** Etiqueta legible para la UI. */
        fun label(): String {
            val size = if (sizeBytes > 0) " · " + humanBytes(sizeBytes) else ""
            return if (isAudio) {
                val br = if (abr > 0) "${abr}kbps" else if (tbr > 0) "${tbr.toInt()}kbps" else "audio"
                "$br · $ext${if (codec.isNotBlank()) " · $codec" else ""}$size"
            } else {
                val res = if (height > 0) "${height}p" else "video"
                val f = if (fps > 30) " ${fps}fps" else ""
                "$res$f · $ext${if (codec.isNotBlank()) " · $codec" else ""}$size"
            }
        }
    }

    data class Probe(
        val title: String,
        val thumbnail: String?,
        val durationSec: Int,
        val video: List<Fmt>,
        val audio: List<Fmt>
    )

    /** Analiza una URL y devuelve sus formatos de vídeo y audio. */
    suspend fun probe(url: String): Result<Probe> = withContext(Dispatchers.IO) {
        runCatching {
            try { DownloadManager.initDeferred.await() } catch (_: Exception) {}
            val info = YoutubeDL.getInstance().getInfo(url.trim())
            val formats = info.formats ?: emptyList()
            val video = ArrayList<Fmt>()
            val audio = ArrayList<Fmt>()
            for (f in formats) {
                val id = f.formatId ?: continue
                val vcodec = (f.vcodec ?: "none")
                val acodec = (f.acodec ?: "none")
                val hasVideo = vcodec.isNotBlank() && vcodec != "none"
                val hasAudio = acodec.isNotBlank() && acodec != "none"
                if (!hasVideo && !hasAudio) continue
                val ext = f.ext ?: ""
                val size = runCatching { f.fileSize }.getOrDefault(0L)
                val note = f.formatNote ?: ""
                if (hasVideo) {
                    video.add(Fmt(id, false, f.height, runCatching { f.fps }.getOrDefault(0), ext,
                        vcodec.substringBefore('.').take(10), 0, runCatching { f.tbr.toFloat() }.getOrDefault(0f), size, note))
                } else {
                    audio.add(Fmt(id, true, 0, 0, ext,
                        acodec.substringBefore('.').take(10), runCatching { f.abr }.getOrDefault(0), runCatching { f.tbr.toFloat() }.getOrDefault(0f), size, note))
                }
            }
            Probe(
                title = info.title ?: url,
                thumbnail = info.thumbnail,
                durationSec = runCatching { info.duration }.getOrDefault(0),
                video = video.sortedWith(compareByDescending<Fmt> { it.height }.thenByDescending { it.tbr }),
                audio = audio.sortedByDescending { if (it.abr > 0) it.abr.toFloat() else it.tbr }
            )
        }
    }

    /**
     * Descarga [formatId] de [url] a la carpeta SAF [folderTreeUri]. Para formatos de vídeo sin audio,
     * yt-dlp fusiona con el mejor audio (ffmpeg). Devuelve el nombre del fichero creado.
     */
    suspend fun download(
        context: Context,
        url: String,
        formatId: String,
        isAudio: Boolean,
        folderTreeUri: String,
        baseName: String,
        onProgress: (Int) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            try { DownloadManager.initDeferred.await() } catch (_: Exception) {}
            require(folderTreeUri.isNotBlank()) { "no download folder set" }
            val tmpDir = File(context.cacheDir, "custom_dl").apply { mkdirs() }
            // Nombre saneado; yt-dlp fija la extensión real vía %(ext)s.
            val safe = baseName.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").trim().take(120).ifBlank { "download" }
            // Limpia restos previos con ese nombre base.
            tmpDir.listFiles()?.filter { it.name.startsWith("$safe.") }?.forEach { it.delete() }
            val outTemplate = File(tmpDir, "$safe.%(ext)s").absolutePath

            // Vídeo posiblemente solo-vídeo → fusiona con bestaudio; si el formato ya trae audio, el
            // fallback "/formatId" lo cubre. Audio → se baja el formato tal cual (contenedor original).
            val selector = if (isAudio) formatId else "$formatId+bestaudio/$formatId"
            val req = YoutubeDLRequest(url.trim()).apply {
                addOption("-f", selector)
                addOption("-o", outTemplate)
                addOption("--no-check-certificate")
                addOption("--no-warnings")
                addOption("--no-playlist")
            }
            YoutubeDL.getInstance().execute(req, "custom:$url") { progress, _, _ -> onProgress(progress.toInt()) }

            val produced = tmpDir.listFiles()?.firstOrNull { it.name.startsWith("$safe.") }
                ?: error("download produced no file")
            val tree = DocumentFile.fromTreeUri(context, folderTreeUri.toUri()) ?: error("invalid folder")
            val mime = mimeFor(produced.extension)
            // Evita duplicar: si existe uno con el mismo nombre, DocumentFile crea "name (1)".
            val dst = tree.createFile(mime, produced.name) ?: error("cannot create file in folder")
            context.contentResolver.openOutputStream(dst.uri)?.use { os ->
                produced.inputStream().use { it.copyTo(os) }
            } ?: error("cannot open output stream")
            produced.delete()
            produced.name
        }
    }

    private fun mimeFor(ext: String): String = when (ext.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "opus", "ogg" -> "audio/ogg"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        else -> "application/octet-stream"
    }

    private fun humanBytes(b: Long): String {
        if (b <= 0) return ""
        val units = arrayOf("B", "KB", "MB", "GB")
        var v = b.toDouble(); var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return if (i == 0) "${b}B" else String.format(java.util.Locale.US, "%.1f%s", v, units[i])
    }

    // Preferencia de la carpeta de descargas personalizadas (SAF tree URI).
    private const val PREFS = "rustify_settings"
    private const val K_FOLDER = "custom_dl_folder"
    fun folder(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(K_FOLDER, "") ?: ""
    fun setFolder(context: Context, uri: String) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(K_FOLDER, uri).apply()
}
