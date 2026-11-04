// sharedUi/src/androidMain/kotlin/com/varuna/rustify/ui/PngEncoding.android.kt
package com.varuna.rustify.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.ByteArrayOutputStream

actual fun ImageBitmap.toPngBytes(): ByteArray =
    ByteArrayOutputStream().also { asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
