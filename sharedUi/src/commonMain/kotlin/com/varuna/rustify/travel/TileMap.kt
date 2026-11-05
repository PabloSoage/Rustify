package com.varuna.rustify.travel

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.varuna.rustify.platform.httpClient
import com.varuna.rustify.ui.LocalAppPlatform
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

/**
 * A slippy map in Compose: raster tiles ([TravelSettings.RASTER_TILES]) in Web Mercator, dragged,
 * pinched, zoomed with the wheel or a double tap, and long-pressed (or right-clicked) to place a
 * point. What MapLibre is on Android, for the platforms that have no MapLibre; it does not rotate.
 *
 * Tiles are asked for only as they come into view, kept in memory while the screen is open and
 * never prefetched, with the app's own User-Agent: what the OpenStreetMap tile policy asks of an app.
 */
@Composable
fun TileMap(
    modifier: Modifier,
    styleIndex: Int,
    marks: TravelMarks,
    camera: TravelCamera,
    onLongPress: (lat: Double, lon: Double) -> Unit,
    onUserMove: () -> Unit
) {
    val prefs = LocalAppPlatform.current.prefs()
    val tiles = remember(styleIndex) { TravelSettings.rasterTiles(prefs, styleIndex) }
    val dark = styleIndex == TravelSettings.STYLE_DARK_INDEX
    val view = remember { MapView(MapView.x(-3.7), MapView.y(40.0), 5.0) }
    val cache = remember(tiles) { TileCache(tiles) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val tilePx = with(density) { TILE_DP.dp.toPx() }
    val longPress by rememberUpdatedState(onLongPress)
    val userMove by rememberUpdatedState(onUserMove)

    LaunchedEffect(view.zoom) { camera.zoom = view.zoom }
    LaunchedEffect(camera) {
        for (move in camera.moves) when (move) {
            is TravelCamera.Move.North -> camera.bearing = 0.0
            is TravelCamera.Move.To -> {
                val toX = MapView.x(move.lon)
                val toY = MapView.y(move.lat)
                val toZ = (move.zoom ?: view.zoom).coerceIn(MIN_ZOOM, (tiles.maxZoom + OVERZOOM).toDouble())
                if (!move.animate) view.set(toX, toY, toZ)
                else {
                    val fromX = view.x
                    val fromY = view.y
                    val fromZ = view.zoom
                    animate(0f, 1f, animationSpec = tween(600)) { t, _ ->
                        view.set(fromX + (toX - fromX) * t, fromY + (toY - fromY) * t, fromZ + (toZ - fromZ) * t)
                    }
                }
            }
        }
    }

    Box(
        modifier
            .background(if (dark) Color(0xFF1B1B1D) else Color(0xFFE8E4DC))
            .pointerInput(tiles) {
                detectTransformGestures { centroid, pan, zoomChange, _ ->
                    userMove()
                    view.pan(-pan.x, -pan.y, tilePx)
                    if (zoomChange != 1f) view.zoomAround(centroid, ln(zoomChange.toDouble()) / ln(2.0), size.width, size.height, tilePx, tiles.maxZoom)
                }
            }
            .pointerInput(tiles) {
                detectTapGestures(
                    onDoubleTap = { at ->
                        userMove()
                        scope.launch {
                            val from = view.zoom
                            animate(0f, 1f, animationSpec = tween(250)) { t, _ ->
                                view.zoomAround(at, from + t - view.zoom, size.width, size.height, tilePx, tiles.maxZoom)
                            }
                        }
                    },
                    onLongPress = { at ->
                        val (lat, lon) = view.latLonAt(at, size.width, size.height, tilePx)
                        longPress(lat, lon)
                    }
                )
            }
            .pointerInput(tiles) {
                // The wheel zooms where the pointer is; the right button places a point, as a long press.
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        when {
                            event.type == PointerEventType.Scroll -> {
                                userMove()
                                // A notch is 1 on the desktop and ~100 pixels in a browser: half a level per event either way.
                                val notch = change.scrollDelta.y.coerceIn(-1f, 1f)
                                view.zoomAround(change.position, -notch / 2.0, size.width, size.height, tilePx, tiles.maxZoom)
                                change.consume()
                            }
                            event.type == PointerEventType.Press && event.buttons.isSecondaryPressed -> {
                                val (lat, lon) = view.latLonAt(change.position, size.width, size.height, tilePx)
                                longPress(lat, lon)
                                change.consume()
                            }
                        }
                    }
                }
            }
    ) {
        Canvas(Modifier.matchParentSize()) {
            drawTiles(view, cache, tilePx, if (dark) INVERT else null) { z, x, y -> scope.launch { cache.load(z, x, y) } }
            drawMarks(view, marks, tilePx)
        }
    }
}

