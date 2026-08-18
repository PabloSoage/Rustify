// core_engine/src/env/mod.rs
//
// The platform boundary: everything the engine needs from the outside world, behind one trait.
//
// Inspired by `stremio-core`'s `Env` (MIT © 2019 SmartCode OOD) — the shape, not the code. Theirs
// cannot be reused as-is: its signature requires `Ctx`, `StreamingServer` and `constants`, i.e. its
// whole model layer. See docs/stremio-core/PLAN-3.x.md §0.1 for the full reasoning.
//
// Why it exists here:
//   * the core stops assuming Android — Windows and iOS implement this trait and inherit the rest;
//   * the core becomes testable without a network, which today it is not (see `mock::MockEnv`);
//   * persistence can move into Rust and be shared instead of reimplemented per platform.
//
// It is used as a generic parameter (`fn resolve<E: Env>(…)`), not as a trait object: zero runtime
// cost, and swapping in `MockEnv` for a test costs nothing either.

pub mod android;
pub mod schema;
pub mod storage;

#[cfg(any(test, feature = "mock-env"))]
pub mod mock;

use serde::de::DeserializeOwned;
use serde::Serialize;
use std::future::Future;
use std::pin::Pin;

/// A boxed future, because trait methods cannot return `impl Future` and stay usable here.
/// `Send` is required: work started by the engine is handed to a multi-threaded Tokio runtime.
pub type EnvFuture<'a, T> = Pin<Box<dyn Future<Output = T> + Send + 'a>>;

/// The common case: a fallible operation that owns everything it needs.
pub type TryEnvFuture<T> = EnvFuture<'static, Result<T, EnvError>>;

// =============================================================================
// ERRORS
// =============================================================================

/// Everything the platform layer can fail at.
///
/// Deliberately coarse. The point is that callers can tell *which layer* failed — network, disk or
/// parsing — without every module inventing its own error type.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum EnvError {
    /// The request did not complete. Not the same as an HTTP error status, which is a successful
    /// fetch of an unsuccessful response and arrives in `HttpResponse::status`.
    Fetch(String),
    /// Reading or writing persisted state failed.
    Storage(String),
    /// The bytes arrived and did not mean what we expected.
    Serde(String),
    Other(String),
}

impl EnvError {
    pub fn message(&self) -> String {
        match self {
            EnvError::Fetch(m) => format!("Failed to fetch: {m}"),
            EnvError::Storage(m) => format!("Storage error: {m}"),
            EnvError::Serde(m) => format!("Serialization error: {m}"),
            EnvError::Other(m) => format!("Other error: {m}"),
        }
    }
}

impl std::fmt::Display for EnvError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.message())
    }
}

impl std::error::Error for EnvError {}

impl From<serde_json::Error> for EnvError {
    fn from(e: serde_json::Error) -> Self {
        EnvError::Serde(e.to_string())
    }
}

// =============================================================================
// HTTP
// =============================================================================

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Method {
    Get,
    Post,
    Put,
    Delete,
    Head,
}

impl Method {
    pub fn as_str(&self) -> &'static str {
        match self {
            Method::Get => "GET",
            Method::Post => "POST",
            Method::Put => "PUT",
            Method::Delete => "DELETE",
            Method::Head => "HEAD",
        }
    }
}

/// A request in our own terms rather than `http::Request<IN>`.
///
/// `stremio-core` types `fetch` as JSON-in/JSON-out. That does not fit here: the Spotify client
/// needs raw bodies, form encoding, response headers, status codes and HTML scraping. Adding `http`
/// and `url` as dependencies just to borrow a signature was not worth it — the `.so` is built with
/// `opt-level = "z"` on purpose.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HttpRequest {
    pub method: Method,
    pub url: String,
    pub headers: Vec<(String, String)>,
    pub body: Option<Vec<u8>>,
    pub timeout_ms: Option<u64>,
}

impl HttpRequest {
    pub fn new(method: Method, url: impl Into<String>) -> Self {
        HttpRequest {
            method,
            url: url.into(),
            headers: Vec::new(),
            body: None,
            timeout_ms: None,
        }
    }

    pub fn get(url: impl Into<String>) -> Self {
        Self::new(Method::Get, url)
    }

    pub fn post(url: impl Into<String>) -> Self {
        Self::new(Method::Post, url)
    }

    pub fn header(mut self, name: impl Into<String>, value: impl Into<String>) -> Self {
        self.headers.push((name.into(), value.into()));
        self
    }

    pub fn bearer(self, token: &str) -> Self {
        self.header("Authorization", format!("Bearer {token}"))
    }

    /// HTTP Basic authentication, as `reqwest`'s `.basic_auth()` used to do.
    pub fn basic_auth(self, username: &str, password: &str) -> Self {
        use base64::Engine as _;
        let encoded =
            base64::engine::general_purpose::STANDARD.encode(format!("{username}:{password}"));
        self.header("Authorization", format!("Basic {encoded}"))
    }

    pub fn timeout_ms(mut self, ms: u64) -> Self {
        self.timeout_ms = Some(ms);
        self
    }

    pub fn body_bytes(mut self, body: Vec<u8>) -> Self {
        self.body = Some(body);
        self
    }

