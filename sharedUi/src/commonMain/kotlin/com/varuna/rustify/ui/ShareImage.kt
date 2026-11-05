// sharedUi/src/commonMain/kotlin/com/varuna/rustify/ui/ShareImage.kt
package com.varuna.rustify.ui

import androidx.compose.runtime.Composable

/**
 * [AppPlatform.shareImage] for a screen sharing what it shows (the recap): on Android the share sheet
 * opens from the screen's activity, as it did before the screens moved, when the composition has one.
 * The function takes the file, its caption (none when blank) and its type, and answers false when
 * nothing could take it.
 */
@Composable
expect fun rememberShareImage(): (file: AppFile, text: String, mimeType: String) -> Boolean

/**
 * [bytes] as `cacheDir/share/<name>`, the folder Android's FileProvider exposes (`file_paths.xml`),
 * ready to hand to [AppPlatform.shareImage]. Blocking: call it off the main thread.
 */
fun AppPlatform.shareFile(name: String, bytes: ByteArray): AppFile {
    val dir = cacheDir.child("share").also { it.makeDirs() }
    return dir.child(name).also { it.writeAllBytes(bytes) }
}
