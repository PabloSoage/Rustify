// shared/src/iosMain/kotlin/com/varuna/rustify/ui/AppFiles.ios.kt
@file:OptIn(kotlinx.cinterop.BetaInteropApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package com.varuna.rustify.ui

import kotlinx.cinterop.BooleanVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSize
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataWithBytes
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.stringByDeletingLastPathComponent
import platform.Foundation.stringByResolvingSymlinksInPath
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile

/** A path under the app's own folders, over Foundation's file manager. */
actual open class AppFile(val path: String)

private val files: NSFileManager get() = NSFileManager.defaultManager

actual fun AppFile.child(name: String): AppFile = AppFile(path.trimEnd('/') + "/" + name)

actual val AppFile.parentDir: AppFile?
    get() = (path as NSString).stringByDeletingLastPathComponent.takeIf { it.isNotEmpty() && it != path }?.let { AppFile(it) }

actual val AppFile.fileName: String get() = path.trimEnd('/').substringAfterLast('/')

actual val AppFile.fullPath: String get() = path

actual fun AppFile.fileExists(): Boolean = files.fileExistsAtPath(path)

actual fun AppFile.readAllText(): String =
    NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null) ?: throw kotlinx.io.IOException("cannot read $path")

actual fun AppFile.writeAllText(text: String) = writeAllBytes(text.encodeToByteArray())

actual fun AppFile.writeAllBytes(bytes: ByteArray) {
    val data = if (bytes.isEmpty()) {
        NSData.create(bytes = null, length = 0u)
    } else {
        bytes.usePinned { NSData.dataWithBytes(it.addressOf(0), bytes.size.convert()) }
    }
    if (!data.writeToFile(path, atomically = false)) throw kotlinx.io.IOException("cannot write $path")
}

actual fun AppFile.renameToFile(target: AppFile): Boolean {
    // rename(2) replaces the target, which moveItemAtPath refuses to.
    if (files.fileExistsAtPath(target.path)) files.removeItemAtPath(target.path, null)
    return files.moveItemAtPath(path, target.path, null)
}

actual fun AppFile.deleteFile(): Boolean = files.removeItemAtPath(path, null)

actual fun AppFile.makeDirs(): Boolean =
    files.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)

actual fun AppFile.listChildren(): List<AppFile> =
    files.contentsOfDirectoryAtPath(path, null)?.map { child(it as String) }.orEmpty()

actual fun AppFile.byteSize(): Long =
    (files.attributesOfItemAtPath(path, null)?.get(NSFileSize) as? platform.Foundation.NSNumber)?.longLongValue ?: 0L

actual fun appFileAt(path: String): AppFile = AppFile(path)

private fun AppFile.existsAsFolder(): Boolean? = memScoped {
    val folder = alloc<BooleanVar>()
    if (files.fileExistsAtPath(path, isDirectory = folder.ptr)) folder.value else null
}

actual fun AppFile.isFolder(): Boolean = existsAsFolder() == true

actual fun AppFile.isPlainFile(): Boolean = existsAsFolder() == false

actual fun AppFile.modifiedMillis(): Long {
    val date = files.attributesOfItemAtPath(path, null)?.get(NSFileModificationDate) as? NSDate ?: return 0L
    return (date.timeIntervalSince1970 * 1000).toLong()
}

actual fun AppFile.resolvedPath(): String? = (path as NSString).stringByResolvingSymlinksInPath

actual fun AppFile.fileUri(): String = NSURL.fileURLWithPath(path).absoluteString ?: "file://$path"

actual fun appFileOfUri(uri: String): AppFile? =
    if (uri.startsWith("file:")) NSURL.URLWithString(uri)?.path?.let { AppFile(it) } else null