    pub fn body_json<T: Serialize>(self, value: &T) -> Result<Self, EnvError> {
        let raw = serde_json::to_vec(value)?;
        Ok(self
            .header("Content-Type", "application/json")
            .body_bytes(raw))
    }

    /// `application/x-www-form-urlencoded`, which is what Spotify's token endpoints want.
    pub fn body_form(self, pairs: &[(&str, &str)]) -> Self {
        let encoded = pairs
            .iter()
            .map(|(k, v)| format!("{}={}", urlencoding::encode(k), urlencoding::encode(v)))
            .collect::<Vec<_>>()
            .join("&");
        self.header("Content-Type", "application/x-www-form-urlencoded")
            .body_bytes(encoded.into_bytes())
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HttpResponse {
    pub status: u16,
    pub headers: Vec<(String, String)>,
    pub body: Vec<u8>,
}

impl HttpResponse {
    /// Convenience for tests and for the mock: a 200 with a text body.
    pub fn ok(body: impl Into<Vec<u8>>) -> Self {
        HttpResponse {
            status: 200,
            headers: Vec::new(),
            body: body.into(),
        }
    }

    pub fn with_status(status: u16, body: impl Into<Vec<u8>>) -> Self {
        HttpResponse {
            status,
            headers: Vec::new(),
            body: body.into(),
        }
    }

    pub fn is_success(&self) -> bool {
        (200..300).contains(&self.status)
    }

    /// Lossy on purpose: a mangled byte in a page title must not fail a request.
    pub fn text(&self) -> String {
        String::from_utf8_lossy(&self.body).into_owned()
    }

    pub fn json<T: DeserializeOwned>(&self) -> Result<T, EnvError> {
        serde_json::from_slice(&self.body).map_err(EnvError::from)
    }

    /// Case-insensitive, because HTTP header names are.
    pub fn header(&self, name: &str) -> Option<&str> {
        self.headers
            .iter()
            .find(|(k, _)| k.eq_ignore_ascii_case(name))
            .map(|(_, v)| v.as_str())
    }
}

// =============================================================================
// LOGGING
// =============================================================================

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum LogLevel {
    Debug,
    Info,
    Warn,
    Error,
}

// =============================================================================
// THE TRAIT
// =============================================================================

/// One implementation per platform; everything else is shared code.
///
/// All methods are associated functions with no `self`: there is exactly one environment per
/// process, and threading a handle through every call site would buy nothing.
///
/// Known exception, stated rather than hidden: `rustypipe` carries its own internal HTTP client and
/// does **not** route through `fetch`. The abstraction is not total and pretending otherwise would
/// make the next reader trust it too much.
pub trait Env: 'static {
    /// Performs the request. Only transport failures are `Err`; an HTTP 500 is `Ok` with `status`.
    fn fetch(req: HttpRequest) -> TryEnvFuture<HttpResponse>;

    /// `Ok(None)` means "no such key", which is not an error.
    fn get_storage(key: &str) -> TryEnvFuture<Option<String>>;

    /// `None` deletes the key. Deleting something absent succeeds.
    fn set_storage(key: &str, value: Option<&str>) -> TryEnvFuture<()>;

    /// Milliseconds since the Unix epoch.
    ///
    /// Not `chrono::DateTime<Utc>`: we would be adding a dependency for an integer. When calendar
    /// arithmetic actually shows up (point K, the release calendar), add `chrono` then.
    fn now_ms() -> i64;

    /// Fire-and-forget background work.
    fn exec_concurrent<F>(future: F)
    where
        F: Future<Output = ()> + Send + 'static;

    fn log(level: LogLevel, tag: &str, message: &str);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn form_body_percent_encodes() {
        let req = HttpRequest::post("https://example.test/token")
            .body_form(&[("grant_type", "client_credentials"), ("scope", "a b")]);
        assert_eq!(
            String::from_utf8(req.body.unwrap()).unwrap(),
            "grant_type=client_credentials&scope=a%20b"
        );
        assert_eq!(
            req.headers
                .iter()
                .find(|(k, _)| k == "Content-Type")
                .map(|(_, v)| v.as_str()),
            Some("application/x-www-form-urlencoded")
        );
    }

    #[test]
    fn basic_auth_matches_the_rfc_example() {
        // RFC 7617 §2: "Aladdin:open sesame" → "QWxhZGRpbjpvcGVuIHNlc2FtZQ=="
        let req = HttpRequest::get("https://example.test").basic_auth("Aladdin", "open sesame");
        assert_eq!(
            req.headers
                .iter()
                .find(|(k, _)| k == "Authorization")
                .map(|(_, v)| v.as_str()),
            Some("Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ==")
        );
    }

    #[test]
    fn header_lookup_ignores_case() {
        let res = HttpResponse {
            status: 200,
            headers: vec![("Content-Type".into(), "application/json".into())],
            body: Vec::new(),
        };
        assert_eq!(res.header("content-type"), Some("application/json"));
        assert_eq!(res.header("CONTENT-TYPE"), Some("application/json"));
        assert_eq!(res.header("content-length"), None);
    }

    #[test]
    fn error_status_is_not_a_fetch_failure() {
        let res = HttpResponse::with_status(503, "unavailable");
        assert!(!res.is_success());
        assert_eq!(res.text(), "unavailable");
    }
}
