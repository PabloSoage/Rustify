package com.varuna.rustify.bridge

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Kotlin half of the wire contract.
 *
 * `core_engine/src/wire_fixtures.rs` serialises one fully-populated value of every type that
 * crosses JNI into `core_engine/tests/fixtures/wire/`, and those files are checked in. This test
 * parses the same files and asserts the whole decoded object, field by field, against what this
 * side expects.
 *
 * ## Why the assertion is the entire object
 *
 * Because the failure this exists to catch is a *rename*, and a rename does not throw. The Kotlin
 * decoder is deliberately tolerant in production — [RustifyJson] ignores unknown keys and falls back
 * to defaults for missing ones, because an old APK must not crash against a newer `.so`. That
 * tolerance is exactly what let `releaseDate` arrive as `""` for three releases. Asserting equality
 * against a distinctive value turns a silently-defaulted field into a failing test:
 * `expected <1997-09-22> but was <null>` names the field and the release it moved in.
 *
 * [STRICT] is the other half: unknown keys are an error here, so a field **added** in Rust with no
 * counterpart over here fails too, rather than being quietly dropped on every device.
 */
class SpotifyModelsContractTest {

    /**
     * Production tolerance, inverted. Everything else matches [RustifyJson] — the custom field
     * serializers ride on the annotations, so `cleanHtml` and `maximiseThumbnail` apply here too and
     * are part of what gets asserted.
     */
    private val strict = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
        encodeDefaults = false
    }

    private val fixtures: File by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "core_engine/tests/fixtures/wire")
            if (candidate.isDirectory) return@lazy candidate
            dir = dir.parentFile
        }
        throw IllegalStateException(
            "core_engine/tests/fixtures/wire not found above ${System.getProperty("user.dir")}. " +
                "Run `cargo test --lib wire_fixtures` in core_engine/ to write it."
        )
    }

    private inline fun <reified T> fixture(name: String): T =
        strict.decodeFromString(File(fixtures, "$name.json").readText())

    // ─── Shared expectations ─────────────────────────────────────────────────────────────────────

    private val image = SpotifyImage(
        url = "https://i.scdn.co/image/cover-640",
        height = 640,
        width = 480
    )

    private val simpleArtist = SimpleArtist(
        id = "artist-id",
        name = "Björk",
        externalUri = "spotify:artist:artist-id",
        images = listOf(image)
    )

    private val simpleAlbum = SimpleAlbum(
        id = "album-id",
        name = "Homogenic",
        externalUri = "spotify:album:album-id",
        releaseDate = "1997-09-22",
        releaseDatePrecision = "day",
        images = listOf(image),
        artists = listOf(simpleArtist),
        albumType = "album"
    )

    private val fullArtist = FullArtist(
        id = "artist-id",
        name = "Björk",
        externalUri = "spotify:artist:artist-id",
        images = listOf(image),
        genres = listOf("art pop", "electronic"),
        followersTotal = 4_200_000
    )

    private val fullTrack = FullTrack(
        id = "track-id",
        name = "Jóga",
        externalUri = "spotify:track:track-id",
        explicit = true,
        durationMs = 303_000,
        isrc = "GBAAA9700123",
        artists = listOf(simpleArtist),
        album = simpleAlbum,
        addedAt = "2024-03-01T10:11:12Z"
    )

    private val user = SpotifyUser(
        id = "user-id",
        name = "Pablo",
        externalUri = "spotify:user:user-id",
        images = listOf(image),
        followersTotal = 17,
        country = "ES",
        product = "premium"
    )

    /** The fixture's description is `<b>Best</b> of &amp; more`; `cleanHtml` runs on the way in. */
    private val simplePlaylist = SimplePlaylist(
        id = "playlist-id",
        name = "Road trip",
        description = "Best of & more",
        images = listOf(image),
        externalUri = "spotify:playlist:playlist-id",
        owner = user,
        tracks = PlaylistTracks(total = 142)
    )

    /** The fixture asks for `=w120-h120`; `maximiseThumbnail` rewrites it on the way in. */
    private val bigThumb = "https://lh3.googleusercontent.com/thumb=w720-h720"

    private val ytmArtistRef = YtmArtistRef(id = "UCartist", name = "Björk")

    private val ytmTrack = YtmTrack(
        videoId = "dQw4w9WgXcQ",
        title = "Jóga",
        artists = listOf(ytmArtistRef),
        albumId = "MPREb_album",
        durationSec = 303,
        thumbnailUrl = bigThumb,
        isExplicit = true
    )

    private val ytmAlbumSlim = YtmAlbumSlim(
        browseId = "MPREb_album",
        title = "Homogenic",
        year = 1997,
        thumbnailUrl = bigThumb
    )

    // ─── Spotify ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the common types are what the core says they are`() {
        assertEquals(image, fixture<SpotifyImage>("SpotifyImage"))
        assertEquals(simpleArtist, fixture<SimpleArtist>("SimpleArtist"))
        assertEquals(fullArtist, fixture<FullArtist>("FullArtist"))
        assertEquals(simpleAlbum, fixture<SimpleAlbum>("SimpleAlbum"))
        assertEquals(user, fixture<SpotifyUser>("SpotifyUser"))
    }

    @Test
    fun `an album carries its own metadata as well as the simple half`() {
        assertEquals(
            FullAlbum(
                id = "album-id",
                name = "Homogenic",
                externalUri = "spotify:album:album-id",
                releaseDate = "1997-09-22",
                releaseDatePrecision = "day",
                images = listOf(image),
                artists = listOf(simpleArtist),
                albumType = "album",
                totalTracks = 10,
                recordLabel = "One Little Indian",
                genres = listOf("art pop")
            ),
            fixture<FullAlbum>("FullAlbum")
        )
    }

    @Test
    fun `a track keeps its id nullable and its album nested`() {
        assertEquals(fullTrack, fixture<FullTrack>("FullTrack"))
    }

    /**
     * The count lives one level down, in `tracks.total`, and it is the field three shipped bugs were
     * about — "add all" adding 50 of 142 among them. Reading it off the wrong level would give
     * `null` here, not an error.
     */
    @Test
    fun `a playlist reports how many tracks it has, from one level down`() {
        val simple = fixture<SimplePlaylist>("SimplePlaylist")
        assertEquals(simplePlaylist, simple)
        assertEquals(142, simple.totalTracks)

        val full = fixture<FullPlaylist>("FullPlaylist")
        assertEquals(
            FullPlaylist(
                id = "playlist-id",
                name = "Road trip",
                description = "Best of & more",
                images = listOf(image),
                externalUri = "spotify:playlist:playlist-id",
                owner = user,
                tracks = PlaylistTracks(total = 142),
                collaborative = true,
                public = false
            ),
            full
        )
        assertEquals(142, full.totalTracks)
    }

    /**
     * The Rust enum is internally tagged, so the discriminator sits beside the payload's own fields
     * rather than wrapping them. That is read by hand on this side, which makes it the piece most
     * worth pinning — and the `artist` variant this side used to carry is the drift that pinning
     * would have caught.
     */
    @Test
    fun `a browse row reads both tagged variants and nothing else`() {
        val section = fixture<BrowseSection>("BrowseSection")
        assertEquals("featured", section.id)
        assertEquals("Featured", section.title)
        assertEquals(
            listOf(
                BrowseSectionItem.PlaylistItem(simplePlaylist),
                BrowseSectionItem.AlbumItem(simpleAlbum)
            ),
            section.items
        )
    }

    @Test
    fun `a page says how far it got and whether there is more`() {
        assertEquals(
            PaginatedResponse(
                items = listOf(fullTrack),
                total = 142,
                limit = 50,
                nextOffset = 50,
                hasMore = true
            ),
            fixture<PaginatedResponse<FullTrack>>("PaginatedResponse")
        )
    }

    @Test
    fun `search results come back already split by kind`() {
        assertEquals(
            NormalizedSearchResults(
                tracks = listOf(fullTrack),
                albums = listOf(simpleAlbum),
                artists = listOf(fullArtist),
                playlists = listOf(simplePlaylist)
            ),
            fixture<NormalizedSearchResults>("NormalizedSearchResults")
        )
    }

    // ─── The failure envelope ────────────────────────────────────────────────────────────────────

    /**
     * `kind` is the field the whole of E-P was about: it is what decides retry, refresh or give up,
     * and reading it wrong is the heart button silently not working an hour in. Two Rust types land
     * on [OperationResult] — the hand-written refusal with no `kind`, and the one `serialize_result`
     * builds for every bridge returning a `Result`, which has it.
     */
    @Test
    fun `a failure says what kind it was, or says nothing and means it`() {
        assertEquals(
            OperationResult(success = false, error = "no such playlist", kind = null),
            fixture<OperationResult>("OperationResult")
        )
        assertEquals(
            OperationResult(
                success = false,
                error = "Spotify API error 401: token expired",
                kind = "auth"
            ),
            fixture<OperationResult>("FailedOperation")
        )
    }

    @Test
    fun `a login carries the token under the name the engine writes`() {
        assertEquals(
            LoginResult(
                success = true,
                user = user,
                error = null,
                accessToken = "BQC-token",
                expiration = 1_759_000_000_000L,
                kind = null
            ),
            fixture<LoginResult>("LoginResult")
        )
    }

    // ─── Spotify lyrics (4.3) ────────────────────────────────────────────────────────────────────

    @Test
    fun `spotify lyrics arrive as timed lines`() {
        assertEquals(
            SpotifyLyrics(
                syncType = "LINE_SYNCED",
                lines = listOf(SpotifyLyricLine(startMs = 1230, words = "All these accidents")),
                provider = "MusixMatch"
            ),
            fixture<SpotifyLyrics>("SpotifyLyrics")
        )
    }

    // ─── InnerTube audio (4.1) ───────────────────────────────────────────────────────────────────

    @Test
    fun `an innertube stream carries its loudness and its expiry`() {
        assertEquals(
            com.varuna.rustify.audio.InnertubeAudio(
                url = "https://rr3---sn.googlevideo.com/videoplayback?expire=1792536061",
                mimeType = "audio/webm",
                itag = 251,
                bitrate = 140_095,
                contentLength = 5_025_760,
                loudnessDb = 5.67,
                approxDurationMs = 305_041,
                expiresAtMs = 1_792_536_061_000,
                client = "visionos-1.03"
            ),
            fixture<com.varuna.rustify.audio.InnertubeAudio>("InnertubeAudio")
        )
    }

    // ─── Listening history (4.0) ─────────────────────────────────────────────────────────────────

    private val play = com.varuna.rustify.metrics.PlayEvent(
        trackId = "4cOdK2wGLETKBW3PvgPWqT",
        isrc = "GBAYE9700120",
        trackName = "Jóga",
        artistIds = listOf("7w29UYBi0qsHi5RTcv3lmA"),
        artistNames = listOf("Björk"),
        albumId = "1DFixLWuPkv3KT3TnV35m3",
        albumName = "Homogenic",
        imageUrl = "https://i.scdn.co/image/play",
        durationMs = 305_000,
        startedAt = 1_700_000_000_000,
        listenedMs = 290_000,
        counted = true,
        completed = true,
        source = "spotify",
        origin = "spotify-extended"
    )

    @Test
    fun `a play and an import report are what the core writes`() {
        assertEquals(play, fixture<com.varuna.rustify.metrics.PlayEvent>("PlayEvent"))
        assertEquals(
            com.varuna.rustify.metrics.PlayImportReport(read = 12, added = 10, duplicates = 2),
            fixture<com.varuna.rustify.metrics.PlayImportReport>("PlayImportReport")
        )
    }

    @Test
    fun `the metrics screen reads the core's stats field by field`() {
        fun row(key: String, name: String) = com.varuna.rustify.metrics.TopRow(
            key = key, name = name, subtitle = "Björk",
            imageUrl = "https://i.scdn.co/image/row", plays = 7, ms = 2_100_000
        )
        assertEquals(
            com.varuna.rustify.metrics.PlayStats(
                totalEvents = 40, totalPlays = 30, distinctTracks = 9, distinctArtists = 4,
                distinctAlbums = 5, totalMs = 6_000_000, daysListened = 3, avgPerDayMin = 33,
                completionRate = 80,
                playsByHour = listOf(1, 2), msByHour = listOf(60_000L, 120_000L),
                playsByWeekday = listOf(3, 4), playsByMonth = listOf(5, 6),
                playsByYear = listOf(com.varuna.rustify.metrics.YearPlays(year = 2024, plays = 30)),
                cumulativeMin = listOf(10L, 20L), cumulativePlays = listOf(1L, 2L),
                minutesPerDay = listOf(10L, 10L),
                topTracks = listOf(row("4cOdK2wGLETKBW3PvgPWqT", "Jóga")),
                topArtists = listOf(row("7w29UYBi0qsHi5RTcv3lmA", "Björk")),
                topAlbums = listOf(row("1DFixLWuPkv3KT3TnV35m3", "Homogenic")),
                recent = listOf(play),
                currentStreakDays = 4,
                longestStreakDays = 11,
                milestones = listOf(
                    com.varuna.rustify.metrics.Milestone(
                        key = "7w29UYBi0qsHi5RTcv3lmA", name = "Björk", plays = 260, tier = "silver"
                    )
                ),
                previousPlays = 25,
                previousMs = 5_000_000
            ),
            fixture<com.varuna.rustify.metrics.PlayStats>("PlayStats")
        )
    }

    // ─── YouTube Music ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `the YouTube Music types are snake_case on the wire`() {
        assertEquals(ytmArtistRef, fixture<YtmArtistRef>("YtmArtistRef"))
        assertEquals(ytmTrack, fixture<YtmTrack>("YtmTrack"))
        assertEquals(ytmAlbumSlim, fixture<YtmAlbumSlim>("YtmAlbumSlim"))
        assertEquals(
            YtmAlbum(
                browseId = "MPREb_album",
                title = "Homogenic",
                artists = listOf(ytmArtistRef),
                year = 1997,
                thumbnailUrl = bigThumb,
                tracks = listOf(ytmTrack)
            ),
            fixture<YtmAlbum>("YtmAlbum")
        )
        assertEquals(
            YtmArtist(
                channelId = "UCartist",
                name = "Björk",
                thumbnailUrl = bigThumb,
                topTracks = listOf(ytmTrack),
                albums = listOf(ytmAlbumSlim)
            ),
            fixture<YtmArtist>("YtmArtist")
        )
        assertEquals(
            YtmPlaylist(
                playlistId = "PLplaylist",
                title = "Road trip",
                author = "Pablo",
                thumbnailUrl = bigThumb,
                tracks = listOf(ytmTrack)
            ),
            fixture<YtmPlaylist>("YtmPlaylist")
        )
        assertEquals(
            YtmSearchResults(
                tracks = listOf(ytmTrack),
                albums = listOf(ytmAlbumSlim),
                artists = listOf(ytmArtistRef),
                playlists = listOf(
                    YtmPlaylist(
                        playlistId = "PLplaylist",
                        title = "Road trip",
                        author = "Pablo",
                        thumbnailUrl = bigThumb,
                        tracks = emptyList()
                    )
                )
            ),
            fixture<YtmSearchResults>("YtmSearchResults")
        )
    }

    // ─── The two lists cannot drift apart either ─────────────────────────────────────────────────

    /**
     * A new wire type on the Rust side writes a fixture nothing over here reads, and that would be
     * invisible — the app would simply never learn to parse it. So the file list is part of the
     * contract: add a type in `wire_fixtures.rs`, and this fails until Kotlin knows about it.
     */
    @Test
    fun `every fixture the core writes has an assertion on this side`() {
        val covered = setOf(
            "SpotifyImage", "SimpleArtist", "FullArtist", "SimpleAlbum", "FullAlbum", "FullTrack",
            "SpotifyUser", "SimplePlaylist", "FullPlaylist", "BrowseSection", "PaginatedResponse",
            "NormalizedSearchResults", "LoginResult", "OperationResult", "FailedOperation",
            "YtmArtistRef", "YtmTrack", "YtmAlbumSlim", "YtmAlbum", "YtmArtist", "YtmPlaylist",
            "YtmSearchResults", "PlayEvent", "PlayImportReport", "PlayStats", "InnertubeAudio", "SpotifyLyrics"
        )
        val written = fixtures.listFiles { f -> f.extension == "json" }
            ?.map { it.nameWithoutExtension }
            ?.toSet()
            .orEmpty()

        assertTrue(
            "fixtures with no assertion in this test: ${written - covered}",
            (written - covered).isEmpty()
        )
        assertTrue(
            "asserted here but never written by the core: ${covered - written}",
            (covered - written).isEmpty()
        )
    }
}
