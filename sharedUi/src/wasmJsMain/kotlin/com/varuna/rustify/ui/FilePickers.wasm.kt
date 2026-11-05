// sharedUi/src/wasmJsMain/kotlin/com/varuna/rustify/ui/FilePickers.wasm.kt
package com.varuna.rustify.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.launch

private fun pickFile(accept: String, onPicked: (String, String) -> Unit, onCancel: () -> Unit): Unit = js(
    """(function() {
        var input = document.createElement('input');
        input.type = 'file';
        input.accept = accept;
        input.oncancel = function() { onCancel(); };
        input.onchange = function() {
            var file = input.files[0];
            if (!file) { onCancel(); return; }
            var reader = new FileReader();
            reader.onload = function() { onPicked(file.name, reader.result.substring(reader.result.indexOf(',') + 1)); };
            reader.onerror = function() { onCancel(); };
            reader.readAsDataURL(file);
        };
        input.click();
    })()"""
)

private fun downloadFile(name: String, mimeType: String, base64: String): Unit = js(
    """(function() {
        var link = document.createElement('a');
        link.href = 'data:' + mimeType + ';base64,' + base64;
        link.download = name;
        document.body.appendChild(link);
        link.click();
        link.remove();
    })()"""
)

@OptIn(ExperimentalEncodingApi::class)
@Composable
actual fun <T> rememberOpenFile(
    mimeTypes: List<String>,
    read: suspend (name: String?, bytes: ByteArray) -> T,
    onResult: (Result<T>?) -> Unit,
): () -> Unit {
    val scope = rememberCoroutineScope()
    return {
        pickFile(
            mimeTypes.joinToString(","),
            { name, data ->
                scope.launch { onResult(runCatching { read(name, Base64.decode(data)) }) }
            },
            { onResult(null) },
        )
    }
}

/** The browser saves where it saves downloads, and may ask: from here it cannot tell, so it never cancels. */
@OptIn(ExperimentalEncodingApi::class)
@Composable
actual fun rememberSaveFile(
    mimeType: String,
    onResult: (Result<Unit>?) -> Unit,
): (suggestedName: String, write: suspend (FileSink) -> Unit) -> Unit {
    val scope = rememberCoroutineScope()
    return { name, write ->
        scope.launch {
            onResult(runCatching {
                val parts = ArrayList<ByteArray>()
                write(FileSink { parts.add(it.copyOf()) })
                val whole = ByteArray(parts.sumOf { it.size })
                var at = 0
                for (part in parts) {
                    part.copyInto(whole, at)
                    at += part.size
                }
                downloadFile(name, mimeType, Base64.encode(whole))
            })
        }
    }
}
