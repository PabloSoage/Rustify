// sharedUi/src/desktopMain/kotlin/com/varuna/rustify/ui/DesktopVideo.kt
package com.varuna.rustify.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.varuna.rustify.platform.Executables
import com.varuna.rustify.platform.Log
import com.varuna.rustify.platform.httpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.takeFrom
import com.varuna.rustify.player.LocalPlayer
import java.io.InputStream
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.ImageInfo

/**
 * Canvas and the song's video on the desktop: the `ffmpeg` program, found on `PATH` as `yt-dlp` is, decoding
 * into a pipe that Compose paints. No VLC to require, and nothing else to carry: what cannot find it does not
 * offer the tabs ([available]). What is heard stays the engine's, so the video is silent and follows it.
 */
object DesktopVideo {
    private const val FPS = 24
    private const val MAX_WIDTH = 960
    private const val MAX_HEIGHT = 720

    /** A picture off the pipe. */
    class Frame(val width: Int, val height: Int, val rgb: ByteArray)

    fun available(): Boolean = Executables.onPath("ffmpeg") != null

    /** The next frame of a stream of binary PPM images (`P6`), or null at its end. */
    internal fun readFrame(input: InputStream): Frame? {
        fun token(): String? {
            val out = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) return null
                if (b.toChar().isWhitespace()) {
                    if (out.isNotEmpty()) return out.toString()
                } else {
                    out.append(b.toChar())
                }
            }
        }
        if (token() != "P6") return null
        val width = token()?.toIntOrNull() ?: return null
        val height = token()?.toIntOrNull() ?: return null
        token() ?: return null
        val rgb = input.readNBytes(width * height * 3)
        return if (rgb.size == width * height * 3) Frame(width, height, rgb) else null
    }

    internal fun Frame.toBitmap(): ImageBitmap {
        val rgba = ByteArray(width * height * 4)
        var from = 0
        var to = 0
        while (from < rgb.size) {
            rgba[to++] = rgb[from++]
            rgba[to++] = rgb[from++]
            rgba[to++] = rgb[from++]
            rgba[to++] = -1
        }
        val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.OPAQUE)
        return SkiaImage.makeRaster(info, rgba, width * 4).toComposeImageBitmap()
    }

    internal fun command(exe: String, source: String, startMs: Long, loop: Boolean): List<String> = buildList {
        addAll(listOf(exe, "-hide_banner", "-loglevel", "error", "-nostdin"))
        if (loop) addAll(listOf("-stream_loop", "-1"))
        if (startMs > 0) addAll(listOf("-ss", (startMs / 1000.0).toString()))
        addAll(listOf("-i", source, "-an", "-r", FPS.toString(), "-vf", "scale='min($MAX_WIDTH,iw)':-2"))
        addAll(listOf("-f", "image2pipe", "-c:v", "ppm", "-"))
    }

    /**
     * The picture to ask for from an HLS manifest: H.264 no taller than [MAX_HEIGHT], which every machine
     * decodes cheaply. Left to choose, ffmpeg opens every variant first (9 s, measured) and takes the
     * largest, which on YouTube is VP9 it may not manage to decode. Null when [manifest] lists none.
     */
    internal fun pickVariant(manifest: String): String? {
        class Variant(val height: Int, val h264: Boolean, val url: String)
        val lines = manifest.lines()
        val variants = lines.indices.filter { lines[it].startsWith("#EXT-X-STREAM-INF") }.mapNotNull { i ->
            val url = lines.getOrNull(i + 1)?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("#") } ?: return@mapNotNull null
            val height = Regex("RESOLUTION=\\d+x(\\d+)").find(lines[i])?.groupValues?.get(1)?.toIntOrNull() ?: 0
            Variant(height, "avc1" in lines[i], url)
        }
        val playable = variants.filter { it.h264 }.ifEmpty { variants }
        return (playable.filter { it.height <= MAX_HEIGHT }.maxByOrNull { it.height } ?: playable.minByOrNull { it.height })?.url
    }

    /** What ffmpeg should open for [source]: one variant of a manifest, or [source] as it is. */
    private suspend fun inputOf(source: String): String {
        if (!source.startsWith("http") || !("m3u8" in source || "/manifest/hls" in source)) return source
        return runCatching {
            val client = httpClient()
            try {
                val manifest = client.get(source).bodyAsText()
                pickVariant(manifest)?.let { variant -> Url(source).toString().let { URLBuilder(it).takeFrom(variant).buildString() } }
            } finally {
                client.close()
            }
        }.getOrNull() ?: source
    }

    /**
     * Decodes [source] from [startMs] and hands over each frame with the time it belongs to. [onFrame]
     * paces it, by holding the pipe until the frame is due, and ends it by answering false.
     */
    suspend fun decode(source: String, startMs: Long, loop: Boolean, onFrame: suspend (Frame, Long) -> Boolean) = withContext(Dispatchers.IO) {
        val exe = Executables.onPath("ffmpeg") ?: return@withContext
        val process = ProcessBuilder(command(exe, inputOf(source), startMs, loop)).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        process.outputStream.close()
        try {
            var index = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val frame = readFrame(process.inputStream) ?: break
                if (!onFrame(frame, startMs + index * 1000 / FPS)) break
                index++
            }
        } finally {
            process.destroyForcibly()
        }
    }

    /** A clip on a loop, on the wall's clock. */
    @Composable
    fun Canvas(url: String, fitWidth: Boolean) {
        var picture by remember(url) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(url) {
            val started = System.currentTimeMillis()
            runCatching {
                decode(url, 0, loop = true) { frame, at ->
                    delay((at - (System.currentTimeMillis() - started)).coerceAtLeast(0))
                    picture = frame.toBitmap()
                    true
                }
            }.onFailure { if (it is CancellationException) throw it else Log.w(TAG, "canvas failed", it) }
        }
        picture?.let {
            Image(it, null, Modifier.fillMaxSize(), contentScale = if (fitWidth) ContentScale.Fit else ContentScale.FillBounds)
        }
    }

    /**
     * The song's video, following the engine: a frame waits until the song reaches it, so pausing the song
     * holds the picture, and a jump further than [RESTART_AFTER_MS] starts the decoder again from there.
     */
    @Composable
    fun Player(url: String) {
        val engine = LocalPlayer.current
        var picture by remember(url) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(url) {
            while (isActive) {
                val from = engine.state.value.positionMs
                runCatching {
                    decode(url, from, loop = false) { frame, at ->
                        var due = at - engine.state.value.positionMs
                        while (due > 0 && due < RESTART_AFTER_MS) {
                            delay(minOf(due, 40L))
                            due = at - engine.state.value.positionMs
                        }
                        if (abs(due) >= RESTART_AFTER_MS) return@decode false
                        // Behind, it catches up by not painting what has gone by.
                        if (due > -FRAME_SKIP_MS) picture = frame.toBitmap()
                        true
                    }
                }.onFailure { if (it is CancellationException) throw it else Log.w(TAG, "video failed", it) }
                delay(RESTART_PAUSE_MS)
            }
        }
        picture?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
    }

    private const val TAG = "DesktopVideo"
    private const val RESTART_AFTER_MS = 1_500L
    private const val FRAME_SKIP_MS = 100L
    private const val RESTART_PAUSE_MS = 300L
}
