package com.varuna.rustify.audio

import com.varuna.rustify.audio.LocalReplayGain.Mode
import com.varuna.rustify.audio.LocalReplayGain.Tags
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalReplayGainTest {
    private val loud = Tags(trackGainDb = -10.0, albumGainDb = -8.0, trackPeak = 1.0, albumPeak = 1.0)

    @Test
    fun `track and album mode pick their gain, each falling back to the other`() {
        // -10 + 4 = -6 dB is half the amplitude.
        assertEquals(0.501f, LocalReplayGain.volumeFor(loud, Mode.TRACK, 4), 0.001f)
        assertEquals(0.631f, LocalReplayGain.volumeFor(loud, Mode.ALBUM, 4), 0.001f)
        assertEquals(0.631f, LocalReplayGain.volumeFor(Tags(albumGainDb = -8.0), Mode.TRACK, 4), 0.001f)
    }

    @Test
    fun `untagged, off, or a quiet master is played as it is`() {
        assertEquals(1f, LocalReplayGain.volumeFor(null, Mode.TRACK, 4))
        assertEquals(1f, LocalReplayGain.volumeFor(Tags(), Mode.TRACK, 4))
        assertEquals(1f, LocalReplayGain.volumeFor(loud, Mode.OFF, 4))
        assertEquals(1f, LocalReplayGain.volumeFor(Tags(trackGainDb = 3.0), Mode.TRACK, 0))
    }

    @Test
    fun `the peak caps what the pre-amp would clip, and a bad tag stays audible`() {
        // -2 + 4 = +2 dB wanted, but a 0.9 peak allows 1/0.9 at most: capped at full volume anyway.
        assertEquals(1f, LocalReplayGain.volumeFor(Tags(trackGainDb = -2.0, trackPeak = 0.9), Mode.TRACK, 4))
        // -6 + 4 = -2 dB (0.794) but a peak of 1.5 allows 0.667 at most.
        assertEquals(0.667f, LocalReplayGain.volumeFor(Tags(trackGainDb = -6.0, trackPeak = 1.5), Mode.TRACK, 4), 0.001f)
        assertEquals(0.1f, LocalReplayGain.volumeFor(Tags(trackGainDb = -59.0), Mode.TRACK, 0))
    }
}
