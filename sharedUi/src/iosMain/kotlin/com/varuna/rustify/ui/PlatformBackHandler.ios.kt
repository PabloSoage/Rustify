// sharedUi/src/iosMain/kotlin/com/varuna/rustify/ui/PlatformBackHandler.ios.kt
package com.varuna.rustify.ui

import androidx.compose.runtime.Composable

// iOS has no system back button, and the edge swipe is not routed to Compose here: every screen's
// own back arrow is the way back, as on the desktop.
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit
