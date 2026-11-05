// sharedUi/src/desktopMain/kotlin/com/varuna/rustify/ui/DesktopLocalMusicScanner.kt
package com.varuna.rustify.ui

import com.varuna.rustify.bridge.FileTreeMusicScanner
import com.varuna.rustify.bridge.LocalMusicScanner
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/** The desktop's local scan: [FileTreeMusicScanner], with covers scaled by the JDK. */
fun DesktopLocalMusicScanner(): LocalMusicScanner = FileTreeMusicScanner(scaleCover = ::scaled)

/** The picture at most 512 px on its longer side, as a JPEG at quality 80, as the phone writes it. */
private fun scaled(bytes: ByteArray): ByteArray? = runCatching {
    val src = ImageIO.read(ByteArrayInputStream(bytes)) ?: return@runCatching null
    val scale = minOf(1.0, FileTreeMusicScanner.COVER_MAX_PX.toDouble() / maxOf(src.width, src.height))
    val w = maxOf(1, (src.width * scale).toInt())
    val h = maxOf(1, (src.height * scale).toInt())
    val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val g = out.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    g.drawImage(src, 0, 0, w, h, null)
    g.dispose()
    val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
    val param = writer.defaultWriteParam.apply {
        compressionMode = ImageWriteParam.MODE_EXPLICIT
        compressionQuality = 0.8f
    }
    val buffer = ByteArrayOutputStream()
    ImageIO.createImageOutputStream(buffer).use { stream ->
        writer.output = stream
        writer.write(null, IIOImage(out, null, null), param)
    }
    writer.dispose()
    buffer.toByteArray()
}.getOrNull()
