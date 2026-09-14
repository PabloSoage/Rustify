// app/src/main/java/com/varuna/rustify/bridge/SpotifyModels.kt
package com.varuna.rustify.bridge

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Kotlin side of the engine's wire types.
 *
 * ## The shape is declared once, and it is declared in Rust
 *
 * Every type here mirrors one in `core_engine/src/spotify/models.rs` or
 * `core_engine/src/youtube/models.rs`. What used to sit underneath each of them was a hand-written
 * `fromJson` reading field by field out of a [JSONObject] — 500 lines whose only tie to the Rust it
 * mirrored was that somebody remembered. It did not hold: **eight** fields declared `String?` here
 * were read with `optString(name, "")` — `releaseDate`, `releaseDatePrecision`, `albumType`,
 * `recordLabel`, `description`, `country`, `product` and `LoginResult.error` — so an absent one
 * arrived as `""` and never as `null`. Three more were on `ExternalIds`, which nothing used and
 * which went with them. And `BrowseSectionItem` carried an `artist` variant the core has never
 * emitted.
 *
 * The decoder is now generated from the declaration below, and the declaration is pinned to the Rust
 * one by `SpotifyModelsContractTest`, which parses fixtures that `core_engine` itself writes
 * (`core_engine/tests/fixtures/wire/`). A field renamed on either side fails that test rather than
 * arriving as an empty string a screen later.
 *
 * ## Tolerant here, strict in the test
 *
 * [RustifyJson] ignores unknown keys and falls back to defaults for missing ones, because a phone
 * running an older APK against a newer `.so` must not crash. The contract test does the opposite —
 * unknown keys are an error there — which is what keeps the tolerance from hiding a real rename.
 */
internal val RustifyJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
    coerceInputValues = false
}

// =============================================================================
// CODEC HELPERS
//
// These carry no knowledge of any field. They exist so the call sites that already hold a
// JSONObject — the playback-state reader, the library caches, the YT Music store — keep working
// without each type growing a hand-written parser again.
// =============================================================================

internal inline fun <reified T> decodeWire(json: String): T = RustifyJson.decodeFromString(json)

internal inline fun <reified T> decodeWire(json: JSONObject): T = decodeWire(json.toString())

internal inline fun <reified T> decodeWireList(array: JSONArray?): List<T> =
    if (array == null) emptyList() else decodeWire(array.toString())

internal inline fun <reified T> encodeWire(value: T): JSONObject =
    JSONObject(RustifyJson.encodeToString(value))

internal inline fun <reified T> encodeWireArray(values: List<T>): JSONArray =
    JSONArray(RustifyJson.encodeToString(values))

// =============================================================================
// FIELD SERIALIZERS
//
// Four transforms that used to live inside the hand-written parsers. They stay on the wire boundary
// rather than becoming the caller's problem, because that is where they were.
// =============================================================================

/** Spotify puts markup in playlist descriptions. Strip it on the way in. */
internal object CleanHtmlSerializer : KSerializer<String?> {
    private val delegate = String.serializer().nullable
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun deserialize(decoder: Decoder): String? = delegate.deserialize(decoder)?.cleanHtml()
    override fun serialize(encoder: Encoder, value: String?) = delegate.serialize(encoder, value)
}

/**
 * `""` means absent.
 *
 * Only for reading files this app wrote before P: the old `toJson` put `""` where the Rust type has
 * `Option<String>`, so a stored favourite has `"album_id": ""` and must still come back as null.
 * New writes omit the key, which is what `Option` deserializes from.
 */
internal object BlankAsNullSerializer : KSerializer<String?> {
    private val delegate = String.serializer().nullable
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun deserialize(decoder: Decoder): String? =
        delegate.deserialize(decoder)?.takeIf { it.isNotBlank() }
    override fun serialize(encoder: Encoder, value: String?) = delegate.serialize(encoder, value)
}

