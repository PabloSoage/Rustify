// sharedUi/src/desktopMain/kotlin/com/varuna/rustify/ui/NotificationPermission.desktop.kt
package com.varuna.rustify.ui

import androidx.compose.runtime.Composable

/** Windows, Linux and macOS ask nothing before an app shows its own progress. */
@Composable
actual fun rememberNotificationPermissionRequest(): () -> Unit = {}
