package com.varuna.rustify.bridge

/**
 * Content identity as a sum type instead of a string with a prefix.
 *
 * The Kotlin half of point D (see `docs/stremio-core/PLAN-3.x.md` §3). It mirrors `TrackId` in
 * `core_engine/src/types/id.rs` exactly, string for string — the Rust type is checked by
 * `id.rs`'s round-trip tests and this one by [TrackRefTest], so the two codecs cannot drift apart
 * without a test going red.
 *
 * **Why the Kotlin half exists at all.** The README of the stremio-core evaluation proposed putting
 * the enum "in the core". That only solves half of it: both bugs this is meant to prevent happened
 * where identity is *manipulated*, and almost all of those places are Kotlin. A Rust enum gives the
 * Kotlin compiler nothing.
 *
 *  - **v2.11.3** — a `spotify:local:` URI was split on `:` and the duration was taken as the id, so
 *    every local track of the same length collided and duplicate `LazyColumn` keys crashed the
 *    playlist screen.
 *  - **v2.11.8** — a `ytm:` id reached the Spotify resolver; the fix was a hand-written
 *    `youtubeIdOf()` added at two call sites.
 *
 * **Nothing about the wire format changes.** `FullTrack.id` stays a `String`; [raw] emits exactly
 * what is stored today and [parse] reads it back. Persisted state, JNI payloads and Drive backups
 * are untouched. What changes is that `when (TrackRef.parse(id))` is *exhaustive*, so a new kind of
 * track cannot be quietly mishandled.
 */
sealed interface TrackRef {

    /** The string form — exactly what is stored in `FullTrack.id`. */
    val raw: String

    /** A real Spotify track: a bare base62 id, no prefix. */
    data class Spotify(val id: String) : TrackRef {
        override val raw: String get() = id
    }

    /** YouTube Music, stored as `ytm:{videoId}`. */
    data class Ytm(val videoId: String) : TrackRef {
        override val raw: String get() = TrackRef.PREFIX_YTM + videoId
    }

    /**
     * A file on this device, stored as `local:{uri}`. The URI is a `content://` or `file://` URI and
     * **contains colons of its own**, which is why parsing only ever strips the prefix.
     */
    data class Local(val uri: String) : TrackRef {
        override val raw: String get() = TrackRef.PREFIX_LOCAL + uri
    }

    /**
     * A local file added to a Spotify playlist from the desktop app, kept as its whole
     * `spotify:local:{artist}:{album}:{title}:{seconds}` URI.
     *
     * It carries **no Spotify id** — [spotifyId] is null here, which is what makes the v2.11.3 bug
     * unrepresentable rather than merely fixed.
     */
    data class SpotifyLocal(override val raw: String) : TrackRef

    /** The YouTube video id, if this is one. Replaces the loose `youtubeIdOf()` helpers. */
    val youtubeVideoId: String? get() = (this as? Ytm)?.videoId

    /** The Spotify track id, if there is one. Null for [SpotifyLocal] — deliberately. */
    val spotifyId: String? get() = (this as? Spotify)?.id

    /** The device URI, if the audio is a file on this device. */
    val localUri: String? get() = (this as? Local)?.uri

    /** Whether the audio lives on the device rather than behind a backend. */
    val isOnDevice: Boolean get() = this is Local

    /** Short stable name for logs and diagnostics. Never persisted. */
    val kind: String
        get() = when (this) {
            is Spotify -> "spotify"
            is Ytm -> "ytm"
            is Local -> "local"
            is SpotifyLocal -> "spotify-local"
        }

