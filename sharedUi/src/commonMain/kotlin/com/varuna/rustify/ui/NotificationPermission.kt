// sharedUi/src/commonMain/kotlin/com/varuna/rustify/ui/NotificationPermission.kt
package com.varuna.rustify.ui

import androidx.compose.runtime.Composable

/**
 * Asks for permission to post notifications, where the system requires one (Android 13 and later:
 * a download's progress is a notification). Returns the function to call before starting something
 * that will notify; it asks only when the permission is missing, and does nothing on the desktop.
 */
@Composable
expect fun rememberNotificationPermissionRequest(): () -> Unit
