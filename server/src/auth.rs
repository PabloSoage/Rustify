// server/src/auth.rs
//
// Nothing without a session (docs serie-4.x/28 §5). The access token is made on first start and
// kept in the data directory; entering it gives the browser a session cookie. Sessions live in
// memory, so a restart asks for the token again — and logging out ends one for real.

use std::collections::{HashMap, HashSet};
use std::path::Path;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use axum::extract::{Request, State};
use axum::http::{header, HeaderMap, StatusCode};
use axum::middleware::Next;
use axum::response::{Html, IntoResponse, Response};
use axum::Form;
use base64::Engine as _;

use crate::App;

pub const COOKIE: &str = "rustify_session";
const TOKEN_FILE: &str = "access-token";
const SESSION_DAYS: u64 = 30;

/// The token given (`RUSTIFY_TOKEN`), or the one kept in `data`, or a new one written there.
/// Answers whether it was just made, so the caller can say it once.
pub fn load_or_create_token(data: &Path, given: Option<String>) -> std::io::Result<(String, bool)> {
    if let Some(token) = given.map(|t| t.trim().to_string()).filter(|t| !t.is_empty()) {
        return Ok((token, false));
    }
    let path = data.join(TOKEN_FILE);
    if let Ok(kept) = std::fs::read_to_string(&path) {
        let kept = kept.trim().to_string();
        if !kept.is_empty() {
            return Ok((kept, false));
        }
    }
    let token = random_text()?;
    write_private(&path, &token)?;
    Ok((token, true))
}

/// 32 random bytes as base64url: unguessable, and safe in a cookie and a form field as it is.
fn random_text() -> std::io::Result<String> {
    let mut bytes = [0u8; 32];
    getrandom::getrandom(&mut bytes).map_err(|e| std::io::Error::other(e.to_string()))?;
    Ok(base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(bytes))
}

#[cfg(unix)]
fn write_private(path: &Path, text: &str) -> std::io::Result<()> {
    use std::io::Write as _;
    use std::os::unix::fs::OpenOptionsExt as _;
    let mut file = std::fs::OpenOptions::new().write(true).create(true).truncate(true).mode(0o600).open(path)?;
    file.write_all(text.as_bytes())
}

#[cfg(not(unix))]
fn write_private(path: &Path, text: &str) -> std::io::Result<()> {
    std::fs::write(path, text)
}

/// The sessions handed out since this process started.
#[derive(Clone, Default)]
pub struct Sessions(Arc<Mutex<HashSet<String>>>);

impl Sessions {
    fn open(&self) -> std::io::Result<String> {
        let id = random_text()?;
        if let Ok(mut set) = self.0.lock() {
            set.insert(id.clone());
        }
        Ok(id)
    }

    fn contains(&self, id: &str) -> bool {
        self.0.lock().map(|set| set.contains(id)).unwrap_or(false)
    }

    fn close(&self, id: &str) {
        if let Ok(mut set) = self.0.lock() {
            set.remove(id);
        }
    }
}

