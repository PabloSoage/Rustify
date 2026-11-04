// sharedUi/src/iosMain/kotlin/com/varuna/rustify/ui/NotificationPermission.ios.kt
package com.varuna.rustify.ui

import androidx.compose.runtime.Composable

/** Nothing to ask: downloads on iOS do not notify. */
@Composable
actual fun rememberNotificationPermissionRequest(): () -> Unit = {}
