// shared/src/wasmJsMain/kotlin/com/varuna/rustify/ui/AppFiles.wasm.kt
package com.varuna.rustify.ui

import com.varuna.rustify.bridge.RustifyJson
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.Serializable

private fun fileCall(body: String): String = js(
    """(function() {
        var x = new XMLHttpRequest();
        x.open('POST', 'api/file', false);
        x.setRequestHeader('Content-Type', 'application/json');
        try { x.send(body); } catch (e) { return '{}'; }
        if (x.status === 401) { window.location.reload(); }
        return x.responseText || '{}';
    })()"""
)

@Serializable
private data class FileRequest(
    val op: String,
    val path: String,
    val target: String? = null,
    val data: String? = null,
    val length: Int? = null,
)

@Serializable
private data class FileAnswer(
    val ok: Boolean = false,
    val exists: Boolean = false,
    val folder: Boolean = false,
    val size: Long = 0,
    val modified: Long = 0,
    val data: String? = null,
    val children: List<String> = emptyList(),
)

private fun ask(op: String, path: String, target: String? = null, data: String? = null, length: Int? = null): FileAnswer {
    val body = RustifyJson.encodeToString(FileRequest.serializer(), FileRequest(op, path, target, data, length))
    return RustifyJson.decodeFromString(FileAnswer.serializer(), fileCall(body))
}

/**
 * A file of the app's: on the web, on the server, under `/files` and `/cache` of its own folder
 * (`rustify-server`, `/api/file`). A call blocks the browser for one round trip, as a file access blocks
 * its thread anywhere else, and no file the app keeps is big: the music stays where it is.
 */
actual open class AppFile(val path: String)

actual fun AppFile.child(name: String): AppFile = AppFile(path.trimEnd('/') + "/" + name)

actual val AppFile.parentDir: AppFile?
    get() = path.trimEnd('/').substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let { AppFile(it) }

actual val AppFile.fileName: String get() = path.trimEnd('/').substringAfterLast('/')

actual val AppFile.fullPath: String get() = path

actual fun AppFile.fileExists(): Boolean = ask("stat", path).exists

actual fun AppFile.readAllText(): String = decoded(ask("read", path)).decodeToString()

actual fun AppFile.writeAllText(text: String) = writeAllBytes(text.encodeToByteArray())

@OptIn(ExperimentalEncodingApi::class)
actual fun AppFile.writeAllBytes(bytes: ByteArray) {
    check(ask("write", path, data = Base64.encode(bytes)).ok) { "could not write $path" }
}

@OptIn(ExperimentalEncodingApi::class)
actual fun AppFile.appendAllBytes(bytes: ByteArray) {
    check(ask("append", path, data = Base64.encode(bytes)).ok) { "could not write $path" }
}

actual fun AppFile.readHeadBytes(count: Int): ByteArray = decoded(ask("read", path, length = count))

@OptIn(ExperimentalEncodingApi::class)
private fun AppFile.decoded(answer: FileAnswer): ByteArray {
    val data = answer.data ?: throw IllegalStateException("no such file: $path")
    return Base64.decode(data)
}

actual fun AppFile.renameToFile(target: AppFile): Boolean = ask("rename", path, target = target.path).ok

actual fun AppFile.deleteFile(): Boolean = ask("delete", path).ok

actual fun AppFile.makeDirs(): Boolean = ask("mkdirs", path).ok

actual fun AppFile.listChildren(): List<AppFile> = ask("list", path).children.map { child(it) }

actual fun AppFile.byteSize(): Long = ask("stat", path).size

actual fun appFileAt(path: String): AppFile = AppFile(path)

actual fun AppFile.isFolder(): Boolean = ask("stat", path).folder

actual fun AppFile.isPlainFile(): Boolean = ask("stat", path).let { it.exists && !it.folder }

actual fun AppFile.modifiedMillis(): Long = ask("stat", path).modified

actual fun AppFile.resolvedPath(): String? = path

actual fun AppFile.fileUri(): String = "file://$path"

actual fun appFileOfUri(uri: String): AppFile? = uri.takeIf { it.startsWith("file://") }?.let { AppFile(it.removePrefix("file://")) }