/// Equal without saying, through the time it took, how much of it was.
fn same(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    a.iter().zip(b).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

fn session_of(headers: &HeaderMap) -> Option<String> {
    headers
        .get_all(header::COOKIE)
        .iter()
        .filter_map(|v| v.to_str().ok())
        .flat_map(|v| v.split(';'))
        .filter_map(|pair| pair.trim().split_once('='))
        .find(|(name, _)| *name == COOKIE)
        .map(|(_, value)| value.to_string())
}

/// Lets through what needs no session (`/healthz`, `/login`) and what has one; the API answers 401
/// to anything else and a page is sent to the login.
pub async fn require(State(app): State<App>, request: Request, next: Next) -> Response {
    let path = request.uri().path().to_string();
    if path == "/healthz" || path == "/login" {
        return next.run(request).await;
    }
    if session_of(request.headers()).is_some_and(|id| app.sessions.contains(&id)) {
        return next.run(request).await;
    }
    if path.starts_with("/api/") {
        StatusCode::UNAUTHORIZED.into_response()
    } else {
        (StatusCode::SEE_OTHER, [(header::LOCATION, "/login")]).into_response()
    }
}

pub async fn login_page() -> Html<String> {
    Html(page(""))
}

pub async fn login(State(app): State<App>, Form(form): Form<HashMap<String, String>>) -> Response {
    let given = form.get("token").map(|t| t.trim()).unwrap_or("");
    if !same(given.as_bytes(), app.token.as_bytes()) {
        // Slows guessing down without a counter to keep.
        tokio::time::sleep(Duration::from_millis(500)).await;
        return (StatusCode::UNAUTHORIZED, Html(page("That is not the access token."))).into_response();
    }
    let Ok(id) = app.sessions.open() else {
        return StatusCode::INTERNAL_SERVER_ERROR.into_response();
    };
    let secure = if app.secure_cookies { "; Secure" } else { "" };
    let cookie = format!(
        "{COOKIE}={id}; Path=/; HttpOnly; SameSite=Strict; Max-Age={}{secure}",
        SESSION_DAYS * 24 * 3600
    );
    (StatusCode::SEE_OTHER, [(header::LOCATION, "/".to_string()), (header::SET_COOKIE, cookie)]).into_response()
}

pub async fn logout(State(app): State<App>, headers: HeaderMap) -> Response {
    if let Some(id) = session_of(&headers) {
        app.sessions.close(&id);
    }
    let cookie = format!("{COOKIE}=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
    (StatusCode::SEE_OTHER, [(header::LOCATION, "/login".to_string()), (header::SET_COOKIE, cookie)]).into_response()
}

fn page(error: &str) -> String {
    let error = if error.is_empty() { String::new() } else { format!("<p class=\"error\">{error}</p>") };
    format!(
        r#"<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Rustify</title>
<style>
:root {{ color-scheme: dark; }}
body {{ margin: 0; min-height: 100vh; display: grid; place-items: center; background: #121212; color: #f5f5f5; font: 16px system-ui, sans-serif; }}
form {{ width: min(360px, calc(100vw - 32px)); display: grid; gap: 12px; }}
input, button {{ font: inherit; padding: 12px 14px; border-radius: 10px; border: 1px solid #333; background: #1e1e1e; color: inherit; }}
button {{ background: #1db954; color: #000; border: 0; font-weight: 600; cursor: pointer; }}
.error {{ color: #ff6b6b; margin: 0; }}
small {{ color: #9a9a9a; }}
</style></head>
<body><form method="post" action="/login">
<h1>Rustify</h1>
{error}
<input name="token" type="password" autocomplete="current-password" placeholder="Access token" autofocus required>
<button type="submit">Enter</button>
<small>The token was written to the server's log the first time it started, and is kept in its data directory as <code>access-token</code>.</small>
</form></body></html>"#
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::http::HeaderValue;

    #[test]
    fn comparison_needs_every_byte() {
        assert!(same(b"abc", b"abc"));
        assert!(!same(b"abc", b"abd"));
        assert!(!same(b"abc", b"ab"));
    }

    #[test]
    fn the_session_cookie_is_found_among_others() {
        let mut headers = HeaderMap::new();
        headers.insert(header::COOKIE, HeaderValue::from_static("theme=dark; rustify_session=xyz; other=1"));
        assert_eq!(session_of(&headers).as_deref(), Some("xyz"));
        headers.insert(header::COOKIE, HeaderValue::from_static("rustify_sessionx=no"));
        assert_eq!(session_of(&headers), None);
    }

    #[test]
    fn a_closed_session_no_longer_opens() {
        let sessions = Sessions::default();
        let id = sessions.open().unwrap();
        assert!(sessions.contains(&id));
        sessions.close(&id);
        assert!(!sessions.contains(&id));
    }

    #[test]
    fn a_token_is_made_once_and_kept() {
        let dir = std::env::temp_dir().join(format!("rustify-server-test-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let _ = std::fs::remove_file(dir.join(TOKEN_FILE));
        let (first, made) = load_or_create_token(&dir, None).unwrap();
        assert!(made);
        let (second, made_again) = load_or_create_token(&dir, None).unwrap();
        assert!(!made_again);
        assert_eq!(first, second);
        let (given, _) = load_or_create_token(&dir, Some(" mine ".into())).unwrap();
        assert_eq!(given, "mine");
        let _ = std::fs::remove_dir_all(&dir);
    }
}
