package com.varuna.rustify.ui

import androidx.compose.ui.graphics.ImageBitmap

/**
 * A rolling horizontal heatmap bitmap (newest column on the right, shifting left on each frame),
 * drawn onto a Compose Canvas. Backed by platform bitmaps (Android Bitmap, AWT BufferedImage, Skia).
 */
expect class SpectrogramImage(width: Int, height: Int) {
    val width: Int
    val height: Int
    fun shiftAndSetColumn(column: IntArray)
    fun asImageBitmap(): ImageBitmap
}
