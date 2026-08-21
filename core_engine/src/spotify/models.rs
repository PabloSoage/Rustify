#![allow(dead_code)]
// core_engine/src/spotify/models.rs
//
// Two tiers of types:
//
//   1. REST types  (RestXxx)  — private structs that Deserialize from the
//      Spotify Web API snake_case JSON.  They are only used inside this module
//      to decode HTTP responses from api.spotify.com/v1/*.
//
//   2. Domain types (FullTrack, SimpleAlbum, …) — the structs that Kotlin
//      receives.  They Serialize to camelCase JSON and are constructed either
//      from REST types (via From impls) or directly from GQL Value parsing.
//
// This strict separation is what eliminates the rename+alias+deserialize_with
// serde tangle that was silently corrupting REST deserialization.

use serde::{Deserialize, Serialize};

// =============================================================================
// ─── REST DESERIALIZE TIER ───────────────────────────────────────────────────
// =============================================================================

// ── Primitives ────────────────────────────────────────────────────────────────

#[derive(Debug, Deserialize, Clone)]
pub(crate) struct RestImage {
    pub url: String,
    pub height: Option<u32>,
    pub width: Option<u32>,
}

#[derive(Debug, Deserialize, Clone)]
pub(crate) struct RestExternalUrls {
    #[serde(default)]
    pub spotify: String,
}

#[derive(Debug, Deserialize, Clone)]
pub(crate) struct RestExternalIds {
    pub isrc: Option<String>,
}

#[derive(Debug, Deserialize, Clone)]
pub(crate) struct RestFollowers {
    pub total: u32,
}

// ── Artists ───────────────────────────────────────────────────────────────────

#[derive(Debug, Deserialize, Clone)]
pub(crate) struct RestSimpleArtist {
    pub id: Option<String>,
    pub name: String,
    pub external_urls: RestExternalUrls,
    #[serde(default)]
    pub images: Option<Vec<RestImage>>,
}

#[derive(Debug, Deserialize, Clone)]
pub(crate) struct RestFullArtist {
    pub id: Option<String>,
    pub name: String,
    pub external_urls: RestExternalUrls,
    #[serde(default)]
    pub images: Vec<RestImage>,
    #[serde(default)]
    pub genres: Vec<String>,
    pub followers: Option<RestFollowers>,
}

// ── Albums ────────────────────────────────────────────────────────────────────

#[derive(Debug, Deserialize, Clone)]
pub(crate) struct RestSimpleAlbum {
    pub id: Option<String>,
    pub name: String,
    pub external_urls: RestExternalUrls,
    pub release_date: Option<String>,
    pub release_date_precision: Option<String>,
    #[serde(default)]
    pub images: Vec<RestImage>,
    #[serde(default)]
    pub artists: Vec<RestSimpleArtist>,
    pub album_type: Option<String>,
}

#[derive(Debug, Deserialize, Clone)]
pub(crate) struct RestFullAlbum {
    pub id: Option<String>,
    pub name: String,
    pub external_urls: RestExternalUrls,
    pub release_date: Option<String>,
    pub release_date_precision: Option<String>,
    #[serde(default)]
    pub images: Vec<RestImage>,
    #[serde(default)]
    pub artists: Vec<RestSimpleArtist>,
    pub album_type: Option<String>,
    pub total_tracks: Option<u32>,
    pub label: Option<String>,
    #[serde(default)]
    pub genres: Vec<String>,
}

// ── Tracks ────────────────────────────────────────────────────────────────────

#[derive(Debug, Deserialize, Clone)]
pub(crate) struct RestFullTrack {
    pub id: Option<String>,
    pub name: String,
    pub external_urls: RestExternalUrls,
    #[serde(default)]
    pub explicit: bool,
    pub duration_ms: u32,
    #[serde(default)]
    pub external_ids: Option<RestExternalIds>,
    #[serde(default)]
    pub artists: Vec<RestSimpleArtist>,
    pub album: Option<RestSimpleAlbum>,
}

// ── Users / Playlists ─────────────────────────────────────────────────────────

#[derive(Debug, Deserialize, Clone)]
pub(crate) struct RestUser {
    pub id: String,
    pub display_name: Option<String>,
    pub external_urls: RestExternalUrls,
    #[serde(default)]
    pub images: Vec<RestImage>,
    pub followers: Option<RestFollowers>,
    pub country: Option<String>,
    pub product: Option<String>,
}

