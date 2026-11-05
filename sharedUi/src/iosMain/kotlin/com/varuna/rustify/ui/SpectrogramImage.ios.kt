package com.varuna.rustify.ui

import androidx.compose.ui.graphics.ImageBitmap

actual class SpectrogramImage actual constructor(actual val width: Int, actual val height: Int) {
    private val image = ImageBitmap(width, height)

    actual fun shiftAndSetColumn(column: IntArray) {}

    actual fun asImageBitmap(): ImageBitmap = image
}
