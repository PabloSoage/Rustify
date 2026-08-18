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
// And nothing is ever fetched from the network while a request is waiting: see [`serve_stream`].

pub mod handles;
pub mod range;

use crate::env::{Env, EnvError, HttpRequest, LogLevel};
use handles::{Source, StreamEntry};
use http_body_util::combinators::BoxBody;
use http_body_util::{BodyExt, Full};
use hyper::body::{Body, Bytes, Frame, SizeHint};
use hyper::header;
use hyper::service::service_fn;
use hyper::{Request, Response, StatusCode};
use hyper_util::rt::TokioIo;
use std::collections::HashSet;
use std::convert::Infallible;
use std::io::SeekFrom;
use std::pin::Pin;
use std::sync::{Mutex, OnceLock};
use std::task::{Context, Poll};
use tokio::io::{AsyncRead, AsyncSeekExt, ReadBuf};
use tokio::net::TcpListener;

/// One response body type for every route, so a small text answer and a whole track can come out of
/// the same function.
type ResBody = BoxBody<Bytes, std::io::Error>;

/// How much is read from disk per frame. Big enough that the syscall count is irrelevant, small
/// enough that a paused download is not holding megabytes.
const CHUNK_BYTES: usize = 64 * 1024;

/// Largest upstream this server will pull into memory to write into the cache.
///
/// Only the **cache fill** is bounded by this, never serving: serving streams off disk a chunk at a
/// time and has no size limit. The fill still buffers, because `Env::fetch` hands back a complete
/// response — this is what stops a mistaken registration from trying to hold a video in RAM.
pub const MAX_CACHE_BYTES: u64 = 64 * 1024 * 1024;

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

    let path = match local_path_for::<E>(&entry) {
        LocalPath::Ready(path) => path,
        LocalPath::NotCachedYet { url, cache } => {
            // **Nothing is fetched while a request is waiting.** Downloading the whole track before
            // answering would mean the player sat silent until the last byte arrived — worse than
            // what it replaced. The fill runs in the background and this request says "not yet", so
            // the caller falls back to the upstream URL exactly as it does when the server is off.
            // The next play finds it on disk.
            start_cache_fill::<E>(url, cache);
            return Response::builder()
                .status(StatusCode::SERVICE_UNAVAILABLE)
                .header(header::RETRY_AFTER, "1")
                .header(header::CONTENT_TYPE, "text/plain; charset=utf-8")
                .body(full_body("not cached yet"))
                .unwrap_or_else(|_| text(StatusCode::SERVICE_UNAVAILABLE, ""));
        }
        LocalPath::Unusable(reason) => {
            E::log(LogLevel::Warn, "LocalServer", &format!("{handle}: {reason}"));
            return text(StatusCode::INTERNAL_SERVER_ERROR, "not servable");
        }
    };

    let metadata = match tokio::fs::metadata(&path).await {
        Ok(m) => m,
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
    let mime = entry.mime.as_deref().unwrap_or("application/octet-stream");

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

    let body = match file_body(&path, start, len).await {
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

/// What [`local_path_for`] found. Synchronous on purpose: deciding must not involve the network.
enum LocalPath {
    Ready(String),
    NotCachedYet { url: String, cache: String },
    Unusable(String),
}

fn local_path_for<E: Env>(entry: &StreamEntry) -> LocalPath {
    match &entry.source {
        Source::File(path) => LocalPath::Ready(path.clone()),
        Source::Upstream { url, cache } => match cache {
            // Refused rather than fetched into a temporary: a cache the caller did not ask for is
            // still a cache, and it would be one nothing ever cleans up.
            None => LocalPath::Unusable("upstream registered without a cache path".into()),
            Some(cache) => {
                // `std::fs` and not `tokio::fs`: this is a stat on a local path, and making the
                // decision async would mean an await between "is it there" and "serve it".
                if std::path::Path::new(cache).exists() {
                    LocalPath::Ready(cache.clone())
                } else {
                    LocalPath::NotCachedYet {
                        url: url.clone(),
                        cache: cache.clone(),
                    }
                }
            }
        },
    }
}

/// Cache paths currently being filled, so a player retrying every second does not start a download
/// per attempt.
static FILLING: OnceLock<Mutex<HashSet<String>>> = OnceLock::new();

fn start_cache_fill<E: Env>(url: String, cache: String) {
    {
        let filling = FILLING.get_or_init(|| Mutex::new(HashSet::new()));
        match filling.lock() {
            Ok(mut set) => {
                if !set.insert(cache.clone()) {
                    return; // already downloading this one
                }
            }
            Err(_) => return,
        }
    }

    E::exec_concurrent(async move {
        let outcome = fill_cache::<E>(&url, &cache).await;
        if let Err(e) = &outcome {
            E::log(
                LogLevel::Warn,
                "LocalServer",
                &format!("cache fill failed for {cache}: {e}"),
            );
        }
        if let Some(filling) = FILLING.get() {
            if let Ok(mut set) = filling.lock() {
                set.remove(&cache);
            }
        }
    });
}

async fn fill_cache<E: Env>(url: &str, cache: &str) -> Result<(), EnvError> {
    let response = E::fetch(HttpRequest::get(url)).await?;
    if !response.is_success() {
        // The status is carried in the message rather than swallowed: an expired upstream shows up
        // as 403/410 here too, and a reader of the log should be able to tell that from a timeout.
        return Err(EnvError::Fetch(format!(
            "upstream answered {}",
            response.status
        )));
    }
    if response.body.len() as u64 > MAX_CACHE_BYTES {
        return Err(EnvError::Storage(format!(
            "refusing to cache {} bytes",
            response.body.len()
        )));
    }
    write_atomically(cache, &response.body).await
}

async fn write_atomically(path: &str, bytes: &[u8]) -> Result<(), EnvError> {
    if let Some(parent) = std::path::Path::new(path).parent() {
        tokio::fs::create_dir_all(parent)
            .await
            .map_err(|e| EnvError::Storage(e.to_string()))?;
    }
    // Same reasoning as `AndroidEnv::set_storage`: a half-written cache entry that looks complete
    // is worse than no cache entry, because nothing will ever go back and fix it.
    let tmp = format!("{path}.part");
    tokio::fs::write(&tmp, bytes)
        .await
        .map_err(|e| EnvError::Storage(e.to_string()))?;
    tokio::fs::rename(&tmp, path)
        .await
        .map_err(|e| EnvError::Storage(e.to_string()))?;
    Ok(())
}

// `read_slice` is gone: it read a whole range into a `Vec` before answering. `FileBody` streams the
// same range off the file handle instead, which is what lets playback start on the first chunk.

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
    fn an_uncached_upstream_is_never_fetched_while_a_request_waits() {
        // The whole point of `LocalPath`: deciding what to serve is synchronous, so there is no way
        // to accidentally reintroduce "download the track, then answer".
        let entry = StreamEntry {
            source: Source::Upstream {
                url: "https://example.test/a.mp3".into(),
                cache: Some("/definitely/not/here/a.mp3".into()),
            },
            mime: None,
            expires_at_ms: None,
        };
        assert!(matches!(
            local_path_for::<crate::env::mock::MockEnv>(&entry),
            LocalPath::NotCachedYet { .. }
        ));
    }

    #[test]
    fn an_upstream_without_a_cache_path_is_unusable_rather_than_fetched() {
        let entry = StreamEntry {
            source: Source::Upstream {
                url: "https://example.test/a.mp3".into(),
                cache: None,
            },
            mime: None,
            expires_at_ms: None,
        };
        assert!(matches!(
            local_path_for::<crate::env::mock::MockEnv>(&entry),
            LocalPath::Unusable(_)
        ));
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
}
