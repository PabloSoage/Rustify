package com.varuna.rustify.bridge

/**
 * Several artists in one local tag (`17` §3, after Auxio): `MediaMetadataRetriever` hands back the
 * artist as one string, so "A; B" was an artist called "A; B" that showed up under neither A nor B.
 *
 * The user picks which characters separate values. Only `;` by default: foobar2000 and Mp3tag write
 * it for multiple values and it is almost never part of a name, while `/`, `&`, `,` and `+` break
 * "AC/DC", "Simon & Garfunkel" or "Tyler, The Creator" — so those are opt-in, and a backslash keeps
 * one literal ("AC\/DC"). Pure, so tested on the JVM.
 */
object ArtistSeparators {
    const val PREF = "local_artist_separators"
    const val DEFAULT = ";"
    const val CHOICES = ";/&,+"

    fun split(raw: String, separators: String): List<String> {
        if (separators.isEmpty()) return listOf(raw.replace("\\", "").trim()).filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '\\' && i + 1 < raw.length && raw[i + 1] in separators -> { current.append(raw[i + 1]); i++ }
                c in separators -> { out += current.toString(); current.clear() }
                else -> current.append(c)
            }
            i++
        }
        out += current.toString()
        return out.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }
}
