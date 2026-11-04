package com.varuna.rustify.player

import android.content.Context
import com.varuna.rustify.engine.StereoConfig
import com.varuna.rustify.ui.AndroidAppPlatform

/**
 * Settings' audio sections over the phone's own effects: the system equaliser and AutoEq are bound to
 * the player's audio session, the stereo tools' values are read on the audio thread, and each of them
 * decides audio offload.
 */
class AndroidAudioSettings(context: Context) : AudioSettings(AndroidAppPlatform(context).prefs()) {
    private val app = context.applicationContext

    override fun effectsChanged() {
        AudioPlayerService.instance?.applyAudioOffload()
    }

    override fun eqEnabled() = EqualizerController.isEnabled(app)
    override fun eqPreset() = EqualizerController.presetIndex(app)
    override fun eqPresets() = EqualizerController.presets()
    override fun eqBands() = EqualizerController.bands().map { EqBand(it.index, it.centerHz, it.minMb, it.maxMb, it.levelMb) }
    override fun setEqEnabled(on: Boolean) = EqualizerController.setEnabled(app, on)
    override fun setEqPreset(index: Int) = EqualizerController.setPreset(app, index)
    override fun setEqBand(index: Int, levelMb: Int) = EqualizerController.setBand(app, index, levelMb)

    override fun currentOutput(): Output? =
        AutoEqController.currentOutput(app)?.let { Output(AutoEqController.keyOf(it), it.productName.toString()) }

    override fun setAutoEqProfile(outputKey: String, profile: AutoEqProfile?) = AutoEqController.setProfile(app, outputKey, profile)

    override fun stereo(): StereoConfig {
        val s = StereoTools.load(app)
        return StereoConfig(balance = s.balance, mono = s.mono, crossfeed = s.crossfeed, voiceReduction = s.voiceReduction)
    }

    override fun setStereo(s: StereoConfig) {
        StereoTools.save(app, StereoTools.Settings(s.crossfeed, s.balance, s.mono, s.voiceReduction))
        effectsChanged()
    }
}
