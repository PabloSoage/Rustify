// sharedUi/src/androidMain/kotlin/com/varuna/rustify/ui/FilePickers.android.kt
package com.varuna.rustify.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.documentfile.provider.DocumentFile
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The Storage Access Framework, as the screens used it before they moved.

@Composable
actual fun <T> rememberOpenFile(
    mimeTypes: List<String>,
    read: suspend (name: String?, input: InputStream) -> T,
    onResult: (Result<T>?) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            onResult(null)
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val name = DocumentFile.fromSingleUri(context, uri)?.name
                    context.contentResolver.openInputStream(uri)?.use { read(name, it) } ?: error("cannot open $uri")
                }
            }
            onResult(result)
        }
    }
    return remember(launcher) { { launcher.launch(mimeTypes.toTypedArray()) } }
}

@Composable
actual fun rememberSaveFile(
    mimeType: String,
    onResult: (Result<Unit>?) -> Unit,
): (suggestedName: String, write: suspend (OutputStream) -> Unit) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Held across recompositions: the launcher's callback is replaced by every one, the writer must not be.
    val pending = remember { arrayOfNulls<suspend (OutputStream) -> Unit>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mimeType)) { uri ->
        val write = pending[0]
        pending[0] = null
        if (uri == null) {
            onResult(null)
            return@rememberLauncherForActivityResult
        }
        if (write == null) {
            // The activity was recreated while the picker was open, and the writer went with it. The
            // picker has already created the file: remove it rather than leave it empty, and say the
            // save failed, since the user did choose a place.
            scope.launch {
                withContext(Dispatchers.IO) { runCatching { DocumentFile.fromSingleUri(context, uri)?.delete() } }
                onResult(Result.failure(IllegalStateException("nothing to write to $uri: the screen was recreated")))
            }
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // "wt": a shorter file written over a longer one must not keep the old tail.
                    val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("cannot open $uri")
                    out.use { write(it) }
                }
            }
            onResult(result)
        }
    }
    return { name, write ->
        pending[0] = write
        launcher.launch(name)
    }
}
