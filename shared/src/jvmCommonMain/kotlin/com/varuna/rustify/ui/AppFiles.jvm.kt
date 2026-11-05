// shared/src/jvmCommonMain/kotlin/com/varuna/rustify/ui/AppFiles.jvm.kt
package com.varuna.rustify.ui

import java.io.File
import java.net.URI

// java.io, as the stores used it before they moved to common code. The same on Android and the desktop.

actual typealias AppFile = File

actual fun AppFile.child(name: String): AppFile = File(this, name)

actual val AppFile.parentDir: AppFile? get() = parentFile

actual val AppFile.fileName: String get() = name

actual val AppFile.fullPath: String get() = absolutePath

actual fun AppFile.fileExists(): Boolean = exists()

actual fun AppFile.readAllText(): String = readText()

actual fun AppFile.writeAllText(text: String) = writeText(text)

actual fun AppFile.writeAllBytes(bytes: ByteArray) = writeBytes(bytes)

actual fun AppFile.appendAllBytes(bytes: ByteArray) = appendBytes(bytes)

actual fun AppFile.readHeadBytes(count: Int): ByteArray =
    runCatching { inputStream().use { it.readNBytes(count) } }.getOrDefault(ByteArray(0))

actual fun AppFile.renameToFile(target: AppFile): Boolean = renameTo(target)

actual fun AppFile.deleteFile(): Boolean = delete()

actual fun AppFile.makeDirs(): Boolean = mkdirs()

actual fun AppFile.listChildren(): List<AppFile> = listFiles()?.toList().orEmpty()

actual fun AppFile.byteSize(): Long = length()

actual fun appFileAt(path: String): AppFile = File(path)

actual fun AppFile.isFolder(): Boolean = isDirectory

actual fun AppFile.isPlainFile(): Boolean = isFile

actual fun AppFile.modifiedMillis(): Long = lastModified()

actual fun AppFile.resolvedPath(): String? = runCatching { canonicalPath }.getOrNull()

actual fun AppFile.fileUri(): String = absoluteFile.toPath().toUri().toString()

actual fun appFileOfUri(uri: String): AppFile? =
    if (uri.startsWith("file:")) runCatching { File(URI(uri)) }.getOrNull() else null