#[derive(Debug, Deserialize, Clone)]
pub(crate) struct RestPlaylistOwner {
    pub id: String,
    pub display_name: Option<String>,
    pub external_urls: RestExternalUrls,
    #[serde(default)]
    pub images: Vec<RestImage>,
}

#[derive(Debug, Deserialize, Clone)]
pub(crate) struct RestPlaylistTracksRef {
    pub total: u32,
}

#[derive(Debug, Deserialize, Clone)]
pub(crate) struct RestSimplePlaylist {
    pub id: String,
    pub name: String,
    pub description: Option<String>,
    #[serde(default)]
    pub images: Vec<RestImage>,
    pub external_urls: RestExternalUrls,
    pub owner: RestPlaylistOwner,
    pub tracks: Option<RestPlaylistTracksRef>,
}

#[derive(Debug, Deserialize, Clone)]
pub(crate) struct RestFullPlaylist {
    pub id: String,
    pub name: String,
    pub description: Option<String>,
    #[serde(default)]
    pub images: Vec<RestImage>,
    pub external_urls: RestExternalUrls,
    pub owner: RestPlaylistOwner,
    pub tracks: Option<RestPlaylistTracksRef>,
    #[serde(default)]
    pub collaborative: bool,
    pub public: Option<bool>,
}

// ── REST Paging ───────────────────────────────────────────────────────────────

#[derive(Debug, Deserialize)]
pub(crate) struct RestPaging<T> {
    #[serde(default = "Vec::new")]
    pub items: Vec<T>,
    pub total: u32,
    pub limit: u32,
    pub offset: u32,
    pub next: Option<String>,
}

#[derive(Debug, Deserialize)]
pub(crate) struct RestPlaylistTrackItem {
    pub track: Option<RestFullTrack>,
}

// ── REST Batch responses ──────────────────────────────────────────────────────

#[derive(Debug, Deserialize)]
pub(crate) struct RestBatchTracksResponse {
    #[serde(default = "Vec::new")]
    pub tracks: Vec<Option<RestFullTrack>>,
}

#[derive(Debug, Deserialize)]
pub(crate) struct RestBatchAlbumsResponse {
    #[serde(default = "Vec::new")]
    pub albums: Vec<Option<RestFullAlbum>>,
}

#[derive(Debug, Deserialize)]
pub(crate) struct RestBatchArtistsResponse {
    #[serde(default = "Vec::new")]
    pub artists: Vec<Option<RestFullArtist>>,
}

#[derive(Debug, Deserialize)]
pub(crate) struct RestTopTracksResponse {
    #[serde(default = "Vec::new")]
    pub tracks: Vec<RestFullTrack>,
}

#[derive(Debug, Deserialize)]
pub(crate) struct RestRelatedArtistsResponse {
    #[serde(default = "Vec::new")]
    pub artists: Vec<RestFullArtist>,
}

// =============================================================================
// ─── FROM CONVERSIONS: REST → DOMAIN ─────────────────────────────────────────
// =============================================================================

impl From<RestImage> for SpotifyImage {
    fn from(r: RestImage) -> Self {
        SpotifyImage { url: r.url, height: r.height, width: r.width }
    }
}

impl From<RestSimpleArtist> for SimpleArtist {
    fn from(r: RestSimpleArtist) -> Self {
        let id = r.id.unwrap_or_default();
        let external_uri = if r.external_urls.spotify.is_empty() {
            if id.is_empty() { String::new() } else { format!("https://open.spotify.com/artist/{}", id) }
        } else {
            r.external_urls.spotify
        };
        SimpleArtist {
            id,
            name: r.name,
            external_uri,
            images: r.images.map(|imgs| imgs.into_iter().map(SpotifyImage::from).collect()),
        }
    }
}

impl From<RestFullArtist> for FullArtist {
    fn from(r: RestFullArtist) -> Self {
        let id = r.id.unwrap_or_default();
        let external_uri = if r.external_urls.spotify.is_empty() {
            if id.is_empty() { String::new() } else { format!("https://open.spotify.com/artist/{}", id) }
        } else {
            r.external_urls.spotify
        };
        FullArtist {
            id,
            name: r.name,
            external_uri,
            images: r.images.into_iter().map(SpotifyImage::from).collect(),
            genres: r.genres,
            followers: r.followers.map(|f| f.total),
        }
    }
}

