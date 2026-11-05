package com.varuna.rustify.travel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Travel in the browser: the shared [TileMap], and the browser's own location where it gives one —
 * only to a secure page (https, or the server opened as localhost); over plain http on the LAN there
 * is none, and the origin is picked by hand as on the desktop.
 */
object WebTravelPlatform : TravelPlatform {
    @Composable
    override fun rememberLocation(): TravelLocation {
        if (!geolocationAvailable()) return TravelLocation.None
        val location = remember { WebTravelLocation() }
        DisposableEffect(location) {
            location.watch()
            onDispose { location.unwatch() }
        }
        return location
    }
}

private class WebTravelLocation : TravelLocation {
    private var watchId = -1

    override val supported = true
    override var hasPermission by mutableStateOf(true)
    // The browser has no system switch to read: a refusal or no fix is what turns this off.
    override var enabled by mutableStateOf(true)
    override var current by mutableStateOf<TravelRouting.Geo?>(null)

    fun watch() {
        if (watchId >= 0) return
        watchId = watchPosition(
            { lat, lon ->
                hasPermission = true
                enabled = true
                current = TravelRouting.Geo(lat, lon, "")
            },
            { code ->
                // 1 = PERMISSION_DENIED; 2 and 3 are no fix and a timeout.
                if (code == 1) hasPermission = false
                enabled = false
            }
        )
    }

    fun unwatch() {
        if (watchId >= 0) clearWatch(watchId)
        watchId = -1
    }

    // Asking again is the only thing a page can do; a refusal stays until the site's settings change.
    override fun requestPermission() {
        unwatch()
        watch()
    }

    override fun openSettings() = requestPermission()
}

private fun geolocationAvailable(): Boolean = js("window.isSecureContext === true && 'geolocation' in navigator")

private fun watchPosition(onFix: (Double, Double) -> Unit, onError: (Int) -> Unit): Int =
    js("navigator.geolocation.watchPosition(p => onFix(p.coords.latitude, p.coords.longitude), e => onError(e.code), { enableHighAccuracy: true, maximumAge: 10000 })")

private fun clearWatch(id: Int) {
    js("navigator.geolocation.clearWatch(id)")
}
