// app/src/main/java/com/varuna/rustify/bridge/SpotifyModelsJson.kt
package com.varuna.rustify.bridge

import com.varuna.rustify.player.PlaybackContext
import org.json.JSONArray
import org.json.JSONObject

/*
 * The org.json half of the wire types, for the Android call sites that already hold a JSONObject —
 * the playback-state reader, the library caches, the YT Music store.
 *
 * The types themselves moved to the shared module (`shared/.../bridge/SpotifyModels.kt`) in 5.x, and
 * org.json went no further than the app: it ships with Android and does not exist on iOS. What used
 * to be members (`track.toJson()`, `FullTrack.fromJson(obj)`) are extensions with the same names, so
 * call sites in this package read exactly as before; elsewhere they need the import.
 *
 * Nothing here knows a field. The decoders are still the generated ones in the shared module.
 */

inline fun <reified T> decodeWire(json: JSONObject): T = decodeWire(json.toString())

inline fun <reified T> decodeWireList(array: JSONArray?): List<T> =
    if (array == null) emptyList() else decodeWire(array.toString())

inline fun <reified T> encodeWire(value: T): JSONObject = JSONObject(encodeWireString(value))

inline fun <reified T> encodeWireArray(values: List<T>): JSONArray =
    JSONArray(encodeWireString(values))