private const val TILE_DP = 256f
private const val MIN_ZOOM = 2.0

/** How far past a source's deepest tiles the map still zooms, stretching them. */
private const val OVERZOOM = 2

/** Night from the streets' tiles: their colours inverted. */
private val INVERT = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f
        )
    )
)

/**
 * Where the map looks: the centre in Web Mercator's unit square ([x] east, [y] south, both 0..1)
 * and the zoom, where the world is `256 dp × 2^zoom` wide.
 */
internal class MapView(x: Double, y: Double, zoom: Double) {
    var x by mutableDoubleStateOf(x)
        private set
    var y by mutableDoubleStateOf(y)
        private set
    var zoom by mutableDoubleStateOf(zoom)
        private set

    fun set(x: Double, y: Double, zoom: Double) {
        this.x = x - floor(x)
        this.y = y.coerceIn(0.0, 1.0)
        this.zoom = zoom
    }

    fun worldPx(tilePx: Float): Double = tilePx * 2.0.pow(zoom)

    /** Moves the centre by [dx], [dy] pixels. */
    fun pan(dx: Float, dy: Float, tilePx: Float) {
        val world = worldPx(tilePx)
        set(x + dx / world, y + dy / world, zoom)
    }

    /** Zooms by [delta] levels keeping the point under [at] where it is on screen. */
    fun zoomAround(at: Offset, delta: Double, width: Int, height: Int, tilePx: Float, maxZoom: Int) {
        val newZoom = (zoom + delta).coerceIn(MIN_ZOOM, (maxZoom + OVERZOOM).toDouble())
        if (newZoom == zoom) return
        val before = worldPx(tilePx)
        val ax = x + (at.x - width / 2.0) / before
        val ay = y + (at.y - height / 2.0) / before
        val after = tilePx * 2.0.pow(newZoom)
        set(ax - (at.x - width / 2.0) / after, ay - (at.y - height / 2.0) / after, newZoom)
    }

    fun latLonAt(at: Offset, width: Int, height: Int, tilePx: Float): Pair<Double, Double> {
        val world = worldPx(tilePx)
        val px = x + (at.x - width / 2.0) / world
        val py = y + (at.y - height / 2.0) / world
        return lat(py) to lon(px - floor(px))
    }

    /** Where (lat, lon) is on a screen [width] × [height], the nearest copy of the world to the centre. */
    fun screenOf(lat: Double, lon: Double, width: Float, height: Float, tilePx: Float): Offset {
        val world = worldPx(tilePx)
        var dx = x(lon) - x
        if (dx > 0.5) dx -= 1.0
        if (dx < -0.5) dx += 1.0
        return Offset((width / 2 + dx * world).toFloat(), (height / 2 + (y(lat) - y) * world).toFloat())
    }

    companion object {
        fun x(lon: Double): Double = (lon + 180.0) / 360.0

        fun y(lat: Double): Double {
            val rad = lat.coerceIn(-85.05112878, 85.05112878) * PI / 180.0
            return (1.0 - ln(tan(rad) + 1.0 / kotlin.math.cos(rad)) / PI) / 2.0
        }

        fun lon(x: Double): Double = x * 360.0 - 180.0

        fun lat(y: Double): Double = atan(sinh(PI * (1.0 - 2.0 * y))) * 180.0 / PI
    }
}

/** The tiles fetched while the map is open, the most recent [CAPACITY]; failures are not asked again. */
internal class TileCache(private val tiles: TravelSettings.RasterTiles) {
    /** The deepest level the source has, past which its tiles are stretched. */
    val maxZoom: Int get() = tiles.maxZoom

    private val images = mutableStateMapOf<Long, ImageBitmap>()
    private val order = ArrayDeque<Long>()
    private val asked = HashSet<Long>()
    private val gate = Semaphore(6)

    operator fun get(z: Int, x: Int, y: Int): ImageBitmap? = images[key(z, x, y)]

