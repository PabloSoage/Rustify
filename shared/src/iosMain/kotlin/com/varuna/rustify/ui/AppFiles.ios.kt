// shared/src/iosMain/kotlin/com/varuna/rustify/ui/AppFiles.ios.kt
@file:OptIn(kotlinx.cinterop.BetaInteropApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package com.varuna.rustify.ui

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataWithBytes
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.stringByDeletingLastPathComponent
import platform.Foundation.stringWithContentsOfFile
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
