// sharedUi/src/desktopMain/kotlin/com/varuna/rustify/ui/FilePickers.desktop.kt
package com.varuna.rustify.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

// The system's own open and save dialogs (AWT's FileDialog is the native one on Windows and macOS,
// GTK's on Linux), rather than Swing's look-alike.

@Composable
actual fun rememberOpenTextFile(mimeTypes: List<String>, onResult: (Result<PickedText>?) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    return {
        val dialog = FileDialog(null as Frame?, null, FileDialog.LOAD)
        dialog.isVisible = true
        val file = dialog.files.firstOrNull()
        if (file == null) {
            onResult(null)
        } else {
            scope.launch {
                val read = withContext(Dispatchers.IO) {
                    runCatching { PickedText(file.nameWithoutExtension.ifBlank { "M3U" }, file.readText(Charsets.UTF_8)) }
                }
                onResult(read)
            }
        }
    }
}

@Composable
actual fun rememberSaveTextFile(mimeType: String, onResult: (Boolean?) -> Unit): (suggestedName: String, text: () -> String) -> Unit = { name, text ->
    val dialog = FileDialog(null as Frame?, null, FileDialog.SAVE)
    dialog.file = name
    dialog.isVisible = true
    val dir = dialog.directory
    val chosen = dialog.file
    if (dir == null || chosen == null) {
        onResult(null)
    } else {
        onResult(runCatching { File(dir, chosen).writeText(text(), Charsets.UTF_8) }.isSuccess)
    }
}