impl From<RestSimpleAlbum> for SimpleAlbum {
    fn from(r: RestSimpleAlbum) -> Self {
        let id = r.id.unwrap_or_default();
        let external_uri = if r.external_urls.spotify.is_empty() {
            if id.is_empty() { String::new() } else { format!("https://open.spotify.com/album/{}", id) }
        } else {
            r.external_urls.spotify
        };
        SimpleAlbum {
            id,
            name: r.name,
            external_uri,
            release_date: r.release_date,
            release_date_precision: r.release_date_precision,
            images: r.images.into_iter().map(SpotifyImage::from).collect(),
            artists: r.artists.into_iter().map(SimpleArtist::from).collect(),
            album_type: r.album_type,
        }
    }
}

impl From<RestFullAlbum> for FullAlbum {
    fn from(r: RestFullAlbum) -> Self {
        let id = r.id.unwrap_or_default();
        let external_uri = if r.external_urls.spotify.is_empty() {
            if id.is_empty() { String::new() } else { format!("https://open.spotify.com/album/{}", id) }
        } else {
            r.external_urls.spotify
        };
        FullAlbum {
            id,
            name: r.name,
            external_uri,
            release_date: r.release_date,
            release_date_precision: r.release_date_precision,
            images: r.images.into_iter().map(SpotifyImage::from).collect(),
            artists: r.artists.into_iter().map(SimpleArtist::from).collect(),
            album_type: r.album_type,
            total_tracks: r.total_tracks,
            record_label: r.label,
            genres: r.genres,
        }
    }
}

impl From<RestFullTrack> for FullTrack {
    fn from(r: RestFullTrack) -> Self {
        let external_uri = if r.external_urls.spotify.is_empty() {
            r.id.as_ref()
                .map(|id| format!("https://open.spotify.com/track/{}", id))
                .unwrap_or_default()
        } else {
            r.external_urls.spotify
        };
        let isrc = r.external_ids
            .and_then(|eids| eids.isrc)
            .unwrap_or_default();
        FullTrack {
            id: r.id,
            name: r.name,
            external_uri,
            explicit: r.explicit,
            duration_ms: r.duration_ms,
            isrc,
            artists: r.artists.into_iter().map(SimpleArtist::from).collect(),
            album: r.album.map(SimpleAlbum::from),
            added_at: None,
        }
    }
}

impl From<RestUser> for SpotifyUser {
    fn from(r: RestUser) -> Self {
        let external_uri = if r.external_urls.spotify.is_empty() {
            format!("https://open.spotify.com/user/{}", r.id)
        } else {
            r.external_urls.spotify
        };
        SpotifyUser {
            id: r.id,
            name: r.display_name,
            external_uri,
            images: r.images.into_iter().map(SpotifyImage::from).collect(),
            followers: r.followers.map(|f| f.total),
            country: r.country,
            product: r.product,
        }
    }
}

fn owner_to_spotify_user(o: RestPlaylistOwner) -> SpotifyUser {
    let external_uri = if o.external_urls.spotify.is_empty() {
        format!("https://open.spotify.com/user/{}", o.id)
    } else {
        o.external_urls.spotify
    };
    SpotifyUser {
        id: o.id,
        name: o.display_name,
        external_uri,
        images: o.images.into_iter().map(SpotifyImage::from).collect(),
        followers: None,
        country: None,
        product: None,
    }
}

impl From<RestSimplePlaylist> for SimplePlaylist {
    fn from(r: RestSimplePlaylist) -> Self {
        let external_uri = if r.external_urls.spotify.is_empty() {
            format!("https://open.spotify.com/playlist/{}", r.id)
        } else {
            r.external_urls.spotify
        };
        SimplePlaylist {
            id: r.id,
            name: r.name,
            description: r.description,
            images: r.images.into_iter().map(SpotifyImage::from).collect(),
            external_uri,
            owner: Some(owner_to_spotify_user(r.owner)),
            tracks: r.tracks.map(|t| PlaylistTracks { total: t.total }),
        }
    }
}

