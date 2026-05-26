// core_engine/src/spotify/client.rs
//
// Central Spotify client — Hybrid GraphQL + REST approach.
// Auth uses open.spotify.com (NOT the official REST API).
// Data operations use BOTH GraphQL (for pagination/IDs) and REST (for details)
// exactly replicating the spotify-gql-client behavior.

use reqwest::{Client, header};
use std::sync::{OnceLock, RwLock};
use std::time::{SystemTime, UNIX_EPOCH};
use serde::{de::DeserializeOwned, Serialize};
use serde_json::Value;
use totp_rs::{Algorithm, TOTP, Secret};

use crate::spotify::models::*;

// =============================================================================
// GLOBAL SINGLETON
// =============================================================================

pub static SPOTIFY_CLIENT: OnceLock<RwLock<SpotifyClient>> = OnceLock::new();

pub fn get_spotify_client() -> &'static RwLock<SpotifyClient> {
    SPOTIFY_CLIENT.get_or_init(|| RwLock::new(SpotifyClient::new()))
}

// =============================================================================
// ERROR TYPE
// =============================================================================

#[derive(Debug)]
pub enum SpotifyError {
    NotAuthenticated,
    TokenExpired,
    ApiError(u16, String),
    NetworkError(String),
    ParseError(String),
    InternalError(String),
}

impl std::fmt::Display for SpotifyError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            SpotifyError::NotAuthenticated => write!(f, "User not authenticated"),
            SpotifyError::TokenExpired => write!(f, "Access token expired"),
            SpotifyError::ApiError(status, msg) => write!(f, "API error {}: {}", status, msg),
            SpotifyError::NetworkError(msg) => write!(f, "Network error: {}", msg),
            SpotifyError::ParseError(msg) => write!(f, "Parse error: {}", msg),
            SpotifyError::InternalError(msg) => write!(f, "Internal error: {}", msg),
        }
    }
}

impl std::error::Error for SpotifyError {}

impl From<reqwest::Error> for SpotifyError {
    fn from(e: reqwest::Error) -> Self {
        SpotifyError::NetworkError(e.to_string())
    }
}

impl From<serde_json::Error> for SpotifyError {
    fn from(e: serde_json::Error) -> Self {
        SpotifyError::ParseError(e.to_string())
    }
}

pub type SpotifyResult<T> = Result<T, SpotifyError>;

// =============================================================================
// GQL REQUEST TYPES
// =============================================================================

#[derive(Debug, Serialize)]
pub struct GqlRequest {
    pub variables: Value,
    #[serde(rename = "operationName")]
    pub operation_name: String,
    pub extensions: GqlExtensions,
}

#[derive(Debug, Serialize)]
pub struct GqlExtensions {
    #[serde(rename = "persistedQuery")]
    pub persisted_query: GqlPersistedQuery,
}

#[derive(Debug, Serialize)]
pub struct GqlPersistedQuery {
    pub version: u32,
    #[serde(rename = "sha256Hash")]
    pub sha256_hash: String,
}

// =============================================================================
// SPOTIFY CLIENT
// =============================================================================

const SPOTIFY_API_BASE: &str = "https://api.spotify.com/v1";
const SPOTIFY_GQL_BASE: &str = "https://api-partner.spotify.com/pathfinder/v2/query";

/// GitHub gist containing the TOTP nuance (shared secret + version).
const NUANCE_GIST_URL: &str = "https://api.github.com/gists/22ed9c6ba463899e933427f7de1f0eef";

/// Spotify server time endpoint for TOTP synchronization.
const SERVER_TIME_URL: &str = "https://open.spotify.com/api/server-time";

/// Generate a random-ish user agent to avoid fingerprinting.
fn generate_user_agent() -> String {
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_secs();
    let variant = now % 5;
    let chrome_minor = (now / 7) % 500;

    let os = match variant {
        0 => "Windows NT 10.0; Win64; x64",
        1 => "Macintosh; Intel Mac OS X 10_15_7",
        2 => "X11; Linux x86_64",
        3 => "Linux; Android 14",
        _ => "Windows NT 10.0; Win64; x64",
    };

    let is_mobile = os.contains("Android") || os.contains("iPhone");
    let mobile_token = if is_mobile { " Mobile" } else { "" };

    format!(
        "Mozilla/5.0 ({}) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.{}.0{} Safari/537.36",
        os, chrome_minor, mobile_token
    )
}