fun SpotifyImage.toJson(): JSONObject = encodeWire(this)
fun SpotifyImage.Companion.fromJson(json: JSONObject): SpotifyImage = decodeWire(json)
fun SpotifyImage.Companion.listFromJsonArray(array: JSONArray?): List<SpotifyImage> = decodeWireList(array)
fun LoginResult.Companion.fromJson(json: JSONObject): LoginResult = decodeWire(json)
fun OperationResult.Companion.fromJson(json: JSONObject): OperationResult = decodeWire(json)
fun SimpleArtist.toJson(): JSONObject = encodeWire(this)
fun SimpleArtist.Companion.fromJson(json: JSONObject): SimpleArtist = decodeWire(json)
fun SimpleArtist.Companion.listFromJsonArray(array: JSONArray?): List<SimpleArtist> = decodeWireList(array)
fun FullArtist.toJson(): JSONObject = encodeWire(this)
fun FullArtist.Companion.fromJson(json: JSONObject): FullArtist = decodeWire(json)
fun FullArtist.Companion.listFromJsonArray(array: JSONArray?): List<FullArtist> = decodeWireList(array)
fun SimpleAlbum.toJson(): JSONObject = encodeWire(this)
fun SimpleAlbum.Companion.fromJson(json: JSONObject): SimpleAlbum = decodeWire(json)
fun SimpleAlbum.Companion.listFromJsonArray(array: JSONArray?): List<SimpleAlbum> = decodeWireList(array)
fun FullAlbum.toJson(): JSONObject = encodeWire(this)
fun FullAlbum.Companion.fromJson(json: JSONObject): FullAlbum = decodeWire(json)
fun FullAlbum.Companion.listFromJsonArray(array: JSONArray?): List<FullAlbum> = decodeWireList(array)
fun FullTrack.toJson(): JSONObject = encodeWire(this)
fun FullTrack.Companion.fromJson(json: JSONObject): FullTrack = decodeWire(json)
fun FullTrack.Companion.listFromJsonArray(array: JSONArray?): List<FullTrack> = decodeWireList(array)
fun SimplePlaylist.toJson(): JSONObject = encodeWire(this)
fun SimplePlaylist.Companion.fromJson(json: JSONObject): SimplePlaylist = decodeWire(json)
fun SimplePlaylist.Companion.listFromJsonArray(array: JSONArray?): List<SimplePlaylist> = decodeWireList(array)
fun FullPlaylist.Companion.fromJson(json: JSONObject): FullPlaylist = decodeWire(json)
fun LocalPlaylist.toJson(): JSONObject = encodeWire(this)
fun LocalPlaylist.Companion.fromJson(json: JSONObject): LocalPlaylist = decodeWire(json)
fun LocalPlaylist.Companion.listFromJsonArray(array: JSONArray?): List<LocalPlaylist> = decodeWireList(array)
fun SpotifyUser.toJson(): JSONObject = encodeWire(this)
fun SpotifyUser.Companion.fromJson(json: JSONObject): SpotifyUser = decodeWire(json)
fun NormalizedSearchResults.Companion.fromJson(json: JSONObject): NormalizedSearchResults = decodeWire(json)
fun BrowseSection.Companion.fromJson(json: JSONObject): BrowseSection = decodeWire(json)
fun BrowseSection.Companion.listFromJsonArray(array: JSONArray?): List<BrowseSection> = decodeWireList(array)
fun YtmArtistRef.toJson(): JSONObject = encodeWire(this)
fun YtmArtistRef.Companion.fromJson(json: JSONObject): YtmArtistRef = decodeWire(json)
fun YtmArtistRef.Companion.listFromJsonArray(array: JSONArray?): List<YtmArtistRef> = decodeWireList(array)
fun YtmArtistRef.Companion.toJsonArray(list: List<YtmArtistRef>): JSONArray = encodeWireArray(list)
fun YtmTrack.toJson(): JSONObject = encodeWire(this)
fun YtmTrack.Companion.fromJson(json: JSONObject): YtmTrack = decodeWire(json)
fun YtmTrack.Companion.listFromJsonArray(array: JSONArray?): List<YtmTrack> = decodeWireList(array)
fun YtmTrack.Companion.toJsonArray(list: List<YtmTrack>): JSONArray = encodeWireArray(list)
fun YtmAlbum.toJson(): JSONObject = encodeWire(this)
fun YtmAlbum.Companion.fromJson(json: JSONObject): YtmAlbum = decodeWire(json)
fun YtmAlbumSlim.toJson(): JSONObject = encodeWire(this)
fun YtmAlbumSlim.Companion.fromJson(json: JSONObject): YtmAlbumSlim = decodeWire(json)
fun YtmAlbumSlim.Companion.listFromJsonArray(array: JSONArray?): List<YtmAlbumSlim> = decodeWireList(array)
fun YtmAlbumSlim.Companion.toJsonArray(list: List<YtmAlbumSlim>): JSONArray = encodeWireArray(list)
fun YtmArtist.Companion.fromJson(json: JSONObject): YtmArtist = decodeWire(json)
fun YtmPlaylist.Companion.fromJson(json: JSONObject): YtmPlaylist = decodeWire(json)
fun YtmPlaylist.Companion.listFromJsonArray(array: JSONArray?): List<YtmPlaylist> = decodeWireList(array)
fun YtmLocalPlaylist.toJson(): JSONObject = encodeWire(this)
fun YtmLocalPlaylist.Companion.fromJson(json: JSONObject): YtmLocalPlaylist = decodeWire(json)
fun YtmLocalPlaylist.Companion.listFromJsonArray(array: JSONArray?): List<YtmLocalPlaylist> = decodeWireList(array)
fun YtmLocalPlaylist.Companion.toJsonArray(list: List<YtmLocalPlaylist>): JSONArray = encodeWireArray(list)
fun YtmSearchResults.Companion.fromJson(json: JSONObject): YtmSearchResults = decodeWire(json)

// Not a wire type: where the queue came from, as playback_state.json keeps it beside the tracks.
fun PlaybackContext.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("label", label)
    put("subtitle", subtitle)
    put("imageUrl", imageUrl)
}

/**
 * Reads a context back out of the saved playback state.
 *
 * It is saved at all because it was not, and the queue was: after the process was killed the
 * queue came back and the context did not, so playback carried on into a `null` context and
 * both the things keyed on one went quiet — no ✓ on the tracks being finished, and no update
 * to "pick up where you left off". Nothing looked broken, which is why it took a while to
 * notice: the music played fine.
 *
 * Null for a state file written before this field existed, which is the behaviour that was
 * there anyway.
 */
fun PlaybackContext.Companion.fromJson(json: JSONObject?): PlaybackContext? {
    val id = json?.optString("id").orEmpty()
    if (id.isBlank()) return null
    return PlaybackContext(
        id = id,
        label = json!!.optString("label"),
        subtitle = json.optString("subtitle"),
        imageUrl = json.optString("imageUrl")
    )
}
