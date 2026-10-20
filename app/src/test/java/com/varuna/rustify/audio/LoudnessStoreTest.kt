package com.varuna.rustify.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class LoudnessStoreTest {

    @Test
    fun `a loud recording is turned down by exactly its excess`() {
        // 6.02 dB is half the amplitude.
        assertEquals(0.5f, LoudnessStore.gainFor(6.0206), 0.001f)
    }

    @Test
    fun `quiet or unknown recordings are left at full volume, never boosted`() {
        assertEquals(1f, LoudnessStore.gainFor(null))
        assertEquals(1f, LoudnessStore.gainFor(0.0))
        assertEquals(1f, LoudnessStore.gainFor(-7.5))
    }

    @Test
    fun `a mis-measured upload cannot become inaudible`() {
        assertEquals(0.25f, LoudnessStore.gainFor(40.0))
    }
}
