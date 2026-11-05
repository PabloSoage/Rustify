// sharedUi/src/wasmJsMain/kotlin/com/varuna/rustify/ui/WebSeams.kt
//
// The small actuals of the interface in a browser. The window is the space, as on the desktop, and the
// browser has its own back button, which goes through the page's history rather than through Compose.
package com.varuna.rustify.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import com.varuna.rustify.platform.currentTimeMillis
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

@Composable
actual fun windowWidthDp(): Int = with(LocalDensity.current) {
    LocalWindowInfo.current.containerSize.width.toDp().value.toInt()
}

@Composable
actual fun isLandscape(): Boolean = LocalWindowInfo.current.containerSize.let { it.width > it.height }

@Composable
actual fun windowHeightDp(): Int = with(LocalDensity.current) {
    LocalWindowInfo.current.containerSize.height.toDp().value.toInt()
}

actual fun ImageBitmap.toPngBytes(): ByteArray =
    checkNotNull(Image.makeFromBitmap(asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)) { "PNG encoding failed" }.bytes

private fun relativeText(tag: String, value: Int, unit: String): String =
    js("new Intl.RelativeTimeFormat(tag, { numeric: 'auto' }).format(value, unit)")

/** The twin of Android's `DateUtils.getRelativeTimeSpanString`: "5 minutes ago", "yesterday". */
actual fun relativeTimeSpan(epochMs: Long): String {
    val seconds = (epochMs - currentTimeMillis()) / 1000
    val (value, unit) = when {
        kotlin.math.abs(seconds) < 60 -> seconds to "second"
        kotlin.math.abs(seconds) < 3_600 -> seconds / 60 to "minute"
        kotlin.math.abs(seconds) < 86_400 -> seconds / 3_600 to "hour"
        kotlin.math.abs(seconds) < 2_592_000 -> seconds / 86_400 to "day"
        kotlin.math.abs(seconds) < 31_536_000 -> seconds / 2_592_000 to "month"
        else -> seconds / 31_536_000 to "year"
    }
    return relativeText(WebLocale.tag, value.toInt(), unit)
}

@Composable
actual fun rememberShareImage(): (file: AppFile, text: String, mimeType: String) -> Boolean {
    val platform = LocalAppPlatform.current
    return remember(platform) { { file, text, mimeType -> platform.shareImage(file, text, mimeType) } }
}

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit

// A page does not ask before a download finishes.
@Composable
actual fun rememberNotificationPermissionRequest(): () -> Unit = {}

/** A browser cannot hand out a folder a server could read; the server's music is its own setting. */
@Composable
actual fun rememberPickFolder(write: Boolean, onResult: (String?) -> Unit): () -> Unit = { onResult(null) }

actual fun folderLabel(folder: String): String = folder
