// server/src/fetch.rs
//
// `/api/fetch?url=…`: what the interface in the browser cannot ask for itself. A page cannot read an
// answer from another site that sends no CORS headers (most of what the other platforms ask directly:
// lyrics, covers, the audio sources), cannot set `User-Agent` or `Cookie`, and a YouTube stream is tied to
// the address that asked for it, which is this server's.
//
// A door like the others, so counted (docs serie-4.x/28 §5). It answers only a session, like everything
// but `/healthz` and `/login`; its cookie is `SameSite=Strict`, so no other site can spend it; and it does
// not reach this machine's own loopback, where the core's server is: that one is `/api/stream`'s, and
// only its stream path. The LAN it does reach, because the user's own Subsonic server lives there.

use std::collections::HashMap;
use std::net::IpAddr;

use axum::body::{Body, Bytes};
use axum::extract::{Query, State};
use axum::http::{header, HeaderMap, HeaderName, HeaderValue, Method, StatusCode};
use axum::response::{IntoResponse, Response};

use crate::App;

/// Whether [url] may be asked for: http or https, and not this machine.
pub fn allowed(url: &reqwest::Url) -> bool {
    if !matches!(url.scheme(), "http" | "https") {
        return false;
    }
    match url.host() {
        Some(url::Host::Domain(name)) => !name.eq_ignore_ascii_case("localhost") && !name.to_ascii_lowercase().ends_with(".localhost"),
        Some(url::Host::Ipv4(ip)) => !IpAddr::V4(ip).is_loopback() && !ip.is_unspecified(),
        Some(url::Host::Ipv6(ip)) => !IpAddr::V6(ip).is_loopback() && !ip.is_unspecified(),
        None => false,
    }
}

/// What the page wants sent that it cannot send itself: `X-Rustify-Headers`, a JSON object.
fn asked_headers(headers: &HeaderMap) -> Vec<(HeaderName, HeaderValue)> {
    let Some(text) = headers.get("x-rustify-headers").and_then(|v| v.to_str().ok()) else {
        return Vec::new();
    };
    let Ok(serde_json::Value::Object(asked)) = serde_json::from_str::<serde_json::Value>(text) else {
        return Vec::new();
    };
    asked
        .iter()
        .filter_map(|(name, value)| {
            let name = HeaderName::from_bytes(name.as_bytes()).ok()?;
            let value = HeaderValue::from_str(value.as_str()?).ok()?;
            Some((name, value))
        })
        .collect()
}

pub async fn forward(
    State(app): State<App>,
    method: Method,
    Query(query): Query<HashMap<String, String>>,
    headers: HeaderMap,
    body: Bytes,
) -> Response {
    let Some(url) = query.get("url").and_then(|u| reqwest::Url::parse(u).ok()).filter(allowed) else {
        return (StatusCode::BAD_REQUEST, "not a URL this server fetches").into_response();
    };
    let mut request = app.fetcher.request(method, url.clone());
    for name in [header::RANGE, header::CONTENT_TYPE, header::ACCEPT] {
        if let Some(value) = headers.get(&name) {
            request = request.header(name, value.clone());
        }
    }
    for (name, value) in asked_headers(&headers) {
        request = request.header(name, value);
    }
    if !body.is_empty() {
        request = request.body(body);
    }
    let upstream = match request.send().await {
        Ok(upstream) => upstream,
        Err(e) => {
            // The query holds tokens; the host is all a log needs.
            tracing::warn!("fetch {}: {}", url.host_str().unwrap_or("?"), e.without_url());
            return StatusCode::BAD_GATEWAY.into_response();
        }
    };
    let mut answer = Response::builder().status(upstream.status());
    for name in [
        header::CONTENT_TYPE,
        header::CONTENT_LENGTH,
        header::CONTENT_RANGE,
        header::ACCEPT_RANGES,
        header::ETAG,
        header::LAST_MODIFIED,
        header::RETRY_AFTER,
    ] {
        if let Some(value) = upstream.headers().get(&name) {
            answer = answer.header(name, value.clone());
        }
    }
    answer
        .body(Body::from_stream(upstream.bytes_stream()))
        .unwrap_or_else(|_| StatusCode::INTERNAL_SERVER_ERROR.into_response())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn url(s: &str) -> reqwest::Url {
        reqwest::Url::parse(s).unwrap()
    }

    #[test]
    fn this_machine_is_out_of_reach_and_the_lan_is_not() {
        for ok in ["https://lrclib.net/api", "http://192.168.1.10:4533/rest/ping.view", "http://nas.local/x", "https://[2606:4700::1111]/"] {
            assert!(allowed(&url(ok)), "{ok}");
        }
        for no in [
            "http://127.0.0.1:8484/api/call/x",
            "http://127.1.2.3/",
            "http://localhost/",
            "http://LOCALHOST:1/",
            "http://a.localhost/",
            "http://0.0.0.0/",
            "http://[::1]/",
            "file:///etc/passwd",
            "ftp://example.com/",
        ] {
            assert!(!allowed(&url(no)), "{no}");
        }
    }

    #[test]
    fn the_headers_a_page_cannot_set_are_read_and_nonsense_is_dropped() {
        let mut headers = HeaderMap::new();
        headers.insert("x-rustify-headers", HeaderValue::from_static(r#"{"User-Agent":"Rustify","Cookie":"arl=x","bad name":"y","N":1}"#));
        let asked = asked_headers(&headers);
        assert_eq!(asked.len(), 2);
        assert_eq!(asked[0].0, header::COOKIE);
        assert_eq!(asked[1].0, header::USER_AGENT);
        assert!(asked_headers(&HeaderMap::new()).is_empty());
    }
}