/** `0` means absent — the same legacy-file concession as [BlankAsNullSerializer], for `year`. */
internal object ZeroAsNullSerializer : KSerializer<Int?> {
    private val delegate = Int.serializer().nullable
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun deserialize(decoder: Decoder): Int? =
        delegate.deserialize(decoder)?.takeIf { it > 0 }
    override fun serialize(encoder: Encoder, value: Int?) = delegate.serialize(encoder, value)
}

/** YouTube Music hands out thumbnails at whatever size it feels like. Ask for the big one. */
internal object ThumbnailSerializer : KSerializer<String> {
    private val delegate = String.serializer()
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun deserialize(decoder: Decoder): String =
        maximiseThumbnail(delegate.deserialize(decoder))
    override fun serialize(encoder: Encoder, value: String) = delegate.serialize(encoder, value)
}

// =============================================================================
// COMMON
// =============================================================================

@Serializable
data class SpotifyImage(
    val url: String = "",
    val height: Int? = null,
    val width: Int? = null
) {
    fun toJson(): JSONObject = encodeWire(this)

    companion object {
        fun fromJson(json: JSONObject): SpotifyImage = decodeWire(json)
        fun listFromJsonArray(array: JSONArray?): List<SpotifyImage> = decodeWireList(array)
    }
}

// =============================================================================
// AUTHENTICATION
// =============================================================================

@Serializable
data class LoginResult(
    val success: Boolean = false,
    val user: SpotifyUser? = null,
    val error: String? = null,
    val accessToken: String? = null,
    @SerialName("accessTokenExpirationTimestampMs") val expiration: Long? = null,
    /** What kind of failure, as stated by the engine. See `errorKindOf`. */
    val kind: String? = null
) {
    companion object {
        fun fromJson(json: JSONObject): LoginResult = decodeWire(json)
    }
}

/**
 * Generic success/error answer from a bridge.
 *
 * Two Rust types land here: `models::OperationResult` for the refusals built by hand, and
 * `lib::FailedOperation` — which `serialize_result` produces for every bridge returning a `Result`,
 * and which is the one that carries [kind].
 */
@Serializable
data class OperationResult(
    val success: Boolean = false,
    val error: String? = null,
    /**
     * What kind of failure the engine says this was — `auth`, `rateLimited`, `transient`,
     * `permanent` — or null when it did not say.
     *
     * Null is not an error: a handful of refusals are built by hand on the Rust side and have no
     * error type behind them to classify, and `errorKindOf` falls back to reading [error] for those.
     */
    val kind: String? = null
) {
    companion object {
        fun fromJson(json: JSONObject): OperationResult = decodeWire(json)
    }
}

// =============================================================================
// ARTISTS
// =============================================================================

@Serializable
data class SimpleArtist(
    val id: String = "",
    val name: String = "",
    val externalUri: String = "",
    val images: List<SpotifyImage>? = null
) {
    fun toJson(): JSONObject = encodeWire(this)

    companion object {
        fun fromJson(json: JSONObject): SimpleArtist = decodeWire(json)
        fun listFromJsonArray(array: JSONArray?): List<SimpleArtist> = decodeWireList(array)
    }
}

@Serializable
data class FullArtist(
    val id: String = "",
    val name: String = "",
    val externalUri: String = "",
    val images: List<SpotifyImage> = emptyList(),
    val genres: List<String> = emptyList(),
    @SerialName("followers") val followersTotal: Int? = null
) {
    fun toJson(): JSONObject = encodeWire(this)

    companion object {
        fun fromJson(json: JSONObject): FullArtist = decodeWire(json)
        fun listFromJsonArray(array: JSONArray?): List<FullArtist> = decodeWireList(array)
    }
}

// =============================================================================
// ALBUMS
// =============================================================================

@Serializable
data class SimpleAlbum(
    val id: String = "",
    val name: String = "",
    val externalUri: String = "",
    val releaseDate: String? = null,
    val releaseDatePrecision: String? = null,
    val images: List<SpotifyImage> = emptyList(),
    val artists: List<SimpleArtist> = emptyList(),
    val albumType: String? = null
) {
    fun toJson(): JSONObject = encodeWire(this)

    companion object {
        fun fromJson(json: JSONObject): SimpleAlbum = decodeWire(json)
        fun listFromJsonArray(array: JSONArray?): List<SimpleAlbum> = decodeWireList(array)
    }
}

