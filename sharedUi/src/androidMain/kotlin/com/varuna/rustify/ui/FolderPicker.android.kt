// sharedUi/src/androidMain/kotlin/com/varuna/rustify/ui/FolderPicker.android.kt
package com.varuna.rustify.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

// The Storage Access Framework's tree picker, as Settings used it before its sections moved.

@Composable
actual fun rememberPickFolder(write: Boolean, onResult: (String?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) {
            onResult(null)
            return@rememberLauncherForActivityResult
        }
        val flags = if (write) Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        else Intent.FLAG_GRANT_READ_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(uri, flags)
        onResult(uri.toString())
    }
    return remember(launcher) { { launcher.launch(null) } }
}

actual fun folderLabel(folder: String): String = Uri.parse(folder).lastPathSegment ?: folder
