// sharedUi/src/desktopMain/kotlin/com/varuna/rustify/ui/ShareImage.desktop.kt
package com.varuna.rustify.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.io.File

@Composable
actual fun rememberShareImage(): (file: File, text: String, mimeType: String) -> Boolean {
    val platform = LocalAppPlatform.current
    return remember(platform) { { file: File, text: String, mimeType: String -> platform.shareImage(file, text, mimeType) } }
}