pub struct SpotifyClient {
    http: Client,
    credentials: Option<SpotifyCredentials>,
    sp_dc: Option<String>,
}

impl SpotifyClient {
    pub fn new() -> Self {
        Self {
            http: Client::builder()
                .user_agent(generate_user_agent())
                .build()
                .unwrap(),
            credentials: None,
            sp_dc: None,
        }
    }

    // =========================================================================
    // AUTHENTICATION
    // =========================================================================

    pub async fn login_with_sp_dc(&mut self, sp_dc: &str) -> SpotifyResult<LoginResult> {
        self.sp_dc = Some(sp_dc.to_string());

        match self.fetch_access_token(sp_dc).await {
            Ok(creds) => {
                self.credentials = Some(creds.clone());
                Ok(LoginResult {
                    success: true,
                    user: None,
                    error: None,
                    access_token: Some(creds.access_token),
                    expiration: Some(creds.expiration),
                })
            }
            Err(e) => {
                self.credentials = None;
                self.sp_dc = None;
                Ok(LoginResult {
                    success: false,
                    user: None,
                    error: Some(e.to_string()),
                    access_token: None,
                    expiration: None,
                })
            }
        }
    }

    pub async fn refresh_token(&mut self) -> SpotifyResult<()> {
        let sp_dc = self.sp_dc.clone()
            .ok_or(SpotifyError::NotAuthenticated)?;

        let creds = self.fetch_access_token(&sp_dc).await?;
        self.credentials = Some(creds);
        Ok(())
    }

    pub fn is_authenticated(&self) -> bool {
        self.credentials.is_some() && !self.is_expired()
    }

    pub fn is_expired(&self) -> bool {
        match &self.credentials {
            Some(creds) => {
                let now = SystemTime::now()
                    .duration_since(UNIX_EPOCH)
                    .unwrap()
                    .as_millis() as u64;
                now >= creds.expiration
            }
            None => true,
        }
    }

    pub fn logout(&mut self) {
        self.credentials = None;
        self.sp_dc = None;
    }

    pub async fn restore_session(
        &mut self,
        sp_dc: &str,
        access_token: Option<&str>,
        expiration: Option<u64>,
    ) -> SpotifyResult<LoginResult> {
        self.sp_dc = Some(sp_dc.to_string());
        if let (Some(token), Some(exp)) = (access_token, expiration) {
            let now = SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_millis() as u64;
            if now + 60000 < exp {
                let creds = SpotifyCredentials {
                    client_id: "".to_string(),
                    access_token: token.to_string(),
                    expiration: exp,
                    is_anonymous: false,
                };
                self.credentials = Some(creds);
                return Ok(LoginResult {
                    success: true,
                    user: None,
                    error: None,
                    access_token: Some(token.to_string()),
                    expiration: Some(exp),
                });
            }
        }
        self.login_with_sp_dc(sp_dc).await
    }

    // =========================================================================
    // AUTH INTERNAL HELPERS
    // =========================================================================

    async fn fetch_access_token(&self, sp_dc: &str) -> SpotifyResult<SpotifyCredentials> {
        // Fetch nuance and server time concurrently to speed up login
        let (nuance_res, server_time_res) = tokio::join!(
            self.fetch_nuance(),
            self.fetch_server_time()
        );

        let nuance = nuance_res?;
        let server_time = server_time_res?;
        let totp_code = self.generate_totp(&nuance.s, server_time)?;
        self.request_token(sp_dc, &totp_code, nuance.v).await
    }

    async fn fetch_nuance(&self) -> SpotifyResult<TotpNuance> {
        let res = self.http.get(NUANCE_GIST_URL)
            .header(header::ACCEPT, "application/vnd.github.v3+json")
            .send()
            .await?;

        if !res.status().is_success() {
            return Err(SpotifyError::ApiError(
                res.status().as_u16(),
                "Failed to fetch TOTP nuance from GitHub".into()
            ));
        }

        let gist: Value = res.json().await?;

        let content_str = gist["files"]["nuances.json"]["content"]
            .as_str()
            .ok_or(SpotifyError::ParseError("Missing nuances.json in gist".into()))?;

        let mut nuances: Vec<TotpNuance> = serde_json::from_str(content_str)
            .map_err(|e| SpotifyError::ParseError(format!("Failed to parse nuances: {}", e)))?;

        nuances.sort_by(|a, b| b.v.cmp(&a.v));
        nuances.into_iter().next()
            .ok_or(SpotifyError::ParseError("No nuances found in gist".into()))
    }

