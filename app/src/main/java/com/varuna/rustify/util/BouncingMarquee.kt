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
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

/**
 * Adaptive marquee modifier that chooses between two scrolling strategies based on
 * how much the text overflows:
 *
 *  - **Small overflow** (overflow <= containerWidth * [circularThresholdRatio]):
 *    Ping-pong (bounce) — scrolls to the end, pauses, scrolls back to the start,
 *    pauses, and repeats. Good for titles that are just a bit too long.
 *
 *  - **Large overflow** (overflow > containerWidth * [circularThresholdRatio]):
 *    Continuous scroll with snap-back — scrolls to the end, pauses, then snaps
 *    instantly back to the start so the user gets back to the beginning quickly.
 *    Good for very long titles where a slow reverse journey would be tedious.
 *
 *  - If the text does NOT overflow the container, it stays completely static.
 *
 * Chainable like `basicMarquee`, e.g. `Modifier.fillMaxWidth().bouncingMarquee()`.
 * Apply it directly on the `Text` (with `maxLines = 1`); it clips to bounds.
 *
 * @param velocity travel speed (dp per second) during the scroll animation.
 * @param edgeDelayMillis pause held at each extreme (start and end).
 * @param circularThresholdRatio if the overflow exceeds this multiple of the container
 *   width, the modifier switches from bounce to continuous+snap mode.
 */
fun Modifier.bouncingMarquee(
    velocity: Dp = 30.dp,
    edgeDelayMillis: Int = 1200,
    circularThresholdRatio: Float = 1.15f,
): Modifier = composed {
    val density = LocalDensity.current
    // Container width (visible) vs content width (natural, unbounded).
    var contentWidth by remember { mutableIntStateOf(0) }
    var textWidth by remember { mutableIntStateOf(0) }
    val offset = remember { Animatable(0f) }
    val overflow = (textWidth - contentWidth).coerceAtLeast(0)

    LaunchedEffect(overflow, velocity, contentWidth) {
        // No overflow -> stay static (snap back to origin, no coroutine loop).
        if (overflow <= 0) {
            offset.snapTo(0f)
            return@LaunchedEffect
        }
        val distance = overflow.toFloat()
        val pxPerSec = with(density) { velocity.toPx() }.coerceAtLeast(1f)
        val durationMs = ((distance / pxPerSec) * 1000f).roundToInt().coerceAtLeast(1)

        // Decide mode based on overflow vs container width.
        val useCircular = contentWidth > 0 && overflow > (contentWidth * circularThresholdRatio)

        offset.snapTo(0f)
        while (isActive) {
            // Forward scroll: start -> end
            offset.animateTo(-distance, tween(durationMs, easing = LinearEasing))
            delay(edgeDelayMillis.toLong())

            if (useCircular) {
                // Circular mode (long text): snap back instantly then pause at
                // the start before scrolling again — feels natural, not rushed.
                offset.snapTo(0f)
                delay(edgeDelayMillis.toLong())
            } else {
                // Bounce mode (short text): animate back to start, then pause.
                offset.animateTo(0f, tween(durationMs, easing = LinearEasing))
                delay(edgeDelayMillis.toLong())
            }
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
