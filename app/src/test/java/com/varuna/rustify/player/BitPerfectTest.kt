package com.varuna.rustify.player

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BitPerfectTest {
    private fun pcm(rate: Int, encoding: Int = C.ENCODING_PCM_16BIT, channels: Int = 2) = BitPerfect.Pcm(rate, encoding, channels)

    // What a typical USB DAC offers: the mixer's default plus bit-perfect at its rates.
    private val dac = listOf(
        BitPerfect.Offer(pcm(48_000), bitPerfect = false),
        BitPerfect.Offer(pcm(44_100), bitPerfect = true),
        BitPerfect.Offer(pcm(48_000), bitPerfect = true),
        BitPerfect.Offer(pcm(96_000, C.ENCODING_PCM_FLOAT), bitPerfect = true),
    )

    @Test
    fun theOfferWithTheTracksOwnFormatIsChosen() {
        assertEquals(BitPerfect.Offer(pcm(44_100), true), BitPerfect.choose(pcm(44_100), dac))
        assertEquals(BitPerfect.Offer(pcm(48_000), true), BitPerfect.choose(pcm(48_000), dac))
    }

    @Test
    fun anythingThatWouldNeedAConversionIsRefused() {
        assertNull("no 88.2 kHz", BitPerfect.choose(pcm(88_200), dac))
        assertNull("96 kHz only as float", BitPerfect.choose(pcm(96_000), dac))
        assertNull("mono", BitPerfect.choose(pcm(44_100, channels = 1), dac))
        assertNull("the mixer's own offer is not bit-perfect", BitPerfect.choose(pcm(48_000), dac.filter { !it.bitPerfect }))
    }

    @Test
    fun media3WritesSixteenBitUnlessFloatOutputIsOn() {
        assertEquals(C.ENCODING_PCM_16BIT, BitPerfect.sinkEncoding(C.ENCODING_PCM_16BIT, floatOutput = true))
        assertEquals(C.ENCODING_PCM_16BIT, BitPerfect.sinkEncoding(C.ENCODING_PCM_24BIT, floatOutput = false))
        assertEquals(C.ENCODING_PCM_FLOAT, BitPerfect.sinkEncoding(C.ENCODING_PCM_24BIT, floatOutput = true))
    }

    @Test
    fun whatTheUserTurnedOnIsNamedInOrder() {
        assertNull(BitPerfect.spoiler(false, false, 0, false, false))
        assertEquals(BitPerfect.Spoiler.EQUALIZER, BitPerfect.spoiler(true, true, 4, true, true))
        assertEquals(BitPerfect.Spoiler.FADE, BitPerfect.spoiler(false, false, 4, true, false))
        assertEquals(BitPerfect.Spoiler.SKIP_SILENCE, BitPerfect.spoiler(false, false, 0, false, true))
    }
}
