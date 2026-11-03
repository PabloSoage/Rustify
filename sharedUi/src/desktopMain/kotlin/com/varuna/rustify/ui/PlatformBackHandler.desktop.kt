// sharedUi/src/desktopMain/kotlin/com/varuna/rustify/ui/PlatformBackHandler.desktop.kt
package com.varuna.rustify.ui

import androidx.compose.runtime.Composable

// A desktop has no system back. Esc and the mouse's back button can take this role once the window
// routes them (docs serie-4.x/27 §2.1); until then the screens' own back arrows do.
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit
