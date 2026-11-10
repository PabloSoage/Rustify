package com.varuna.rustify.platform

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Access to the local network (Android 17+ / API 37).
 *
 * Android 17 gates raw socket traffic, mDNS/SSDP discovery and incoming/outgoing connections
 * to local network addresses behind `android.permission.ACCESS_LOCAL_NETWORK` (group NEARBY_DEVICES).
 * On Android < 17, the permission does not exist and standard INTERNET covers local network.
 */
object LocalNetworkAccess {
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    /**
     * Whether the platform requires explicit local network permission.
     * Pure function of [sdkInt] for testability on the JVM.
     */
    fun needed(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt >= 37

    /**
     * Whether the permission is granted. On Android < 37, always true.
     */
    fun isGranted(isGrantedCheck: () -> Boolean, sdkInt: Int = Build.VERSION.SDK_INT): Boolean {
        if (!needed(sdkInt)) return true
        return isGrantedCheck()
    }

    /**
     * Whether [context] has permission to access the local network.
     */
    fun granted(context: Context): Boolean {
        return isGranted(
            isGrantedCheck = {
                ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    /**
     * Whether a permission request should be initiated for a feature that uses the local network.
     * Pure function for testing.
     */
    fun shouldRequest(
        actionUsesLocalNetwork: Boolean,
        isGranted: Boolean,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): Boolean {
        if (!needed(sdkInt)) return false
        if (!actionUsesLocalNetwork) return false
        return !isGranted
    }

    // The request itself is MainActivity's launcher (AndroidAppPlatform.ensureLocalNetwork), and
    // the settings page is AndroidAppPlatform.openAppSettings: one of each, nothing here.
}
