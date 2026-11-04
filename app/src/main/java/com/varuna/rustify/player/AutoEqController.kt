package com.varuna.rustify.player

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.DynamicsProcessing
import com.varuna.rustify.bridge.NativeEngine
import com.varuna.rustify.bridge.RustifyJson
import com.varuna.rustify.ui.AndroidAppPlatform
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * AutoEq per output device (`21`, 5.x §5): each pair of headphones or earbuds gets its own correction
 * curve from AutoEq, applied with `DynamicsProcessing` on the player's session and switched when the
 * output changes — the earbuds in the morning, the car in the afternoon, nothing on the speaker.
 *
 * The curve is computed by the core (`autoeq.rs`) as [BANDS] bands with AutoEq's own pre-amp, so a
 * boost cannot clip. Like the equaliser, an effect on the session needs the CPU, so offload is off
 * while a profile is active ([AudioOffload.Blocker.AUTOEQ]).
 */
object AutoEqController {
    const val BANDS = 32

    @Serializable
    private data class Band(val cutoffHz: Float, val gainDb: Float)

    private var dp: DynamicsProcessing? = null
    private var session = 0
    @Volatile private var active = false

    /** Whether a profile is being applied right now (for the offload decision). */
    fun isActive(): Boolean = active

    /** A stable name for an output: its type and the product name it reports. */
    fun keyOf(device: AudioDeviceInfo): String = "${device.type}:${device.productName}"

    /** The output music goes to now: a headset or USB or Bluetooth device before the speaker. */
    fun currentOutput(context: Context): AudioDeviceInfo? {
        val am = context.getSystemService(AudioManager::class.java) ?: return null
        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val preferred = listOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
        )
        return preferred.firstNotNullOfOrNull { t -> outputs.firstOrNull { it.type == t } }
    }

    fun profiles(context: Context): Map<String, AutoEqProfile> = AutoEqProfiles.all(AndroidAppPlatform(context).prefs())

    fun setProfile(context: Context, deviceKey: String, profile: AutoEqProfile?) {
        AutoEqProfiles.set(AndroidAppPlatform(context).prefs(), deviceKey, profile)
        refresh(context)
    }

    /** Binds to the player's [sessionId] and applies the profile of the current output. */
    @Synchronized
    fun attach(context: Context, sessionId: Int) {
        if (sessionId == 0 || sessionId == session) return
        release()
        session = sessionId
        refresh(context)
    }

    @Synchronized
    fun release() {
        runCatching { dp?.release() }
        dp = null
        session = 0
        active = false
    }

    /** The output or the profiles changed: apply the right curve, or none. */
    @Synchronized
    fun refresh(context: Context) {
        if (session == 0) return
        val device = currentOutput(context)
        val profile = device?.let { profiles(context)[keyOf(it)] }
        val bands = profile?.let {
            runCatching {
                RustifyJson.decodeFromString(ListSerializer(Band.serializer()), NativeEngine.autoEqBands(it.peq, BANDS))
            }.getOrNull()
        }
        if (bands.isNullOrEmpty()) {
            runCatching { dp?.enabled = false }
            active = false
        } else {
            runCatching {
                val effect = dp ?: DynamicsProcessing(
                    0, session,
                    DynamicsProcessing.Config.Builder(
                        DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                        true, BANDS, false, 0, false, 0, false
                    ).build()
                ).also { dp = it }
                effect.setPreEqAllChannelsTo(DynamicsProcessing.Eq(true, true, BANDS))
                bands.forEachIndexed { i, b ->
                    effect.setPreEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, b.cutoffHz, b.gainDb))
                }
                effect.enabled = true
                active = true
                android.util.Log.i("AutoEq", "${profile.name} on ${device.productName}")
            }.onFailure {
                android.util.Log.w("AutoEq", "could not apply: ${it.message}")
                active = false
            }
        }
        AudioPlayerService.instance?.applyAudioOffload()
    }
}
