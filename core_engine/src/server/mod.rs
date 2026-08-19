// core_engine/src/server/mod.rs
//
// A local HTTP server on the loopback interface, so Media3 can play an ordinary `http://` URL
// instead of needing a custom `DataSource` for the disk cache and for decryption.
//
// Point C of docs/stremio-core/PLAN-3.x.md. The design is `stremio-core`'s `StreamingServer`
// (settings, health probe, `Range` handling); the implementation is ours, because there is nothing
// to extend — the previous loopback server was **deleted in E11** as dead code, and the note that
// killed it is the reason for the two rules below:
//
//   1. **Bind to 127.0.0.1 and only 127.0.0.1.** The old one had a `0.0.0.0` fallback, which put
//      `/resolve` on the LAN for anyone on the same Wi-Fi.
//   2. **Every request carries a per-process token.** On Android any installed app can talk to
//      localhost, so binding to loopback is necessary and nowhere near sufficient.
//
// Bodies are **streamed off disk**, not buffered — see [`FileBody`]. A player asking for a whole
// track must not make this process hold that track in memory, and it must start receiving bytes
// immediately rather than after the last one is ready.
//
// **Nothing is ever downloaded while a request is waiting.** Since 3.1 that is structural rather
// than careful: [`register`] hands back a loopback URL only when the file is already on disk, and
// otherwise says so, so the caller keeps playing the upstream URL exactly as it did before this
// server existed. That is what let the `503 Retry-After` path and the fallback dance in the player
// go away at once.
//
// 3.2 adds the second thing it can serve, and it does not weaken that rule: an encrypted Deezer
// track, **streamed** from the CDN and decrypted stripe by stripe as it passes through
// ([`proxy`]). Nothing is downloaded first there either — the first byte out is produced from the
// first stripe in. A proxy entry is registered deliberately, to play now, never as a fallback for a
// file that turned out to be missing, which is the distinction that keeps the 503 buried.

pub mod cache;
pub mod fill;
pub mod handles;
pub mod proxy;
pub mod range;

use crate::env::{Env, EnvError, HttpRequest, LogLevel};
use handles::{Served, StreamEntry};
use http_body_util::combinators::BoxBody;
use http_body_util::{BodyExt, Full};
use hyper::body::{Body, Bytes, Frame, SizeHint};
use hyper::header;
use hyper::service::service_fn;
use hyper::{Request, Response, StatusCode};
use hyper_util::rt::TokioIo;
use std::convert::Infallible;
use std::io::SeekFrom;
use std::pin::Pin;
use std::sync::OnceLock;
use std::task::{Context, Poll};
use tokio::io::{AsyncRead, AsyncSeekExt, ReadBuf};
use tokio::net::TcpListener;

/// One response body type for every route, so a small text answer and a whole track can come out of
/// the same function.
type ResBody = BoxBody<Bytes, std::io::Error>;

/// How much is read from disk per frame. Big enough that the syscall count is irrelevant, small
/// enough that a paused download is not holding megabytes.
const CHUNK_BYTES: usize = 64 * 1024;

/// How long a registration stays valid by default.
///
/// The handle table is not a cache: it maps an opaque name to a path, and the path can be swept out
/// from under it. A day is long enough that no listening session outlives it, and short enough that
/// the table cannot grow without bound across weeks of use.
const DEFAULT_TTL_MS: i64 = 24 * 60 * 60 * 1000;

/// How long a Deezer proxy registration stays valid.
///
/// Two hours rather than a day, because behind it is a CDN URL with its own expiry. Outliving what
/// it points at buys nothing and turns "this track is over" into "this track fails oddly".
const PROXY_TTL_MS: i64 = 2 * 60 * 60 * 1000;

/// What the app needs to build URLs for this server.
#[derive(Debug, Clone)]
pub struct ServerHandle {
    pub port: u16,
    pub token: String,
}

impl ServerHandle {
    /// The URL to hand to the player for a registered stream.
    pub fn stream_url(&self, handle: &str) -> String {
        format!(
            "http://127.0.0.1:{}/stream/{}?t={}",
            self.port, handle, self.token
        )
    }
}

static SERVER: OnceLock<ServerHandle> = OnceLock::new();

/// The running server, if [`start`] has succeeded.
pub fn current() -> Option<&'static ServerHandle> {
    SERVER.get()
}

