// sharedUi/src/commonMain/kotlin/com/varuna/rustify/ui/ShareImage.kt
package com.varuna.rustify.ui

import androidx.compose.runtime.Composable
import java.io.File

/**
 * [AppPlatform.shareImage] for a screen sharing what it shows (the recap): on Android the share sheet
 * opens from the screen's activity, as it did before the screens moved, when the composition has one.
 * The function takes the file, its caption (none when blank) and its type, and answers false when
 * nothing could take it.
 */
@Composable
expect fun rememberShareImage(): (file: File, text: String, mimeType: String) -> Boolean
