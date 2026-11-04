// sharedUi/src/desktopMain/kotlin/com/varuna/rustify/ui/FolderPicker.desktop.kt
package com.varuna.rustify.ui

import androidx.compose.runtime.Composable
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser

// macOS's own folder dialog (AWT's FileDialog in folder mode); elsewhere Swing's chooser, since AWT
// has no folder mode on Windows or Linux. Nothing is granted: the desktop reads and writes paths.

@Composable
actual fun rememberPickFolder(write: Boolean, onResult: (String?) -> Unit): () -> Unit = { onResult(pickFolder()) }

actual fun folderLabel(folder: String): String = folder

private fun pickFolder(): String? {
    if (System.getProperty("os.name").orEmpty().lowercase().contains("mac")) {
        val key = "apple.awt.fileDialogForDirectories"
        val before = System.getProperty(key)
        System.setProperty(key, "true")
        try {
            val dialog = FileDialog(null as Frame?, null, FileDialog.LOAD)
            dialog.isVisible = true
            val dir = dialog.directory ?: return null
            val name = dialog.file ?: return null
            return File(dir, name).absolutePath
        } finally {
            if (before == null) System.clearProperty(key) else System.setProperty(key, before)
        }
    }
    val chooser = JFileChooser(File(System.getProperty("user.home").orEmpty())).apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        isAcceptAllFileFilterUsed = false
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.absolutePath else null
}
