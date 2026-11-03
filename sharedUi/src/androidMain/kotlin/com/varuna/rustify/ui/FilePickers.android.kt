// sharedUi/src/androidMain/kotlin/com/varuna/rustify/ui/FilePickers.android.kt
package com.varuna.rustify.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The Storage Access Framework, as LibraryScreen and PlaylistScreen used it before they moved.

@Composable
actual fun rememberOpenTextFile(mimeTypes: List<String>, onResult: (Result<PickedText>?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            onResult(null)
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val read = withContext(Dispatchers.IO) {
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                        ?: error("cannot open $uri")
                    val name = DocumentFile.fromSingleUri(context, uri)?.name
                        ?.substringBeforeLast('.')?.ifBlank { null } ?: "M3U"
                    PickedText(name, text)
                }
            }
            onResult(read)
        }
    }
    return remember(launcher) { { launcher.launch(mimeTypes.toTypedArray()) } }
}

@Composable
actual fun rememberSaveTextFile(mimeType: String, onResult: (Boolean?) -> Unit): (suggestedName: String, text: () -> String) -> Unit {
    val context = LocalContext.current
    // Held across recompositions: the launcher's callback is replaced by every one, the text must not be.
    val pending = remember { arrayOfNulls<() -> String>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mimeType)) { uri ->
        val text = pending[0]
        pending[0] = null
        if (uri == null || text == null) {
            onResult(null)
            return@rememberLauncherForActivityResult
        }
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text().toByteArray(Charsets.UTF_8)) } != null
        }.getOrDefault(false)
        onResult(ok)
    }
    return { name, text ->
        pending[0] = text
        launcher.launch(name)
    }
}
