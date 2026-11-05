// sharedUi/src/commonMain/kotlin/com/varuna/rustify/ui/WindowSize.kt
package com.varuna.rustify.ui

import androidx.compose.runtime.Composable

// The three things the screens read about the space they have. On the phone it is Android's
// `Configuration`, as the screens always read it; on the desktop, the window the user sized.

/** How wide the screen (the window, on the desktop) is, in dp. */
@Composable
expect fun windowWidthDp(): Int

/** In landscape: the tablet layout on the phone, and nearly always on the desktop. */
@Composable
expect fun isLandscape(): Boolean

/** The height, as [windowWidthDp] the width. */
@Composable
expect fun windowHeightDp(): Int
