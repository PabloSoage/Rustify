package com.varuna.rustify.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Doze exemption.
 *
 * Once the device enters Doze — screen off, unplugged, stationary and unused for a while — Android
 * applies these restrictions, verbatim from its documentation:
 *
 * > * Suspends network access.
 * > * Ignores wake locks.
 *
 * Both of those are precisely what advancing to the next track needs: the stream URL is resolved over
 * the network, and the player holds a wake lock while doing it. So in Doze playback stops at a track
 * boundary and only resumes when the device is woken. **A wake lock cannot fix this** — the system
 * ignores it — and neither can a foreground service, which is not an exemption.
 *
 * The one thing that does work is the exemption list:
 *
 * > An app that is partially exempt can use the network and hold partial wake locks during Doze and
 * > App Standby.
 *
 * Google Play forbids asking for this except when "the core function of the app is adversely
 * affected". A music player that stops between songs is that case, and Rustify is distributed as an
 * APK rather than through Play regardless.
 *
 * Worth surfacing rather than asking for once and forgetting: reinstalling the app clears the
 * exemption, and Rustify is reinstalled on every release.
 */
object BatteryOptimization {

    /** True when Android will let the app use the network and hold wake locks during Doze. */
    fun isExempt(context: Context): Boolean = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(false)

    /**
     * Opens the system prompt that adds the app to the exemption list. Falls back to the full
     * battery-optimization settings list if the direct prompt is unavailable — some OEM builds
     * remove it — so the user always has a way through.
     *
     * Returns the intent to launch, or null if neither is resolvable.
     */
    fun requestIntent(context: Context): Intent? {
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        )
        if (direct.resolveActivity(context.packageManager) != null) return direct

        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        return if (list.resolveActivity(context.packageManager) != null) list else null
    }
}
