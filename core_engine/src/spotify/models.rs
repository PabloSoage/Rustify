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
    #[serde(rename = "refreshToken", skip_serializing_if = "Option::is_none")]
    pub refresh_token: Option<String>,
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