// sharedUi/src/commonMain/kotlin/com/varuna/rustify/ui/WindowSize.kt
package com.varuna.rustify.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo

/**
 * The two things the screens read from Android's `LocalConfiguration`, from the window instead, which
 * both platforms have: on the phone it is the screen, on the desktop the window the user sized.
 */
@Composable
fun windowWidthDp(): Int = with(LocalDensity.current) {
    LocalWindowInfo.current.containerSize.width.toDp().value.toInt()
}

/** Wider than tall: the tablet layout on the phone, and nearly always on the desktop. */
@Composable
fun isLandscape(): Boolean = LocalWindowInfo.current.containerSize.let { it.width > it.height }

/** The window's height, as [windowWidthDp] its width. */
@Composable
fun windowHeightDp(): Int = with(LocalDensity.current) {
    LocalWindowInfo.current.containerSize.height.toDp().value.toInt()
}
