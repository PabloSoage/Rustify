package com.varuna.rustify.ui

import kotlin.concurrent.Volatile

/**
 * Seam connecting [com.varuna.rustify.ui.screens.AudioLabScreen] to platform audio output / capture.
 * On Android, wired to AudioTap (PCM buffers from ExoPlayer) and AudioOffload.
 * On Desktop / iOS, defaults to no live capture (sampleRate = 0).
 */
object AudioLabBridge {
    @Volatile
    var sampleRate: () -> Int = { 0 }

    @Volatile
    var latestSamples: (n: Int) -> FloatArray = { n -> FloatArray(n) }

    @Volatile
    var onVisibilityChanged: (visible: Boolean) -> Unit = {}
}