impl From<RestFullPlaylist> for FullPlaylist {
    fn from(r: RestFullPlaylist) -> Self {
        let external_uri = if r.external_urls.spotify.is_empty() {
            format!("https://open.spotify.com/playlist/{}", r.id)
        } else {
            r.external_urls.spotify
        };
        FullPlaylist {
            id: r.id,
            name: r.name,
            description: r.description,
            images: r.images.into_iter().map(SpotifyImage::from).collect(),
            external_uri,
            owner: Some(owner_to_spotify_user(r.owner)),
            tracks: r.tracks.map(|t| PlaylistTracks { total: t.total }),
            collaborative: r.collaborative,
            public: r.public,
        }
    }
}

// =============================================================================
// ─── DOMAIN / SERIALIZE TIER ─────────────────────────────────────────────────
// =============================================================================

// ── Authentication ────────────────────────────────────────────────────────────

/// Credentials obtained from the Spotify token endpoint (open.spotify.com).
/// Still needs Deserialize because we parse it directly from the REST response.
#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct SpotifyCredentials {
    #[serde(rename = "clientId")]
    pub client_id: String,

    #[serde(rename = "accessToken")]
    pub access_token: String,

    #[serde(rename = "accessTokenExpirationTimestampMs")]
    pub expiration: u64,

    #[serde(rename = "isAnonymous")]
    pub is_anonymous: bool,
}

/// TOTP nuance fetched from the GitHub gist.
#[derive(Debug, Deserialize, Clone)]
pub struct TotpNuance {
    pub v: u32,
    pub s: String,
}

/// Result returned to Kotlin after a login attempt.
#[derive(Debug, Serialize)]
pub struct LoginResult {
    pub success: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub user: Option<SpotifyUser>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
    #[serde(rename = "accessToken", skip_serializing_if = "Option::is_none")]
    pub access_token: Option<String>,
    #[serde(rename = "accessTokenExpirationTimestampMs", skip_serializing_if = "Option::is_none")]
    pub expiration: Option<u64>,
}

// ── Common ────────────────────────────────────────────────────────────────────

/// Image object used across artists, albums, playlists, and user profiles.
#[derive(Debug, Serialize, Clone)]
pub struct SpotifyImage {
    pub url: String,
    pub height: Option<u32>,
    pub width: Option<u32>,
}

// ── Artists ───────────────────────────────────────────────────────────────────

/// Simplified artist used in track/album listings.
#[derive(Debug, Serialize, Clone)]
pub struct SimpleArtist {
    pub id: String,
    pub name: String,
    #[serde(rename = "externalUri")]
    pub external_uri: String,
    pub images: Option<Vec<SpotifyImage>>,
}

/// Full artist with genres and follower count.
#[derive(Debug, Serialize, Clone)]
pub struct FullArtist {
    pub id: String,
    pub name: String,
    #[serde(rename = "externalUri")]
    pub external_uri: String,
    pub images: Vec<SpotifyImage>,
    pub genres: Vec<String>,
    pub followers: Option<u32>,
}

// ── Albums ────────────────────────────────────────────────────────────────────

/// Simplified album used in track listings and search results.
#[derive(Debug, Serialize, Clone)]
pub struct SimpleAlbum {
    pub id: String,
    pub name: String,
    #[serde(rename = "externalUri")]
    pub external_uri: String,
    #[serde(rename = "releaseDate")]
    pub release_date: Option<String>,
    #[serde(rename = "releaseDatePrecision")]
    pub release_date_precision: Option<String>,
    pub images: Vec<SpotifyImage>,
    pub artists: Vec<SimpleArtist>,
    #[serde(rename = "albumType")]
    pub album_type: Option<String>,
}

/// Full album with additional metadata.
#[derive(Debug, Serialize, Clone)]
pub struct FullAlbum {
    pub id: String,
    pub name: String,
    #[serde(rename = "externalUri")]
    pub external_uri: String,
    #[serde(rename = "releaseDate")]
    pub release_date: Option<String>,
    #[serde(rename = "releaseDatePrecision")]
    pub release_date_precision: Option<String>,
    pub images: Vec<SpotifyImage>,
    pub artists: Vec<SimpleArtist>,
    #[serde(rename = "albumType")]
    pub album_type: Option<String>,
    #[serde(rename = "totalTracks")]
    pub total_tracks: Option<u32>,
    #[serde(rename = "recordLabel")]
    pub record_label: Option<String>,
    pub genres: Vec<String>,
}

// ── Tracks ────────────────────────────────────────────────────────────────────

