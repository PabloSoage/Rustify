// sharedUi/src/commonMain/kotlin/com/varuna/rustify/ui/FolderPicker.kt
package com.varuna.rustify.ui

import androidx.compose.runtime.Composable

/**
 * Lets the user pick a folder: Android's document tree picker, whose grant is made persistent (for
 * reading, and for writing too when [write]), or the system's folder dialog on the desktop. Returns
 * the function that opens it. [onResult] gets the folder — a tree URI on Android, an absolute path
 * on the desktop, which is what the local scan and the downloads take there — or null when cancelled.
 */
@Composable
expect fun rememberPickFolder(write: Boolean, onResult: (String?) -> Unit): () -> Unit

/** A folder [rememberPickFolder] answered, as Settings lists it: its last segment on Android, the path on the desktop. */
expect fun folderLabel(folder: String): String
