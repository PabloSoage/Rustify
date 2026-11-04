// sharedUi/src/commonMain/kotlin/com/varuna/rustify/ui/PngEncoding.kt
package com.varuna.rustify.ui

import androidx.compose.ui.graphics.ImageBitmap

/** The image as a PNG file's bytes (a recap card to share): Android's Bitmap, Skia on the desktop. */
expect fun ImageBitmap.toPngBytes(): ByteArray