    async fn fetch_server_time(&self) -> SpotifyResult<u64> {
        let res = self.http.get(SERVER_TIME_URL)
            .send()
            .await?;

        if !res.status().is_success() {
            return Err(SpotifyError::ApiError(
                res.status().as_u16(),
                "Failed to fetch server time".into()
            ));
        }

        let time_resp: ServerTimeResponse = res.json().await
            .map_err(|e| SpotifyError::ParseError(format!("Failed to parse server time: {}", e)))?;

        Ok(time_resp.server_time)
    }

    fn generate_totp(&self, secret_b32: &str, timestamp_seconds: u64) -> SpotifyResult<String> {
        let secret_bytes = Secret::Encoded(secret_b32.to_string())
            .to_bytes()
            .map_err(|e| SpotifyError::InternalError(format!("Failed to decode TOTP secret: {}", e)))?;

        let totp = TOTP::new(
            Algorithm::SHA1,
            6,
            1,
            30,
            secret_bytes,
        ).map_err(|e| SpotifyError::InternalError(format!("Failed to create TOTP: {}", e)))?;

        Ok(totp.generate(timestamp_seconds))
    }

    async fn request_token(&self, sp_dc: &str, totp: &str, totp_ver: u32) -> SpotifyResult<SpotifyCredentials> {
        let url = format!(
            "https://open.spotify.com/api/token?reason=transport&productType=web-player&totp={}&totpServer={}&totpVer={}",
            totp, totp, totp_ver
        );

        let clean_sp_dc = sp_dc.trim_start_matches("sp_dc=").trim_end_matches(';');
        let cookie_header = format!("sp_dc={};", clean_sp_dc);

        let res = self.http.get(&url)
            .header(header::COOKIE, cookie_header)
            .header("App-Platform", "WebPlayer")
            .send()
            .await?;

        if !res.status().is_success() {
            let status = res.status().as_u16();
            let body = res.text().await.unwrap_or_default();
            return Err(SpotifyError::ApiError(status, format!("Token request failed: {}", body)));
        }

        let creds: SpotifyCredentials = res.json().await
            .map_err(|e| SpotifyError::ParseError(format!("Failed to parse token response: {}", e)))?;

        if creds.access_token.len() < 100 {
            eprintln!(
                "[Spotify] Warning: access token is only {} chars (expected ~374). Auth may not work.",
                creds.access_token.len()
            );
        }

        Ok(creds)
    }

    // =========================================================================
    // REST API (api.spotify.com/v1)
    // =========================================================================

    /// Get the current access token string.
    pub fn access_token(&self) -> SpotifyResult<&str> {
        self.credentials.as_ref()
            .map(|c| c.access_token.as_str())
            .ok_or(SpotifyError::NotAuthenticated)
    }

    /// Check if response was successful, extract error body if not.
    async fn check_response_success(res: reqwest::Response) -> SpotifyResult<reqwest::Response> {
        if !res.status().is_success() {
            let status = res.status().as_u16();
            let body = res.text().await.unwrap_or_default();
            return Err(SpotifyError::ApiError(status, body));
        }
        Ok(res)
    }

    pub async fn api_get<T: DeserializeOwned>(&self, path: &str) -> SpotifyResult<T> {
        let token = self.access_token()?;
        let url = format!("{}{}", SPOTIFY_API_BASE, path);

        let res = self.http.get(&url)
            .header(header::AUTHORIZATION, format!("Bearer {}", token))
            .header("App-Platform", "WebPlayer")
            .send()
            .await?;

        let res = Self::check_response_success(res).await?;
        res.json().await.map_err(|e| SpotifyError::ParseError(format!("api_get parse error: {}", e)))
    }

    pub async fn api_post<T: DeserializeOwned>(&self, path: &str, body: &Value) -> SpotifyResult<T> {
        let token = self.access_token()?;
        let url = format!("{}{}", SPOTIFY_API_BASE, path);

        let res = self.http.post(&url)
            .header(header::AUTHORIZATION, format!("Bearer {}", token))
            .header("App-Platform", "WebPlayer")
            .json(body)
            .send()
            .await?;

        let res = Self::check_response_success(res).await?;
        res.json().await.map_err(|e| SpotifyError::ParseError(format!("api_post parse error: {}", e)))
    }