/// Full track with all metadata needed for display and matching.
#[derive(Debug, Serialize, Clone)]
pub struct FullTrack {
    pub id: Option<String>,
    pub name: String,
    #[serde(rename = "externalUri")]
    pub external_uri: String,
    pub explicit: bool,
    #[serde(rename = "durationMs")]
    pub duration_ms: u32,
    pub isrc: String,
    pub artists: Vec<SimpleArtist>,
    pub album: Option<SimpleAlbum>,
    #[serde(rename = "addedAt", skip_serializing_if = "Option::is_none")]
    pub added_at: Option<String>,
}

// ── Playlists ─────────────────────────────────────────────────────────────────

/// Playlist owner (simplified user profile).
pub type PlaylistOwner = SpotifyUser;

/// Track count container for playlists.
#[derive(Debug, Serialize, Clone)]
pub struct PlaylistTracks {
    pub total: u32,
}

/// Simplified playlist for library listings and search results.
#[derive(Debug, Serialize, Clone)]
pub struct SimplePlaylist {
    pub id: String,
    pub name: String,
    pub description: Option<String>,
    pub images: Vec<SpotifyImage>,
    #[serde(rename = "externalUri")]
    pub external_uri: String,
    pub owner: Option<PlaylistOwner>,
    pub tracks: Option<PlaylistTracks>,
}

/// Full playlist with collaborative and public flags.
#[derive(Debug, Serialize, Clone)]
pub struct FullPlaylist {
    pub id: String,
    pub name: String,
    pub description: Option<String>,
    pub images: Vec<SpotifyImage>,
    #[serde(rename = "externalUri")]
    pub external_uri: String,
    pub owner: Option<PlaylistOwner>,
    pub tracks: Option<PlaylistTracks>,
    pub collaborative: bool,
    pub public: Option<bool>,
}

// ── User Profile ──────────────────────────────────────────────────────────────

/// Current user's profile.
#[derive(Debug, Serialize, Clone)]
pub struct SpotifyUser {
    pub id: String,
    pub name: Option<String>,
    #[serde(rename = "externalUri")]
    pub external_uri: String,
    pub images: Vec<SpotifyImage>,
    /// Serialized as a plain integer to match what the Kotlin parser expects.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub followers: Option<u32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub country: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub product: Option<String>,
}

// ── Browse / Home ─────────────────────────────────────────────────────────────

/// A browse section combining featured playlists or new releases.
#[derive(Debug, Serialize, Clone)]
pub struct BrowseSection {
    pub id: String,
    pub title: String,
    pub items: Vec<BrowseSectionItem>,
}

/// A polymorphic item within a browse section.
#[derive(Debug, Serialize, Clone)]
#[serde(tag = "type")]
pub enum BrowseSectionItem {
    #[serde(rename = "playlist")]
    Playlist(SimplePlaylist),
    #[serde(rename = "album")]
    Album(SimpleAlbum),
}

// ── Pagination ────────────────────────────────────────────────────────────────

/// Unified paginated response sent to Kotlin.
#[derive(Debug, Serialize)]
pub struct PaginatedResponse<T: Serialize> {
    pub items: Vec<T>,
    pub total: u32,
    pub limit: u32,
    #[serde(rename = "nextOffset")]
    pub next_offset: Option<u32>,
    #[serde(rename = "hasMore")]
    pub has_more: bool,
}

// ── Search ────────────────────────────────────────────────────────────────────

/// Normalized search results sent to Kotlin.
#[derive(Debug, Serialize)]
pub struct NormalizedSearchResults {
    pub tracks: Vec<FullTrack>,
    pub albums: Vec<SimpleAlbum>,
    pub artists: Vec<FullArtist>,
    pub playlists: Vec<SimplePlaylist>,
}

// ── Internal ──────────────────────────────────────────────────────────────────

/// Server time response from open.spotify.com.
#[derive(Debug, Deserialize)]
pub struct ServerTimeResponse {
    #[serde(rename = "serverTime")]
    pub server_time: u64,
}

/// Generic success/error response sent back to Kotlin via JNI.
#[derive(Debug, Serialize)]
pub struct OperationResult {
    pub success: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

impl OperationResult {
    pub fn ok() -> Self {
        Self { success: true, error: None }
    }

    pub fn err(msg: impl Into<String>) -> Self {
        Self { success: false, error: Some(msg.into()) }
    }
}
#[cfg(test)]
mod tests {
    use super::*;

