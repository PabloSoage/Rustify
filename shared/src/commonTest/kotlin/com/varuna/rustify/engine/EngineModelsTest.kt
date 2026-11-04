// shared/src/commonTest/kotlin/com/varuna/rustify/engine/EngineModelsTest.kt
package com.varuna.rustify.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The same JSON strings the Rust tests in `core_engine/src/engine/tests.rs` assert, read from this
 * side. Change one, change the other.
 */
class EngineModelsTest {
    @Test
    fun readyDecodesFromTheRustShape() {
        assertEquals(
            EngineEvent.Ready(token = 12, durationMs = 1000),
            EngineEvent.parse("""{"type":"ready","token":12,"durationMs":1000}"""),
        )
    }

    @Test
    fun errorDecodesWithItsCodeAndKind() {
        assertEquals(
            EngineEvent.Error(token = 1, code = "http", kind = "transient", message = "the source answered 503"),
            EngineEvent.parse(
                """{"type":"error","token":1,"code":"http","kind":"transient","message":"the source answered 503"}""",
            ),
        )
    }

    @Test
    fun noEventAndUnknownEventsAreNull() {
        assertNull(EngineEvent.parse("null"))
        assertNull(EngineEvent.parse("""{"type":"somethingNew","x":1}"""))
    }

    @Test
    fun loadEncodesInTheShapeRustReads() {
        val json = EngineCommand.Load(token = 4, uri = "https://x/a.m4a", startMs = 1500, gainDb = -3.5f, play = true).toJson()
        assertEquals(
            """{"type":"load","token":4,"uri":"https://x/a.m4a","startMs":1500,"gainDb":-3.5,"play":true}""",
            json,
        )
    }

    @Test
    fun objectsEncodeAsBareTypes() {
        assertEquals("""{"type":"pause"}""", EngineCommand.Pause.toJson())
        assertEquals("""{"type":"seek","positionMs":30000}""", EngineCommand.Seek(30_000).toJson())
    }

    @Test
    fun aLoadCarriesReplayGainOnlyWhenThereIsSome() {
        val json = EngineCommand.Load(
            token = 4,
            uri = "/music/a.flac",
            play = false,
            replayGain = TrackGains(trackGainDb = -7.5, albumGainDb = -6.0, trackPeak = 0.98),
            albumRun = true,
        ).toJson()
        assertEquals(LOAD_WITH_REPLAY_GAIN, json)
    }

    @Test
    fun defaultEffectsEncodeInTheShapeRustPins() {
        assertEquals(DEFAULT_EFFECTS, EngineJson.encodeToString(EffectsConfig.serializer(), EffectsConfig()))
        assertEquals("""{"type":"setEffects","effects":$DEFAULT_EFFECTS}""", EngineCommand.SetEffects(EffectsConfig()).toJson())
    }

    @Test
    fun everyEffectRoundTripsThroughTheRustShape() {
        val full = EffectsConfig(
            eq = EqConfig(
                enabled = true,
                preampDb = -3f,
                bands = listOf(
                    EqBand(FilterKind.LOW_SHELF, 60f, 4.5f, 0.7f),
                    EqBand(FilterKind.PEAK, 910f, -2f, 0.96f),
                    EqBand(FilterKind.HIGH_SHELF, 14_000f, 3f, 0.7f),
                ),
            ),
            autoEq = AutoEqConfig(enabled = true, peq = "Preamp: -6.1 dB", bands = listOf(GraphicBand(25f, -1.5f))),
            level = LevelConfig(normalize = false, replayGain = ReplayGainMode.ALBUM, preampDb = 2f),
            stereo = StereoConfig(balance = -40, mono = true, crossfeed = true, voiceReduction = true, width = 1.5f),
            fades = FadeConfig(pauseMs = 300, betweenSongsMs = 4000, crossfadeMs = 6000),
            speed = 1.25f,
            pitchSemitones = -2f,
            skipSilence = true,
        )
        assertEquals(FULL_EFFECTS, EngineJson.encodeToString(EffectsConfig.serializer(), full))
        assertEquals(full, EngineJson.decodeFromString(EffectsConfig.serializer(), FULL_EFFECTS))
    }

    private companion object {
        // The same strings as DEFAULT_EFFECTS_JSON, FULL_EFFECTS_JSON and the replay gain load in
        // core_engine/src/engine/tests.rs.
        const val DEFAULT_EFFECTS = """{"eq":{"enabled":false,"preampDb":0.0,"bands":[]},"autoEq":{"enabled":false,"peq":"","bands":[]},"level":{"normalize":true,"replayGain":"auto","preampDb":4.0},"stereo":{"balance":0,"mono":false,"crossfeed":false,"voiceReduction":false,"width":1.0},"fades":{"pauseMs":0,"betweenSongsMs":0,"crossfadeMs":0},"speed":1.0,"pitchSemitones":0.0,"skipSilence":false}"""
        const val FULL_EFFECTS = """{"eq":{"enabled":true,"preampDb":-3.0,"bands":[{"kind":"lowShelf","freqHz":60.0,"gainDb":4.5,"q":0.7},{"kind":"peak","freqHz":910.0,"gainDb":-2.0,"q":0.96},{"kind":"highShelf","freqHz":14000.0,"gainDb":3.0,"q":0.7}]},"autoEq":{"enabled":true,"peq":"Preamp: -6.1 dB","bands":[{"cutoffHz":25.0,"gainDb":-1.5}]},"level":{"normalize":false,"replayGain":"album","preampDb":2.0},"stereo":{"balance":-40,"mono":true,"crossfeed":true,"voiceReduction":true,"width":1.5},"fades":{"pauseMs":300,"betweenSongsMs":4000,"crossfadeMs":6000},"speed":1.25,"pitchSemitones":-2.0,"skipSilence":true}"""
        const val LOAD_WITH_REPLAY_GAIN = """{"type":"load","token":4,"uri":"/music/a.flac","startMs":0,"gainDb":0.0,"play":false,"replayGain":{"trackGainDb":-7.5,"albumGainDb":-6.0,"trackPeak":0.98},"albumRun":true}"""
    }
}
