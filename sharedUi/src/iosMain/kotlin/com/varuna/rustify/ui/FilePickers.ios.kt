// sharedUi/src/iosMain/kotlin/com/varuna/rustify/ui/FilePickers.ios.kt
@file:OptIn(kotlinx.cinterop.BetaInteropApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package com.varuna.rustify.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.lastPathComponent
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UniformTypeIdentifiers.UTTypeData
import platform.UniformTypeIdentifiers.UTTypeFolder
import platform.darwin.NSObject
import platform.posix.memcpy

// The system's document picker. It holds its delegate weakly, so the one on screen is kept here.
private var activeDelegate: NSObject? = null

private class PickerDelegate(
    private val onPicked: (NSURL) -> Unit,
    private val onCancelled: () -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        activeDelegate = null
        (didPickDocumentsAtURLs.firstOrNull() as? NSURL)?.let(onPicked) ?: onCancelled()
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        activeDelegate = null
        onCancelled()
    }
}

private fun show(picker: UIDocumentPickerViewController, onPicked: (NSURL) -> Unit, onCancelled: () -> Unit) {
    val delegate = PickerDelegate(onPicked, onCancelled)
    activeDelegate = delegate
    picker.delegate = delegate
    if (!IosPresenter.present(picker)) {
        activeDelegate = null
        onCancelled()
    }
}

private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val out = ByteArray(size)
    out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}

@Composable
actual fun <T> rememberOpenFile(
    mimeTypes: List<String>,
    read: suspend (name: String?, bytes: ByteArray) -> T,
    onResult: (Result<T>?) -> Unit,
): () -> Unit {
    val scope = rememberCoroutineScope()
    return {
        // asCopy: a copy in the app's own inbox, readable without asking for access to the original.
        val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeData), asCopy = true)
        show(picker, onPicked = { url ->
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val data = NSData.dataWithContentsOfURL(url) ?: throw kotlinx.io.IOException("cannot read ${url.lastPathComponent}")
                        read(url.lastPathComponent, data.toByteArray())
                    }
                }
                onResult(result)
            }
        }, onCancelled = { onResult(null) })
    }
}

@Composable
actual fun rememberSaveFile(
    mimeType: String,
    onResult: (Result<Unit>?) -> Unit,
): (suggestedName: String, write: suspend (FileSink) -> Unit) -> Unit {
    val scope = rememberCoroutineScope()
    return { name, write ->
        // The picker exports a file that exists, so it is written first, under the name suggested,
        // and goes where the user says. Cancelling leaves only a temporary file, removed here.
        scope.launch {
            val temp = AppFile(NSTemporaryDirectory()).child(name)
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    var bytes = ByteArray(0)
                    write(FileSink { bytes += it })
                    temp.writeAllBytes(bytes)
                }
            }
            if (written.isFailure) {
                onResult(written)
                return@launch
            }
            val picker = UIDocumentPickerViewController(forExportingURLs = listOf(NSURL.fileURLWithPath(temp.path)), asCopy = true)
            show(picker, onPicked = {
                temp.deleteFile()
                onResult(Result.success(Unit))
            }, onCancelled = {
                temp.deleteFile()
                onResult(null)
            })
        }
    }
}

@Composable
actual fun rememberPickFolder(write: Boolean, onResult: (String?) -> Unit): () -> Unit = {
    val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeFolder))
    show(picker, onPicked = { url ->
        // Access lasts for this run of the app; a folder kept across launches needs a bookmark.
        url.startAccessingSecurityScopedResource()
        onResult(url.path)
    }, onCancelled = { onResult(null) })
}

actual fun folderLabel(folder: String): String = folder.trimEnd('/').substringAfterLast('/')
