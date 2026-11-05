// sharedUi/src/androidMain/kotlin/com/varuna/rustify/ui/WindowSize.android.kt
package com.varuna.rustify.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

// The configuration, as the screens read it before they moved: its orientation, not a comparison of
// the window's sides, and its screen size in dp.

@Composable
actual fun windowWidthDp(): Int = LocalConfiguration.current.screenWidthDp

@Composable
actual fun isLandscape(): Boolean = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

@Composable
actual fun windowHeightDp(): Int = LocalConfiguration.current.screenHeightDp