    fun wanted(z: Int, x: Int, y: Int): Boolean = key(z, x, y) !in asked

    @OptIn(ExperimentalResourceApi::class)
    suspend fun load(z: Int, x: Int, y: Int) {
        val k = key(z, x, y)
        if (!asked.add(k)) return
        val image = gate.withPermit {
            runCatching {
                val response = http.get(tiles.url(z, x, y)) { header(HttpHeaders.UserAgent, TravelRouting.USER_AGENT) }
                if (response.status.isSuccess()) response.readRawBytes().decodeToImageBitmap() else null
            }.getOrNull()
        } ?: return
        images[k] = image
        order.addLast(k)
        while (order.size > CAPACITY) {
            val old = order.removeFirst()
            images.remove(old)
            asked.remove(old)
        }
    }

    private fun key(z: Int, x: Int, y: Int): Long = (z.toLong() shl 50) or (x.toLong() shl 25) or y.toLong()

    companion object {
        const val CAPACITY = 400

        private val http by lazy {
            httpClient {
                install(HttpTimeout) {
                    connectTimeoutMillis = 10_000
                    socketTimeoutMillis = 15_000
                }
            }
        }
    }
}

/** The tiles in view at the zoom's level; one not here yet is drawn from a coarser one already in. */
private fun DrawScope.drawTiles(
    view: MapView,
    cache: TileCache,
    tilePx: Float,
    filter: ColorFilter?,
    fetch: (z: Int, x: Int, y: Int) -> Unit
) {
    val z = floor(view.zoom).toInt().coerceAtMost(cache.maxZoom).coerceAtLeast(0)
    val n = 1 shl z
    val world = view.worldPx(tilePx)
    val ts = world / n
    val left = view.x * world - size.width / 2
    val top = view.y * world - size.height / 2
    val firstX = floor(left / ts).toInt()
    val lastX = floor((left + size.width) / ts).toInt()
    val firstY = floor(top / ts).toInt().coerceAtLeast(0)
    val lastY = floor((top + size.height) / ts).toInt().coerceAtMost(n - 1)
    val side = kotlin.math.ceil(ts).toInt() + 1
    for (ty in firstY..lastY) for (tx in firstX..lastX) {
        val wx = ((tx % n) + n) % n
        val dst = IntOffset((tx * ts - left).toInt(), (ty * ts - top).toInt())
        val image = cache[z, wx, ty]
        if (image != null) {
            drawImage(image, dstOffset = dst, dstSize = IntSize(side, side), colorFilter = filter)
            continue
        }
        if (cache.wanted(z, wx, ty)) fetch(z, wx, ty)
        // A coarser tile, the part of it this one is.
        for (up in 1..4) {
            val pz = z - up
            if (pz < 0) break
            val parent = cache[pz, wx shr up, ty shr up] ?: continue
            val part = parent.width / (1 shl up)
            val sx = (wx and ((1 shl up) - 1)) * part
            val sy = (ty and ((1 shl up) - 1)) * part
            drawImage(
                parent, srcOffset = IntOffset(sx, sy), srcSize = IntSize(part, part),
                dstOffset = dst, dstSize = IntSize(side, side), colorFilter = filter
            )
            break
        }
    }
}

/** Route under the points, as MapLibre's layers are ordered on Android, and the same colours and sizes. */
private fun DrawScope.drawMarks(view: MapView, marks: TravelMarks, tilePx: Float) {
    val points = marks.route?.points.orEmpty()
    if (points.size >= 2) {
        val path = Path()
        points.forEachIndexed { i, (lat, lon) ->
            val p = view.screenOf(lat, lon, size.width, size.height, tilePx)
            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        drawPath(
            path, Color(0xFF1DB954).copy(alpha = 0.85f),
            style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
    fun point(geo: TravelRouting.Geo?, color: Color, radius: Float) {
        geo ?: return
        val p = view.screenOf(geo.lat, geo.lon, size.width, size.height, tilePx)
        drawCircle(Color.White, radius = (radius + 2).dp.toPx(), center = p)
        drawCircle(color, radius = radius.dp.toPx(), center = p)
    }
    point(marks.user, Color(0xFF1DB954).copy(alpha = 0.95f), 8f)
    point(marks.destination, Color(0xFFE53935), 10f)
    point(marks.origin, Color(0xFF2196F3), 10f)
}
