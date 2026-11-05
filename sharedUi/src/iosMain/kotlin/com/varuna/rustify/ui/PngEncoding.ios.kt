// sharedUi/src/iosMain/kotlin/com/varuna/rustify/ui/PngEncoding.ios.kt
package com.varuna.rustify.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

// Skia, as on the desktop: Compose draws with it on iOS too.
actual fun ImageBitmap.toPngBytes(): ByteArray =
    checkNotNull(Image.makeFromBitmap(asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)) { "PNG encoding failed" }.bytes
