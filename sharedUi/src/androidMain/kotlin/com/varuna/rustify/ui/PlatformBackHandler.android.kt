// sharedUi/src/androidMain/kotlin/com/varuna/rustify/ui/PlatformBackHandler.android.kt
package com.varuna.rustify.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = BackHandler(enabled, onBack)