@Serializable
data class FullAlbum(
    val id: String = "",
    val name: String = "",
    val externalUri: String = "",
    val releaseDate: String? = null,
    val releaseDatePrecision: String? = null,
    val images: List<SpotifyImage> = emptyList(),
    val artists: List<SimpleArtist> = emptyList(),
    val albumType: String? = null,
    val totalTracks: Int? = null,
    val recordLabel: String? = null,
    val genres: List<String> = emptyList()
) {
    fun toJson(): JSONObject = encodeWire(this)

    companion object {
        fun fromJson(json: JSONObject): FullAlbum = decodeWire(json)
        fun listFromJsonArray(array: JSONArray?): List<FullAlbum> = decodeWireList(array)
    }
}

// =============================================================================
// TRACKS
// =============================================================================

@Serializable
data class FullTrack(
    val id: String? = null,
    val name: String = "",
    val externalUri: String = "",
    val explicit: Boolean = false,
    val durationMs: Int = 0,
    val isrc: String = "",
    val artists: List<SimpleArtist> = emptyList(),
    val album: SimpleAlbum? = null,
    val addedAt: String? = null
) {
    fun toJson(): JSONObject = encodeWire(this)

    companion object {
        fun fromJson(json: JSONObject): FullTrack = decodeWire(json)
        fun listFromJsonArray(array: JSONArray?): List<FullTrack> = decodeWireList(array)
    }
}

// =============================================================================
// PLAYLISTS
// =============================================================================

typealias PlaylistOwner = SpotifyUser

/**
 * Track count container.
 *
 * Spotify nests the total one level down and the Rust type keeps that nesting, so this one does
 * too — [SimplePlaylist.totalTracks] is the accessor callers had before.
 */
@Serializable
data class PlaylistTracks(val total: Int = 0)

@Serializable
data class SimplePlaylist(
    val id: String = "",
    val name: String = "",
    @Serializable(with = CleanHtmlSerializer::class) val description: String? = null,
    val images: List<SpotifyImage> = emptyList(),
    val externalUri: String = "",
    val owner: PlaylistOwner? = null,
    val tracks: PlaylistTracks? = null
) {
    val totalTracks: Int? get() = tracks?.total

    fun toJson(): JSONObject = encodeWire(this)

    companion object {
        fun fromJson(json: JSONObject): SimplePlaylist = decodeWire(json)
        fun listFromJsonArray(array: JSONArray?): List<SimplePlaylist> = decodeWireList(array)
    }
}

@Serializable
data class FullPlaylist(
    val id: String = "",
    val name: String = "",
    @Serializable(with = CleanHtmlSerializer::class) val description: String? = null,
    val images: List<SpotifyImage> = emptyList(),
    val externalUri: String = "",
    val owner: PlaylistOwner? = null,
    val tracks: PlaylistTracks? = null,
    val collaborative: Boolean = false,
    val public: Boolean? = null
) {
    val totalTracks: Int? get() = tracks?.total

    companion object {
        fun fromJson(json: JSONObject): FullPlaylist = decodeWire(json)
    }
}

/**
 * A purely local playlist. Stores only "local:..." ids (references to live tracks in
 * [com.varuna.rustify.bridge.SpotifyRepository.localTracks]); the full FullTrack objects are
 * resolved by lookup when opened, to keep the file small and free of duplicated/stale metadata.
 * Its id is prefixed with "localpl:" to avoid colliding with Spotify ids.
 *
 * App-side only: this one has no Rust counterpart, so nothing in the contract test pins it.
 */
@Serializable
data class LocalPlaylist(
    val id: String = "",
    val name: String = "",
    val trackIds: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = encodeWire(this)

    companion object {
        fun fromJson(json: JSONObject): LocalPlaylist = decodeWire(json)
        fun listFromJsonArray(array: JSONArray?): List<LocalPlaylist> = decodeWireList(array)
    }
}