/// Starts the server if it is not already running, and returns its port and token.
///
/// Idempotent: a second call returns the first server. The port is ephemeral (bind to 0) because a
/// fixed port is both a collision risk and a thing other apps can find.
pub async fn start<E: Env>() -> Result<ServerHandle, EnvError> {
    if let Some(existing) = SERVER.get() {
        return Ok(existing.clone());
    }

    let listener = TcpListener::bind(("127.0.0.1", 0))
        .await
        .map_err(|e| EnvError::Other(format!("could not bind loopback: {e}")))?;
    let port = listener
        .local_addr()
        .map_err(|e| EnvError::Other(format!("could not read local port: {e}")))?
        .port();

    let handle = ServerHandle {
        port,
        token: random_hex(32),
    };

    // If another thread won the race, use its server and drop ours — the listener closes when it
    // goes out of scope. Note `OnceLock::set` hands back *our* value on failure, not the stored
    // one, so the winner has to be read out separately.
    if SERVER.set(handle.clone()).is_err() {
        return Ok(SERVER
            .get()
            .expect("set failed, so it is already initialised")
            .clone());
    }

    let token = handle.token.clone();
    E::exec_concurrent(async move {
        loop {
            let (stream, _peer) = match listener.accept().await {
                Ok(accepted) => accepted,
                Err(e) => {
                    E::log(
                        LogLevel::Warn,
                        "LocalServer",
                        &format!("accept failed: {e}"),
                    );
                    continue;
                }
            };
            let token = token.clone();
            E::exec_concurrent(async move {
                let io = TokioIo::new(stream);
                let service = service_fn(move |req| {
                    let token = token.clone();
                    async move { route::<E>(req, token).await }
                });
                if let Err(e) = hyper::server::conn::http1::Builder::new()
                    .serve_connection(io, service)
                    .await
                {
                    E::log(
                        LogLevel::Debug,
                        "LocalServer",
                        &format!("connection ended: {e}"),
                    );
                }
            });
        }
    });

    E::log(
        LogLevel::Info,
        "LocalServer",
        &format!("listening on 127.0.0.1:{port}"),
    );
    Ok(handle)
}

// =============================================================================
// REGISTRATION
// =============================================================================

/// What the app asks for when it wants a track served from the cache.
#[derive(Debug, Clone)]
pub struct Registration {
    /// Where the bytes come from if they are not cached yet. Must be `http(s)`.
    ///
    /// **Empty means "look, do not fetch"**: answer `Ready` if the track is already on disk and
    /// `NotCached` otherwise, without starting anything. That is the question the player asks
    /// *before* resolving — a track played before needs no backend, no yt-dlp and no network at
    /// all, and finding that out has to be cheaper than the thing it avoids.
    pub upstream_url: String,
    /// Stable per track, per backend and per format. Sanitised before it touches the filesystem.
    pub cache_key: String,
    pub mime: Option<String>,
    /// Present when the bytes are Deezer-encrypted and have to be decrypted on the way in.
    pub deezer_sng_id: Option<String>,
    /// How long the resulting handle stays valid. `None` for [`DEFAULT_TTL_MS`].
    pub ttl_ms: Option<i64>,
}

/// What came of a [`register`] call.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Registered {
    /// The bytes are on disk. Play this URL.
    Ready(String),
    /// Not cached. **Play the upstream URL** — that is what the app did before this server existed
    /// and it still works. A background fill has been started, so a later play will be `Ready`.
    NotCached,
    /// Nothing was registered, and the reason is for the log rather than for the user.
    Refused(&'static str),
}

/// Why this registration will not be accepted, if it will not be.
///
/// Pure, so the rules are testable on their own. The important one is the second: local music is
/// **not** routed through this server. `content://` already plays through Media3, and putting it
/// behind HTTP would add a hop, a port and a token to something that has no problem — routing for
/// the sake of routing is how what already worked stops working.
fn refusal_for(request: &Registration, cache_root: &str) -> Option<&'static str> {
    if cache_root.is_empty() {
        return Some("no cache directory");
    }
    if request.cache_key.is_empty() {
        return Some("no cache key");
    }
    // An empty upstream is the lookup-only question and is allowed. Anything else has to be remote.
    if !request.upstream_url.is_empty()
        && !(request.upstream_url.starts_with("http://")
            || request.upstream_url.starts_with("https://"))
    {
        return Some("only remote streams are routed");
    }
    None
}

