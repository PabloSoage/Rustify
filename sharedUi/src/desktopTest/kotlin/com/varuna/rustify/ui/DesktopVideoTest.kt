package com.varuna.rustify.ui

import com.varuna.rustify.platform.Executables
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class DesktopVideoTest {
    @Test
    fun framesAreReadOneAfterAnotherAndACutOneIsTheEnd() {
        val one = "P6\n2 1\n255\n".toByteArray() + byteArrayOf(1, 2, 3, 4, 5, 6)
        val cut = "P6\n2 1\n255\n".toByteArray() + byteArrayOf(9, 9)
        val input = ByteArrayInputStream(one + one + cut)

        val frame = assertNotNull(DesktopVideo.readFrame(input))
        assertEquals(2, frame.width)
        assertEquals(1, frame.height)
        assertEquals(listOf<Byte>(1, 2, 3, 4, 5, 6), frame.rgb.toList())
        assertNotNull(DesktopVideo.readFrame(input))
        assertNull(DesktopVideo.readFrame(input), "half a frame")
        assertNull(DesktopVideo.readFrame(ByteArrayInputStream(ByteArray(0))))
        assertNull(DesktopVideo.readFrame(ByteArrayInputStream("<html>".toByteArray())))
    }

    @Test
    fun theCommandLoopsOnlyWhenAskedAndStartsWhereItIsTold() {
        val canvas = DesktopVideo.command("ffmpeg", "u", 0, loop = true)
        assertEquals(1, canvas.count { it == "-stream_loop" })
        assertTrue(canvas.indexOf("-stream_loop") < canvas.indexOf("-i"), "an input option")
        assertTrue("-ss" !in canvas)
        assertTrue("-an" in canvas, "silent: what is heard is the engine's")

        val video = DesktopVideo.command("ffmpeg", "u", 90_500, loop = false)
        assertTrue("-stream_loop" !in video)
        assertEquals("90.5", video[video.indexOf("-ss") + 1])
    }

    @Test
    fun aManifestGivesTheTallestH264UpTo720() {
        val manifest = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=9000000,CODECS="vp09.00.51.08",RESOLUTION=3840x2160
            https://m/4k.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=4000000,CODECS="avc1.640028,mp4a.40.2",RESOLUTION=1920x1080
            https://m/1080.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,CODECS="avc1.4d401f,mp4a.40.2",RESOLUTION=1280x720
            https://m/720.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,CODECS="vp09.00.31.08",RESOLUTION=1280x720
            https://m/720vp9.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=500000,CODECS="avc1.4d4015,mp4a.40.2",RESOLUTION=640x360
            https://m/360.m3u8
        """.trimIndent()

        assertEquals("https://m/720.m3u8", DesktopVideo.pickVariant(manifest))
        val onlyLarge = "#EXT-X-STREAM-INF:CODECS=\"avc1\",RESOLUTION=1920x1080\nhttps://m/a\n#EXT-X-STREAM-INF:CODECS=\"avc1\",RESOLUTION=2560x1440\nhttps://m/b"
        assertEquals("https://m/a", DesktopVideo.pickVariant(onlyLarge), "the smallest, when all are large")
        val noH264 = "#EXT-X-STREAM-INF:CODECS=\"vp09\",RESOLUTION=640x360\nhttps://m/v"
        assertEquals("https://m/v", DesktopVideo.pickVariant(noH264))
        assertNull(DesktopVideo.pickVariant("#EXTM3U\n#EXTINF:6,\nsegment.ts"))
    }

    // With the program, where there is one: a second of red, then of blue, which comes out as those colours.
    @Test
    fun aRealVideoDecodesToItsColoursWhereThereIsFfmpeg() = runBlocking {
        val exe = Executables.onPath("ffmpeg") ?: return@runBlocking
        val dir: File = Files.createTempDirectory("video").toFile()
        try {
            val clip = File(dir, "clip.mp4")
            val (code, out) = assertNotNull(
                Executables.run(
                    listOf(
                        exe, "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "color=c=red:s=64x36:d=1:r=24",
                        "-f", "lavfi", "-i", "color=c=blue:s=64x36:d=1:r=24",
                        "-filter_complex", "[0][1]concat=n=2:v=1", "-pix_fmt", "yuv420p", clip.path,
                    ),
                    timeoutSeconds = 60,
                ),
            )
            assertEquals(0, code, out)

            val frames = ArrayList<Pair<Long, DesktopVideo.Frame>>()
            DesktopVideo.decode(clip.path, 0, loop = false) { frame, at ->
                frames += at to frame
                true
            }
            assertEquals(48, frames.size)
            assertEquals(1000L, frames[24].first)
            fun DesktopVideo.Frame.isReddish() = (rgb[0].toInt() and 0xFF) > 200 && (rgb[2].toInt() and 0xFF) < 60
            fun DesktopVideo.Frame.isBluish() = (rgb[2].toInt() and 0xFF) > 200 && (rgb[0].toInt() and 0xFF) < 60
            assertTrue(frames[5].second.isReddish())
            assertTrue(frames[40].second.isBluish())

            val later = ArrayList<DesktopVideo.Frame>()
            DesktopVideo.decode(clip.path, 1_000, loop = false) { frame, _ ->
                later += frame
                later.size < 3
            }
            assertTrue(later[0].isBluish(), "from where it was told")
        } finally {
            dir.deleteRecursively()
        }
    }
}
