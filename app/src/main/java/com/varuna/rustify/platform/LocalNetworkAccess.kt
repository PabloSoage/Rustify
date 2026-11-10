package com.varuna.rustify.platform

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
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

    /**
     * Opens the application's details settings page in the Android system settings.
     */
    fun openAppSettings(context: Context) {
        runCatching {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    /**
     * A Composable launcher for requesting local network permission.
     */
    @Composable
    fun rememberLauncher(onResult: (Boolean) -> Unit): () -> Unit {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            onResult(granted)
        }
        return {
            launcher.launch(PERMISSION)
        }
    }
}
