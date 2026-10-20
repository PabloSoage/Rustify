package com.varuna.rustify.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Whether the device has a network that claims to reach the internet.
 *
 * For wording only — "No connection" instead of the engine's `error sending request for url (…)` —
 * never for deciding whether to try: a captive portal or a dead VPN passes this check and still
 * fails, so the request is what tells the truth.
 */
object Connectivity {
    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
