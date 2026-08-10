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
     * Candidate ways to reach the exemption, best first. The caller launches them in order until one
     * opens, because `resolveActivity` is not a reliable gate here: package-visibility rules and OEM
     * builds that reshuffle Settings both make it answer null for activities that do exist. Trying
     * and catching is the honest test.
     *
     *  1. The direct prompt — one tap, flips the flag itself.
     *  2. The full battery-optimization list, for builds that drop the direct prompt.
     *  3. The app's own settings page, which is where manufacturer battery controls live.
     */
    fun requestIntents(context: Context): List<Intent> = listOf(
        Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        ),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )
    )

    /**
     * True on manufacturers whose battery management is **separate from Android's** exemption list —
     * Xiaomi/Redmi/POCO (MIUI, HyperOS) being the case in hand, plus the other usual offenders.
     *
     * This matters because [isExempt] can keep reporting false after the user has correctly set the
     * manufacturer's own control to "no restrictions": the two are different switches, and only the
     * Android one is readable. Without saying so, the app looks broken and the user has no way to
     * tell they already did the right thing.
     */
    fun hasVendorBatteryLayer(): Boolean {
        val vendor = (android.os.Build.MANUFACTURER + " " + android.os.Build.BRAND).lowercase()
        return listOf("xiaomi", "redmi", "poco", "huawei", "honor", "oppo", "realme", "vivo", "oneplus")
            .any { it in vendor }
    }
}