    pub async fn api_put(&self, path: &str, body: &Value) -> SpotifyResult<()> {
        let token = self.access_token()?;
        let url = format!("{}{}", SPOTIFY_API_BASE, path);

        let res = self.http.put(&url)
            .header(header::AUTHORIZATION, format!("Bearer {}", token))
            .header("App-Platform", "WebPlayer")
            .json(body)
            .send()
            .await?;

        Self::check_response_success(res).await?;
        Ok(())
    }

    pub async fn api_delete(&self, path: &str, body: &Value) -> SpotifyResult<()> {
        let token = self.access_token()?;
        let url = format!("{}{}", SPOTIFY_API_BASE, path);

        let res = self.http.delete(&url)
            .header(header::AUTHORIZATION, format!("Bearer {}", token))
            .header("App-Platform", "WebPlayer")
            .json(body)
            .send()
            .await?;

        Self::check_response_success(res).await?;
        Ok(())
    }

    // =========================================================================
    // REST BATCH ENDPOINTS
    // =========================================================================

    /// Batch-fetch full tracks from REST API.  Uses RestBatchTracksResponse
    /// (snake_case) to deserialize correctly, then converts to domain FullTrack.
    pub async fn batch_get_tracks(&self, ids: &[String]) -> SpotifyResult<Vec<FullTrack>> {
        if ids.is_empty() {
            return Ok(vec![]);
        }

        let mut all_tracks: Vec<FullTrack> = Vec::new();
        // Spotify limits /v1/tracks to 50 IDs per request
        for chunk in ids.chunks(50) {
            let joined = chunk.join(",");
            let path = format!("/tracks?ids={}", joined);
            let response: RestBatchTracksResponse = self.api_get(&path).await?;
            all_tracks.extend(response.tracks.into_iter().flatten().map(FullTrack::from));
            tokio::time::sleep(std::time::Duration::from_millis(50)).await;
        }

        Ok(all_tracks)
    }

    /// Batch-fetch full albums from REST API.
    pub async fn batch_get_albums(&self, ids: &[String]) -> SpotifyResult<Vec<FullAlbum>> {
        if ids.is_empty() {
            return Ok(vec![]);
        }

        let mut all_albums: Vec<FullAlbum> = Vec::new();
        // Spotify limits /v1/albums to 20 IDs per request
        for chunk in ids.chunks(20) {
            let joined = chunk.join(",");
            let path = format!("/albums?ids={}", joined);
            let response: RestBatchAlbumsResponse = self.api_get(&path).await?;
            all_albums.extend(response.albums.into_iter().flatten().map(FullAlbum::from));
            tokio::time::sleep(std::time::Duration::from_millis(50)).await;
        }

        Ok(all_albums)
    }

    /// Batch-fetch full artists from REST API.
    pub async fn batch_get_artists(&self, ids: &[String]) -> SpotifyResult<Vec<FullArtist>> {
        if ids.is_empty() {
            return Ok(vec![]);
        }

        let mut all_artists: Vec<FullArtist> = Vec::new();
        // Spotify limits /v1/artists to 50 IDs per request
        for chunk in ids.chunks(50) {
            let joined = chunk.join(",");
            let path = format!("/artists?ids={}", joined);
            let response: RestBatchArtistsResponse = self.api_get(&path).await?;
            all_artists.extend(response.artists.into_iter().flatten().map(FullArtist::from));
            tokio::time::sleep(std::time::Duration::from_millis(50)).await;
        }

        Ok(all_artists)
    }

    // =========================================================================
    // GRAPHQL API (api-partner.spotify.com)
    // =========================================================================

