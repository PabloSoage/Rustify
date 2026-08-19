// core_engine/src/spotify/client.rs
//
// Central Spotify client — Hybrid GraphQL + REST approach.
// Auth uses open.spotify.com (NOT the official REST API).
// Data operations use BOTH GraphQL (for pagination/IDs) and REST (for details)
// exactly replicating the spotify-gql-client behavior.

use regex::Regex;
use serde::{de::DeserializeOwned, Deserialize, Serialize};
use serde_json::Value;
use std::collections::HashMap;
use std::marker::PhantomData;
use std::sync::{OnceLock, RwLock};
use totp_rs::{Algorithm, Secret, TOTP};

use crate::env::android::AndroidEnv;
use crate::env::{Env, EnvError, HttpRequest, HttpResponse, Method};
use crate::spotify::models::*;

// =============================================================================
// GLOBAL SINGLETON
// =============================================================================

/// The process-wide Spotify client.
///
/// **There is no lock around it, and that is the point.** Until 3.1 this was an
/// `OnceLock<RwLock<SpotifyClient>>`, and `login`, `refresh_token` and `restore_session` each took
/// the *write* guard and then `.await`ed a network round-trip while holding it. Every other bridge
/// takes a read guard, so for the whole duration of a slow token restore — ten seconds on a bad
/// connection — every other call into the engine was parked on that lock. Including
/// `isSpotifyAuthenticatedNative`, which reads one `Option` and is called from the main thread at
/// startup, and `setLanguageNative`, and `getSpotifyHashesNative`.
///
/// That is the app not responding while the app still works: the UI thread is stuck on a mutex
/// behind a socket, but every coroutine already dispatched keeps running. It matches the report in
/// §7.1 of the plan exactly — "you can use the app but the home screen does not load".
///
/// The fix is not "be careful not to hold the guard across an await". It is to have no guard to
/// hold: the mutable session lives behind its own small `RwLock` *inside* the client (as the token
/// cache, the hashes and the cache dir already did), every method takes `&self`, and the guards are
/// held for the few instructions it takes to read or replace an `Option`. Nothing here can block on
/// anything that touches the network, because nothing here holds a lock while it does.
pub static SPOTIFY_CLIENT: OnceLock<SpotifyClient> = OnceLock::new();

pub fn get_spotify_client() -> &'static SpotifyClient {
    SPOTIFY_CLIENT.get_or_init(SpotifyClient::new)
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

// `From<reqwest::Error>` was removed in 3.0: nothing in this layer can produce one any more. The
// Spotify client no longer knows which HTTP stack it is talking through — `EnvError` is what it
// sees, and `AndroidEnv` is the only place `reqwest` still appears.

impl From<serde_json::Error> for SpotifyError {
    fn from(e: serde_json::Error) -> Self {
        SpotifyError::ParseError(e.to_string())
    }
}

