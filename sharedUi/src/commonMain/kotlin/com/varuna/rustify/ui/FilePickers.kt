// sharedUi/src/commonMain/kotlin/com/varuna/rustify/ui/FilePickers.kt
package com.varuna.rustify.ui

import androidx.compose.runtime.Composable

/** A text file the user picked: its name without the extension, and what it holds. */
data class PickedText(val name: String, val text: String)

/**
 * Lets the user pick a text file to read (an M3U to import): Android's document picker, or the
 * system's open dialog on the desktop. Returns the function that opens it. [onResult] gets null when
 * the user cancelled, a failure when the file could not be read, and runs on the main thread.
 */
@Composable
expect fun rememberOpenTextFile(mimeTypes: List<String>, onResult: (Result<PickedText>?) -> Unit): () -> Unit

/**
 * Lets the user choose where to save a text file (an exported M3U). The returned function takes the
 * suggested file name and the text to write. [onResult] gets null when cancelled, else whether the
 * write succeeded.
 */
@Composable
expect fun rememberSaveTextFile(mimeType: String, onResult: (Boolean?) -> Unit): (suggestedName: String, text: () -> String) -> Unit
