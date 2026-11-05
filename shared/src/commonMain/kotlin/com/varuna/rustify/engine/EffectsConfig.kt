// shared/src/commonMain/kotlin/com/varuna/rustify/engine/EffectsConfig.kt
package com.varuna.rustify.engine

import com.varuna.rustify.platform.KeyValueStore
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/*
 * The engine's audio effects (`core_engine/src/engine/effects.rs`), sent whole as
 * `{"type":"setEffects","effects":{…}}` whenever any of them changes. On Android the same settings
 * drive Media3's processors and `audiofx`; on the platforms that play through the engine they arrive
 * here.
 *
 * Every default is the engine's own, so `EffectsConfig()` leaves the audio as it is (and encodes to
 * exactly what `effects_serialise_in_a_stable_shape_and_read_back` pins on the Rust side).
 */

@Serializable
data class EffectsConfig(
    val eq: EqConfig = EqConfig(),
    val autoEq: AutoEqConfig = AutoEqConfig(),
    val level: LevelConfig = LevelConfig(),
    val stereo: StereoConfig = StereoConfig(),
    val fades: FadeConfig = FadeConfig(),
    /** Playback rate without a change of pitch; the engine takes 0.25 to 4. */
    val speed: Float = 1f,
    /** Pitch in semitones, independent of the speed; the engine takes ±12. */
    val pitchSemitones: Float = 0f,
    /** Long silent stretches cut short (half a second of each is kept). */
    val skipSilence: Boolean = false,
) {
    companion object {
        // Android's preference keys, in its `rustify_settings` file: EqualizerController,
        // AutoEqController, LoudnessStore, LocalReplayGain, StereoTools, PauseFade, TransitionFade,
        // PlaybackSpeed and SkipSilence each own some of them there. The other platforms keep the
        // same names in their own settings store, so a setting means the same thing everywhere.
        const val KEY_EQ_ENABLED = "eq_enabled"
        const val KEY_EQ_PRESET = "eq_preset"
        const val KEY_EQ_BANDS = "eq_bands"
        const val KEY_AUTOEQ_PROFILES = "autoeq_profiles"
        const val KEY_NORMALIZE = "normalize_volume"
        const val KEY_REPLAYGAIN_MODE = "replaygain_mode"
        const val KEY_REPLAYGAIN_PREAMP = "replaygain_preamp_db"
        const val KEY_STEREO_CROSSFEED = "stereo_crossfeed"
        const val KEY_STEREO_BALANCE = "stereo_balance"
        const val KEY_STEREO_MONO = "stereo_mono"
        const val KEY_STEREO_VOICE = "stereo_voice_reduction"
        const val KEY_PAUSE_FADE = "pause_fade"
        const val KEY_FADE_BETWEEN = "fade_between_songs_seconds"
        const val KEY_SPEED = "playback_speed"
        const val KEY_PITCH = "playback_pitch_semitones"
        const val KEY_SKIP_SILENCE = "skip_silence"

        /** Every key [fromSettings] reads: what to watch to send the effects again. */
        val SETTINGS_KEYS: List<String> = listOf(
            KEY_EQ_ENABLED, KEY_EQ_PRESET, KEY_EQ_BANDS, KEY_AUTOEQ_PROFILES, KEY_NORMALIZE,
            KEY_REPLAYGAIN_MODE, KEY_REPLAYGAIN_PREAMP, KEY_STEREO_CROSSFEED, KEY_STEREO_BALANCE,
            KEY_STEREO_MONO, KEY_STEREO_VOICE, KEY_PAUSE_FADE, KEY_FADE_BETWEEN, KEY_SPEED, KEY_PITCH,
            KEY_SKIP_SILENCE,
        )

        /** Android's `PauseFade.DURATION_MS`. */
        const val PAUSE_FADE_MS = 300

        /** Android's `TransitionFade.MAX_SECONDS`. */
        const val MAX_FADE_BETWEEN_SECONDS = 12

        /**
         * The effects as Android's settings describe them, read from [settings] with Android's keys
         * and defaults. [autoEqDevice] is the key of the output playing now in `autoeq_profiles`
         * (Android uses the Bluetooth/USB device; elsewhere it is the output device's name); `null`
         * leaves AutoEq off.
         */
        fun fromSettings(settings: KeyValueStore, autoEqDevice: String? = null): EffectsConfig {
            val preset = settings.getInt(KEY_EQ_PRESET, 0)
            // A preset by index, or (-1) the levels moved by hand, comma-separated millibels.
            val levelsDb = StandardEqualizer.presets.getOrNull(preset)?.levelsDb
                ?: settings.getString(KEY_EQ_BANDS)
                    ?.split(",")
                    ?.mapNotNull { it.trim().toIntOrNull() }
                    ?.map { it / 100f }
                ?: emptyList()
            val peq = autoEqDevice?.let { profilePeq(settings.getString(KEY_AUTOEQ_PROFILES), it) }
            val mode = settings.getString(KEY_REPLAYGAIN_MODE)
                ?.let { name -> ReplayGainMode.entries.firstOrNull { it.name == name } }
                ?: ReplayGainMode.AUTO
            return EffectsConfig(
                eq = EqConfig(
                    enabled = settings.getBoolean(KEY_EQ_ENABLED, false),
                    bands = StandardEqualizer.bands(levelsDb),
                ),
                autoEq = AutoEqConfig(enabled = peq != null, peq = peq.orEmpty()),
                level = LevelConfig(
                    normalize = settings.getBoolean(KEY_NORMALIZE, true),
                    replayGain = mode,
                    preampDb = settings.getInt(KEY_REPLAYGAIN_PREAMP, 4).toFloat(),
                ),
                stereo = StereoConfig(
                    balance = settings.getInt(KEY_STEREO_BALANCE, 0).coerceIn(-100, 100),
                    mono = settings.getBoolean(KEY_STEREO_MONO, false),
                    crossfeed = settings.getBoolean(KEY_STEREO_CROSSFEED, false),
                    voiceReduction = settings.getBoolean(KEY_STEREO_VOICE, false),
                ),
                fades = FadeConfig(
                    pauseMs = if (settings.getBoolean(KEY_PAUSE_FADE, true)) PAUSE_FADE_MS else 0,
                    betweenSongsMs = settings.getInt(KEY_FADE_BETWEEN, 0).coerceIn(0, MAX_FADE_BETWEEN_SECONDS) * 1000,
                ),
                // PlaybackSpeed's ranges: 0.5 to 2, and six semitones either way.
                speed = settings.getFloat(KEY_SPEED, 1f).coerceIn(0.5f, 2f),
                pitchSemitones = settings.getInt(KEY_PITCH, 0).coerceIn(-6, 6).toFloat(),
                skipSilence = settings.getBoolean(KEY_SKIP_SILENCE, false),
            )
        }

        /** The parametric EQ of [device]'s profile in Android's `autoeq_profiles` map, if it has one. */
        private fun profilePeq(raw: String?, device: String): String? = runCatching {
            val profile = Json.parseToJsonElement(raw ?: return null).jsonObject[device] ?: return null
            profile.jsonObject["peq"]?.jsonPrimitive?.content
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}

/** The user's equaliser: any number of peaking or shelving bands, and a pre-amp. */
@Serializable
data class EqConfig(
    val enabled: Boolean = false,
    val preampDb: Float = 0f,
    val bands: List<EqBand> = emptyList(),
)

@Serializable
data class EqBand(
    val kind: FilterKind = FilterKind.PEAK,
    /** The centre of a peak, the corner of a shelf. */
    val freqHz: Float = 1000f,
    val gainDb: Float = 0f,
    val q: Float = StandardEqualizer.Q,
)

@Serializable
enum class FilterKind {
    @SerialName("peak") PEAK,
    @SerialName("lowShelf") LOW_SHELF,
    @SerialName("highShelf") HIGH_SHELF,
}

/**
 * Headphone correction. [peq] is AutoEq's `ParametricEQ.txt` (what Android stores per device), applied
 * filter by filter; with none, [bands] — exactly what the core's `autoEqBands` answers.
 */
@Serializable
data class AutoEqConfig(
    val enabled: Boolean = false,
    val peq: String = "",
    val bands: List<GraphicBand> = emptyList(),
)

/** One band of `autoEqBands`: a gain up to [cutoffHz]. */
@Serializable
data class GraphicBand(val cutoffHz: Float = 0f, val gainDb: Float = 0f)

/** How loud each track plays: normalisation (YouTube's loudness) and ReplayGain. */
@Serializable
data class LevelConfig(
    val normalize: Boolean = true,
    val replayGain: ReplayGainMode = ReplayGainMode.AUTO,
    val preampDb: Float = 4f,
)

/** Android's `LocalReplayGain.Mode`, by the same names. */
@Serializable
enum class ReplayGainMode {
    @SerialName("off") OFF,
    @SerialName("track") TRACK,
    @SerialName("album") ALBUM,
    @SerialName("auto") AUTO,
}

/** Android's stereo tools, plus a width (mid/side: 0 mono, 1 as recorded, 2 twice as wide). */
@Serializable
data class StereoConfig(
    /** -100 (left only) … 0 … 100 (right only). */
    val balance: Int = 0,
    val mono: Boolean = false,
    val crossfeed: Boolean = false,
    val voiceReduction: Boolean = false,
    val width: Float = 1f,
)

@Serializable
data class FadeConfig(
    /** The fade on pause and resume. */
    val pauseMs: Int = 0,
    /** Android's "fade between songs": each fades in at its start and out at its end. */
    val betweenSongsMs: Int = 0,
    /** A true crossfade into the preloaded song, which Android does not have. 0 is gapless. */
    val crossfadeMs: Int = 0,
)

/** A file's ReplayGain (`audio::replaygain`), when the caller has read it. */
@Serializable
data class TrackGains(
    val trackGainDb: Double? = null,
    val albumGainDb: Double? = null,
    val trackPeak: Double? = null,
    val albumPeak: Double? = null,
)

/**
 * The equaliser where there is no system one to ask: five bands and ten presets in the layout of
 * AOSP's bundled equaliser, the one most phones report to `android.media.audiofx.Equalizer`. So an
 * `eq_preset` index or an `eq_bands` list saved on a phone means the same curve here.
 */
object StandardEqualizer {
    const val Q = 0.96f
    const val MIN_DB = -15f
    const val MAX_DB = 15f

    val centresHz: List<Float> = listOf(60f, 230f, 910f, 3_600f, 14_000f)

    data class Preset(val name: String, val levelsDb: List<Float>)

    val presets: List<Preset> = listOf(
        Preset("Normal", listOf(3f, 0f, 0f, 0f, 3f)),
        Preset("Classical", listOf(5f, 3f, -2f, 4f, 4f)),
        Preset("Dance", listOf(6f, 0f, 2f, 4f, 1f)),
        Preset("Flat", listOf(0f, 0f, 0f, 0f, 0f)),
        Preset("Folk", listOf(3f, 0f, 0f, 2f, -1f)),
        Preset("Heavy Metal", listOf(4f, 1f, 9f, 3f, 0f)),
        Preset("Hip Hop", listOf(5f, 3f, 0f, 1f, 3f)),
        Preset("Jazz", listOf(4f, 2f, -2f, 2f, 5f)),
        Preset("Pop", listOf(-1f, 2f, 5f, 1f, -2f)),
        Preset("Rock", listOf(5f, 3f, -1f, 3f, 5f)),
    )

    /** The five bands at [levelsDb] (missing ones flat, each kept in range). */
    fun bands(levelsDb: List<Float>): List<EqBand> = centresHz.mapIndexed { i, hz ->
        EqBand(FilterKind.PEAK, hz, levelsDb.getOrElse(i) { 0f }.coerceIn(MIN_DB, MAX_DB), Q)
    }
}