// =============================================================================
// USER
// =============================================================================

@Serializable
data class SpotifyUser(
    val id: String = "",
    val name: String? = null,
    val externalUri: String = "",
    val images: List<SpotifyImage> = emptyList(),
    @SerialName("followers") val followersTotal: Int? = null,
    val country: String? = null,
    val product: String? = null
) {
    fun toJson(): JSONObject = encodeWire(this)

    companion object {
        fun fromJson(json: JSONObject): SpotifyUser = decodeWire(json)
    }
}

// =============================================================================
// PAGINATION
// =============================================================================

@Serializable
data class PaginatedResponse<T>(
    val items: List<T> = emptyList(),
    val total: Int = 0,
    val limit: Int = 20,
    val nextOffset: Int? = null,
    val hasMore: Boolean = false
)

// =============================================================================
// SEARCH
// =============================================================================

@Serializable
data class NormalizedSearchResults(
    val tracks: List<FullTrack> = emptyList(),
    val albums: List<SimpleAlbum> = emptyList(),
    val artists: List<FullArtist> = emptyList(),
    val playlists: List<SimplePlaylist> = emptyList()
) {
    companion object {
        fun fromJson(json: JSONObject): NormalizedSearchResults = decodeWire(json)
    }
}

// =============================================================================
// BROWSE
// =============================================================================

@Serializable
data class BrowseSection(
    val id: String = "",
    val title: String = "",
    @Serializable(with = BrowseItemsSerializer::class)
    val items: List<BrowseSectionItem> = emptyList()
) {
    companion object {
        fun fromJson(json: JSONObject): BrowseSection = decodeWire(json)
        fun listFromJsonArray(array: JSONArray?): List<BrowseSection> = decodeWireList(array)
    }
}

/**
 * One item in a browse row.
 *
 * The Rust enum is `#[serde(tag = "type")]`, which puts the discriminator *inside* the object
 * alongside the playlist's or album's own fields. kotlinx's polymorphism writes the payload under a
 * nested key instead, so the two variants are read by hand here — the same shape serde produces, in
 * fifteen lines, rather than a second declaration of every field.
 *
 * There used to be a third variant, `ArtistItem`, with two screens rendering it. The core has never
 * had an `artist` variant, so neither branch could run. It is gone.
 */
sealed class BrowseSectionItem {
    data class PlaylistItem(val playlist: SimplePlaylist) : BrowseSectionItem()
    data class AlbumItem(val album: SimpleAlbum) : BrowseSectionItem()

    internal companion object {
        /** Null for a variant this build does not know — a newer core may add one. */
        fun fromElement(json: Json, element: JsonElement): BrowseSectionItem? {
            val obj = element as? JsonObject ?: return null
            // The tag comes back off before the payload is decoded, exactly as serde takes it off
            // before deserialising the variant. Leaving it on is only invisible because production
            // ignores unknown keys — which is the whole shape of bug this pair of tests exists for,
            // and it is how this line came to be written.
            val payload = JsonObject(obj - "type")
            return when (obj["type"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
                "playlist" -> PlaylistItem(json.decodeFromJsonElement(SimplePlaylist.serializer(), payload))
                "album" -> AlbumItem(json.decodeFromJsonElement(SimpleAlbum.serializer(), payload))
                else -> null
            }
        }

        fun toElement(json: Json, item: BrowseSectionItem): JsonObject {
            val (tag, payload) = when (item) {
                is PlaylistItem ->
                    "playlist" to json.encodeToJsonElement(SimplePlaylist.serializer(), item.playlist)
                is AlbumItem ->
                    "album" to json.encodeToJsonElement(SimpleAlbum.serializer(), item.album)
            }
            return JsonObject(payload.jsonObject + ("type" to JsonPrimitive(tag)))
        }
    }
}

internal object BrowseItemsSerializer : KSerializer<List<BrowseSectionItem>> {
    private val delegate = ListSerializer(JsonElement.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): List<BrowseSectionItem> {
        val input = decoder as? JsonDecoder
            ?: throw IllegalStateException("BrowseSection is only ever decoded from JSON")
        return delegate.deserialize(decoder)
            .mapNotNull { BrowseSectionItem.fromElement(input.json, it) }
    }

