// server/src/stream.rs
//
// `/api/stream/{handle}?t=…` → the core's own server, which keeps listening on 127.0.0.1 only. The
// browser plays a Deezer track or a cached file through here without ever seeing that address.
//
// It is not a proxy: the only thing reachable is `/stream/` on the core's port, the handle and the
// token are checked to be the characters the core makes them of, and redirects are not followed.

use std::collections::HashMap;

use axum::body::Body;
use axum::extract::{Path, Query, State};
use axum::http::{header, HeaderMap, StatusCode};
use axum::response::{IntoResponse, Response};

use crate::App;

/// What the core's handles and tokens are made of (hex, base64url); anything else is refused
/// before a request is built from it.
fn plain(s: &str) -> bool {
    !s.is_empty() && s.len() <= 256 && s.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
}

pub async fn forward(
    State(app): State<App>,
    Path(handle): Path<String>,
    Query(query): Query<HashMap<String, String>>,
    headers: HeaderMap,
) -> Response {
    let Some(port) = app.stream_port else {
        return (StatusCode::SERVICE_UNAVAILABLE, "the core's stream server is not running").into_response();
    };
    let token = query.get("t").map(String::as_str).unwrap_or("");
    if !plain(&handle) || !plain(token) {
        return StatusCode::BAD_REQUEST.into_response();
    }
    let mut request = app.http.get(format!("http://127.0.0.1:{port}/stream/{handle}?t={token}"));
    if let Some(range) = headers.get(header::RANGE) {
        request = request.header(header::RANGE, range.clone());
    }
    let upstream = match request.send().await {
        Ok(upstream) => upstream,
        Err(e) => {
            tracing::warn!("stream {handle}: {e}");
            return StatusCode::BAD_GATEWAY.into_response();
        }
    };
    let mut answer = Response::builder().status(upstream.status());
    for name in [header::CONTENT_TYPE, header::CONTENT_LENGTH, header::CONTENT_RANGE, header::ACCEPT_RANGES] {
        if let Some(value) = upstream.headers().get(&name) {
            answer = answer.header(name, value.clone());
        }
    }
    answer
        .header(header::CACHE_CONTROL, "no-store")
        .body(Body::from_stream(upstream.bytes_stream()))
        .unwrap_or_else(|_| StatusCode::INTERNAL_SERVER_ERROR.into_response())
}

#[cfg(test)]
mod tests {
    use super::plain;

    #[test]
    fn only_the_cores_characters_pass() {
        assert!(plain("deadbeef"));
        assert!(plain("a-b_C9"));
        assert!(!plain(""));
        assert!(!plain("../etc/passwd"));
        assert!(!plain("x?t=y"));
        assert!(!plain("a b"));
        assert!(!plain(&"a".repeat(257)));
    }
}
