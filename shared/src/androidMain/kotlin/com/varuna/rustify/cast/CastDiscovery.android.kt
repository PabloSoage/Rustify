// shared/src/androidMain/kotlin/com/varuna/rustify/cast/CastDiscovery.android.kt
package com.varuna.rustify.cast

import android.content.Context
import android.net.wifi.WifiManager

suspend fun CastDiscovery.search(context: Context): List<CastDiscovery.Device> = search {
    val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    val lock = wifi?.createMulticastLock("rustify-ssdp")?.apply {
        setReferenceCounted(true)
        runCatching { acquire() }
    }
    AutoCloseable { runCatching { lock?.release() } }
}
