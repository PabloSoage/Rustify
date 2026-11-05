package com.varuna.rustify.travel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.useContents
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLLocationAccuracyNearestTenMeters
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.darwin.NSObject

/**
 * Travel on iOS: the shared [TileMap], and Core Location while the screen is open. The question is
 * asked on entry, as iOS apps do, once; after a refusal the button opens the app's settings.
 */
object IosTravelPlatform : TravelPlatform {
    @Composable
    override fun rememberLocation(): TravelLocation {
        val location = remember { IosTravelLocation() }
        DisposableEffect(location) {
            location.start()
            onDispose { location.stop() }
        }
        return location
    }
}

@OptIn(ExperimentalForeignApi::class)
private class IosTravelLocation : TravelLocation {
    private val manager = CLLocationManager()
    private val delegate = Delegate(this)

    override val supported = true
    override var hasPermission by mutableStateOf(false)
    override var enabled by mutableStateOf(CLLocationManager.locationServicesEnabled())
    override var current by mutableStateOf<TravelRouting.Geo?>(null)

    fun start() {
        manager.delegate = delegate
        manager.desiredAccuracy = kCLLocationAccuracyNearestTenMeters
        manager.distanceFilter = 2.0
        authorizationChanged()
        if (manager.authorizationStatus == kCLAuthorizationStatusNotDetermined) manager.requestWhenInUseAuthorization()
    }

    fun stop() {
        manager.stopUpdatingLocation()
        manager.delegate = null
    }

    fun authorizationChanged() {
        val status = manager.authorizationStatus
        hasPermission = status == kCLAuthorizationStatusAuthorizedWhenInUse || status == kCLAuthorizationStatusAuthorizedAlways
        enabled = CLLocationManager.locationServicesEnabled()
        if (hasPermission) manager.startUpdatingLocation() else manager.stopUpdatingLocation()
    }

    fun moved(location: CLLocation) {
        location.coordinate.useContents { current = TravelRouting.Geo(latitude, longitude, "") }
    }

    override fun requestPermission() {
        if (manager.authorizationStatus == kCLAuthorizationStatusNotDetermined) manager.requestWhenInUseAuthorization()
        else openSettings()
    }

    // iOS has no page for the location switch an app may open; the app's own settings lead to it.
    override fun openSettings() {
        val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
        UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
    }

    private class Delegate(private val owner: IosTravelLocation) : NSObject(), CLLocationManagerDelegateProtocol {
        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) = owner.authorizationChanged()

        @ObjCSignatureOverride
        override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
            (didUpdateLocations.lastOrNull() as? CLLocation)?.let(owner::moved)
        }

        @ObjCSignatureOverride
        override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {}
    }
}
