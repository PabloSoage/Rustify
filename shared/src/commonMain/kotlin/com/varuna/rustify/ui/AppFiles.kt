// shared/src/commonMain/kotlin/com/varuna/rustify/ui/AppFiles.kt
package com.varuna.rustify.ui

/**
 * A file or a folder of the app's own: [AppPlatform.filesDir], [AppPlatform.cacheDir] and what is
 * under them. On Android and the desktop it **is** `java.io.File` (a typealias), so everything that
 * handed the stores a `filesDir` still hands them the same object, and every file keeps its name and
 * its folder. Common code reaches it only through the functions below, which name the `java.io` call
 * each one makes there; another platform brings its own.
 */
expect open class AppFile

/** [name] inside this folder: `File(this, name)`. */
expect fun AppFile.child(name: String): AppFile

/** The folder this is in: `parentFile`. */
expect val AppFile.parentDir: AppFile?

/** The last part of the path: `name`. */
expect val AppFile.fileName: String

/** The absolute path, as the core is handed it: `absolutePath`. */
expect val AppFile.fullPath: String

expect fun AppFile.fileExists(): Boolean

/** The whole file as UTF-8 text: `readText()`. Throws when it cannot be read. */
expect fun AppFile.readAllText(): String

/** Replaces the file with [text] in UTF-8: `writeText(text)`. */
expect fun AppFile.writeAllText(text: String)

/** Replaces the file with [bytes]: `writeBytes(bytes)`. */
expect fun AppFile.writeAllBytes(bytes: ByteArray)

/** `renameTo(target)`: false when it did not happen. */
expect fun AppFile.renameToFile(target: AppFile): Boolean

/** `delete()`: false when there was nothing to delete or it could not be. */
expect fun AppFile.deleteFile(): Boolean

/** `mkdirs()`, the missing parents included. */
expect fun AppFile.makeDirs(): Boolean

/** What this folder holds (`listFiles()`); empty when it is not a folder or cannot be read. */
expect fun AppFile.listChildren(): List<AppFile>

/** The size in bytes: `length()`, 0 when it does not exist. */
expect fun AppFile.byteSize(): Long

/** The file at [path]: `File(path)`. */
expect fun appFileAt(path: String): AppFile

/** `isDirectory`. */
expect fun AppFile.isFolder(): Boolean

/** `isFile`. */
expect fun AppFile.isPlainFile(): Boolean

/** When it last changed, in ms since the epoch: `lastModified()`, 0 when it does not exist. */
expect fun AppFile.modifiedMillis(): Long

/** The path with its links followed: `canonicalPath`; null when that cannot be worked out. */
expect fun AppFile.resolvedPath(): String?

/** Its `file://` URI: percent-encoded, three slashes, the form the engine opens and Coil loads. */
expect fun AppFile.fileUri(): String

/** The file a `file://` URI names, or null when it names none (a phone's `content://` track). */
expect fun appFileOfUri(uri: String): AppFile?

/** The file's text, or null when there is no file. Throws when one exists and cannot be read. */
fun AppFile.readTextOrNull(): String? = if (fileExists()) readAllText() else null

/**
 * Replaces the file with [text] so that a crash halfway leaves the old one: written beside it as
 * `<name>.tmp`, then renamed over it. Where the rename is refused, the file is written in place and
 * the temporary one removed — what every store here did by hand before.
 */
fun AppFile.writeTextAtomically(text: String) {
    val dir = parentDir ?: return writeAllText(text)
    val tmp = dir.child("$fileName.tmp")
    tmp.writeAllText(text)
    if (!tmp.renameToFile(this)) {
        writeAllText(text)
        tmp.deleteFile()
    }
}