/// Registers a track for serving, and starts a background fill if it is not cached yet.
///
/// Synchronous, and does no I/O beyond one `exists` check: this runs on the path that is about to
/// start playback, and the only decision it makes is "is the file there".
pub fn register<E: Env>(request: Registration, cache_root: &str) -> Registered {
    // Validated before the server is consulted, so what a request is *allowed* to be does not
    // depend on whether the server happened to come up — and so it can be tested without one.
    if let Some(reason) = refusal_for(&request, cache_root) {
        return Registered::Refused(reason);
    }
    let server = match current() {
        Some(server) => server,
        // Indistinguishable from "not cached" on purpose: either way the caller plays the upstream
        // URL, and a caller that has to branch on *why* will eventually get the branch wrong.
        None => return Registered::NotCached,
    };

    let path = cache::path_for(cache_root, &request.cache_key);
    let path_str = path.to_string_lossy().into_owned();

    // `std::fs` and not `tokio::fs`: this is one stat on a local path, and making it async would
    // put an await between "is it there" and "hand out its URL".
    if path.is_file() {
        let entry = StreamEntry {
            served: Served::File(path_str),
            mime: request.mime,
            expires_at_ms: Some(E::now_ms() + request.ttl_ms.unwrap_or(DEFAULT_TTL_MS)),
        };
        return Registered::Ready(server.stream_url(&handles::register::<E>(entry)));
    }

    // Lookup-only: the caller wanted to know, not to start anything.
    if request.upstream_url.is_empty() {
        return Registered::NotCached;
    }

    let kind = match request.deezer_sng_id {
        Some(sng_id) if !sng_id.is_empty() => fill::Kind::Deezer { sng_id },
        _ => fill::Kind::Plain,
    };
    fill::start::<E>(
        request.upstream_url,
        path_str,
        kind,
        Some(cache_root.to_owned()),
    );
    Registered::NotCached
}

/// Registers an encrypted Deezer track to be **streamed and decrypted on the way through**.
///
/// Separate from [`register`] because it answers a different question. `register` asks "is this
/// already on disk?" and refuses to start anything if the answer is no. This one is a deliberate
/// "play this now, from the CDN", and it always succeeds when the server is up — there is nothing
/// to wait for, because the decryption happens as the bytes arrive rather than before they do.
///
/// Returns `None` when the server is not running, which the caller handles by playing the track the
/// way it did before this existed.
pub fn register_proxy<E: Env>(
    url: &str,
    sng_id: &str,
    mime: Option<String>,
    ttl_ms: Option<i64>,
) -> Option<String> {
    let server = current()?;
    if !(url.starts_with("http://") || url.starts_with("https://")) || sng_id.is_empty() {
        return None;
    }
    let entry = StreamEntry {
        served: Served::DeezerProxy {
            url: url.to_owned(),
            sng_id: sng_id.to_owned(),
        },
        mime,
        // Shorter than a cache entry's day by default: behind this handle is a CDN URL that expires,
        // and a handle that outlives what it points at only produces confusing failures later.
        expires_at_ms: Some(E::now_ms() + ttl_ms.unwrap_or(PROXY_TTL_MS)),
    };
    Some(server.stream_url(&handles::register::<E>(entry)))
}

// =============================================================================
// ROUTING
// =============================================================================

