package com.varuna.rustify.bridge

/**
 * The folder view of the local library (4.10, Musicolet 3.7): the scanned tracks laid out as the
 * tree they sit in on the device, from the path in each track's SAF document id ([M3u.pathOf]).
 *
 * The tree starts where the tracks part ways — `/storage/emulated/0/Music` is not three taps
 * of nothing — and a level shows its sub-folders, each with how many tracks it holds below,
 * then its own tracks. Pure, so tested on the JVM.
 */
object LocalFolders {
    data class Folder(val name: String, val path: String, val trackCount: Int)

    data class Level<T>(val folders: List<Folder>, val tracks: List<T>)

    /** The folder every track is under; "" when there are none or no paths. */
    fun root(paths: List<String>): String {
        val dirs = paths.map { it.substringBeforeLast('/', "") }.filter { it.isNotEmpty() }
        if (dirs.isEmpty()) return ""
        var common = dirs.first().split('/')
        for (d in dirs.drop(1)) {
            val parts = d.split('/')
            var n = 0
            while (n < common.size && n < parts.size && common[n] == parts[n]) n++
            common = common.take(n)
        }
        return common.joinToString("/")
    }

    /** What [at] holds: its sub-folders (by name) and its own tracks (by file name). */
    fun <T> level(items: List<T>, pathOf: (T) -> String?, at: String): Level<T> {
        val prefix = if (at.isEmpty()) "" else "$at/"
        val counts = sortedMapOf<String, Int>(String.CASE_INSENSITIVE_ORDER)
        val here = mutableListOf<Pair<String, T>>()
        for (item in items) {
            val path = pathOf(item) ?: continue
            if (!path.startsWith(prefix)) continue
            val rest = path.substring(prefix.length)
            val slash = rest.indexOf('/')
            if (slash < 0) here += rest to item
            else counts.merge(rest.substring(0, slash), 1, Int::plus)
        }
        return Level(
            counts.map { (name, n) -> Folder(name, prefix + name, n) },
            here.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.first }).map { it.second }
        )
    }

    /** Whether [path] is [folder] or below it. */
    fun isUnder(path: String, folder: String): Boolean {
        val f = folder.trimEnd('/')
        return path == f || path.startsWith("$f/")
    }
}
