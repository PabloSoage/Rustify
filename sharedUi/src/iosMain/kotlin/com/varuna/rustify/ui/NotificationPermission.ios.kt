// sharedUi/src/iosMain/kotlin/com/varuna/rustify/ui/NotificationPermission.ios.kt
package com.varuna.rustify.ui

import androidx.compose.runtime.Composable

/** Nothing to ask: iOS has no downloads that notify (AppServices.can(DOWNLOADS) is false there). */
@Composable
actual fun rememberNotificationPermissionRequest(): () -> Unit = {}