    /// Perform a GraphQL persisted query POST to the Spotify partner API.
    pub async fn gql_post(&self, variables: Value, operation_name: &str, sha256_hash: &str) -> SpotifyResult<Value> {
        let token = self.access_token()?;

        let body = GqlRequest {
            variables,
            operation_name: operation_name.to_string(),
            extensions: GqlExtensions {
                persisted_query: GqlPersistedQuery {
                    version: 1,
                    sha256_hash: sha256_hash.to_string(),
                },
            },
        };

        let res = self.http.post(SPOTIFY_GQL_BASE)
            .header(header::AUTHORIZATION, format!("Bearer {}", token))
            .header(header::CONTENT_TYPE, "application/json")
            .header("App-Platform", "WebPlayer")
            .json(&body)
            .send()
            .await?;

        let res = Self::check_response_success(res).await?;

        let json: Value = res.json().await
            .map_err(|e| SpotifyError::ParseError(format!("GQL {} parse error: {}", operation_name, e)))?;

        if let Some(errors) = json.get("errors") {
            if let Some(arr) = errors.as_array() {
                if let Some(first) = arr.first() {
                    let msg = first.get("message")
                        .and_then(|m| m.as_str())
                        .unwrap_or("Unknown GraphQL error");
                    return Err(SpotifyError::ApiError(400, msg.to_string()));
                }
            }
        }
        if let Some(error) = json.get("error") {
            let msg = error.get("message")
                .and_then(|m| m.as_str())
                .unwrap_or("Unknown GraphQL error");
            return Err(SpotifyError::ApiError(400, msg.to_string()));
        }

        Ok(json)
    }
}

// =============================================================================
// GQL RESPONSE PARSING HELPERS
// =============================================================================

pub fn id_from_uri(uri: &str) -> Option<&str> {
    uri.split(':').last()
}

pub fn parse_images_from_sources(sources: &Value) -> Vec<SpotifyImage> {
    sources.as_array()
        .map(|arr| {
            arr.iter().filter_map(|s| {
                Some(SpotifyImage {
                    url: s["url"].as_str()?.to_string(),
                    height: s["height"].as_u64().map(|h| h as u32),
                    width: s["width"].as_u64().map(|w| w as u32),
                })
            }).collect()
        })
        .unwrap_or_default()
}

pub fn parse_images_nested(images_data: &Value) -> Vec<SpotifyImage> {
    images_data["items"]
        .as_array()
        .map(|imgs| {
            imgs.iter()
                .flat_map(|img| {
                    let empty = vec![];
                    img["sources"].as_array().unwrap_or(&empty).iter().filter_map(|s| {
                        Some(SpotifyImage {
                            url: s["url"].as_str()?.to_string(),
                            height: s["height"].as_u64().map(|h| h as u32),
                            width: s["width"].as_u64().map(|w| w as u32),
                        })
                    }).collect::<Vec<_>>()
                })
                .collect::<Vec<_>>()
        })
        .unwrap_or_default()
}

pub fn parse_gql_artists(artists_data: &Value) -> Vec<SimpleArtist> {
    let empty = vec![];
    artists_data["items"]
        .as_array()
        .unwrap_or(&empty)
        .iter()
        .filter_map(|a| {
            let uri = a["uri"].as_str()?;
            let artist_id = id_from_uri(uri)?.to_string();
            Some(SimpleArtist {
                id: artist_id.clone(),
                name: a["profile"]["name"].as_str().unwrap_or("").to_string(),
                external_uri: format!("https://open.spotify.com/artist/{}", artist_id),
                images: None,
            })
        })
        .collect()
}

pub fn parse_gql_track(track: &Value) -> Option<FullTrack> {
    let uri = track.get("uri").or_else(|| track.get("_uri"))
        .and_then(|v| v.as_str())?;
    let track_id = id_from_uri(uri)?.to_string();

    let duration_ms = track["duration"]["totalMilliseconds"]
        .as_u64()
        .unwrap_or(0) as u32;

    let explicit = track["contentRating"]["label"].as_str() == Some("EXPLICIT");

    let artists = parse_gql_artists(&track["artists"]);

    let album = track.get("albumOfTrack").and_then(|album_data| {
        let album_uri = album_data["uri"].as_str()?;
        let album_id = id_from_uri(album_uri)?.to_string();

        let images = parse_images_from_sources(&album_data["coverArt"]["sources"]);
        let album_artists = parse_gql_artists(&album_data["artists"]);

        Some(SimpleAlbum {
            id: album_id.clone(),
            name: album_data["name"].as_str().unwrap_or("").to_string(),
            external_uri: format!("https://open.spotify.com/album/{}", album_id),
            release_date: None,
            release_date_precision: None,
            images,
            artists: album_artists,
            album_type: None,
        })
    });

    let isrc = track.get("externalIds")
        .and_then(|eids| eids["isrc"].as_str())
        .unwrap_or("")
        .to_string();

    Some(FullTrack {
        id: Some(track_id.clone()),
        name: track["name"].as_str().unwrap_or("").to_string(),
        duration_ms,
        explicit,
        artists,
        album,
        external_uri: format!("https://open.spotify.com/track/{}", track_id),
        isrc,
    })
}