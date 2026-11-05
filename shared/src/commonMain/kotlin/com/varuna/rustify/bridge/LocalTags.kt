// shared/src/commonMain/kotlin/com/varuna/rustify/bridge/LocalTags.kt
package com.varuna.rustify.bridge

import kotlinx.serialization.Serializable

/**
 * What `readTags` answers (`audio::tags::LocalTags` in the core): a local file's tags and duration,
 * for the platforms that read them through the core rather than Android's `MediaMetadataRetriever`.
 */
@Serializable
data class LocalTags(
    val title: String? = null,
    /** Every value of the artist field; one value may still hold several artists ("A; B"). */
    val artists: List<String> = emptyList(),
    val album: String? = null,
    val albumArtist: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val durationMs: Long = 0,
    val isrc: String? = null,
    val year: Int? = null,
    val hasCover: Boolean = false,
)

/** What `readCover` answers (`audio::tags::Cover`): the embedded picture, base64. */
@Serializable
data class LocalCover(val mimeType: String = "", val data: String = "")

/** What `writeTags` takes (`audio::tags::TagsToWrite`). A null field leaves the file's as it is. */
@Serializable
data class TagsToWrite(
    val title: String? = null,
    val artists: List<String> = emptyList(),
    val album: String? = null,
    val albumArtist: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val isrc: String? = null,
    val year: Int? = null,
    /** The front cover, base64. */
    val cover: String? = null,
    val coverMimeType: String? = null,
)
