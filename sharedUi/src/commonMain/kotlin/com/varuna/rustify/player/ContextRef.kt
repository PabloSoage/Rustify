package com.varuna.rustify.player

/**
 * What a [PlaybackContext.id] names, parsed once instead of split on `:` where it is read.
 *
 * The id is stored ("where you left off", the ✓ per row, Drive's `player::places`) as one string,
 * `kind:value`, and the core keeps it opaque. Reading it back used to be a `split(':')` in the resume
 * code and a `startsWith("album:")` in [com.varuna.rustify.bridge.ListeningSession.stub] — the shape
 * CLAUDE.md rules out for track ids, for the same reason: a branch per kind wants an exhaustive
 * `when`, not a string test somebody forgets to extend.
 *
 * [wire] is the stored form and must not change for the kinds that exist: sessions already saved,
 * and those arriving from an older version over Drive, are read with [parse].
 */
sealed interface ContextRef {
    val wire: String

    data class Album(val albumId: String) : ContextRef {
        override val wire get() = "album:$albumId"
    }

    data class Playlist(val playlistId: String) : ContextRef {
        override val wire get() = "playlist:$playlistId"
    }

    /** A radio is regenerated from the track it was seeded on, so its value is that track's id. */
    data class Radio(val seedTrackId: String) : ContextRef {
        override val wire get() = "radio:$seedTrackId"
    }

    /** Your Spotify liked songs: one per account, so it carries no id of its own. */
    data object Liked : ContextRef {
        override val wire get() = PlaybackContext.LIKED_SONGS_ID
    }

    /**
     * A kind this version does not know — say, one added later and received over Drive. Kept with
     * its id rather than dropped, so it can still be shown and forgotten; it just cannot be resumed.
     */
    data class Unknown(override val wire: String) : ContextRef

    companion object {
        fun parse(id: String): ContextRef {
            val sep = id.indexOf(':')
            if (sep <= 0 || sep == id.length - 1) return Unknown(id)
            // Everything after the first ':' is the value: a Spotify id has none, but a value is
            // never cut short if one ever does.
            val value = id.substring(sep + 1)
            return when (id.substring(0, sep)) {
                "album" -> Album(value)
                "playlist" -> Playlist(value)
                "radio" -> Radio(value)
                "liked" -> Liked
                else -> Unknown(id)
            }
        }
    }
}