    // ============================================================================================
    // Why these exist
    //
    // This file was at 0 % coverage, and it is the worst place in the crate for that to be true. It
    // is the seam between Spotify's JSON and every type the app holds, and a break here **does not
    // fail to compile**: `serde` is asked for a field that is no longer there, a `#[serde(default)]`
    // hands back an empty string, and a track quietly arrives with no artist and no cover. Nothing
    // errors. The screen just looks wrong.
    //
    // So the tests are about the translation and not about the structs: real-shaped payloads in,
    // assertions on what came out.
    // ============================================================================================

    fn track_json() -> serde_json::Value {
        // The shape of `GET /v1/tracks/{id}`, trimmed to the fields we read.
        serde_json::json!({
            "id": "4cOdK2wGLETKBW3PvgPWqT",
            "name": "Never Gonna Give You Up",
            "external_urls": { "spotify": "https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT" },
            "explicit": false,
            "duration_ms": 213573,
            "external_ids": { "isrc": "GBARL9300135" },
            "artists": [{
                "id": "0gxyHStUsqpMadRV0Di1Qt",
                "name": "Rick Astley",
                "external_urls": { "spotify": "https://open.spotify.com/artist/0gxyHStUsqpMadRV0Di1Qt" }
            }],
            "album": {
                "id": "6eUW0wxWtzkFdaEFsTJto6",
                "name": "Whenever You Need Somebody",
                "external_urls": { "spotify": "https://open.spotify.com/album/6eUW0wxWtzkFdaEFsTJto6" },
                "release_date": "1987-11-12",
                "release_date_precision": "day",
                "album_type": "album",
                "images": [{ "url": "https://i.scdn.co/image/abc", "height": 640, "width": 640 }],
                "artists": []
            }
        })
    }

    #[test]
    fn a_rest_track_becomes_the_track_the_app_holds() {
        let rest: RestFullTrack = serde_json::from_value(track_json()).expect("shape must parse");
        let track = FullTrack::from(rest);

        assert_eq!(track.id.as_deref(), Some("4cOdK2wGLETKBW3PvgPWqT"));
        assert_eq!(track.name, "Never Gonna Give You Up");
        assert_eq!(track.duration_ms, 213_573);
        assert!(!track.explicit);
        // The ISRC lives one level down, under `external_ids`. Reading it from the wrong place gives
        // an empty string rather than an error, and an empty ISRC is what makes the YouTube matcher
        // fall back to guessing by title.
        assert_eq!(track.isrc, "GBARL9300135");
        assert_eq!(track.artists.len(), 1);
        assert_eq!(track.artists[0].name, "Rick Astley");
        let album = track.album.expect("the album must survive the conversion");
        assert_eq!(album.name, "Whenever You Need Somebody");
        assert_eq!(album.release_date.as_deref(), Some("1987-11-12"));
        assert_eq!(album.release_date_precision.as_deref(), Some("day"));
        assert_eq!(album.images.len(), 1);
        assert_eq!(album.images[0].width, Some(640));
    }

    #[test]
    fn a_track_with_everything_optional_missing_still_parses() {
        // Spotify omits fields rather than sending nulls, and a local track omits most of them. The
        // failure this guards against is the whole response being discarded because one optional
        // field was declared required.
        let minimal = serde_json::json!({
            "id": null,
            "name": "A local file",
            "external_urls": {},
            "duration_ms": 1000
        });
        let rest: RestFullTrack = serde_json::from_value(minimal).expect("must parse");
        let track = FullTrack::from(rest);

        assert!(track.id.is_none());
        assert_eq!(track.isrc, "", "no isrc is an empty one, not a failure");
        assert!(track.artists.is_empty());
        assert!(track.album.is_none());
        // With no id and no url there is nothing to build a link from, and inventing one would
        // produce a url that 404s.
        assert_eq!(track.external_uri, "");
    }

    #[test]
    fn a_missing_url_is_rebuilt_from_the_id_rather_than_left_empty() {
        let mut value = track_json();
        value["external_urls"] = serde_json::json!({});
        let rest: RestFullTrack = serde_json::from_value(value).unwrap();
        let track = FullTrack::from(rest);
        assert_eq!(
            track.external_uri,
            "https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT"
        );
    }