impl From<EnvError> for SpotifyError {
    fn from(e: EnvError) -> Self {
        match e {
            // A failed `fetch` is a transport failure by definition: an HTTP error status is a
            // *successful* fetch and arrives in `HttpResponse::status`.
            EnvError::Fetch(m) => SpotifyError::NetworkError(m),
            EnvError::Serde(m) => SpotifyError::ParseError(m),
            EnvError::Storage(m) | EnvError::Other(m) => SpotifyError::InternalError(m),
        }
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

/// Treat a token as expired this long before it really is, so a request that is valid when it is
/// built is still valid when it lands.
const ACCESS_TOKEN_MARGIN_MS: u64 = 30_000;

/// Longest `Retry-After` worth waiting out inside a request. See [`SpotifyClient::send_with_retry`].
const MAX_RETRY_AFTER: std::time::Duration = std::time::Duration::from_secs(3);

const SPOTIFY_API_BASE: &str = "https://api.spotify.com/v1";
const SPOTIFY_GQL_BASE: &str = "https://api-partner.spotify.com/pathfinder/v2/query";

/// GitHub gist containing the TOTP nuance (shared secret + version).
const NUANCE_GIST_URL: &str = "https://api.github.com/gists/22ed9c6ba463899e933427f7de1f0eef";

/// Spotify server time endpoint for TOTP synchronization.
const SERVER_TIME_URL: &str = "https://open.spotify.com/api/server-time";

/// The user agent every Spotify request presents.
///
/// A `&'static str` rather than a `String`: it used to be built once when the `reqwest::Client` was
/// constructed, and since requests became plain data it is stamped on **every** one — so returning
/// an owned `String` meant an allocation per request for a constant.
fn generate_user_agent() -> &'static str {
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
}

/// How long the GQL hash scraper may take. Generous because it pulls the web-player bundle, which is
/// several megabytes, and then a series of chunks — but bounded, because it runs at login.
const SCRAPE_TIMEOUT_MS: u64 = 30_000;

/// How long any single Spotify request may take before it is abandoned. Was a client-wide setting
/// on the old `reqwest::Client`; now stamped on every request by [`SpotifyClient::base_request`].
const REQUEST_TIMEOUT_MS: u64 = 15_000;

/// The Spotify client, generic over its platform.
///
/// `E` **defaults to [`AndroidEnv`]**, which is what makes this a small change rather than a
/// rewrite: every existing mention of `SpotifyClient` still means `SpotifyClient<AndroidEnv>` and
/// keeps compiling. A test writes `SpotifyClient::<MockEnv>::new()` and gets a client that touches
/// no network at all.
///
/// `PhantomData<fn() -> E>` rather than `PhantomData<E>`: the function-pointer form is `Send + Sync`
/// whatever `E` is, and this struct lives inside a `RwLock` in a `static`.
pub struct SpotifyClient<E: Env = AndroidEnv> {
    /// The signed-in user, behind its own lock so that changing it never means locking the client.
    /// See [`SPOTIFY_CLIENT`] for what that used to cost.
    session: RwLock<Session>,
    client_credentials: RwLock<Option<String>>,
    client_credentials_expiration: RwLock<u64>,
    gql_hashes: RwLock<HashMap<String, String>>,
    cache_dir: RwLock<Option<String>>,
    accept_language: RwLock<Option<String>>,
    _env: PhantomData<fn() -> E>,
}

/// The mutable half of a signed-in session.
///
/// One struct rather than two fields so that the rule about it can be stated once: **the guard on
/// this is never held across an `await`.** Read what you need, drop the guard, do the network, take
/// the guard again to store the result.
#[derive(Debug, Default, Clone)]
struct Session {
    credentials: Option<SpotifyCredentials>,
    sp_dc: Option<String>,
}

impl<E: Env> SpotifyClient<E> {
    pub fn new() -> Self {
        Self {
            session: RwLock::new(Session::default()),
            client_credentials: RwLock::new(None),
            client_credentials_expiration: RwLock::new(0),
            gql_hashes: RwLock::new(HashMap::new()),
            cache_dir: RwLock::new(None),
            accept_language: RwLock::new(None),
            _env: PhantomData,
        }
    }

    pub fn set_accept_language(&self, lang: &str) {
        *self.accept_language.write().unwrap() = Some(lang.to_string());
    }

    /// Every outgoing request starts here.
    ///
    /// The user agent and the timeout used to be baked into a single `reqwest::Client`, so they
    /// applied by construction. `Env::fetch` is deliberately unopinionated — a platform layer has no
    /// business knowing that Spotify wants to be talked to like a desktop browser — so those live
    /// here instead, in the one place that does know.
    fn base_request(&self, method: Method, url: impl Into<String>) -> HttpRequest {
        let mut req = HttpRequest::new(method, url)
            .header("User-Agent", generate_user_agent())
            .timeout_ms(REQUEST_TIMEOUT_MS);
        if let Some(lang) = self.accept_language.read().unwrap().clone() {
            req = req.header("Accept-Language", lang);
        }
        req
    }

    // =========================================================================
    // AUTHENTICATION
    // =========================================================================
    //
    // Every method here takes `&self`. That is not a style choice — see [`SPOTIFY_CLIENT`]. The
    // rule these all obey: **the session guard is never held across an `await`.** Take what is
    // needed, drop the guard, do the network, take the guard again to store the answer.

