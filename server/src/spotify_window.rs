// server/src/spotify_window.rs
//
// Signing in to Spotify from the page with one click, the way the desktop does it. No page can read
// Spotify's cookie (another site's), nor frame its login (it refuses to be framed), so the server
// opens `rustify-login` — the desktop's sign-in window over the system's webview (desktopApp/login)
// — on its own screen, and hands the `sp_dc` it answers to the page that asked. That is the user's
// screen when the server runs on their computer, which is how the desktop and the Windows installer
// run it; a server with no screen says so, and the page offers the other ways in.
//
//   GET  /api/spotify-window   {"available":true|false}
//   POST /api/spotify-window?lang=es
//        200 {"sp_dc":"…"}   signed in
//        204                 the window was closed
//        409                 one is already open
//        503 {"error":"…"}   no helper, no screen, or it could not start
//
// Both behind the session (auth::require), like everything under /api/.

use std::collections::HashMap;
use std::path::PathBuf;
use std::process::Stdio;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

use axum::extract::Query;
use axum::http::{header, StatusCode};
use axum::response::{IntoResponse, Response};
use tokio::io::{AsyncBufReadExt, BufReader};

const HELPER: &str = if cfg!(windows) { "rustify-login.exe" } else { "rustify-login" };

/// Longer than anyone takes to sign in, two-factor step included; then the window is closed.
const PATIENCE: Duration = Duration::from_secs(15 * 60);

/// One window at a time: a second click while it is open would only stack windows.
static OPEN: AtomicBool = AtomicBool::new(false);

/// The helper, found the way the desktop app finds it (LoginHelper.locate): `RUSTIFY_LOGIN_HELPER`,
/// then next to this program (the installers put both in one folder), then the repository's build.
fn locate() -> Option<PathBuf> {
    let mut candidates = Vec::new();
    if let Some(given) = std::env::var_os("RUSTIFY_LOGIN_HELPER").filter(|v| !v.is_empty()) {
        candidates.push(PathBuf::from(given));
    }
    if let Some(dir) = std::env::current_exe().ok().and_then(|exe| exe.parent().map(PathBuf::from)) {
        candidates.push(dir.join(HELPER));
    }
    candidates.push(PathBuf::from("desktopApp/build/login-target/release").join(HELPER));
    candidates.push(PathBuf::from("../desktopApp/build/login-target/release").join(HELPER));
    candidates.into_iter().find(|path| path.is_file())
}

/// Whether a window can appear here at all: Windows and macOS always have a screen for a logged-in
/// user; Linux has one when a display is named (a NAS or a container has none).
fn has_screen() -> bool {
    if cfg!(target_os = "linux") {
        ["DISPLAY", "WAYLAND_DISPLAY"].iter().any(|name| std::env::var_os(name).is_some_and(|v| !v.is_empty()))
    } else {
        true
    }
}

fn json(status: StatusCode, body: String) -> Response {
    (status, [(header::CONTENT_TYPE, "application/json")], body).into_response()
}

fn error(why: &str) -> Response {
    json(StatusCode::SERVICE_UNAVAILABLE, serde_json::json!({ "error": why }).to_string())
}

pub async fn available() -> Response {
    let available = has_screen() && locate().is_some();
    json(StatusCode::OK, serde_json::json!({ "available": available }).to_string())
}

pub async fn open(Query(query): Query<HashMap<String, String>>) -> Response {
    if !has_screen() {
        return error("this server has no screen to open a window on");
    }
    let Some(helper) = locate() else {
        return error("rustify-login is not installed next to the server");
    };
    // The helper checks it too; nothing but a language tag reaches its command line.
    let lang = query
        .get("lang")
        .map(|tag| tag.split('-').next().unwrap_or(""))
        .filter(|tag| !tag.is_empty() && tag.len() <= 8 && tag.bytes().all(|b| b.is_ascii_alphabetic()))
        .unwrap_or("en")
        .to_string();
    if OPEN.swap(true, Ordering::SeqCst) {
        return StatusCode::CONFLICT.into_response();
    }
    struct Release;
    impl Drop for Release {
        fn drop(&mut self) {
            OPEN.store(false, Ordering::SeqCst);
        }
    }
    let _release = Release;

    // --watch-stdin and kill_on_drop: a page that goes away (its request dropped) closes the window.
    let child = tokio::process::Command::new(&helper)
        .args(["--lang", &lang, "--watch-stdin"])
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .kill_on_drop(true)
        .spawn();
    let mut child = match child {
        Ok(child) => child,
        Err(e) => {
            tracing::warn!("could not start {}: {e}", helper.display());
            return error("the sign-in window could not start");
        }
    };
    let Some(stdout) = child.stdout.take() else {
        return error("the sign-in window could not start");
    };
    let mut line = String::new();
    let read = tokio::time::timeout(PATIENCE, BufReader::new(stdout).read_line(&mut line)).await;
    if read.is_err() {
        tracing::info!("the Spotify window was open for {} minutes; closing it", PATIENCE.as_secs() / 60);
        return StatusCode::NO_CONTENT.into_response();
    }
    let answer: Option<serde_json::Value> = serde_json::from_str(line.trim()).ok();
    if let Some(sp_dc) = answer.as_ref().and_then(|a| a.get("sp_dc")).and_then(|v| v.as_str()).filter(|v| !v.is_empty()) {
        // The helper tidies up and exits on its own once it has answered; it is not waited for.
        let _ = child.stdin.take();
        tokio::spawn(async move {
            let _ = tokio::time::timeout(Duration::from_secs(10), child.wait()).await;
        });
        return json(StatusCode::OK, serde_json::json!({ "sp_dc": sp_dc }).to_string());
    }
    if let Some(why) = answer.as_ref().and_then(|a| a.get("error")).and_then(|v| v.as_str()) {
        tracing::warn!("the Spotify window failed: {why}");
        return error(why);
    }
    StatusCode::NO_CONTENT.into_response()
}
