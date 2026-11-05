// sharedUi/src/commonMain/kotlin/com/varuna/rustify/player/AudioSettings.kt
package com.varuna.rustify.player

import com.varuna.rustify.bridge.NativeEngine
import com.varuna.rustify.bridge.RustifyJson
import com.varuna.rustify.engine.EffectsConfig
import com.varuna.rustify.engine.StandardEqualizer
import com.varuna.rustify.engine.StereoConfig
import com.varuna.rustify.platform.KeyValueStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.math.roundToInt

/** An AutoEq correction, as `autoeq_profiles` keeps it per output. */
@Serializable
data class AutoEqProfile(val name: String, val source: String, val path: String, val peq: String)

/** One headphone in AutoEq's index. */
@Serializable
data class AutoEqEntry(val name: String, val path: String, val source: String)

/** AutoEq's stored profiles and the core's index: what does not depend on how a curve is applied. */
object AutoEqProfiles {
    private val SERIALIZER = MapSerializer(String.serializer(), AutoEqProfile.serializer())

    fun all(prefs: KeyValueStore): Map<String, AutoEqProfile> = runCatching {
        val raw = prefs.getString(EffectsConfig.KEY_AUTOEQ_PROFILES) ?: return emptyMap()
        RustifyJson.decodeFromString(SERIALIZER, raw)
    }.getOrDefault(emptyMap())

    fun set(prefs: KeyValueStore, outputKey: String, profile: AutoEqProfile?) {
        val map = all(prefs).toMutableMap()
        if (profile == null) map.remove(outputKey) else map[outputKey] = profile
        prefs.putString(EffectsConfig.KEY_AUTOEQ_PROFILES, RustifyJson.encodeToString(SERIALIZER, map))
    }

    suspend fun search(query: String): List<AutoEqEntry> = runCatching {
        RustifyJson.decodeFromString(ListSerializer(AutoEqEntry.serializer()), NativeEngine.autoEqSearch(query))
    }.getOrDefault(emptyList())

    /** Downloads [entry]'s parametric EQ; null when it cannot be had. */
    suspend fun download(entry: AutoEqEntry): AutoEqProfile? = runCatching {
        val text = RustifyJson.decodeFromString(String.serializer(), NativeEngine.autoEqProfile(entry.path))
        AutoEqProfile(entry.name, entry.source, entry.path, text)
    }.getOrNull()
}

/**
 * What Settings' audio sections read and change, by the phone's keys. Where the engine plays, writing
 * the value is all it takes: the player watches the keys ([EffectsConfig.SETTINGS_KEYS]) and the
 * equaliser is the five bands of [StandardEqualizer]. Android's effects live on its audio session,
 * and its subclass goes through their controllers instead.
 */
open class AudioSettings(val prefs: KeyValueStore) {
    /** One band of the equaliser: its centre, its range and where it is, in millibels. */
    data class EqBand(val index: Int, val centerHz: Int, val minMb: Int, val maxMb: Int, val levelMb: Int)

    /** An output music can go to: the key its AutoEq profile is kept under and the name to show. */
    data class Output(val key: String, val name: String)

    /** An effect changed, for whatever has to decide something from it (Android: audio offload). */
    protected open fun effectsChanged() = Unit

    // ── Equaliser ───────────────────────────────────────────────────────────────────────────────

    open fun eqEnabled(): Boolean = prefs.getBoolean(EffectsConfig.KEY_EQ_ENABLED, false)

    open fun eqPreset(): Int = prefs.getInt(EffectsConfig.KEY_EQ_PRESET, 0)

    open fun eqPresets(): List<String> = StandardEqualizer.presets.map { it.name }

    open fun eqBands(): List<EqBand> {
        val config = EffectsConfig.fromSettings(prefs).eq
        val min = (StandardEqualizer.MIN_DB * 100).toInt()
        val max = (StandardEqualizer.MAX_DB * 100).toInt()
        return config.bands.mapIndexed { i, band ->
            EqBand(i, band.freqHz.toInt(), min, max, (band.gainDb * 100).roundToInt())
        }
    }

