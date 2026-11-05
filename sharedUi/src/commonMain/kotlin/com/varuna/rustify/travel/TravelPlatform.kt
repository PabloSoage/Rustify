package com.varuna.rustify.travel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.varuna.rustify.ui.AppPlatform
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * What the Travel screen needs from the platform: the map, the device's location and a way out to
 * a navigation app. Android's are MapLibre, `LocationManager` and Google Maps' intents, as they
 * always were; the rest draw [TileMap] and say where they have no location, so the origin is picked
 * by hand instead (search or a long press, as on the phone).
 */
interface TravelPlatform {
    /**
     * The map, full size, showing [marks] in the style [styleIndex] (an index of
     * [TravelSettings.MAP_STYLES]) and following [camera]. [onLongPress] gets the point pressed;
     * [onUserMove] is called when the person moves the map, so the screen stops following them.
     */
    @Composable
    fun Map(
        modifier: Modifier,
        styleIndex: Int,
        marks: TravelMarks,
        camera: TravelCamera,
        onLongPress: (lat: Double, lon: Double) -> Unit,
        onUserMove: () -> Unit
    ) = TileMap(modifier, styleIndex, marks, camera, onLongPress, onUserMove)

    /** The device's location, remembered by the screen; [TravelLocation.None] where there is none. */
    @Composable
    fun rememberLocation(): TravelLocation = TravelLocation.None

    /** Opens the route [url] (Google Maps' directions link) to drive by; [destLat]/[destLon] for apps that take only a point. */
    fun navigate(url: String, destLat: Double, destLon: Double, platform: AppPlatform) {
        platform.openUrl(url)
    }

    /** Who the map's tiles are from, written over the map. */
    val attribution: String get() = "© OpenStreetMap · Esri · OpenTopoMap"

    /** [TileMap], no location, the browser for directions: the desktop and the web. */
    object Default : TravelPlatform
}

/** What the map shows: where the person is, the origin they fixed, the destination and the route. */
data class TravelMarks(
    val user: TravelRouting.Geo? = null,
    val origin: TravelRouting.Geo? = null,
    val destination: TravelRouting.Geo? = null,
    val route: TravelRouting.Route? = null
)

/**
 * Where the screen wants the map to look, and what the map reports back: its [zoom] and, on maps
 * that rotate, its [bearing]. Moves queue until a map takes them, so one asked for before the map
 * is ready is not lost.
 */
class TravelCamera {
    sealed interface Move {
        /** To [lat], [lon] at [zoom], or at the zoom it has when null. */
        data class To(val lat: Double, val lon: Double, val zoom: Double?, val animate: Boolean) : Move

        /** North up again. */
        data object North : Move
    }

    private val queue = Channel<Move>(Channel.UNLIMITED)

    /** The moves asked for, for the map to take in order. */
    val moves: ReceiveChannel<Move> get() = queue

    var zoom by mutableDoubleStateOf(5.0)
    var bearing by mutableDoubleStateOf(0.0)

    fun animateTo(lat: Double, lon: Double, zoom: Double? = null) {
        queue.trySend(Move.To(lat, lon, zoom, animate = true))
    }

    fun moveTo(lat: Double, lon: Double, zoom: Double? = null) {
        queue.trySend(Move.To(lat, lon, zoom, animate = false))
    }

    fun north() {
        queue.trySend(Move.North)
    }
}

/** The device's location as the Travel screen reads it, kept current while the screen is open. */
interface TravelLocation {
    /** False where there is no location at all (the desktop, the web): the origin is picked by hand. */
    val supported: Boolean

    val hasPermission: Boolean

    /** The system's location switch is on: what the screen's "turn it on" card is about. */
    val enabled: Boolean

    val current: TravelRouting.Geo?

    fun requestPermission()

    /** The system's location settings, to turn location on. */
    fun openSettings()

    object None : TravelLocation {
        override val supported = false
        override val hasPermission = false
        override val enabled = false
        override val current: TravelRouting.Geo? = null
        override fun requestPermission() {}
        override fun openSettings() {}
    }
}