    companion object {
        const val PREFIX_YTM = "ytm:"
        const val PREFIX_LOCAL = "local:"
        const val PREFIX_SPOTIFY_LOCAL = "spotify:local:"

        /**
         * Reads any id the app stores. Returns null for a blank id or a prefix with nothing after
         * it — a missing id is a case callers should handle, not a crash.
         *
         * Note `localpl:` does not match `local:`: the sixth character is `p`, not `:`. That is
         * checked by a test rather than left to the reader's trust.
         */
        fun parse(raw: String?): TrackRef? = when (kindOf(raw)) {
            null -> null
            Kind.SPOTIFY_LOCAL -> SpotifyLocal(raw!!)
            Kind.YTM -> Ytm(raw!!.substring(PREFIX_YTM.length))
            Kind.LOCAL -> Local(raw!!.substring(PREFIX_LOCAL.length))
            Kind.SPOTIFY -> Spotify(raw!!)
        }

        /** What [kindOf] answers. Internal: the public surface is [parse] and the predicates. */
        private enum class Kind { SPOTIFY, YTM, LOCAL, SPOTIFY_LOCAL }

        /**
         * **The one place that knows the prefixes.** Both [parse] and every predicate go through it.
         *
         * Split out from [parse] for a reason that shows up in a profiler rather than a compiler:
         * the predicates are called from Compose composables, which re-run them on every
         * recomposition, and building a `TrackRef` just to ask "is this local?" allocated an object
         * and a substring each time. This answers without allocating, and there is still exactly one
         * copy of the prefix logic — which was the entire point of the type.
         */
        private fun kindOf(raw: String?): Kind? {
            if (raw.isNullOrBlank()) return null
            // Longest prefix first: `spotify:local:` must be recognised before anything treats the
            // string as a bare Spotify id. `localpl:` never matches `local:` — the sixth character
            // is 'p', not ':' — and that is checked by a test rather than left to trust.
            if (raw.startsWith(PREFIX_SPOTIFY_LOCAL)) return Kind.SPOTIFY_LOCAL
            if (raw.startsWith(PREFIX_YTM)) {
                return if (hasPayload(raw, PREFIX_YTM.length)) Kind.YTM else null
            }
            // A prefix check and a substring, never a split: splitting is what broke v2.11.3, and a
            // `content://` URI is full of colons.
            if (raw.startsWith(PREFIX_LOCAL)) {
                return if (hasPayload(raw, PREFIX_LOCAL.length)) Kind.LOCAL else null
            }
            return Kind.SPOTIFY
        }

        /**
         * Whether anything but whitespace follows [from].
         *
         * Exactly `raw.substring(from).isNotBlank()`, without the substring. Written out rather than
         * simplified to a length check because those are not the same thing: `"ytm: "` has a
         * payload by length and none by content, and quietly changing which one wins is the kind of
         * difference that shows up as a bug months later.
         */
        private fun hasPayload(raw: String, from: Int): Boolean {
            for (i in from until raw.length) {
                if (!raw[i].isWhitespace()) return true
            }
            return false
        }

        // ---------------------------------------------------------------------------------
        // Predicates for call sites that only need a yes/no.
        //
        // These exist so no caller writes `startsWith("ytm:")` again. A raw prefix test looks
        // harmless until you notice it also matches ids the prefix does not really own — the
        // reason `localpl:` needed its own test — and until the day a fourth kind arrives and
        // nothing tells you which of forty call sites forgot about it.
        //
        // Where a call site genuinely *branches* per kind, use an exhaustive `when` on [parse]
        // instead: that is the half a boolean cannot give you.
        // ---------------------------------------------------------------------------------

        // Allocation-free: they ask [kindOf], they do not build a TrackRef.
        fun isSpotify(raw: String?): Boolean = kindOf(raw) == Kind.SPOTIFY
        fun isYtm(raw: String?): Boolean = kindOf(raw) == Kind.YTM
        fun isLocal(raw: String?): Boolean = kindOf(raw) == Kind.LOCAL
        fun isSpotifyLocal(raw: String?): Boolean = kindOf(raw) == Kind.SPOTIFY_LOCAL

        /** The YouTube video id of `raw`, or null if it is not a YouTube Music track. */
        fun youtubeVideoIdOf(raw: String?): String? =
            if (kindOf(raw) == Kind.YTM) raw!!.substring(PREFIX_YTM.length) else null

        /** The device URI of `raw`, or null if the audio is not a file on this device. */
        fun localUriOf(raw: String?): String? =
            if (kindOf(raw) == Kind.LOCAL) raw!!.substring(PREFIX_LOCAL.length) else null
    }
}

