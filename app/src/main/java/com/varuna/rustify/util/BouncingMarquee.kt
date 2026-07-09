package com.varuna.rustify.util

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

/**
 * BUG B: Drop-in replacement for [androidx.compose.foundation.basicMarquee] that,
 * instead of looping unidirectionally, does a ping-pong (bounce):
 *
 *  - Measures the natural width of the content vs the width of the container.
 *  - If the content OVERFLOWS: scrolls to the end, PAUSES (~[edgeDelayMillis]),
 *    returns to the start, PAUSES, and repeats forever.
 *  - If it does NOT overflow: does nothing (content stays static, no scroll).
 *
 * Chainable like `basicMarquee`, e.g. `Modifier.fillMaxWidth().bouncingMarquee()`.
 * Apply it directly on the `Text` (with `maxLines = 1`); it clips to bounds.
 *
 * @param velocity travel speed (dp per second) during the ida/vuelta animation.
 * @param edgeDelayMillis pause held at each extreme (start and end).
 */
fun Modifier.bouncingMarquee(
    velocity: Dp = 30.dp,
    edgeDelayMillis: Int = 1200,
): Modifier = composed {
    val density = LocalDensity.current
    // Container width (visible) vs content width (natural, unbounded).
    var contentWidth by remember { mutableIntStateOf(0) }
    var textWidth by remember { mutableIntStateOf(0) }
    val offset = remember { Animatable(0f) }
    val overflow = (textWidth - contentWidth).coerceAtLeast(0)

    LaunchedEffect(overflow, velocity) {
        // No overflow -> stay static (snap back to origin, no coroutine loop).
        if (overflow <= 0) {
            offset.snapTo(0f)
            return@LaunchedEffect
        }
        val distance = overflow.toFloat()
        val pxPerSec = with(density) { velocity.toPx() }.coerceAtLeast(1f)
        val durationMs = ((distance / pxPerSec) * 1000f).roundToInt().coerceAtLeast(1)
        // Ping-pong: end -> pause -> start -> pause -> repeat.
        offset.snapTo(0f)
        while (isActive) {
            offset.animateTo(-distance, tween(durationMs, easing = LinearEasing)) // ida hasta el final
            delay(edgeDelayMillis.toLong())                                        // pausa al final
            offset.animateTo(0f, tween(durationMs, easing = LinearEasing))         // vuelta al inicio
            delay(edgeDelayMillis.toLong())                                        // pausa al inicio
        }
    }

    this
        .clipToBounds()
        .layout { measurable, constraints ->
            // Measure the content with no width restriction to know its natural width.
            val placeable = measurable.measure(constraints.copy(maxWidth = Constraints.Infinity))
            textWidth = placeable.width
            // The layout only occupies the width the container allows.
            val w = constraints.constrainWidth(placeable.width)
            contentWidth = w
            layout(w, placeable.height) {
                placeable.placeRelative(offset.value.roundToInt(), 0)
            }
        }
}
