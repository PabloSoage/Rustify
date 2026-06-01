// core_engine/src/spotify/client.rs
//
// Central Spotify client — Hybrid GraphQL + REST approach.
// Auth uses open.spotify.com (NOT the official REST API).
// Data operations use BOTH GraphQL (for pagination/IDs) and REST (for details)
// exactly replicating the spotify-gql-client behavior.

use reqwest::{Client, header};
use std::sync::{OnceLock, RwLock};
use std::time::{SystemTime, UNIX_EPOCH};
use serde::{de::DeserializeOwned, Deserialize, Serialize};
use serde_json::Value;
use totp_rs::{Algorithm, TOTP, Secret};
use regex::Regex;
use std::collections::HashMap;

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
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36".to_string()
}

pub struct SpotifyClient {
    http: Client,
    credentials: Option<SpotifyCredentials>,
    sp_dc: Option<String>,
    refresh_token: RwLock<Option<String>>,
    client_credentials: RwLock<Option<String>>,
    client_credentials_expiration: RwLock<u64>,
    developer_client_id: RwLock<Option<String>>,
    developer_client_secret: RwLock<Option<String>>,
    gql_hashes: RwLock<HashMap<String, String>>,
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
            refresh_token: RwLock::new(None),
            client_credentials: RwLock::new(None),
            client_credentials_expiration: RwLock::new(0),
            developer_client_id: RwLock::new(None),
            developer_client_secret: RwLock::new(None),
            gql_hashes: RwLock::new(HashMap::new()),
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
                    refresh_token: None,
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
                    refresh_token: None,
                })
            }
        }
    }

    pub async fn login_with_auth_code(&mut self, code: &str, redirect_uri: &str) -> SpotifyResult<LoginResult> {
        let client_id = {
            let id = self.developer_client_id.read().unwrap();
            id.clone().ok_or(SpotifyError::InternalError("Client ID not configured".into()))?
        };
        let client_secret = {
            let secret = self.developer_client_secret.read().unwrap();
            secret.clone().ok_or(SpotifyError::InternalError("Client Secret not configured".into()))?
        };

        let url = "https://accounts.spotify.com/api/token";
        let res = self.http.post(url)
            .basic_auth(&client_id, Some(&client_secret))
            .form(&[
                ("grant_type", "authorization_code"),
                ("code", code),
                ("redirect_uri", redirect_uri),
            ])
            .send()
            .await?;

        if !res.status().is_success() {
            let status = res.status().as_u16();
            let body = res.text().await.unwrap_or_default();
            return Err(SpotifyError::ApiError(status, format!("Auth code exchange failed: {}", body)));
        }

        #[derive(Deserialize)]
        struct TokenResponse {
            access_token: String,
            refresh_token: Option<String>,
            expires_in: u64,
        }

        let resp: TokenResponse = res.json().await?;
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_millis() as u64;

        let creds = SpotifyCredentials {
            client_id: client_id.clone(),
            access_token: resp.access_token.clone(),
            expiration: now + (resp.expires_in * 1000),
            is_anonymous: false,
        };

        self.credentials = Some(creds.clone());
        if let Some(ref rt) = resp.refresh_token {
            *self.refresh_token.write().unwrap() = Some(rt.clone());
        }

        Ok(LoginResult {
            success: true,
            user: None,
            error: None,
            access_token: Some(creds.access_token),
            expiration: Some(creds.expiration),
            refresh_token: resp.refresh_token.clone(),
        })
    }

    pub async fn refresh_token(&mut self) -> SpotifyResult<()> {
        if let Some(ref rt) = *self.refresh_token.read().unwrap() {
            let client_id = {
                let id = self.developer_client_id.read().unwrap();
                id.clone().ok_or(SpotifyError::InternalError("Client ID not configured".into()))?
            };
            let client_secret = {
                let secret = self.developer_client_secret.read().unwrap();
                secret.clone().ok_or(SpotifyError::InternalError("Client Secret not configured".into()))?
            };

            let url = "https://accounts.spotify.com/api/token";
            let res = self.http.post(url)
                .basic_auth(&client_id, Some(&client_secret))
                .form(&[
                    ("grant_type", "refresh_token"),
                    ("refresh_token", rt),
                ])
                .send()
                .await?;

            if !res.status().is_success() {
                let status = res.status().as_u16();
                let body = res.text().await.unwrap_or_default();
                return Err(SpotifyError::ApiError(status, format!("Refresh token failed: {}", body)));
            }

            #[derive(Deserialize)]
            struct RefreshResponse {
                access_token: String,
                refresh_token: Option<String>,
                expires_in: u64,
            }

            let resp: RefreshResponse = res.json().await?;
            let now = SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_millis() as u64;

            let creds = SpotifyCredentials {
                client_id: client_id.clone(),
                access_token: resp.access_token.clone(),
                expiration: now + (resp.expires_in * 1000),
                is_anonymous: false,
            };

            self.credentials = Some(creds);
            if let Some(new_rt) = resp.refresh_token {
                *self.refresh_token.write().unwrap() = Some(new_rt);
            }
            return Ok(());
        }

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
        *self.refresh_token.write().unwrap() = None;
    }

    pub async fn restore_session(
        &mut self,
        sp_dc: &str,
        access_token: Option<&str>,
        expiration: Option<u64>,
        refresh_token_opt: Option<&str>,
    ) -> SpotifyResult<LoginResult> {
        if let Some(rt) = refresh_token_opt {
            if !rt.is_empty() {
                *self.refresh_token.write().unwrap() = Some(rt.to_string());
            }
        }

        self.sp_dc = if sp_dc.is_empty() { None } else { Some(sp_dc.to_string()) };

        if let (Some(token), Some(exp)) = (access_token, expiration) {
            let now = SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_millis() as u64;
            if now + 60000 < exp {
                let client_id = self.developer_client_id.read().unwrap().clone().unwrap_or_default();
                let creds = SpotifyCredentials {
                    client_id,
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
                    refresh_token: self.refresh_token.read().unwrap().clone(),
                });
            }
        }

        if self.refresh_token.read().unwrap().is_some() {
            match self.refresh_token().await {
                Ok(_) => {
                    let creds = self.credentials.as_ref().unwrap();
                    let rt = self.refresh_token.read().unwrap().clone();
                    return Ok(LoginResult {
                        success: true,
                        user: None,
                        error: None,
                        access_token: Some(creds.access_token.clone()),
                        expiration: Some(creds.expiration),
                        refresh_token: rt,
                    });
                }
                Err(e) => {
                    return Ok(LoginResult {
                        success: false,
                        user: None,
                        error: Some(format!("Failed to refresh session: {}", e)),
                        access_token: None,
                        expiration: None,
                        refresh_token: None,
                    });
                }
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
    // =========================================================================
    // CLIENT CREDENTIALS FLOW (for REST API)
    // =========================================================================

    pub fn set_developer_credentials(&self, client_id: &str, client_secret: &str) {
        *self.developer_client_id.write().unwrap() = Some(client_id.to_string());
        *self.developer_client_secret.write().unwrap() = Some(client_secret.to_string());
        *self.client_credentials.write().unwrap() = None;
        *self.client_credentials_expiration.write().unwrap() = 0;
    }

    pub async fn fetch_client_credentials_token(&self) -> SpotifyResult<String> {
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_secs();

        // Read from cache
        {
            let token_lock = self.client_credentials.read().unwrap();
            let exp_lock = self.client_credentials_expiration.read().unwrap();
            if let Some(ref token) = *token_lock {
                if now + 60 < *exp_lock {
                    return Ok(token.clone());
                }
            }
        }

        // Get developer credentials or fall back to defaults
        let client_id = {
            let id = self.developer_client_id.read().unwrap();
            id.clone().unwrap_or_else(|| "4c97a4b21a07409ea4017e889d701ab0".to_string())
        };
        let client_secret = {
            let secret = self.developer_client_secret.read().unwrap();
            secret.clone().unwrap_or_else(|| "049386348c4146059d646b9a89d7fa2a".to_string())
        };

        let url = "https://accounts.spotify.com/api/token";
        
        let res = self.http.post(url)
            .basic_auth(&client_id, Some(&client_secret))
            .form(&[("grant_type", "client_credentials")])
            .send()
            .await?;

        if !res.status().is_success() {
            let status = res.status().as_u16();
            let body = res.text().await.unwrap_or_default();
            return Err(SpotifyError::ApiError(status, format!("Client credentials request failed: {}", body)));
        }

        #[derive(Deserialize)]
        struct ClientCredentialsResponse {
            access_token: String,
            expires_in: u64,
        }

        let resp: ClientCredentialsResponse = res.json().await?;
        let token = resp.access_token.clone();

        // Update cache
        *self.client_credentials.write().unwrap() = Some(token.clone());
        *self.client_credentials_expiration.write().unwrap() = now + resp.expires_in;

        Ok(token)
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
        let token = if let Ok(user_token) = self.access_token() {
            user_token.to_string()
        } else {
            self.fetch_client_credentials_token().await?
        };
        let url = format!("{}{}", SPOTIFY_API_BASE, path);

        let res = self.http.get(&url)
            .header(header::AUTHORIZATION, format!("Bearer {}", token))
            .send()
            .await?;

        let res = Self::check_response_success(res).await?;
        res.json().await.map_err(|e| SpotifyError::ParseError(format!("api_get parse error: {}", e)))
    }

    pub async fn api_post<T: DeserializeOwned>(&self, path: &str, body: &Value) -> SpotifyResult<T> {
        let token = if let Ok(user_token) = self.access_token() {
            user_token.to_string()
        } else {
            self.fetch_client_credentials_token().await?
        };
        let url = format!("{}{}", SPOTIFY_API_BASE, path);

        let res = self.http.post(&url)
            .header(header::AUTHORIZATION, format!("Bearer {}", token))
            .json(body)
            .send()
            .await?;

        let res = Self::check_response_success(res).await?;
        res.json().await.map_err(|e| SpotifyError::ParseError(format!("api_post parse error: {}", e)))
    }

    pub async fn api_put(&self, path: &str, body: &Value) -> SpotifyResult<()> {
        let token = if let Ok(user_token) = self.access_token() {
            user_token.to_string()
        } else {
            self.fetch_client_credentials_token().await?
        };
        let url = format!("{}{}", SPOTIFY_API_BASE, path);

        let res = self.http.put(&url)
            .header(header::AUTHORIZATION, format!("Bearer {}", token))
            .json(body)
            .send()
            .await?;

        Self::check_response_success(res).await?;
        Ok(())
    }

    pub async fn api_delete(&self, path: &str, body: &Value) -> SpotifyResult<()> {
        let token = if let Ok(user_token) = self.access_token() {
            user_token.to_string()
        } else {
            self.fetch_client_credentials_token().await?
        };
        let url = format!("{}{}", SPOTIFY_API_BASE, path);

        let res = self.http.delete(&url)
            .header(header::AUTHORIZATION, format!("Bearer {}", token))
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

        let hash = if sha256_hash.is_empty() {
            self.get_gql_hash(operation_name).await?
        } else {
            sha256_hash.to_string()
        };

        let body = GqlRequest {
            variables,
            operation_name: operation_name.to_string(),
            extensions: GqlExtensions {
                persisted_query: GqlPersistedQuery {
                    version: 1,
                    sha256_hash: hash,
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

    /// Scrapes open.spotify.com and Spotify Web Player JS chunks to dynamically extract the latest sha256 GQL operation hashes.
    pub async fn fetch_gql_hashes(&self) -> SpotifyResult<()> {
        // Step 1: Fetch Spotify homepage
        let resp = self.http.get("https://open.spotify.com").send().await?;
        let html = resp.text().await?;

        // Step 2: Extract JS links
        let re_js = Regex::new(r#"src="([^"]+\.js)""#).unwrap();
        let js_links: Vec<String> = re_js.captures_iter(&html)
            .map(|cap| cap[1].to_string())
            .collect();

        // Step 3: Find web-player JS pack
        let js_pack_url = js_links
            .iter()
            .find(|link| link.contains("web-player/web-player") && link.ends_with(".js"))
            .cloned();

        let js_pack_url = match js_pack_url {
            Some(url) => {
                if url.starts_with('/') {
                    format!("https://open.spotify.com{}", url)
                } else {
                    url
                }
            }
            None => return Err(SpotifyError::InternalError("Could not find web-player valid JS link".into())),
        };

        // Step 4: Extract CDN base URL from the JS pack URL
        let cdn_base = js_pack_url
            .rsplit_once('/')
            .map(|(base, _)| base.to_string())
            .ok_or_else(|| SpotifyError::InternalError("Could not parse CDN base from JS URL".into()))?;

        // Step 5: Fetch the web-player JS pack
        let resp = self.http.get(&js_pack_url).send().await?;
        let js_content = resp.text().await?;

        // Step 6: Extract chunk mappings
        let re_obj = Regex::new(r#"\{(\d+:"[^"]+"(?:,\d+:"[^"]+")*)\}"#).unwrap();
        let matches: Vec<_> = re_obj.find_iter(&js_content).map(|m| m.as_str()).collect();

        if matches.len() < 5 {
            return Err(SpotifyError::InternalError(format!(
                "Could not find both mappings in the JS code (matches found: {})",
                matches.len()
            )));
        }

        // map1 is at index 3: key -> hash (e.g. 4406 -> "8bfb4d6d")
        // map2 is at index 4: key -> name (e.g. 4406 -> "xpui-routes-search")
        let hash_map = self.parse_js_dict(matches[3])?;
        let name_map = self.parse_js_dict(matches[4])?;

        // Step 7: Combine chunks
        let mut combined_chunks = Vec::new();
        for (key, name) in &name_map {
            if let Some(hash) = hash_map.get(key) {
                combined_chunks.push(format!("{}.{}.js", name, hash));
            }
        }

        // Step 8: Fetch main JS and chunks to search for operation hashes
        let mut raw_hashes = js_content;

        // Fetch each chunk and append to raw_hashes
        for chunk in combined_chunks {
            let url = format!("{}/{}", cdn_base, chunk);
            if let Ok(resp) = self.http.get(&url).send().await {
                if resp.status().is_success() {
                    if let Ok(text) = resp.text().await {
                        raw_hashes.push_str(&text);
                    }
                }
            }
        }

        // Step 9: Parse all GQL operation names and sha256 hashes using Regex
        let re_hash = Regex::new(r#""([^"]+)","(query|mutation)","([a-f0-9]{64})""#).unwrap();
        let mut new_hashes = HashMap::new();
        for cap in re_hash.captures_iter(&raw_hashes) {
            let op_name = cap[1].to_string();
            let hash = cap[3].to_string();
            new_hashes.insert(op_name, hash);
        }

        eprintln!("[Spotify] Dynamically loaded {} GQL operation hashes", new_hashes.len());

        let mut cache = self.gql_hashes.write().unwrap();
        *cache = new_hashes;

        Ok(())
    }

    fn parse_js_dict(&self, s: &str) -> SpotifyResult<HashMap<i32, String>> {
        let content = s.trim_start_matches('{').trim_end_matches('}');
        let mut map = HashMap::new();

        let mut current = content;
        while !current.is_empty() {
            if let Some(colon_idx) = current.find(':') {
                let key_str = &current[..colon_idx];
                let key: i32 = key_str
                    .parse()
                    .map_err(|_| SpotifyError::ParseError(format!("Failed to parse key: {}", key_str)))?;

                let remainder = &current[colon_idx + 1..];
                if !remainder.starts_with('"') {
                    return Err(SpotifyError::ParseError("Value does not start with quote".into()));
                }

                if let Some(end_quote_idx) = remainder[1..].find('"') {
                    let end_quote_real_idx = end_quote_idx + 1;
                    let value = &remainder[1..end_quote_real_idx];
                    map.insert(key, value.to_string());

                    if remainder.len() > end_quote_real_idx + 1 {
                        if remainder.as_bytes()[end_quote_real_idx + 1] == b',' {
                            current = &remainder[end_quote_real_idx + 2..];
                        } else {
                            break;
                        }
                    } else {
                        break;
                    }
                } else {
                    return Err(SpotifyError::ParseError("Value does not end with quote".into()));
                }
            } else {
                break;
            }
        }

        Ok(map)
    }

    /// Retrieve GQL operation hash by name, either from cache or scraping open.spotify.com, with hardcoded fallbacks.
    pub async fn get_gql_hash(&self, operation_name: &str) -> SpotifyResult<String> {
        // First try reading from cache
        {
            let cache = self.gql_hashes.read().unwrap();
            if let Some(hash) = cache.get(operation_name) {
                return Ok(hash.clone());
            }
        }

        // If not found, fetch them
        if let Err(e) = self.fetch_gql_hashes().await {
            eprintln!("[Spotify] Failed to fetch GQL hashes dynamically: {}. Using fallback.", e);
        }

        // Try reading from cache again
        {
            let cache = self.gql_hashes.read().unwrap();
            if let Some(hash) = cache.get(operation_name) {
                return Ok(hash.clone());
            }
        }

        // Fallback static hashes for common operations just in case
        let fallback = match operation_name {
            "getAlbum" => "317769974246830509a25b3992b8d00ca45a556dfbfbf6d8b9415c1e5509c25f",
            "fetchPlaylist" => "63df14979e27306db09b9f71c4c1a792440026e64c39ebc6381df43d46342807",
            "queryArtistOverview" => "54b684534720973a903332eb45c613e55136ff937e29cf9787ffda42a1768df2",
            "searchDesktop" => "4801118d4a100f756e833d33984436a3899cff359c532f8fd3aaf174b60b3b49",
            "searchTracks" => "bc1ca2fcd0ba1013a0fc88e6cc4f190af501851e3dafd3e1ef85840297694428",
            "searchAlbums" => "a71d2c993fc98e1c880093738a55a38b57e69cc4ce5a8c113e6c5920f9513ee2",
            "searchArtists" => "0e6f9020a66fe15b93b3bb5c7e6484d1d8cb3775963996eaede72bac4d97e909",
            "searchPlaylists" => "fc3a690182167dbad20ac7a03f842b97be4e9737710600874cb903f30112ad58",
            "queryWhatsNewFeed" => "3b53dede3c6054e8b7c962dd280eb6761c5d1c82b06b039f4110d76a62b4966b",
            "addToLibrary" => "a3c1ff58e6a36fec5fe1e3a193dc95d9071d96b9ba53c5ba9c1494fb1ee73915",
            "removeFromLibrary" => "a3c1ff58e6a36fec5fe1e3a193dc95d9071d96b9ba53c5ba9c1494fb1ee73915",
            _ => "",
        };

        if !fallback.is_empty() {
            eprintln!("[Spotify] GQL hash for {} not found, using static fallback", operation_name);
            return Ok(fallback.to_string());
        }

        Err(SpotifyError::InternalError(format!("Could not find hash for GQL operation: {}", operation_name)))
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