    pub async fn login_with_sp_dc(&self, sp_dc: &str) -> SpotifyResult<LoginResult> {
        // Stored before the request so a `refresh_token` racing this one has something to work
        // with, and dropped immediately: the fetch below is the ten-second part.
        {
            let mut session = self.session.write().unwrap();
            session.sp_dc = Some(sp_dc.to_string());
        }

        match self.fetch_access_token(sp_dc).await {
            Ok(creds) => {
                self.session.write().unwrap().credentials = Some(creds.clone());
                Ok(LoginResult {
                    success: true,
                    user: None,
                    error: None,
                    access_token: Some(creds.access_token),
                    expiration: Some(creds.expiration),
                })
            }
            Err(e) => {
                *self.session.write().unwrap() = Session::default();
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

    pub async fn refresh_token(&self) -> SpotifyResult<()> {
        let sp_dc = self
            .session
            .read()
            .unwrap()
            .sp_dc
            .clone()
            .ok_or(SpotifyError::NotAuthenticated)?;

        let creds = self.fetch_access_token(&sp_dc).await?;
        self.session.write().unwrap().credentials = Some(creds);
        Ok(())
    }

    pub fn is_authenticated(&self) -> bool {
        let session = self.session.read().unwrap();
        match &session.credentials {
            Some(creds) => self.now_ms() < creds.expiration,
            None => false,
        }
    }

    pub fn is_expired(&self) -> bool {
        !self.is_authenticated()
    }

    pub fn logout(&self) {
        *self.session.write().unwrap() = Session::default();
    }

    pub async fn restore_session(
        &self,
        sp_dc: &str,
        access_token: Option<&str>,
        expiration: Option<u64>,
    ) -> SpotifyResult<LoginResult> {
        {
            let mut session = self.session.write().unwrap();
            session.sp_dc = if sp_dc.is_empty() {
                None
            } else {
                Some(sp_dc.to_string())
            };
        }

        if let (Some(token), Some(exp)) = (access_token, expiration) {
            // A cached token with a minute to live is the whole reason this path exists: it makes
            // start-up free instead of a network round-trip on every launch.
            if self.now_ms() + 60_000 < exp {
                let creds = SpotifyCredentials {
                    client_id: String::new(),
                    access_token: token.to_string(),
                    expiration: exp,
                    is_anonymous: false,
                };
                self.session.write().unwrap().credentials = Some(creds);
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

    /// The clock, through [`Env`] rather than `SystemTime`.
    ///
    /// The point of the abstraction: a test can wind this forward and watch a token expire without
    /// waiting an hour. `max(0)` because the trait speaks `i64` (a clock can be set before the
    /// epoch) and every comparison here is against a `u64` timestamp from Spotify.
    fn now_ms(&self) -> u64 {
        E::now_ms().max(0) as u64
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
        let req = self
            .base_request(Method::Get, NUANCE_GIST_URL)
            .header("Accept", "application/vnd.github.v3+json");
        let res = E::fetch(req).await?;

        if !res.is_success() {
            return Err(SpotifyError::ApiError(
                res.status,
                "Failed to fetch TOTP nuance from GitHub".into()
            ));
        }

        let gist: Value = res.json()?;

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
        let res = E::fetch(self.base_request(Method::Get, SERVER_TIME_URL)).await?;

        if !res.is_success() {
            return Err(SpotifyError::ApiError(
                res.status,
                "Failed to fetch server time".into()
            ));
        }

        let time_resp: ServerTimeResponse = res.json()
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

        let req = self
            .base_request(Method::Get, url.as_str())
            .header("Cookie", cookie_header)
            .header("App-Platform", "WebPlayer");
        let res = E::fetch(req).await?;

        if !res.is_success() {
            return Err(SpotifyError::ApiError(res.status, format!("Token request failed: {}", res.text())));
        }

        let creds: SpotifyCredentials = res.json()
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

    pub fn set_cache_dir(&self, path: &str) {
        *self.cache_dir.write().unwrap() = Some(path.to_string());
        if let Err(e) = self.load_hashes_from_disk() {
            eprintln!("[Spotify] Failed to load hashes from disk cache: {}", e);
        }
    }

    // `clone_http()` was removed in 3.0. It existed so other modules could borrow this client's
    // `reqwest::Client`; there is no client to borrow now — callers use `E::fetch` directly, which
    // is the same HTTP stack without the coupling.

    pub fn update_gql_hashes(&self, new_hashes: HashMap<String, String>) {
        {
            let mut cache = self.gql_hashes.write().unwrap();
            *cache = new_hashes;
        }
        if let Err(e) = self.save_hashes_to_disk() {
            eprintln!("[Spotify] Failed to save hashes to disk cache: {}", e);
        }
    }

    /// Returns a snapshot of the current GQL operation hashes cache.
    pub fn get_gql_hashes_snapshot(&self) -> HashMap<String, String> {
        self.gql_hashes.read().unwrap().clone()
    }


    fn get_hashes_file_path(&self) -> Option<std::path::PathBuf> {
        let dir = self.cache_dir.read().unwrap();
        dir.as_ref().map(|d| std::path::Path::new(d).join("spotify_gql_hashes.json"))
    }

    pub fn load_hashes_from_disk(&self) -> Result<(), Box<dyn std::error::Error>> {
        if let Some(path) = self.get_hashes_file_path() {
            if path.exists() {
                let file = std::fs::File::open(path)?;
                let reader = std::io::BufReader::new(file);
                let loaded_hashes: HashMap<String, String> = serde_json::from_reader(reader)?;
                let mut cache = self.gql_hashes.write().unwrap();
                *cache = loaded_hashes;
                eprintln!("[Spotify] Loaded {} GQL operation hashes from disk cache", cache.len());
            }
        }
        Ok(())
    }

    pub fn save_hashes_to_disk(&self) -> Result<(), Box<dyn std::error::Error>> {
        if let Some(path) = self.get_hashes_file_path() {
            let file = std::fs::File::create(path)?;
            let writer = std::io::BufWriter::new(file);
            let cache = self.gql_hashes.read().unwrap();
            serde_json::to_writer_pretty(writer, &*cache)?;
            eprintln!("[Spotify] Saved {} GQL operation hashes to disk cache", cache.len());
        }
        Ok(())
    }

    pub async fn fetch_client_credentials_token(&self) -> SpotifyResult<String> {
        // Seconds, not millis: this cache is keyed on `expires_in`, which Spotify gives in seconds.
        let now = self.now_ms() / 1000;

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

        // App-level Spotify credentials for the client-credentials grant.
        let client_id = "4c97a4b21a07409ea4017e889d701ab0".to_string();
        let client_secret = "049386348c4146059d646b9a89d7fa2a".to_string();

        let url = "https://accounts.spotify.com/api/token";

        let req = self
            .base_request(Method::Post, url)
            .basic_auth(&client_id, &client_secret)
            .body_form(&[("grant_type", "client_credentials")]);
        let res = E::fetch(req).await?;

        if !res.is_success() {
            return Err(SpotifyError::ApiError(res.status, format!("Client credentials request failed: {}", res.text())));
        }

        #[derive(Deserialize)]
        struct ClientCredentialsResponse {
            access_token: String,
            expires_in: u64,
        }

        let resp: ClientCredentialsResponse = res.json()?;
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
    ///
    /// Refuses to hand out a token that has already expired. That sounds obvious, and it was not
    /// what this did: it returned whatever was stored, so every request kept presenting a dead
    /// token and coming back `401 Missing/invalid/expired access token` — including
    /// `api_get`, which has a client-credentials fallback for public data that could never be
    /// reached, because this returned `Ok` and the fallback only runs on `Err`.
    ///
    /// The margin is for the request that is fine when it is built and expired by the time it
    /// arrives.
    /// Returns an owned `String` rather than a `&str` since 3.1: the token lives behind the session
    /// lock now, and handing out a reference into it would mean handing out the guard's lifetime.
    /// Every caller already wrote `.to_string()` anyway.
    pub fn access_token(&self) -> SpotifyResult<String> {
        let session = self.session.read().unwrap();
        let creds = session
            .credentials
            .as_ref()
            .ok_or(SpotifyError::NotAuthenticated)?;
        if self.now_ms() + ACCESS_TOKEN_MARGIN_MS >= creds.expiration {
            return Err(SpotifyError::TokenExpired);
        }
        Ok(creds.access_token.clone())
    }

    /// Check if response was successful, extract error body if not.
    fn check_response_success(res: HttpResponse) -> SpotifyResult<HttpResponse> {
        if !res.is_success() {
            return Err(SpotifyError::ApiError(res.status, res.text()));
        }
        Ok(res)
    }

    /// Send a request with automatic retries on transient network failures and
    /// rate-limiting / 5xx responses (E10 RC-3). Up to 3 attempts with exponential
    /// backoff (400ms, 800ms, 1600ms). Honours `Retry-After` when present.
    /// AUTH/401 and other permanent errors are returned immediately so the caller
    /// can refresh the session or surface the error.
    ///
    /// Takes the request **by value** now that a request is plain data. The old signature took a
    /// closure building a `reqwest::RequestBuilder` because a builder cannot be reused, and every
    /// caller carried a `try_clone().unwrap_or_else(|| …rebuild the whole thing…)` to work around
    /// it. Cloning a struct needs no fallback path, so five of those disappeared.
    async fn send_with_retry(&self, req: HttpRequest) -> SpotifyResult<HttpResponse> {
        let mut delay = std::time::Duration::from_millis(400);
        for attempt in 0..3u32 {
            match E::fetch(req.clone()).await {
                Ok(r) => {
                    let code = r.status;
                    if code == 429 || (500..600).contains(&code) {
                        if attempt == 2 {
                            return Ok(r);
                        }
                        let wait = r
                            .header("retry-after")
                            .and_then(|v| v.parse::<u64>().ok())
                            .map(std::time::Duration::from_secs)
                            .unwrap_or(delay);
                        // Honouring an arbitrarily long `Retry-After` blocks the caller for as long
                        // as Spotify feels like, and a rate limit measured in tens of seconds is not
                        // something to wait out inside a tap: the UI sat frozen with no feedback for
                        // a minute and then showed a 429 anyway. Past the cap, hand the response
                        // back now so the caller can say "rate limited, try again shortly" while it
                        // still means something.
                        if wait > MAX_RETRY_AFTER {
                            return Ok(r);
                        }
                        tokio::time::sleep(wait).await;
                        delay *= 2;
                        continue;
                    }
                    return Ok(r);
                }
                Err(e) => {
                    // Every `Err` from `fetch` is a transport failure — timeout, DNS, connection
                    // refused — because an HTTP error status comes back as `Ok`. The old code had to
                    // sort `reqwest::Error` into transient and permanent kinds; there is nothing
                    // left to sort, so all of them are worth one more attempt.
                    if attempt == 2 {
                        return Err(SpotifyError::from(e));
                    }
                    tokio::time::sleep(delay).await;
                    delay *= 2;
                }
            }
        }
        unreachable!()
    }

    /// Sends, retries, and rejects an error status — the three steps every REST call repeated.
    async fn send_checked(&self, req: HttpRequest) -> SpotifyResult<HttpResponse> {
        let res = self.send_with_retry(req).await?;
        Self::check_response_success(res)
    }

    pub async fn api_get<T: DeserializeOwned>(&self, path: &str) -> SpotifyResult<T> {
        let token = if let Ok(user_token) = self.access_token() {
            user_token
        } else {
            self.fetch_client_credentials_token().await?
        };
        let url = format!("{}{}", SPOTIFY_API_BASE, path);

        let req = self.base_request(Method::Get, url).bearer(&token);
        let res = self.send_checked(req).await?;
        res.json()
            .map_err(|e| SpotifyError::ParseError(format!("api_get parse error: {}", e)))
    }

    /// Writes are **user-scoped**, so there is deliberately no client-credentials fallback here.
    ///
    /// An app token can read public metadata, which is why [`api_get`] falls back to one. It cannot
    /// touch a user's library: `POST /playlists/{id}/tracks` with one comes back 400/403, which the
    /// Kotlin retry layer classifies as permanent and gives up on — "adding to a playlist fails
    /// with a 400". Propagating `TokenExpired` instead classifies as AUTH, which refreshes the
    /// session and retries, and that is the outcome the caller actually wants.
    pub async fn api_post<T: DeserializeOwned>(&self, path: &str, body: &Value) -> SpotifyResult<T> {
        let token = self.access_token()?;
        let url = format!("{}{}", SPOTIFY_API_BASE, path);

        let req = self
            .base_request(Method::Post, url)
            .bearer(&token)
            .body_json(body)?;
        let res = self.send_checked(req).await?;
        res.json()
            .map_err(|e| SpotifyError::ParseError(format!("api_post parse error: {}", e)))
    }

    /// User-scoped write. No client-credentials fallback — see [`api_post`].
    pub async fn api_put(&self, path: &str, body: &Value) -> SpotifyResult<()> {
        let token = self.access_token()?;
        let url = format!("{}{}", SPOTIFY_API_BASE, path);

        let req = self
            .base_request(Method::Put, url)
            .bearer(&token)
            .body_json(body)?;
        self.send_checked(req).await?;
        Ok(())
    }

    /// User-scoped write. No client-credentials fallback — see [`api_post`].
    pub async fn api_delete(&self, path: &str, body: &Value) -> SpotifyResult<()> {
        let token = self.access_token()?;
        let url = format!("{}{}", SPOTIFY_API_BASE, path);

        let req = self
            .base_request(Method::Delete, url)
            .bearer(&token)
            .body_json(body)?;
        self.send_checked(req).await?;
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

        let req = self
            .base_request(Method::Post, SPOTIFY_GQL_BASE)
            .bearer(token)
            .header("App-Platform", "WebPlayer")
            .body_json(&body)?;

        let res = self.send_checked(req).await?;

        let json: Value = res.json()
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
        let new_hashes = scrape_gql_hashes::<E>().await?;
        self.update_gql_hashes(new_hashes);
        Ok(())
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
            "getTrack" => "612585ae06ba435ad26369870deaae23b5c8800a256cd8a57e08eddc25a37294",
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

pub fn parse_gql_track(track_val: &Value) -> Option<FullTrack> {
    let (track, parent_uri) = if track_val.get("data").is_some() {
        (&track_val["data"], track_val.get("uri").or_else(|| track_val.get("_uri")))
    } else {
        (track_val, None)
    };

    let uri = track.get("uri").or_else(|| track.get("_uri")).or(parent_uri)
        .and_then(|v| v.as_str())?;
    // Local tracks (added to a Spotify playlist from the desktop app) have a
    // `spotify:local:{artist}:{album}:{title}:{seconds}` URI and no real track id. Parsing them the
    // normal way took the LAST colon segment (the duration) as the "id", so many local tracks ended up
    // sharing an id like "180" → duplicate LazyColumn keys crashed the playlist screen. Parse them
    // properly instead: no Spotify id, real title/artist/album/duration, keeping the URI intact.
    if uri.starts_with("spotify:local:") {
        return parse_local_track(uri);
    }
    let track_id = id_from_uri(uri)?.to_string();

    let duration_ms = track["duration"]["totalMilliseconds"]
        .as_u64()
        .or_else(|| track["trackDuration"]["totalMilliseconds"].as_u64())
        .or_else(|| track["duration_ms"].as_u64())
        .or_else(|| track["durationMs"].as_u64())
        .unwrap_or(0) as u32;

    let explicit = track["contentRating"]["label"].as_str() == Some("EXPLICIT");

    let mut artists = parse_gql_artists(&track["artists"]);
    if artists.is_empty() {
        artists = parse_gql_artists(&track["firstArtist"]);
        let mut others = parse_gql_artists(&track["otherArtists"]);
        artists.append(&mut others);
    }

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
        added_at: None,
    })
}

/// Decode a field of a `spotify:local:` URI: spaces are encoded as `+`, other chars as `%XX`.
fn local_decode(s: &str) -> String {
    let s = s.replace('+', " ");
    let bytes = s.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' && i + 2 < bytes.len() {
            let hi = (bytes[i + 1] as char).to_digit(16);
            let lo = (bytes[i + 2] as char).to_digit(16);
            if let (Some(h), Some(l)) = (hi, lo) {
                out.push((h * 16 + l) as u8);
                i += 3;
                continue;
            }
        }
        out.push(bytes[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

/// Build a [`FullTrack`] from a `spotify:local:{artist}:{album}:{title}:{seconds}` URI (a local file the
/// user added to a Spotify playlist from the desktop app). `id` is `None` (no Spotify id) so the UI never
/// collides on it; the URI is preserved so local-music matching can still find the file by name/artist.
fn parse_local_track(uri: &str) -> Option<FullTrack> {
    let parts: Vec<&str> = uri.splitn(6, ':').collect();
    let field = |i: usize| parts.get(i).map(|s| local_decode(s)).unwrap_or_default();
    let artist = field(2);
    let album_name = field(3);
    let title = field(4);
    let duration_ms = parts.get(5).and_then(|s| s.parse::<u32>().ok()).unwrap_or(0) * 1000;

    let artists = if artist.is_empty() {
        Vec::new()
    } else {
        vec![SimpleArtist { id: String::new(), name: artist, external_uri: String::new(), images: None }]
    };
    let album = if album_name.is_empty() {
        None
    } else {
        Some(SimpleAlbum {
            id: String::new(),
            name: album_name,
            external_uri: String::new(),
            release_date: None,
            release_date_precision: None,
            images: Vec::new(),
            artists: Vec::new(),
            album_type: Some("local".to_string()),
        })
    };

    Some(FullTrack {
        id: None,
        name: if title.is_empty() { uri.to_string() } else { title },
        external_uri: uri.to_string(),
        explicit: false,
        duration_ms,
        isrc: String::new(),
        artists,
        album,
        added_at: None,
    })
}

pub fn parse_js_dict_raw(s: &str) -> SpotifyResult<HashMap<i32, String>> {
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

/// Scrapes open.spotify.com and its JS chunks for the current GQL operation hashes.
///
/// Generic over the environment like everything else since 3.0. The former
/// `scrape_gql_hashes_with_client(&reqwest::Client)` had to be handed the client because it was a
/// private field nobody outside could reach; now the platform *is* the type parameter.
pub async fn scrape_gql_hashes<E: Env>() -> SpotifyResult<HashMap<String, String>> {
    // Step 1: Fetch Spotify homepage
    let html = E::fetch(HttpRequest::get("https://open.spotify.com").header("User-Agent", generate_user_agent()).timeout_ms(SCRAPE_TIMEOUT_MS))
        .await?
        .text();

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
    let js_content = E::fetch(HttpRequest::get(js_pack_url.as_str()).timeout_ms(SCRAPE_TIMEOUT_MS)).await?.text();

    // Step 6: Extract chunk mappings
    let re_obj = Regex::new(r#"\{(\d+:"[^"]+"(?:,\d+:"[^"]+")*)}"#).unwrap();
    let matches: Vec<_> = re_obj.find_iter(&js_content).map(|m| m.as_str()).collect();

    if matches.len() < 5 {
        return Err(SpotifyError::InternalError(format!(
            "Could not find both mappings in the JS code (matches found: {})",
            matches.len()
        )));
    }

    let hash_map = parse_js_dict_raw(matches[3])?;
    let name_map = parse_js_dict_raw(matches[4])?;

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
        if let Ok(resp) = E::fetch(HttpRequest::get(url.as_str()).timeout_ms(SCRAPE_TIMEOUT_MS)).await {
            if resp.is_success() {
                raw_hashes.push_str(&resp.text());
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

    eprintln!("[Spotify] Dynamically loaded {} GQL operation hashes via scrape_gql_hashes", new_hashes.len());

    Ok(new_hashes)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::env::mock::{self, MockEnv};

    /// The point of point A, demonstrated: a real `SpotifyClient`, exercised end to end, with no
    /// network, no device and no Android. None of this was testable before 3.0.
    fn client() -> SpotifyClient<MockEnv> {
        SpotifyClient::<MockEnv>::new()
    }

    #[tokio::test]
    async fn an_unauthenticated_client_refuses_writes_without_calling_out() {
        let _guard = mock::lock_and_reset();
        // No canned responses registered at all: if this touched the network the mock would say so.
        let result: SpotifyResult<Value> = client().api_post("/me/tracks", &Value::Null).await;
        assert!(matches!(result, Err(SpotifyError::NotAuthenticated)));
        assert!(mock::state().requests.is_empty());
    }

    #[tokio::test]
    async fn a_refused_app_token_is_surfaced_with_its_status() {
        let _guard = mock::lock_and_reset();
        // The token endpoint deliberately does *not* go through `send_with_retry` — it did not
        // before 3.0 either — so this is one attempt and one error, not three.
        mock::on_prefix(
            "https://accounts.spotify.com/api/token",
            HttpResponse {
                status: 429,
                headers: vec![("Retry-After".into(), "0".into())],
                body: b"slow down".to_vec(),
            },
        );
        let result = client().fetch_client_credentials_token().await;
        assert!(matches!(result, Err(SpotifyError::ApiError(429, _))));
        assert_eq!(mock::state().requests.len(), 1);
    }

    #[tokio::test]
    async fn a_long_retry_after_is_surfaced_immediately_rather_than_waited_out() {
        let _guard = mock::lock_and_reset();
        // 600 s is far past MAX_RETRY_AFTER. The test finishing at all is the assertion: before the
        // cap existed this would have parked the caller for ten minutes.
        mock::on_prefix(
            "https://api.spotify.com/v1",
            HttpResponse {
                status: 429,
                headers: vec![("Retry-After".into(), "600".into())],
                body: Vec::new(),
            },
        );
        mock::on_prefix(
            "https://accounts.spotify.com/api/token",
            HttpResponse::ok(r#"{"access_token":"app-token","expires_in":3600}"#),
        );
        let result: SpotifyResult<Value> = client().api_get("/tracks?ids=1").await;
        assert!(matches!(result, Err(SpotifyError::ApiError(429, _))));
        // One token call plus exactly one API attempt — not three.
        let api_attempts = mock::state()
            .requests
            .iter()
            .filter(|r| r.url.starts_with("https://api.spotify.com/v1"))
            .count();
        assert_eq!(api_attempts, 1);
    }

    #[tokio::test]
    async fn public_reads_fall_back_to_an_app_token() {
        let _guard = mock::lock_and_reset();
        mock::on_prefix(
            "https://accounts.spotify.com/api/token",
            HttpResponse::ok(r#"{"access_token":"app-token","expires_in":3600}"#),
        );
        mock::on_prefix(
            "https://api.spotify.com/v1/tracks",
            HttpResponse::ok(r#"{"ok":true}"#),
        );

        let value: Value = client().api_get("/tracks?ids=1").await.unwrap();
        assert_eq!(value["ok"], Value::Bool(true));

        // And the fallback token really was presented, rather than the request going out bare.
        let sent = mock::state()
            .requests
            .iter()
            .find(|r| r.url.starts_with("https://api.spotify.com/v1/tracks"))
            .cloned()
            .expect("the API call should have been made");
        assert!(sent
            .headers
            .iter()
            .any(|(k, v)| k == "Authorization" && v == "Bearer app-token"));
    }

    #[tokio::test]
    async fn the_accept_language_setting_reaches_the_wire() {
        let _guard = mock::lock_and_reset();
        mock::on_prefix(
            "https://accounts.spotify.com/api/token",
            HttpResponse::ok(r#"{"access_token":"app-token","expires_in":3600}"#),
        );
        mock::on_prefix("https://api.spotify.com/v1", HttpResponse::ok("{}"));

        let client = client();
        client.set_accept_language("ja");
        let _: SpotifyResult<Value> = client.api_get("/tracks?ids=1").await;

        let sent = mock::state()
            .requests
            .iter()
            .find(|r| r.url.starts_with("https://api.spotify.com/v1"))
            .cloned()
            .expect("the API call should have been made");
        assert!(sent
            .headers
            .iter()
            .any(|(k, v)| k == "Accept-Language" && v == "ja"));
    }

    // =========================================================================
    // POINT N — the start-up block
    // =========================================================================

    #[tokio::test]
    async fn a_login_in_flight_does_not_block_a_reader() {
        // This is the ANR, reproduced without a device. Before 3.1 `login_with_sp_dc` took
        // `&mut self`, so the JNI bridge held the *write* guard of the process-wide `RwLock` for
        // the whole network round-trip, and `isSpotifyAuthenticatedNative` — one `Option` read,
        // called from the main thread at start-up — parked behind it.
        //
        // The reason this test cannot regress is that it does not test timing: it takes a shared
        // reference and calls both. If anyone reintroduces `&mut self`, this stops compiling.
        let _guard = mock::lock_and_reset();
        mock::state().now_ms = 1_000;
        mock::on_prefix(
            "https://open.spotify.com",
            HttpResponse::ok(r#"{"serverTime":1}"#),
        );
        mock::on_prefix("https://api.github.com", HttpResponse::ok("{}"));

        let client = client();
        let login = client.login_with_sp_dc("cookie");
        assert!(!client.is_authenticated());
        let _ = login.await;
    }

    #[tokio::test]
    async fn a_restored_session_with_a_live_token_never_touches_the_network() {
        // The start-up path. If this ever makes a request, every cold start pays a round-trip
        // before the home screen can ask whether it is signed in.
        let _guard = mock::lock_and_reset();
        mock::state().now_ms = 1_000_000;

        let client = client();
        let result = client
            .restore_session("cookie", Some("cached-token"), Some(1_000_000 + 600_000))
            .await
            .unwrap();

        assert!(result.success);
        assert!(client.is_authenticated());
        assert_eq!(client.access_token().unwrap(), "cached-token");
        assert!(mock::state().requests.is_empty());
    }

    #[tokio::test]
    async fn a_token_that_expires_while_the_app_is_open_stops_being_handed_out() {
        let _guard = mock::lock_and_reset();
        mock::state().now_ms = 1_000_000;
        let client = client();
        let _ = client
            .restore_session("cookie", Some("cached-token"), Some(1_000_000 + 600_000))
            .await;
        assert!(client.is_authenticated());

        // Ten minutes later, past the expiry.
        mock::state().now_ms = 1_000_000 + 600_001;
        assert!(!client.is_authenticated());
        assert!(matches!(
            client.access_token(),
            Err(SpotifyError::TokenExpired)
        ));
    }

    #[tokio::test]
    async fn logging_out_clears_the_cookie_as_well_as_the_token() {
        // Leaving `sp_dc` behind would let a later `refresh_token` sign the user back in.
        let _guard = mock::lock_and_reset();
        mock::state().now_ms = 1_000;
        let client = client();
        let _ = client
            .restore_session("cookie", Some("t"), Some(1_000 + 600_000))
            .await;
        assert!(client.is_authenticated());

        client.logout();
        assert!(!client.is_authenticated());
        assert!(matches!(
            client.refresh_token().await,
            Err(SpotifyError::NotAuthenticated)
        ));
        assert!(mock::state().requests.is_empty());
    }
}