    #[test]
    fn an_artist_carries_its_genres_and_follower_count() {
        let value = serde_json::json!({
            "id": "0gxyHStUsqpMadRV0Di1Qt",
            "name": "Rick Astley",
            "external_urls": { "spotify": "https://open.spotify.com/artist/0gxyHStUsqpMadRV0Di1Qt" },
            "genres": ["dance pop", "new wave pop"],
            "followers": { "total": 3_500_000 },
            "images": [{ "url": "https://i.scdn.co/image/xyz", "height": 320, "width": 320 }]
        });
        let rest: RestFullArtist = serde_json::from_value(value).unwrap();
        let artist = FullArtist::from(rest);

        assert_eq!(artist.genres, vec!["dance pop", "new wave pop"]);
        // `followers` is an object on the wire and an integer in our type. Reading it as a number
        // straight off the wire silently yields none.
        assert_eq!(artist.followers, Some(3_500_000));
        assert_eq!(artist.images.len(), 1);
    }

    #[test]
    fn an_artist_with_no_followers_block_is_unknown_rather_than_zero() {
        // None and zero are different claims, and the UI shows one of them.
        let value = serde_json::json!({
            "id": "x", "name": "Someone", "external_urls": { "spotify": "" }
        });
        let rest: RestFullArtist = serde_json::from_value(value).unwrap();
        assert_eq!(FullArtist::from(rest).followers, None);
    }

    #[test]
    fn an_album_keeps_the_date_precision_it_was_given() {
        // The three precisions are the reason `calendar` exists. Losing the precision here would
        // put every year-only release on the 1st of January before the calendar ever saw it.
        for (date, precision) in [("1987-11-12", "day"), ("1987-11", "month"), ("1987", "year")] {
            let value = serde_json::json!({
                "id": "a", "name": "n",
                "external_urls": { "spotify": "" },
                "release_date": date,
                "release_date_precision": precision
            });
            let rest: RestSimpleAlbum = serde_json::from_value(value).unwrap();
            let album = SimpleAlbum::from(rest);
            assert_eq!(album.release_date.as_deref(), Some(date));
            assert_eq!(album.release_date_precision.as_deref(), Some(precision));
        }
    }

    #[test]
    fn a_user_profile_survives_a_free_account_with_no_pictures() {
        let value = serde_json::json!({
            "id": "someuser",
            "display_name": null,
            "external_urls": { "spotify": "https://open.spotify.com/user/someuser" },
            "product": "free"
        });
        let rest: RestUser = serde_json::from_value(value).unwrap();
        let user = SpotifyUser::from(rest);

        assert_eq!(user.id, "someuser");
        assert!(user.name.is_none());
        assert!(user.images.is_empty());
        assert_eq!(user.product.as_deref(), Some("free"));
        assert_eq!(user.followers, None);
    }

    #[test]
    fn what_we_serialise_is_what_the_kotlin_side_reads() {
        // The names crossing JNI are `camelCase`, and they are renames rather than the field names.
        // A rename dropped here compiles perfectly and arrives on the other side as a missing field,
        // which Kotlin reads as a default. This is the assertion that keeps the two in step.
        let rest: RestFullTrack = serde_json::from_value(track_json()).unwrap();
        let json = serde_json::to_value(FullTrack::from(rest)).unwrap();

        assert!(json.get("externalUri").is_some(), "externalUri");
        assert!(json.get("durationMs").is_some(), "durationMs");
        assert!(json.get("isrc").is_some(), "isrc");
        // `added_at` is absent rather than null when there is none: the Kotlin parser treats a
        // present-but-null field differently from a missing one.
        assert!(json.get("addedAt").is_none(), "addedAt must be omitted, not null");

        let album = json.get("album").unwrap();
        assert!(album.get("releaseDate").is_some(), "releaseDate");
        assert!(album.get("releaseDatePrecision").is_some(), "releaseDatePrecision");
        assert!(album.get("albumType").is_some(), "albumType");
    }

    #[test]
    fn an_operation_result_says_which_way_it_went() {
        let ok = serde_json::to_value(OperationResult { success: true, error: None }).unwrap();
        assert_eq!(ok["success"], serde_json::json!(true));

        let bad = serde_json::to_value(OperationResult::err("nope")).unwrap();
        assert_eq!(bad["success"], serde_json::json!(false));
        assert_eq!(bad["error"], serde_json::json!("nope"));
    }
}