/**
 * Playlist identity. Same idea, different prefixes.
 *
 * One honest gap, the same one the Rust side documents: a **remote YouTube Music playlist id has no
 * prefix**, so it cannot be told apart from a Spotify playlist id by looking at it — only the
 * calling screen knows. [parse] therefore yields [Spotify] for a bare id and [Ytm] is only ever
 * built explicitly. This type refuses to guess rather than pretending to know.
 */
sealed interface PlaylistRef {

    val raw: String

    data class Spotify(val id: String) : PlaylistRef {
        override val raw: String get() = id
    }

    /** A playlist that exists only on this device, stored as `localpl:{uuid}`. */
    data class Local(val id: String) : PlaylistRef {
        override val raw: String get() = PlaylistRef.PREFIX_LOCAL + id
    }

    /** A YouTube Music playlist that exists only on this device, stored as `ytmpl:{uuid}`. */
    data class YtmLocal(val id: String) : PlaylistRef {
        override val raw: String get() = PlaylistRef.PREFIX_YTM_LOCAL + id
    }

    /** A remote YouTube Music playlist. Never produced by [parse]; see the type docs. */
    data class Ytm(val id: String) : PlaylistRef {
        override val raw: String get() = id
    }

    /** Whether the playlist exists only on this device, in either flavour. */
    val isDeviceOnly: Boolean get() = this is Local || this is YtmLocal

    companion object {
        const val PREFIX_LOCAL = "localpl:"
        const val PREFIX_YTM_LOCAL = "ytmpl:"

        fun parse(raw: String?): PlaylistRef? = when (kindOf(raw)) {
            null -> null
            Kind.LOCAL -> Local(raw!!.substring(PREFIX_LOCAL.length))
            Kind.YTM_LOCAL -> YtmLocal(raw!!.substring(PREFIX_YTM_LOCAL.length))
            Kind.SPOTIFY -> Spotify(raw!!)
        }

        private enum class Kind { SPOTIFY, LOCAL, YTM_LOCAL }

        /** The one place that knows the playlist prefixes. Allocation-free, like its track twin. */
        private fun kindOf(raw: String?): Kind? {
            if (raw.isNullOrBlank()) return null
            if (raw.startsWith(PREFIX_LOCAL)) {
                return if (hasPayload(raw, PREFIX_LOCAL.length)) Kind.LOCAL else null
            }
            if (raw.startsWith(PREFIX_YTM_LOCAL)) {
                return if (hasPayload(raw, PREFIX_YTM_LOCAL.length)) Kind.YTM_LOCAL else null
            }
            return Kind.SPOTIFY
        }

        private fun hasPayload(raw: String, from: Int): Boolean {
            for (i in from until raw.length) {
                if (!raw[i].isWhitespace()) return true
            }
            return false
        }

        /** A playlist stored only on this device under `localpl:`. */
        fun isLocal(raw: String?): Boolean = kindOf(raw) == Kind.LOCAL

        /** A YouTube Music playlist stored only on this device under `ytmpl:`. */
        fun isYtmLocal(raw: String?): Boolean = kindOf(raw) == Kind.YTM_LOCAL

        /** The device-local id, without its prefix, for either flavour. */
        fun deviceLocalIdOf(raw: String?): String? = when (kindOf(raw)) {
            Kind.LOCAL -> raw!!.substring(PREFIX_LOCAL.length)
            Kind.YTM_LOCAL -> raw!!.substring(PREFIX_YTM_LOCAL.length)
            else -> null
        }
    }
}
