// desktopApp/src/jvmMain/kotlin/com/varuna/rustify/desktop/WindowMemory.kt
package com.varuna.rustify.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import com.varuna.rustify.platform.DesktopStores
import java.awt.GraphicsEnvironment
import java.awt.Rectangle

/**
 * Where the window was, how big, and whether it was maximised, from one run to the next — in the
 * desktop's settings (`DesktopStores.named("desktop_window")`), so a portable copy keeps it in its
 * folder. The size and place kept are the last ones it had un-maximised, so un-maximising after a
 * restart gives back that window rather than one the size of the screen.
 *
 * A place that no screen shows any more (a monitor unplugged since) is dropped: the system places the
 * window instead.
 */
object WindowMemory {
    /**
     * The smallest window that keeps the tablet layout: Material's "expanded" width (840 dp), where a
     * tablet in landscape starts, and a height that leaves it landscape at that width.
     */
    val MIN_SIZE = DpSize(840.dp, 560.dp)
    private val DEFAULT_SIZE = DpSize(1100.dp, 760.dp)

    private val store by lazy { DesktopStores.named("desktop_window") }

    private const val X = "x"
    private const val Y = "y"
    private const val WIDTH = "width"
    private const val HEIGHT = "height"
    private const val MAXIMISED = "maximised"

    data class Saved(val placement: WindowPlacement, val position: WindowPosition, val size: DpSize)

    fun load(): Saved {
        val width = store.getFloat(WIDTH, DEFAULT_SIZE.width.value)
        val height = store.getFloat(HEIGHT, DEFAULT_SIZE.height.value)
        val size = DpSize(
            width.coerceAtLeast(MIN_SIZE.width.value).dp,
            height.coerceAtLeast(MIN_SIZE.height.value).dp,
        )
        val x = store.getString(X)?.toFloatOrNull()
        val y = store.getString(Y)?.toFloatOrNull()
        val position = if (x != null && y != null && onSomeScreen(x, y, size)) WindowPosition(x.dp, y.dp) else WindowPosition.PlatformDefault
        val placement = if (store.getBoolean(MAXIMISED, false)) WindowPlacement.Maximized else WindowPlacement.Floating
        return Saved(placement, position, size)
    }

    fun save(state: WindowState) {
        val maximised = state.placement != WindowPlacement.Floating
        store.putBoolean(MAXIMISED, maximised)
        // A minimised or maximised window's size and place are not the ones to come back to.
        if (maximised || state.isMinimized) return
        store.putFloat(WIDTH, state.size.width.value)
        store.putFloat(HEIGHT, state.size.height.value)
        val position = state.position
        if (position is WindowPosition.Absolute) {
            store.putString(X, position.x.value.toString())
            store.putString(Y, position.y.value.toString())
        }
    }

    /** Whether enough of the window's title bar would be on a screen to grab it. */
    private fun onSomeScreen(x: Float, y: Float, size: DpSize): Boolean = runCatching {
        val titleBar = Rectangle(x.toInt(), y.toInt(), size.width.value.toInt(), 40)
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.any { device ->
            val visible = device.defaultConfiguration.bounds.intersection(titleBar)
            !visible.isEmpty && visible.width >= 120 && visible.height >= 20
        }
    }.getOrDefault(false)
}
