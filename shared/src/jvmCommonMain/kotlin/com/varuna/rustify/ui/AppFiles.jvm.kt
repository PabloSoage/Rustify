// shared/src/jvmCommonMain/kotlin/com/varuna/rustify/ui/AppFiles.jvm.kt
package com.varuna.rustify.ui

import java.io.File

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

actual fun AppFile.renameToFile(target: AppFile): Boolean = renameTo(target)

actual fun AppFile.deleteFile(): Boolean = delete()

actual fun AppFile.makeDirs(): Boolean = mkdirs()

actual fun AppFile.listChildren(): List<AppFile> = listFiles()?.toList().orEmpty()

actual fun AppFile.byteSize(): Long = length()