    override fun serialize(encoder: Encoder, value: List<BrowseSectionItem>) {
        val output = encoder as? JsonEncoder
            ?: throw IllegalStateException("BrowseSection is only ever encoded to JSON")
        output.encodeJsonElement(JsonArray(value.map { BrowseSectionItem.toElement(output.json, it) }))
    }
}

// =============================================================================
// HELPERS
// =============================================================================

fun String.cleanHtml(): String {
    return this.replace(Regex("<[^>]*>"), "")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&#39;", "'")
        .trim()
}

/**
 * Returns the best available cover URL for this track.
 * For Spotify tracks: the largest album image URL.
 * For local tracks: the file:// URI of the cached cover jpg (stored in externalUri).
 */
fun FullTrack.effectiveCoverUrl(): String? =
    album?.images?.maxByOrNull { it.width ?: 0 }?.url
        ?: externalUri.takeIf { it.isNotBlank() }

/**
 * Largest available source. The order of `images` is whatever Spotify's GraphQL returned in
 * `coverArt.sources`, which is **not** guaranteed to be widest-first, so anything that will be seen
 * at full size (sharing, full-screen art) has to ask for the biggest rather than take the first.
 */
internal fun List<SpotifyImage>.largest(): SpotifyImage? =
    maxByOrNull { (it.width ?: 0) * (it.height ?: 0) }

/** Maximise a YouTube Music thumbnail URL. Removes size-restricting params (`=w120-h120` → `=w720-h720`). */
internal fun maximiseThumbnail(url: String): String {
    if (url.isBlank()) return url
    return url
        .replace(Regex("""=w\d+-h\d+"""), "=w720-h720")
        .replace(Regex("""=s\d+"""), "=s720")
        .replace("/hqdefault.", "/maxresdefault.")
}

// =============================================================================
// YOUTUBE MUSIC MODELS
//
// Mirrors of core_engine/src/youtube/models.rs. snake_case on the wire, because those types are
// `Deserialize` as well and the core reads back what it wrote.
// =============================================================================

@Serializable
data class YtmArtistRef(
    val id: String = "",
    val name: String = ""
) {
    fun toJson(): JSONObject = encodeWire(this)

    companion object {
        fun fromJson(json: JSONObject): YtmArtistRef = decodeWire(json)
        fun listFromJsonArray(array: JSONArray?): List<YtmArtistRef> = decodeWireList(array)
        fun toJsonArray(list: List<YtmArtistRef>): JSONArray = encodeWireArray(list)
    }
}

@Serializable
data class YtmTrack(
    @SerialName("video_id") val videoId: String = "",
    val title: String = "",
    val artists: List<YtmArtistRef> = emptyList(),
    @SerialName("album_id")
    @Serializable(with = BlankAsNullSerializer::class) val albumId: String? = null,
    @SerialName("duration_sec") val durationSec: Int = 0,
    @SerialName("thumbnail_url")
    @Serializable(with = ThumbnailSerializer::class) val thumbnailUrl: String = "",
    @SerialName("is_explicit") val isExplicit: Boolean = false
) {
    fun toJson(): JSONObject = encodeWire(this)

    fun toFullTrack(): FullTrack = FullTrack(
        id = "ytm:$videoId", name = title, externalUri = "https://music.youtube.com/watch?v=$videoId",
        explicit = isExplicit, durationMs = durationSec * 1000, isrc = "",
        artists = artists.map { SimpleArtist(it.id, it.name, "", null) },
        album = SimpleAlbum("", "YouTube Music", "", null, null,
            listOf(SpotifyImage(maximiseThumbnail(thumbnailUrl), 720, 720)), emptyList(), null)
    )

    companion object {
        fun fromJson(json: JSONObject): YtmTrack = decodeWire(json)
        fun listFromJsonArray(array: JSONArray?): List<YtmTrack> = decodeWireList(array)
        fun toJsonArray(list: List<YtmTrack>): JSONArray = encodeWireArray(list)
    }
}

@Serializable
data class YtmAlbum(
    @SerialName("browse_id") val browseId: String = "",
    val title: String = "",
    val artists: List<YtmArtistRef> = emptyList(),
    @Serializable(with = ZeroAsNullSerializer::class) val year: Int? = null,
    @SerialName("thumbnail_url")
    @Serializable(with = ThumbnailSerializer::class) val thumbnailUrl: String = "",
    val tracks: List<YtmTrack> = emptyList()
) {
    fun toJson(): JSONObject = encodeWire(this)

    companion object {
        fun fromJson(json: JSONObject): YtmAlbum = decodeWire(json)
    }
}

@Serializable
data class YtmAlbumSlim(
    @SerialName("browse_id") val browseId: String = "",
    val title: String = "",
    @Serializable(with = ZeroAsNullSerializer::class) val year: Int? = null,
    @SerialName("thumbnail_url")
    @Serializable(with = ThumbnailSerializer::class) val thumbnailUrl: String = ""
) {
    fun toJson(): JSONObject = encodeWire(this)

    companion object {
        fun fromJson(json: JSONObject): YtmAlbumSlim = decodeWire(json)
        fun listFromJsonArray(array: JSONArray?): List<YtmAlbumSlim> = decodeWireList(array)
        fun toJsonArray(list: List<YtmAlbumSlim>): JSONArray = encodeWireArray(list)
    }
}

@Serializable
data class YtmArtist(
    @SerialName("channel_id") val channelId: String = "",
    val name: String = "",
    @SerialName("thumbnail_url")
    @Serializable(with = ThumbnailSerializer::class) val thumbnailUrl: String = "",
    @SerialName("top_tracks") val topTracks: List<YtmTrack> = emptyList(),
    val albums: List<YtmAlbumSlim> = emptyList()
) {
    companion object {
        fun fromJson(json: JSONObject): YtmArtist = decodeWire(json)
    }
}

@Serializable
data class YtmPlaylist(
    @SerialName("playlist_id") val playlistId: String = "",
    val title: String = "",
    @Serializable(with = BlankAsNullSerializer::class) val author: String? = null,
    @SerialName("thumbnail_url")
    @Serializable(with = ThumbnailSerializer::class) val thumbnailUrl: String = "",
    val tracks: List<YtmTrack> = emptyList()
) {
    companion object {
        fun fromJson(json: JSONObject): YtmPlaylist = decodeWire(json)
        fun listFromJsonArray(array: JSONArray?): List<YtmPlaylist> = decodeWireList(array)
    }
}

/** App-side only: the YT Music equivalent of [LocalPlaylist], with no Rust counterpart. */
@Serializable
data class YtmLocalPlaylist(
    val localId: String = "",
    val name: String = "",
    val items: List<YtmTrack> = emptyList(),
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
) {
    fun toJson(): JSONObject = encodeWire(this)

    companion object {
        fun fromJson(json: JSONObject): YtmLocalPlaylist = decodeWire(json)
        fun listFromJsonArray(array: JSONArray?): List<YtmLocalPlaylist> = decodeWireList(array)
        fun toJsonArray(list: List<YtmLocalPlaylist>): JSONArray = encodeWireArray(list)
    }
}

@Serializable
data class YtmSearchResults(
    val tracks: List<YtmTrack> = emptyList(),
    val albums: List<YtmAlbumSlim> = emptyList(),
    val artists: List<YtmArtistRef> = emptyList(),
    val playlists: List<YtmPlaylist> = emptyList()
) {
    companion object {
        fun fromJson(json: JSONObject): YtmSearchResults = decodeWire(json)
    }
}
