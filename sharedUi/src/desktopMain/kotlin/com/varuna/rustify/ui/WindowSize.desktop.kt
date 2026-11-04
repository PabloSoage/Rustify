// sharedUi/src/desktopMain/kotlin/com/varuna/rustify/ui/WindowSize.desktop.kt
package com.varuna.rustify.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo

// The window the user sized.

@Composable
actual fun windowWidthDp(): Int = with(LocalDensity.current) {
    LocalWindowInfo.current.containerSize.width.toDp().value.toInt()
}

@Composable
actual fun isLandscape(): Boolean = LocalWindowInfo.current.containerSize.let { it.width > it.height }

@Composable
actual fun windowHeightDp(): Int = with(LocalDensity.current) {
    LocalWindowInfo.current.containerSize.height.toDp().value.toInt()
}
