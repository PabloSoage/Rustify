// shared/src/commonTest/kotlin/com/varuna/rustify/engine/EffectsConfigTest.kt
package com.varuna.rustify.engine

import com.varuna.rustify.platform.MemoryStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Android's settings, by its own keys, read into what the engine takes. The keys are spelled out. */
class EffectsConfigTest {
    @Test
    fun nothingStoredMeansAndroidsDefaults() {
        val c = EffectsConfig.fromSettings(MemoryStore())
        assertFalse(c.eq.enabled, "the equaliser is off until turned on")
        assertEquals(StandardEqualizer.presets[0].levelsDb, c.eq.bands.map { it.gainDb }, "preset 0, as Android")
        assertEquals(LevelConfig(normalize = true, replayGain = ReplayGainMode.AUTO, preampDb = 4f), c.level)
        assertEquals(EffectsConfig.PAUSE_FADE_MS, c.fades.pauseMs, "the pause fade is on by default")
        assertEquals(0, c.fades.betweenSongsMs)
        assertEquals(0, c.fades.crossfadeMs)
        assertEquals(1f, c.speed)
        assertEquals(0f, c.pitchSemitones)
        assertFalse(c.autoEq.enabled)
        assertFalse(c.skipSilence)
        assertEquals(StereoConfig(), c.stereo)
    }

    @Test
    fun androidsKeysMapOntoTheEngine() {
        val store = MemoryStore()
        store.putBoolean("eq_enabled", true)
        store.putInt("eq_preset", -1)
        store.putString("eq_bands", "300,-150,0,600,-1500")
        store.putString("replaygain_mode", "ALBUM")
        store.putInt("replaygain_preamp_db", 2)
        store.putBoolean("normalize_volume", false)
        store.putInt("stereo_balance", -40)
        store.putBoolean("stereo_mono", true)
        store.putBoolean("stereo_crossfeed", true)
        store.putBoolean("stereo_voice_reduction", true)
        store.putBoolean("pause_fade", false)
        store.putInt("fade_between_songs_seconds", 30)
        store.putFloat("playback_speed", 1.25f)
        store.putInt("playback_pitch_semitones", -2)
        store.putBoolean("skip_silence", true)
        store.putString(
            "autoeq_profiles",
            """{"8:WH-1000XM4":{"name":"Sony WH-1000XM4","source":"oratory1990","path":"x","peq":"Preamp: -6.1 dB"}}""",
        )

        val c = EffectsConfig.fromSettings(store, autoEqDevice = "8:WH-1000XM4")

        assertTrue(c.eq.enabled)
        assertEquals(listOf(3f, -1.5f, 0f, 6f, -15f), c.eq.bands.map { it.gainDb }, "millibels to decibels")
        assertEquals(StandardEqualizer.centresHz, c.eq.bands.map { it.freqHz })
        assertEquals(LevelConfig(normalize = false, replayGain = ReplayGainMode.ALBUM, preampDb = 2f), c.level)
        assertEquals(StereoConfig(balance = -40, mono = true, crossfeed = true, voiceReduction = true), c.stereo)
        assertEquals(0, c.fades.pauseMs)
        assertEquals(12_000, c.fades.betweenSongsMs, "capped at Android's twelve seconds")
        assertEquals(1.25f, c.speed)
        assertEquals(-2f, c.pitchSemitones)
        assertTrue(c.skipSilence)
        assertEquals(AutoEqConfig(enabled = true, peq = "Preamp: -6.1 dB"), c.autoEq)
        assertFalse(EffectsConfig.fromSettings(store, autoEqDevice = "another").autoEq.enabled, "no profile for that output")
    }

    @Test
    fun aPresetByIndexIsThatPresetsCurve() {
        val store = MemoryStore()
        store.putInt("eq_preset", 9)
        store.putString("eq_bands", "1500,1500,1500,1500,1500")
        val c = EffectsConfig.fromSettings(store)
        assertEquals(StandardEqualizer.presets[9].levelsDb, c.eq.bands.map { it.gainDb }, "the preset, not the hand-set levels")
    }

    @Test
    fun everyKeyReadIsOneToWatch() {
        assertEquals(16, EffectsConfig.SETTINGS_KEYS.toSet().size)
        assertTrue("playback_speed" in EffectsConfig.SETTINGS_KEYS)
    }
}