async fn route<E: Env>(
    req: Request<hyper::body::Incoming>,
    token: String,
) -> Result<Response<ResBody>, Infallible> {
    let path = req.uri().path().to_owned();
    let query = req.uri().query().unwrap_or("").to_owned();

    // The token is checked before anything else looks at the path, so an unauthorised caller cannot
    // even learn which paths exist.
    if !token_matches(&query, &token) {
        return Ok(text(StatusCode::FORBIDDEN, "forbidden"));
    }

    if path == "/health" {
        return Ok(json(
            StatusCode::OK,
            &format!(r#"{{"ok":true,"handles":{}}}"#, handles::len()),
        ));
    }

    if let Some(handle) = path.strip_prefix("/stream/") {
        if handle.is_empty() || handle.contains('/') {
            return Ok(text(StatusCode::BAD_REQUEST, "bad handle"));
        }
        let range_header = req
            .headers()
            .get(header::RANGE)
            .and_then(|v| v.to_str().ok())
            .map(str::to_owned);
        return Ok(serve_stream::<E>(handle, range_header.as_deref()).await);
    }

    Ok(text(StatusCode::NOT_FOUND, "not found"))
}

/// Constant-time-ish comparison of the `t=` query parameter against the session token.
///
/// Not a defence against a timing attack — an attacker who can time this can also just read the
/// port from `/proc/net/tcp`. It is here so a wrong token is rejected on its content and not on its
/// length, which is the mistake that makes a token guessable one byte at a time.
fn token_matches(query: &str, token: &str) -> bool {
    let supplied = query
        .split('&')
        .find_map(|pair| pair.strip_prefix("t="))
        .unwrap_or("");
    if supplied.len() != token.len() {
        return false;
    }
    supplied
        .bytes()
        .zip(token.bytes())
        .fold(0u8, |acc, (a, b)| acc | (a ^ b))
        == 0
}

// =============================================================================
// STREAMING
// =============================================================================

async fn serve_stream<E: Env>(handle: &str, range_header: Option<&str>) -> Response<ResBody> {
    let entry = match handles::resolve::<E>(handle) {
        Some(entry) => entry,
        // Deliberately indistinguishable from "never existed": whether a handle has expired is not
        // something a caller who does not already know it should be able to learn.
        None => return text(StatusCode::NOT_FOUND, "unknown handle"),
    };

    let mime = entry
        .mime
        .clone()
        .unwrap_or_else(|| "application/octet-stream".to_string());

    match &entry.served {
        Served::File(path) => serve_file::<E>(path, &mime, range_header).await,
        Served::DeezerProxy { url, sng_id } => {
            serve_proxy::<E>(url, sng_id, &mime, range_header).await
        }
    }
}

async fn serve_file<E: Env>(
    path: &str,
    mime: &str,
    range_header: Option<&str>,
) -> Response<ResBody> {
    let metadata = match tokio::fs::metadata(path).await {
        Ok(m) => m,
        // The cache was swept between registration and this request. `404` rather than `500`: the
        // player treats it as a dead source and re-resolves, which is the outcome that recovers.
        Err(e) => {
            E::log(
                LogLevel::Warn,
                "LocalServer",
                &format!("cannot stat {path}: {e}"),
            );
            return text(StatusCode::NOT_FOUND, "gone");
        }
    };
    let file_len = metadata.len();

    let (status, start, len, content_range) = match range::parse(range_header, file_len) {
        range::RangeOutcome::Unsatisfiable => {
            return Response::builder()
                .status(StatusCode::RANGE_NOT_SATISFIABLE)
                .header(header::CONTENT_RANGE, format!("bytes */{file_len}"))
                .header(header::ACCEPT_RANGES, "bytes")
                .body(full_body(Bytes::new()))
                .unwrap_or_else(|_| text(StatusCode::INTERNAL_SERVER_ERROR, ""));
        }
        range::RangeOutcome::Whole => (StatusCode::OK, 0, file_len, None),
        range::RangeOutcome::Partial(r) => (
            StatusCode::PARTIAL_CONTENT,
            r.start,
            r.len(),
            Some(format!("bytes {}-{}/{}", r.start, r.end, file_len)),
        ),
    };

    let body = match file_body(path, start, len).await {
        Ok(body) => body,
        Err(e) => {
            E::log(
                LogLevel::Warn,
                "LocalServer",
                &format!("cannot open {path}: {e}"),
            );
            return text(StatusCode::INTERNAL_SERVER_ERROR, "read failed");
        }
    };

    let mut builder = Response::builder()
        .status(status)
        .header(header::CONTENT_TYPE, mime)
        .header(header::ACCEPT_RANGES, "bytes")
        .header(header::CONTENT_LENGTH, len);
    if let Some(range) = content_range {
        builder = builder.header(header::CONTENT_RANGE, range);
    }
    builder
        .body(body)
        .unwrap_or_else(|_| text(StatusCode::INTERNAL_SERVER_ERROR, ""))
}

/// Serves an encrypted Deezer track straight off the CDN, decrypting it on the way through.
///
/// The arithmetic lives in [`proxy`] and is tested there; this function is the plumbing around it.
async fn serve_proxy<E: Env>(
    url: &str,
    sng_id: &str,
    mime: &str,
    range_header: Option<&str>,
) -> Response<ResBody> {
    let mut intent = proxy::parse_intent(range_header);
    let ranged = !matches!(intent, proxy::Intent::Whole);

    // A suffix range (`bytes=-N`) is the one shape that cannot be stripe-aligned without knowing how
    // big the file is, and there is no way to know that without asking. One byte is the cheapest
    // question there is; if even that fails, serving from the start beats refusing.
    if let proxy::Intent::Suffix(n) = intent {
        intent = match probe_total::<E>(url).await {
            Some(total) => proxy::resolve_suffix(n, total),
            None => proxy::Intent::Whole,
        };
    }

    let plan = proxy::plan_upstream(intent);
    let mut request = HttpRequest::get(url);
    if let Some(range) = &plan.upstream_range {
        request = request.header("Range", range.clone());
    }

    let (head, stream) = match E::fetch_stream(request).await {
        Ok(pair) => pair,
        Err(e) => {
            E::log(
                LogLevel::Warn,
                "LocalServer",
                &format!("proxy could not reach the CDN: {e}"),
            );
            return text(StatusCode::BAD_GATEWAY, "upstream unreachable");
        }
    };

    if !head.is_success() {
        // **The upstream status is passed through, not collapsed into 502.** A Deezer URL that has
        // expired answers 403 or 410, and that is exactly what `AudioPlayerService` keys on to drop
        // the cached URL and re-resolve. Turning it into 502 would make that recovery unreachable —
        // the mistake `docs/11` warned about and 3.0 already had to fix once.
        let status =
            StatusCode::from_u16(head.status).unwrap_or(StatusCode::BAD_GATEWAY);
        E::log(
            LogLevel::Warn,
            "LocalServer",
            &format!("CDN answered {} for a proxied track", head.status),
        );
        return text(status, "upstream refused");
    }

    // How big is the track? `Content-Range` says so when we asked for a range; otherwise
    // `Content-Length` does, but only when we asked for the whole thing.
    let total = head.content_range_total().or_else(|| {
        if plan.aligned == 0 {
            head.content_length()
        } else {
            None
        }
    });

    let answer = proxy::answer(&plan, ranged, total);

    if answer.status == 416 {
        let mut builder = Response::builder()
            .status(StatusCode::RANGE_NOT_SATISFIABLE)
            .header(header::ACCEPT_RANGES, "bytes");
        if let Some(range) = answer.content_range {
            builder = builder.header(header::CONTENT_RANGE, range);
        }
        return builder
            .body(full_body(Bytes::new()))
            .unwrap_or_else(|_| text(StatusCode::INTERNAL_SERVER_ERROR, ""));
    }

    let body = proxy::DecryptingBody::new(stream, sng_id, &plan, answer.remaining).boxed();

    let mut builder = Response::builder()
        .status(StatusCode::from_u16(answer.status).unwrap_or(StatusCode::OK))
        .header(header::CONTENT_TYPE, mime)
        .header(header::ACCEPT_RANGES, "bytes");
    if let Some(length) = answer.content_length {
        builder = builder.header(header::CONTENT_LENGTH, length);
    }
    if let Some(range) = answer.content_range {
        builder = builder.header(header::CONTENT_RANGE, range);
    }
    builder
        .body(body)
        .unwrap_or_else(|_| text(StatusCode::INTERNAL_SERVER_ERROR, ""))
}

/// Asks the CDN for one byte, purely to read the total size out of its `Content-Range`.
///
/// Only ever used for a suffix range, which players ask for rarely. Cheaper than the alternatives:
/// a `HEAD` that some CDNs answer differently, or fetching the tail and discovering afterwards that
/// it does not start on a stripe boundary.
async fn probe_total<E: Env>(url: &str) -> Option<u64> {
    let request = HttpRequest::get(url).header("Range", "bytes=0-0");
    let response = E::fetch(request).await.ok()?;
    if !response.is_success() {
        return None;
    }
    let value = response.header("Content-Range")?;
    value.rsplit('/').next()?.trim().parse().ok()
}

// =============================================================================
// HELPERS
// =============================================================================

/// A body already in memory. `Full`'s error type is `Infallible`, so the `match` has no arms —
/// which is how you say "this cannot happen" to the compiler rather than to the reader.
fn full_body(bytes: impl Into<Bytes>) -> ResBody {
    Full::new(bytes.into())
        .map_err(|never: Infallible| match never {})
        .boxed()
}

fn text(status: StatusCode, body: &str) -> Response<ResBody> {
    Response::builder()
        .status(status)
        .header(header::CONTENT_TYPE, "text/plain; charset=utf-8")
        .body(full_body(body.to_owned()))
        .expect("a plain-text response cannot fail to build")
}

fn json(status: StatusCode, body: &str) -> Response<ResBody> {
    Response::builder()
        .status(status)
        .header(header::CONTENT_TYPE, "application/json")
        .body(full_body(body.to_owned()))
        .expect("a json response cannot fail to build")
}

// =============================================================================
// STREAMING BODY
// =============================================================================

/// A response body read off a file a chunk at a time.
///
/// This is the difference between a player that starts within a moment and one that waits for the
/// whole track. `tokio::fs::read` into a `Full` body would have to finish reading the file before a
/// single byte reached the socket, and would hold the entire track in memory while it did.
///
/// `Unpin` by construction — `tokio::fs::File` is `Unpin` and the rest is a `u64` — so `get_mut`
/// needs no unsafe projection.
struct FileBody {
    file: tokio::fs::File,
    /// Bytes still owed for this response. Bounds the read so a `Range` stops where it should
    /// rather than running to the end of the file.
    remaining: u64,
}

impl Body for FileBody {
    type Data = Bytes;
    type Error = std::io::Error;

    fn poll_frame(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
    ) -> Poll<Option<Result<Frame<Self::Data>, Self::Error>>> {
        let this = self.get_mut();
        if this.remaining == 0 {
            return Poll::Ready(None);
        }
        let want = this.remaining.min(CHUNK_BYTES as u64) as usize;
        let mut buf = vec![0u8; want];
        let mut read_buf = ReadBuf::new(&mut buf);

        match Pin::new(&mut this.file).poll_read(cx, &mut read_buf) {
            Poll::Pending => Poll::Pending,
            Poll::Ready(Err(e)) => Poll::Ready(Some(Err(e))),
            Poll::Ready(Ok(())) => {
                let filled = read_buf.filled().len();
                if filled == 0 {
                    // EOF with bytes still owed: the file shrank under us, or `Content-Length` was
                    // computed against a different version of it. Ending the body is the only
                    // honest move — inventing padding would corrupt the audio.
                    this.remaining = 0;
                    return Poll::Ready(None);
                }
                buf.truncate(filled);
                this.remaining -= filled as u64;
                Poll::Ready(Some(Ok(Frame::data(Bytes::from(buf)))))
            }
        }
    }

    fn size_hint(&self) -> SizeHint {
        SizeHint::with_exact(self.remaining)
    }
}

/// Opens `path`, seeks to `start`, and hands back a body good for `len` bytes.
async fn file_body(path: &str, start: u64, len: u64) -> std::io::Result<ResBody> {
    let mut file = tokio::fs::File::open(path).await?;
    if start > 0 {
        file.seek(SeekFrom::Start(start)).await?;
    }
    Ok(FileBody {
        file,
        remaining: len,
    }
    .boxed())
}

/// `n` random bytes as lowercase hex.
///
/// From the OS entropy source. If that fails there is no safe fallback — a predictable token is
/// worse than no server — so the failure is loud.
pub(crate) fn random_hex(n: usize) -> String {
    use std::fmt::Write as _;
    let mut bytes = vec![0u8; n];
    getrandom::getrandom(&mut bytes).expect("OS entropy is unavailable");
    let mut out = String::with_capacity(n * 2);
    for b in bytes {
        let _ = write!(out, "{b:02x}");
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::env::mock::{self, MockEnv};

    #[test]
    fn a_wrong_token_is_refused_and_a_right_one_is_not() {
        let token = "0123456789abcdef";
        assert!(token_matches("t=0123456789abcdef", token));
        assert!(token_matches("x=1&t=0123456789abcdef", token));
        assert!(!token_matches("t=0123456789abcdee", token));
        assert!(!token_matches("t=", token));
        assert!(!token_matches("", token));
        // A prefix of the real token must not pass.
        assert!(!token_matches("t=0123", token));
    }

    #[test]
    fn random_hex_is_the_right_length_and_not_constant() {
        assert_eq!(random_hex(32).len(), 64);
        assert_ne!(random_hex(32), random_hex(32));
    }

    #[test]
    fn a_stream_url_carries_the_token_and_stays_on_loopback() {
        let handle = ServerHandle {
            port: 41234,
            token: "abc".into(),
        };
        assert_eq!(
            handle.stream_url("deadbeef"),
            "http://127.0.0.1:41234/stream/deadbeef?t=abc"
        );
    }

    fn registration(url: &str) -> Registration {
        Registration {
            upstream_url: url.into(),
            cache_key: "spotify_abc123".into(),
            mime: Some("audio/mpeg".into()),
            deezer_sng_id: None,
            ttl_ms: None,
        }
    }

    #[test]
    fn local_files_are_not_routed_through_the_server() {
        // Not an oversight waiting to be "fixed": `content://` already plays, and putting it behind
        // HTTP would add a hop, a port and a token to something that has no problem. This test is
        // what keeps that a decision rather than a thing nobody got round to.
        assert_eq!(
            refusal_for(
                &registration("content://media/external/audio/12"),
                "/tmp/cache"
            ),
            Some("only remote streams are routed")
        );
        assert_eq!(
            refusal_for(&registration("/storage/emulated/0/Music/a.mp3"), "/tmp/cache"),
            Some("only remote streams are routed")
        );
        assert_eq!(
            refusal_for(&registration("file:///storage/a.mp3"), "/tmp/cache"),
            Some("only remote streams are routed")
        );
        // And a remote one is not refused, so the rule above is not simply "refuse everything".
        assert_eq!(
            refusal_for(&registration("https://cdn.test/a.mp3"), "/tmp/cache"),
            None
        );
    }

    #[test]
    fn a_lookup_only_registration_is_allowed_to_have_no_upstream() {
        // The question the player asks before it resolves anything: "have I got this already?".
        // Refusing it would mean every play went through a backend even when the bytes were on disk.
        let mut lookup = registration("https://cdn.test/a.mp3");
        lookup.upstream_url = String::new();
        assert_eq!(refusal_for(&lookup, "/tmp/cache"), None);
    }

    #[test]
    fn a_registration_with_nowhere_to_put_the_bytes_is_refused() {
        assert_eq!(
            refusal_for(&registration("https://cdn.test/a.mp3"), ""),
            Some("no cache directory")
        );
        let mut keyless = registration("https://cdn.test/a.mp3");
        keyless.cache_key = String::new();
        assert_eq!(refusal_for(&keyless, "/tmp/cache"), Some("no cache key"));
    }

    #[test]
    fn an_uncached_track_is_never_given_a_loopback_url() {
        // The rule the whole design rests on: the player learns about this server only once the
        // bytes are on disk, so no request can arrive and have to wait for a download.
        let _guard = mock::lock_and_reset();
        let root = std::env::temp_dir().join(format!("rustify-reg-{}", random_hex(8)));
        std::fs::create_dir_all(&root).unwrap();

        let outcome = register::<MockEnv>(
            registration("https://cdn.test/not-here.mp3"),
            root.to_str().unwrap(),
        );
        assert_eq!(outcome, Registered::NotCached);

        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn a_cached_track_is_served_from_the_path_the_key_maps_to() {
        let _guard = mock::lock_and_reset();
        let root = std::env::temp_dir().join(format!("rustify-reg-{}", random_hex(8)));
        std::fs::create_dir_all(&root).unwrap();
        let root_str = root.to_str().unwrap();
        std::fs::write(cache::path_for(root_str, "spotify_abc123"), b"audio").unwrap();

        match register::<MockEnv>(registration("https://cdn.test/a.mp3"), root_str) {
            // With no server running the answer is `NotCached`, which is correct and not what this
            // test is about; when one is up the URL must be a loopback one carrying the token.
            Registered::Ready(url) => {
                assert!(url.starts_with("http://127.0.0.1:"));
                assert!(url.contains("/stream/"));
                assert!(url.contains("?t="));
            }
            Registered::NotCached => assert!(current().is_none()),
            other => panic!("unexpected registration outcome: {other:?}"),
        }

        let _ = std::fs::remove_dir_all(&root);
    }
}
