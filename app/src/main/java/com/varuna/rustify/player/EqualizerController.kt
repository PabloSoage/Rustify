package com.varuna.rustify.player

import android.content.Context
import android.media.audiofx.Equalizer

/**
 * The system equaliser on the player's audio session (4.4).
 *
 * The bands and presets are the device's own (`android.media.audiofx.Equalizer`), so they differ
 * between phones; what is stored is the preset index or the level per band, and re-applied whenever
 * the player gets a new audio session. Off by default: an equaliser nobody asked for changes how
 * every song sounds.
 */
object EqualizerController {
    private const val PREFS = "rustify_settings"
    private const val KEY_ENABLED = "eq_enabled"
    private const val KEY_PRESET = "eq_preset"   // -1 = custom bands
    private const val KEY_BANDS = "eq_bands"     // comma-separated millibels

    data class Band(val index: Int, val centerHz: Int, val minMb: Int, val maxMb: Int, val levelMb: Int)

    private var eq: Equalizer? = null
    private var session = 0

    /** Binds to [sessionId]; a no-op for the session already bound. Never throws. */
    @Synchronized
    fun attach(context: Context, sessionId: Int) {
        if (sessionId == 0 || (sessionId == session && eq != null)) return
        release()
        runCatching {
            eq = Equalizer(0, sessionId)
            session = sessionId
            apply(context)
        }.onFailure { android.util.Log.w("Equalizer", "not available on session $sessionId: ${it.message}") }
    }

    @Synchronized
    fun release() {
        runCatching { eq?.release() }
        eq = null
        session = 0
    }

    fun isEnabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, false)

    fun presetIndex(context: Context) = prefs(context).getInt(KEY_PRESET, 0)

    /** The device's presets, by name. Empty when no equaliser is bound yet. */
    @Synchronized
    fun presets(): List<String> = eq?.let { e ->
        (0 until e.numberOfPresets).map { runCatching { e.getPresetName(it.toShort()) }.getOrDefault("#$it") }
    }.orEmpty()

    @Synchronized
    fun bands(): List<Band> = eq?.let { e ->
        val range = e.bandLevelRange
        (0 until e.numberOfBands).map { i ->
            Band(i, e.getCenterFreq(i.toShort()) / 1000, range[0].toInt(), range[1].toInt(), e.getBandLevel(i.toShort()).toInt())
        }
    }.orEmpty()

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()
        apply(context)
        // The equaliser needs the PCM in the app, so offload has to step aside now, not next song.
        AudioPlayerService.instance?.applyAudioOffload()
    }

    fun setPreset(context: Context, index: Int) {
        prefs(context).edit().putInt(KEY_PRESET, index).apply()
        apply(context)
    }

    /** Moving a band switches to a custom curve, starting from the levels currently applied. */
    @Synchronized
    fun setBand(context: Context, index: Int, levelMb: Int) {
        val levels = bands().map { it.levelMb }.toMutableList()
        if (index !in levels.indices) return
        levels[index] = levelMb
        prefs(context).edit().putInt(KEY_PRESET, -1).putString(KEY_BANDS, levels.joinToString(",")).apply()
        apply(context)
    }

    @Synchronized
    private fun apply(context: Context) {
        val e = eq ?: return
        runCatching {
            val p = prefs(context)
            e.enabled = p.getBoolean(KEY_ENABLED, false)
            val preset = p.getInt(KEY_PRESET, 0)
            if (preset >= 0 && preset < e.numberOfPresets) {
                e.usePreset(preset.toShort())
            } else {
                p.getString(KEY_BANDS, null)?.split(",")?.mapNotNull { it.toIntOrNull() }?.forEachIndexed { i, mb ->
                    if (i < e.numberOfBands) e.setBandLevel(i.toShort(), mb.toShort())
                }
            }
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
