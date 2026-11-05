// sharedUi/src/commonMain/kotlin/com/varuna/rustify/ui/PlatformBackHandler.kt
package com.varuna.rustify.ui

import androidx.compose.runtime.Composable

/**
 * The system's "back" (Android's back gesture or button), taken while [enabled]. On the desktop there
 * is no system back yet; the screens' own back arrows still work there.
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean = true, onBack: () -> Unit)
