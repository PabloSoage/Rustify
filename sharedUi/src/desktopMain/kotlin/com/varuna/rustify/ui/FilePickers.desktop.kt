// sharedUi/src/desktopMain/kotlin/com/varuna/rustify/ui/FilePickers.desktop.kt
package com.varuna.rustify.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The system's own open and save dialogs (AWT's FileDialog is the native one on Windows and macOS,
// GTK's on Linux), rather than Swing's look-alike.

@Composable
actual fun <T> rememberOpenFile(
    mimeTypes: List<String>,
    read: suspend (name: String?, input: InputStream) -> T,
    onResult: (Result<T>?) -> Unit,
): () -> Unit {
    val scope = rememberCoroutineScope()
    return {
        val dialog = FileDialog(null as Frame?, null, FileDialog.LOAD)
        dialog.isVisible = true
        val file = dialog.files.firstOrNull()
        if (file == null) {
            onResult(null)
        } else {
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { file.inputStream().use { read(file.name, it) } } }
                onResult(result)
            }
        }
    }
}

@Composable
actual fun rememberSaveFile(
    mimeType: String,
    onResult: (Result<Unit>?) -> Unit,
): (suggestedName: String, write: suspend (OutputStream) -> Unit) -> Unit {
    val scope = rememberCoroutineScope()
    return { name, write ->
        val dialog = FileDialog(null as Frame?, null, FileDialog.SAVE)
        dialog.file = name
        dialog.isVisible = true
        val dir = dialog.directory
        val chosen = dialog.file
        if (dir == null || chosen == null) {
            onResult(null)
        } else {
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { File(dir, chosen).outputStream().use { write(it) } } }
                onResult(result)
            }
        }
    }
}
