// sharedUi/src/desktopMain/kotlin/com/varuna/rustify/ui/PngEncoding.desktop.kt
package com.varuna.rustify.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

actual fun ImageBitmap.toPngBytes(): ByteArray =
    checkNotNull(Image.makeFromBitmap(asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)) { "PNG encoding failed" }.bytes
