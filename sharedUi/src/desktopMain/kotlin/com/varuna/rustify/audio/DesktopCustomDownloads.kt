// sharedUi/src/desktopMain/kotlin/com/varuna/rustify/audio/DesktopCustomDownloads.kt
package com.varuna.rustify.audio

import com.varuna.rustify.platform.Executables
import com.varuna.rustify.platform.KeyValueStore
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * [CustomDownloads] over the `yt-dlp` program, which the audio sources also look for on `PATH`. Without it
 * the screen says so when asked, and installing it needs no restart. A video format with no sound is merged
 * with the best audio, for which yt-dlp needs `ffmpeg`, and says so if there is none.
 */
class DesktopCustomDownloads(private val prefs: KeyValueStore) : CustomDownloads {
    private fun program(): String = Executables.onPath("yt-dlp") ?: error("yt-dlp is not installed (not on PATH)")

    override suspend fun probe(url: String): Result<CustomProbe> = withContext(Dispatchers.IO) {
        runCatching {
            val (code, out) = Executables.run(
                listOf(program(), "-J", "--no-warnings", "--no-playlist", url.trim()),
                timeoutSeconds = PROBE_TIMEOUT_S,
            ) ?: error("yt-dlp did not finish")
            if (code != 0) error(out.lineSequence().lastOrNull { it.isNotBlank() }?.trim()?.take(200).orEmpty())
            parseInfo(out.substring(out.indexOf('{').coerceAtLeast(0)), url)
        }
    }

    override suspend fun download(
        url: String,
        formatId: String,
        isAudio: Boolean,
        folder: String,
        baseName: String,
        onProgress: (Int) -> Unit,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(folder.isNotBlank()) { "no download folder set" }
            val dir = File(folder).apply { mkdirs() }
            val safe = safeName(baseName)
            val selector = if (isAudio) formatId else "$formatId+bestaudio/$formatId"
            val process = ProcessBuilder(
                program(), "-f", selector, "-o", File(dir, "$safe.%(ext)s").absolutePath,
                // --progress because --print quiets everything else.
                "--no-warnings", "--no-playlist", "--newline", "--progress", "--print", "after_move:filepath", url.trim(),
            ).redirectErrorStream(true).start()
            process.outputStream.close()
            var saved: String? = null
            var last = ""
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        currentCoroutineContext().ensureActive()
                        val percent = progressOf(line)
                        if (percent != null) {
                            onProgress(percent)
                        } else if (line.isNotBlank()) {
                            last = line.trim()
                            if (File(last).isFile) saved = last
                        }
                    }
                }
                val code = process.waitFor()
                if (code != 0) error(last.take(200))
            } catch (e: CancellationException) {
                process.destroyForcibly()
                throw e
            }
            File(saved ?: error("download produced no file: ${last.take(200)}")).name
        }
    }

    override fun folder(): String = prefs.getString(KEY_FOLDER).orEmpty()

    override fun setFolder(folder: String) = prefs.putString(KEY_FOLDER, folder)

    override fun folderLabel(folder: String): String = folder

    companion object {
        private const val KEY_FOLDER = "custom_dl_folder"
        private const val PROBE_TIMEOUT_S = 60L
        private val PROGRESS = Regex("""^\[download]\s+(\d+(?:\.\d+)?)%""")

        /** The percentage in a line of yt-dlp's `--newline` output; null when it is another kind of line. */
        internal fun progressOf(line: String): Int? =
            PROGRESS.find(line)?.groupValues?.get(1)?.toDoubleOrNull()?.toInt()?.coerceIn(0, 100)

        /** What a file may be called on every system: the phone's rule. */
        internal fun safeName(base: String): String =
            base.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").trim().take(120).ifBlank { "download" }

        private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        private fun JsonElement?.number(): Double = (this as? JsonPrimitive)?.doubleOrNull ?: 0.0

        /** What `yt-dlp -J` answers, split and sorted as the phone's list. */
        internal fun parseInfo(json: String, url: String): CustomProbe {
            val info = Json.parseToJsonElement(json) as JsonObject
            val video = ArrayList<CustomFormat>()
            val audio = ArrayList<CustomFormat>()
            for (element in info["formats"] as? JsonArray ?: JsonArray(emptyList())) {
                val f = element as? JsonObject ?: continue
                val id = f["format_id"].text() ?: continue
                val vcodec = f["vcodec"].text() ?: "none"
                val acodec = f["acodec"].text() ?: "none"
                val hasVideo = vcodec.isNotBlank() && vcodec != "none"
                val hasAudio = acodec.isNotBlank() && acodec != "none"
                if (!hasVideo && !hasAudio) continue
                val ext = f["ext"].text().orEmpty()
                val size = f["filesize"].number().toLong().takeIf { it > 0 } ?: f["filesize_approx"].number().toLong()
                val note = f["format_note"].text().orEmpty()
                val tbr = f["tbr"].number().toFloat()
                if (hasVideo) {
                    video += CustomFormat(id, false, f["height"].number().toInt(), f["fps"].number().toInt(), ext, vcodec.substringBefore('.').take(10), 0, tbr, size, note)
                } else {
                    audio += CustomFormat(id, true, 0, 0, ext, acodec.substringBefore('.').take(10), f["abr"].number().toInt(), tbr, size, note)
                }
            }
            return CustomProbe(
                title = info["title"].text() ?: url,
                thumbnail = info["thumbnail"].text(),
                durationSec = info["duration"].number().toInt(),
                video = video.sortedWith(compareByDescending<CustomFormat> { it.height }.thenByDescending { it.tbr }),
                audio = audio.sortedByDescending { if (it.abr > 0) it.abr.toFloat() else it.tbr },
            )
        }
    }
}
