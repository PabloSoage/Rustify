package com.varuna.rustify.webplayer

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.varuna.rustify.R
import kotlin.math.abs

/**
 * Screen hosting Spotify's web player.
 *
 * The WebView itself lives in [WebPlayerController], not here: this screen only attaches and detaches
 * it, so navigating away does not stop playback. Everything that makes the embed work — granting the
 * Widevine permission and filtering requests with uBlock Origin's lists — is set up by the controller.
 */
@Composable
fun WebPlayerScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    onEnter: () -> Unit = {}
) {
    val context = LocalContext.current
    var filtersReady by remember { mutableStateOf(false) }
    // A desktop layout by default: Spotify serves phone user agents a "get the app" page instead of
    // a usable library. The width is cycleable because the desktop layout is dense on a phone —
    // the same knob Chrome DevTools' device emulation gives you.
    var layoutWidth by remember { mutableIntStateOf(WebPlayerController.layoutWidth(context)) }
    // The virtual mouse (see the overlay below). Off by default: with it on, the page no longer
    // receives touches directly.
    var pointerMode by remember { mutableStateOf(false) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var cursor by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(Unit) { onEnter() }

    // Compile the filter lists before the page loads so the first requests are already covered.
    LaunchedEffect(Unit) {
        filtersReady = runCatching { AdblockFilters.ensureLoaded(context) }.getOrDefault(false)
        WebPlayerController.getOrCreate(context)
        WebPlayerController.loadHomeIfNeeded()
    }

    // Detach (never destroy) so audio survives leaving the screen.
    DisposableEffect(Unit) { onDispose { WebPlayerController.detachFromParent() } }

    BackHandler { onBackClick() }

    Column(modifier = modifier.fillMaxSize().background(Color(0xFF121212))) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 40.dp, start = 8.dp, end = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBackClick) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.settings_back),
                    tint = Color.White
                )
            }
            Text(
                text = stringResource(R.string.web_player_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { layoutWidth = WebPlayerController.cycleLayoutWidth(context) }) {
                Icon(
                    if (layoutWidth == 0) Icons.Default.PhoneAndroid else Icons.Default.DesktopWindows,
                    contentDescription = stringResource(R.string.web_player_layout_width),
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = if (layoutWidth == 0) stringResource(R.string.web_player_layout_mobile)
                           else layoutWidth.toString(),
                    color = Color.White,
                    fontSize = 12.sp
                )
            }
            IconButton(onClick = { pointerMode = !pointerMode }) {
                Icon(
                    if (pointerMode) Icons.Default.Mouse else Icons.Default.TouchApp,
                    contentDescription = stringResource(R.string.web_player_pointer),
                    tint = if (pointerMode) Color(0xFF1DB954) else Color.White
                )
            }
            IconButton(onClick = { WebPlayerController.getOrCreate(context).reload() }) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.web_player_reload),
                    tint = Color.White
                )
            }
        }

        // Neither gesture is discoverable, and without them tapping a song looks broken.
        Text(
            text = if (pointerMode) stringResource(R.string.web_player_pointer_hint)
                   else stringResource(R.string.web_player_two_finger_hint),
            color = Color(0xFF9E9E9E),
            fontSize = 11.sp,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp)
        )

        if (!filtersReady) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF1DB954),
                trackColor = Color(0xFF333333)
            )
        }

        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    val first = viewSize == IntSize.Zero
                    viewSize = size
                    if (first) cursor = Offset(size.width / 2f, size.height / 2f)
                }
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebPlayerController.getOrCreate(ctx).also { wv ->
                        // Re-entering the screen reuses the same instance, which may still be
                        // attached to the previous (now discarded) host.
                        (wv.parent as? ViewGroup)?.removeView(wv)
                    }
                }
            )

            if (pointerMode && viewSize.width > 0 && viewSize.height > 0) {
                val w = viewSize.width.toFloat()
                val h = viewSize.height.toFloat()
                // A trackpad, not a touchscreen: the overlay swallows every touch and turns it into
                // mouse input aimed at the cursor. Dragging under your own fingertip is exactly what
                // does not work on a page whose controls are a few device pixels wide.
                Box(
                    Modifier
                        .matchParentSize()
                        .pointerInput(viewSize) {
                            val slop = viewConfiguration.touchSlop
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                // Relative, like a trackpad: what moves the cursor is how far the
                                // finger travels, not where on the screen it landed.
                                var last = down.position
                                var travelled = 0f
                                var twoFingers = false
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pressed = event.changes.filter { it.pressed }
                                    if (pressed.size >= 2) twoFingers = true
                                    event.changes.forEach { it.consume() }
                                    val lead = pressed.firstOrNull() ?: break
                                    val delta = lead.position - last
                                    last = lead.position
                                    travelled += abs(delta.x) + abs(delta.y)
                                    if (delta == Offset.Zero) continue
                                    if (twoFingers) {
                                        // Content follows the fingers, as on a phone.
                                        WebPlayerController.pointerScroll(
                                            cursor.x / w, cursor.y / h,
                                            -delta.x * SCROLL_GAIN, -delta.y * SCROLL_GAIN
                                        )
                                    } else {
                                        cursor = Offset(
                                            (cursor.x + delta.x * POINTER_GAIN).coerceIn(0f, w - 1f),
                                            (cursor.y + delta.y * POINTER_GAIN).coerceIn(0f, h - 1f)
                                        )
                                        WebPlayerController.pointerMove(cursor.x / w, cursor.y / h)
                                    }
                                }
                                // A tap is a press that went nowhere. Two fingers make it the
                                // double-click Spotify's rows need; one finger is a plain click.
                                if (travelled < slop) {
                                    WebPlayerController.pointerClick(
                                        cursor.x / w, cursor.y / h, double = twoFingers
                                    )
                                }
                            }
                        }
                )
                Canvas(Modifier.matchParentSize()) { drawCursor(cursor) }
            }
        }
    }
}

/** How far the cursor travels per unit of finger travel. Below 1 for fine aim on a scaled page. */
private const val POINTER_GAIN = 0.75f

/** Finger-to-page scroll ratio. Above 1 because the page is laid out wider than the screen. */
private const val SCROLL_GAIN = 2.5f

/**
 * A classic arrow pointer: white fill, dark outline, so it reads against both Spotify's dark
 * chrome and the white panels inside it.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCursor(at: Offset) {
    val s = 1.6f
    val path = Path().apply {
        moveTo(at.x, at.y)
        lineTo(at.x, at.y + 17 * s)
        lineTo(at.x + 4.2f * s, at.y + 13.2f * s)
        lineTo(at.x + 7f * s, at.y + 19.5f * s)
        lineTo(at.x + 10f * s, at.y + 18.2f * s)
        lineTo(at.x + 7.2f * s, at.y + 12f * s)
        lineTo(at.x + 12f * s, at.y + 12f * s)
        close()
    }
    drawPath(path, Color.White)
    drawPath(path, Color(0xFF101010), style = Stroke(width = 1.5f * s))
}
