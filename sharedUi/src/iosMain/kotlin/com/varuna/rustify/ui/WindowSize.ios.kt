// sharedUi/src/iosMain/kotlin/com/varuna/rustify/ui/WindowSize.ios.kt
package com.varuna.rustify.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo

// The space the app's view has, as on the desktop: the screen on an iPhone, the window on an iPad
// (Split View, Stage Manager). Wider than tall is the tablet layout, as an Android device in
// landscape: the phone's layout on an iPhone held upright, the tablet's on an iPad held sideways.

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
