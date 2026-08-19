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

use bytes::Bytes;


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


/// A response's metadata, without its body — what a streaming fetch can hand back before the bytes
/// have arrived.
///
/// Separate from [`HttpResponse`] rather than a field of it. Making `HttpResponse` a head plus an
/// optional body would give every existing caller an `Option` to unwrap for a case that cannot
/// happen to them, which is how a type stops carrying information.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HttpResponseHead {
    pub status: u16,
    pub headers: Vec<(String, String)>,
}

impl HttpResponseHead {
    pub fn is_success(&self) -> bool {
        (200..300).contains(&self.status)
    }

    /// Case-insensitive, because header names are.
    pub fn header(&self, name: &str) -> Option<&str> {
        self.headers
            .iter()
            .find(|(k, _)| k.eq_ignore_ascii_case(name))
            .map(|(_, v)| v.as_str())
    }

    pub fn content_length(&self) -> Option<u64> {
        self.header("Content-Length")?.trim().parse().ok()
    }

    /// The total size from a `Content-Range: bytes a-b/total`, when the server sent one.
    ///
    /// This is the only way to learn how big a track is when you asked for part of it, and the proxy
    /// needs it to tell the player what it is seeking within. A `*` total means "I do not know",
    /// which is not an error and not a number.
    pub fn content_range_total(&self) -> Option<u64> {
        let value = self.header("Content-Range")?;
        let total = value.rsplit('/').next()?.trim();
        total.parse().ok()
    }
}

/// A response body arriving in pieces.
///
/// The pieces are whatever the network hands over: they are **not** aligned to anything, and a
/// consumer that assumes otherwise is the bug this type exists to make visible. The Deezer proxy
/// buffers them back into 2048-byte stripes precisely because it cannot assume.
///
/// A **channel** rather than a boxed `Stream`, for one concrete reason: this ends up inside
/// `http_body_util::BoxBody`, which requires `Send + Sync`, and `Pin<Box<dyn Stream + Send>>` is not
/// `Sync`. The alternative box that drops `Sync` also drops `Send`, and the server spawns its
/// connections. A `Receiver` is both, and needs no trait imported to poll.
///
/// The channel is **bounded**, which is what makes backpressure free: a producer that has run ahead
/// blocks on `send` until the player asks for more, so a paused track is not still downloading.
pub struct ByteStream(tokio::sync::mpsc::Receiver<Result<Bytes, EnvError>>);

impl ByteStream {
    /// How far ahead of the reader a producer may get. Small on purpose — see above.
    pub const BUFFERED_CHUNKS: usize = 4;

    pub fn new(receiver: tokio::sync::mpsc::Receiver<Result<Bytes, EnvError>>) -> Self {
        ByteStream(receiver)
    }

    /// A sender/receiver pair sized for streaming.
    pub fn channel() -> (
        tokio::sync::mpsc::Sender<Result<Bytes, EnvError>>,
        ByteStream,
    ) {
        let (tx, rx) = tokio::sync::mpsc::channel(Self::BUFFERED_CHUNKS);
        (tx, ByteStream(rx))
    }

    /// Everything at once, for a body that is already in memory. No task, so a test is deterministic.
    pub fn from_chunks(chunks: Vec<Bytes>) -> Self {
        let (tx, rx) = tokio::sync::mpsc::channel(chunks.len().max(1));
        for chunk in chunks {
            // Cannot fail: the channel was sized to hold exactly this many and nobody else has it.
            let _ = tx.try_send(Ok(chunk));
        }
        ByteStream(rx)
    }

    /// The next piece, or `None` when there are no more.
    pub fn poll_chunk(
        &mut self,
        cx: &mut std::task::Context<'_>,
    ) -> std::task::Poll<Option<Result<Bytes, EnvError>>> {
        self.0.poll_recv(cx)
    }
}

impl std::fmt::Debug for ByteStream {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("ByteStream")
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

    /// Downloads `req` straight to `path`, returning how many bytes were written.
    ///
    /// Separate from [`Env::fetch`] because [`HttpResponse::body`] is a `Vec<u8>`: fetching a
    /// 40 MB FLAC through it means holding 40 MB of it in RAM, which is why the stream cache used
    /// to carry a `MAX_CACHE_BYTES` ceiling that a lossless track can genuinely exceed. This writes
    /// as the bytes arrive and never holds more than one chunk.
    ///
    /// The write must be atomic from a reader's point of view: a half-downloaded file that looks
    /// complete is worse than no file, because nothing will ever go back and fix it. Implementors
    /// write to a temporary beside `path` and rename.
    ///
    /// A non-2xx status is an `Err`, unlike in `fetch` — there is no useful "response object" to
    /// hand back when the destination is a file.
    fn fetch_to_file(req: HttpRequest, path: &str) -> TryEnvFuture<u64>;

    /// Performs the request and hands back the head plus the body **as it arrives**.
    ///
    /// The third mode, and the one the other two cannot cover. [`Env::fetch`] materialises the whole
    /// body before anyone sees a byte of it, and [`Env::fetch_to_file`] writes it away and tells you
    /// how much; neither lets a caller *transform bytes on their way through*. That is exactly what
    /// serving an encrypted Deezer track requires: decrypt each stripe as it lands and emit it, so
    /// playback starts on the first one rather than after the last.
    ///
    /// A non-2xx status is `Ok` with the status in the head, as in [`Env::fetch`] — a 403 from a
    /// CDN is a successful fetch of an unsuccessful response, and the proxy needs to pass it on
    /// rather than convert it into a transport failure.
    ///
    /// **No whole-request timeout.** A proxied stream lives as long as the track does, and a ceiling
    /// here would cut playback off mid-song. Implementors bound the *connection*, not the transfer.
    fn fetch_stream(req: HttpRequest) -> TryEnvFuture<(HttpResponseHead, ByteStream)>;

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
