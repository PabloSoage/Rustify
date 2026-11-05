package com.varuna.rustify.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopCustomDownloadsTest {
    @Test
    fun formatsAreSplitAndSortedAsThePhonesList() {
        val probe = DesktopCustomDownloads.parseInfo(
            """{"title":"Me at the zoo","thumbnail":"https://i/x.jpg","duration":19.1,"formats":[
                {"format_id":"sb0","ext":"mhtml","vcodec":"none","acodec":"none"},
                {"format_id":"140","ext":"m4a","vcodec":"none","acodec":"mp4a.40.2","abr":129.5,"tbr":129.5,"filesize":309288},
                {"format_id":"249","ext":"webm","vcodec":"none","acodec":"opus","abr":48.0,"tbr":48.0},
                {"format_id":"18","ext":"mp4","vcodec":"avc1.42001E","acodec":"mp4a.40.2","height":240,"fps":15,"tbr":200.0,"filesize_approx":500000},
                {"format_id":"134","ext":"mp4","vcodec":"avc1.4d4015","acodec":"none","height":360,"fps":60,"tbr":300.0,"format_note":"360p"},
                {"ext":"mp4","vcodec":"avc1","acodec":"none"}
            ]}""",
            "https://example/x",
        )

        assertEquals("Me at the zoo", probe.title)
        assertEquals(19, probe.durationSec)
        assertEquals(listOf("134", "18"), probe.video.map { it.id })
        assertEquals(listOf("140", "249"), probe.audio.map { it.id })
        assertEquals("129kbps · m4a · mp4a · 302.0KB", probe.audio[0].label())
        assertEquals("360p 60fps · mp4 · avc1", probe.video[0].label())
        assertEquals("240p · mp4 · avc1 · 488.3KB", probe.video[1].label(), "the approximate size when there is no other")
    }

    @Test
    fun aPageWithNoTitleIsNamedByItsAddress() {
        assertEquals("https://example/x", DesktopCustomDownloads.parseInfo("{}", "https://example/x").title)
    }

    @Test
    fun progressIsReadFromItsOwnLinesOnly() {
        assertEquals(12, DesktopCustomDownloads.progressOf("[download]  12.3% of   10.00MiB at  1.00MiB/s ETA 00:09"))
        assertEquals(100, DesktopCustomDownloads.progressOf("[download] 100% of 10.00MiB in 00:03"))
        assertNull(DesktopCustomDownloads.progressOf("[download] Destination: a.m4a"))
        assertNull(DesktopCustomDownloads.progressOf("D:/Music/50% of it.m4a"))
    }

    @Test
    fun aTitleIsMadeSafeForAnySystem() {
        assertEquals("AC_DC_ _Back_", DesktopCustomDownloads.safeName("AC/DC: \"Back\""))
        assertEquals("download", DesktopCustomDownloads.safeName(" \n ".trim()))
    }
}
