// sharedUi/src/commonMain/kotlin/com/varuna/rustify/ui/FilePickers.kt
package com.varuna.rustify.ui

import androidx.compose.runtime.Composable
import java.io.InputStream
import java.io.OutputStream

/** A text file the user picked: its name without the extension, and what it holds. */
data class PickedText(val name: String, val text: String)

/**
 * Lets the user pick a file to read: Android's document picker, or the system's open dialog on the
 * desktop. Returns the function that opens it. [read] runs off the main thread with the file's name
 * (when the platform knows it) and its bytes; [onResult] gets null when the user cancelled, a failure
 * when the file could not be opened or [read] threw, and runs on the main thread.
 */
@Composable
expect fun <T> rememberOpenFile(
    mimeTypes: List<String>,
    read: suspend (name: String?, input: InputStream) -> T,
    onResult: (Result<T>?) -> Unit,
): () -> Unit

/**
 * Lets the user choose where to save a file. The returned function takes the suggested file name and
 * the writer, which runs off the main thread once a place is chosen. [onResult] gets null when
 * cancelled, else whether the write succeeded, on the main thread.
 */
@Composable
expect fun rememberSaveFile(
    mimeType: String,
    onResult: (Result<Unit>?) -> Unit,
): (suggestedName: String, write: suspend (OutputStream) -> Unit) -> Unit

/** [rememberOpenFile] for a text file (an M3U to import). */
@Composable
fun rememberOpenTextFile(mimeTypes: List<String>, onResult: (Result<PickedText>?) -> Unit): () -> Unit =
    rememberOpenFile(
        mimeTypes,
        read = { name, input ->
            PickedText(name?.substringBeforeLast('.')?.ifBlank { null } ?: "M3U", input.readBytes().toString(Charsets.UTF_8))
        },
        onResult = onResult,
    )

/**
 * [rememberSaveFile] for a text file (an exported M3U, the log): the text is produced once a place is
 * chosen, and [onResult] gets what [rememberSaveFile] answers.
 */
@Composable
fun rememberSaveTextFile(mimeType: String, onResult: (Result<Unit>?) -> Unit): (suggestedName: String, text: () -> String) -> Unit {
    val save = rememberSaveFile(mimeType, onResult)
    return { name, text -> save(name) { it.write(text().toByteArray(Charsets.UTF_8)) } }
}
