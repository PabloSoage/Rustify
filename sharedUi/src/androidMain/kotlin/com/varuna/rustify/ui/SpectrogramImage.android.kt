package com.varuna.rustify.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

actual class SpectrogramImage actual constructor(actual val width: Int, actual val height: Int) {
    private val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val px = IntArray(width * height)

    actual fun shiftAndSetColumn(column: IntArray) {
        bitmap.getPixels(px, 0, width, 0, 0, width, height)
        for (y in 0 until height) {
            System.arraycopy(px, y * width + 1, px, y * width, width - 1)
            px[y * width + width - 1] = column[y]
        }
        bitmap.setPixels(px, 0, width, 0, 0, width, height)
    }

    actual fun asImageBitmap(): ImageBitmap = bitmap.asImageBitmap()
}
