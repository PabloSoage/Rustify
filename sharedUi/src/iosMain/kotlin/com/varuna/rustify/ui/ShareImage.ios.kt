// sharedUi/src/iosMain/kotlin/com/varuna/rustify/ui/ShareImage.ios.kt
package com.varuna.rustify.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberShareImage(): (file: AppFile, text: String, mimeType: String) -> Boolean {
    val platform = LocalAppPlatform.current
    return remember(platform) { { file, text, mimeType -> platform.shareImage(file, text, mimeType) } }
}
