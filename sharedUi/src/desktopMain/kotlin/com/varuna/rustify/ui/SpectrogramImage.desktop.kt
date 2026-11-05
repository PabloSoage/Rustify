package com.varuna.rustify.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt

actual class SpectrogramImage actual constructor(actual val width: Int, actual val height: Int) {
    private val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    private val px = (image.raster.dataBuffer as DataBufferInt).data
    private var composeBitmap: ImageBitmap = image.toComposeImageBitmap()

    actual fun shiftAndSetColumn(column: IntArray) {
        for (y in 0 until height) {
            System.arraycopy(px, y * width + 1, px, y * width, width - 1)
            px[y * width + width - 1] = column[y]
        }
        composeBitmap = image.toComposeImageBitmap()
    }

    actual fun asImageBitmap(): ImageBitmap = composeBitmap
}