    open fun setEqEnabled(on: Boolean) = prefs.putBoolean(EffectsConfig.KEY_EQ_ENABLED, on)

    open fun setEqPreset(index: Int) = prefs.putInt(EffectsConfig.KEY_EQ_PRESET, index)

    /** Moving a band switches to a custom curve, starting from the levels currently applied. */
    open fun setEqBand(index: Int, levelMb: Int) {
        val levels = eqBands().map { it.levelMb }.toMutableList()
        if (index !in levels.indices) return
        levels[index] = levelMb
        // The curve first: the preset's write is what the player reacts to.
        prefs.putString(EffectsConfig.KEY_EQ_BANDS, levels.joinToString(","))
        prefs.putInt(EffectsConfig.KEY_EQ_PRESET, -1)
    }

    // ── AutoEq ──────────────────────────────────────────────────────────────────────────────────

    /** Asks the system which output music goes to now; [currentOutput] answers from then on. */
    open suspend fun refreshOutput() = Unit

    /** The output music goes to now, if a profile can be kept for it. */
    open fun currentOutput(): Output? = null

    open fun setAutoEqProfile(outputKey: String, profile: AutoEqProfile?) = AutoEqProfiles.set(prefs, outputKey, profile)

    // ── The rest of the effects ─────────────────────────────────────────────────────────────────

    open fun stereo(): StereoConfig = StereoConfig(
        balance = prefs.getInt(EffectsConfig.KEY_STEREO_BALANCE, 0),
        mono = prefs.getBoolean(EffectsConfig.KEY_STEREO_MONO, false),
        crossfeed = prefs.getBoolean(EffectsConfig.KEY_STEREO_CROSSFEED, false),
        voiceReduction = prefs.getBoolean(EffectsConfig.KEY_STEREO_VOICE, false),
    )

    open fun setStereo(s: StereoConfig) {
        prefs.putBoolean(EffectsConfig.KEY_STEREO_CROSSFEED, s.crossfeed)
        prefs.putInt(EffectsConfig.KEY_STEREO_BALANCE, s.balance.coerceIn(-100, 100))
        prefs.putBoolean(EffectsConfig.KEY_STEREO_MONO, s.mono)
        prefs.putBoolean(EffectsConfig.KEY_STEREO_VOICE, s.voiceReduction)
        effectsChanged()
    }

    fun fadeSeconds(): Int = prefs.getInt(EffectsConfig.KEY_FADE_BETWEEN, 0)

    fun setFadeSeconds(seconds: Int) {
        prefs.putInt(EffectsConfig.KEY_FADE_BETWEEN, seconds.coerceIn(0, EffectsConfig.MAX_FADE_BETWEEN_SECONDS))
        effectsChanged()
    }

    fun skipSilence(): Boolean = prefs.getBoolean(EffectsConfig.KEY_SKIP_SILENCE, false)

    fun setSkipSilence(on: Boolean) {
        prefs.putBoolean(EffectsConfig.KEY_SKIP_SILENCE, on)
        effectsChanged()
    }

    // ── How the queue moves ─────────────────────────────────────────────────────────────────────

    fun autoplayRadio(): Boolean = prefs.getBoolean(KEY_AUTOPLAY_RADIO, true)

    fun setAutoplayRadio(on: Boolean) = prefs.putBoolean(KEY_AUTOPLAY_RADIO, on)

    fun previousAlways(): Boolean = prefs.getBoolean(KEY_PREVIOUS_ALWAYS, false)

    fun setPreviousAlways(on: Boolean) = prefs.putBoolean(KEY_PREVIOUS_ALWAYS, on)

    companion object {
        const val KEY_AUTOPLAY_RADIO = "settings_autoplay_radio"
        const val KEY_PREVIOUS_ALWAYS = "previous_always_goes_back"
    }
}
