// sharedUi/src/commonMain/kotlin/com/varuna/rustify/audio/CustomDownloads.kt
package com.varuna.rustify.audio

import com.varuna.rustify.ui.formatFixedUs

/** A downloadable format of a pasted URL. [isAudio] = audio-only (no video). */
data class CustomFormat(
    val id: String,
    val isAudio: Boolean,
    val height: Int,
    val fps: Int,
    val ext: String,
    val codec: String,
    val abr: Int,
    val tbr: Float,
    val sizeBytes: Long,
    val note: String,
) {
    /** Human-readable label for the UI. */
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

    private fun humanBytes(b: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var v = b.toDouble()
        var i = 0
        while (v >= 1024 && i < units.lastIndex) {
            v /= 1024
            i++
        }
        return if (i == 0) "${b}B" else formatFixedUs(v, 1) + units[i]
    }
}

/** What a pasted URL offers. */
data class CustomProbe(
    val title: String,
    val thumbnail: String?,
    val durationSec: Int,
    val video: List<CustomFormat>,
    val audio: List<CustomFormat>,
)

/**
 * Custom downloads: any URL yt-dlp knows, with its qualities listed and the chosen one saved to a folder
 * of its own. yt-dlp is each platform's (youtubedl-android on the phone, the program on the desktop);
 * where there is none, `AppServices.customDownloads` is null and nothing offers it.
 */
interface CustomDownloads {
    suspend fun probe(url: String): Result<CustomProbe>

    /** Saves [formatId] of [url] in [folder] as [baseName]; answers the file's name. [onProgress] is 0..100. */
    suspend fun download(
        url: String,
        formatId: String,
        isAudio: Boolean,
        folder: String,
        baseName: String,
        onProgress: (Int) -> Unit,
    ): Result<String>

    /** The folder chosen for these, as [com.varuna.rustify.ui.rememberPickFolder] answered it; "" when none. */
    fun folder(): String

    fun setFolder(folder: String)

    /** [folder] as the screen shows it. */
    fun folderLabel(folder: String): String
}
