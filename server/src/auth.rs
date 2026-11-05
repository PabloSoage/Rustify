// server/src/auth.rs
//
// Nothing without a session (docs serie-4.x/28 §5), and nothing to copy around to get one:
//
// - At this very machine a page is let in as it is (`opened_here`): whoever sits here can read the
//   server's data directory anyway.
// - Anywhere else it is an account, a name and a password, made on the first visit from this machine
//   or the home network (`may_make_account`). The browser's password manager remembers it, and a
//   session lasts a year and survives restarts (kept in `sessions`), so each device asks once.
// - `--token` / `RUSTIFY_TOKEN`, when given, is accepted too: for scripts and for a server whose
//   first visit can only come from outside.
//
// One account for now: the core behind this is one Spotify session. Several people each with their
// own library needs a core per person (docs serie-4.x/28 §5).

use std::collections::HashMap;
use std::net::{IpAddr, SocketAddr};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use argon2::password_hash::{PasswordHash, PasswordHasher, PasswordVerifier, SaltString};
use argon2::Argon2;
use axum::extract::{ConnectInfo, Request, State};
use axum::http::{header, HeaderMap, HeaderValue, StatusCode};
use axum::middleware::Next;
use axum::response::{Html, IntoResponse, Response};
use axum::Form;
use base64::Engine as _;

use crate::App;

pub const COOKIE: &str = "rustify_session";
const ACCOUNT_FILE: &str = "account.json";
const SESSIONS_FILE: &str = "sessions";
/// Chrome keeps no cookie longer than 400 days; a year is under that everywhere.
const SESSION_DAYS: u64 = 365;
const MIN_PASSWORD: usize = 6;

/// 32 random bytes as base64url: unguessable, and safe in a cookie as it is.
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

fn now_secs() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0)
}

/// The one account: its name and the Argon2id hash of its password, in `account.json`.
#[derive(Clone)]
pub struct Account {
    file: Option<PathBuf>,
    kept: Arc<Mutex<Option<(String, String)>>>,
}

impl Account {
    pub fn load(data: &Path) -> Self {
        let file = data.join(ACCOUNT_FILE);
        let kept = std::fs::read_to_string(&file)
            .ok()
            .and_then(|text| serde_json::from_str::<serde_json::Value>(&text).ok())
            .and_then(|v| Some((v.get("user")?.as_str()?.to_string(), v.get("hash")?.as_str()?.to_string())));
        Account { file: Some(file), kept: Arc::new(Mutex::new(kept)) }
    }

    #[cfg(test)]
    fn in_memory() -> Self {
        Account { file: None, kept: Arc::new(Mutex::new(None)) }
    }

    pub fn exists(&self) -> bool {
        self.kept.lock().map(|k| k.is_some()).unwrap_or(false)
    }

    /// Makes it, unless there is one already: the first to come may, the second may not.
    fn make(&self, user: &str, password: &str) -> std::io::Result<bool> {
        let mut salt = [0u8; 16];
        getrandom::getrandom(&mut salt).map_err(|e| std::io::Error::other(e.to_string()))?;
        let salt = SaltString::encode_b64(&salt).map_err(|e| std::io::Error::other(e.to_string()))?;
        let hash = Argon2::default()
            .hash_password(password.as_bytes(), &salt)
            .map_err(|e| std::io::Error::other(e.to_string()))?
            .to_string();
        let Ok(mut kept) = self.kept.lock() else { return Ok(false) };
        if kept.is_some() {
            return Ok(false);
        }
        if let Some(file) = &self.file {
            write_private(file, &serde_json::json!({ "user": user, "hash": hash }).to_string())?;
        }
        *kept = Some((user.to_string(), hash));
        Ok(true)
    }

    fn check(&self, user: &str, password: &str) -> bool {
        let Some((name, hash)) = self.kept.lock().ok().and_then(|k| k.clone()) else { return false };
        let Ok(parsed) = PasswordHash::new(&hash) else { return false };
        // The name is not a secret; the password is what Argon2 compares.
        name.eq_ignore_ascii_case(user.trim()) && Argon2::default().verify_password(password.as_bytes(), &parsed).is_ok()
    }
}

/// The sessions handed out, each with when it ends, kept in `sessions` so a restart keeps them.
#[derive(Clone, Default)]
pub struct Sessions {
    file: Option<PathBuf>,
    open: Arc<Mutex<HashMap<String, u64>>>,
}

impl Sessions {
    pub fn load(data: &Path) -> Self {
        let file = data.join(SESSIONS_FILE);
        let now = now_secs();
        let open = std::fs::read_to_string(&file)
            .unwrap_or_default()
            .lines()
            .filter_map(|line| line.split_once(' '))
            .filter_map(|(id, ends)| Some((id.to_string(), ends.parse::<u64>().ok()?)))
            .filter(|(_, ends)| *ends > now)
            .collect();
        Sessions { file: Some(file), open: Arc::new(Mutex::new(open)) }
    }

    fn save(&self, open: &HashMap<String, u64>) {
        if let Some(file) = &self.file {
            let text: String = open.iter().map(|(id, ends)| format!("{id} {ends}\n")).collect();
            if let Err(e) = write_private(file, &text) {
                tracing::warn!("could not keep the sessions in {}: {e}", file.display());
            }
        }
    }

    fn open(&self) -> std::io::Result<String> {
        let id = random_text()?;
        if let Ok(mut open) = self.open.lock() {
            let now = now_secs();
            open.retain(|_, ends| *ends > now);
            open.insert(id.clone(), now + SESSION_DAYS * 24 * 3600);
            self.save(&open);
        }
        Ok(id)
    }

    fn contains(&self, id: &str) -> bool {
        self.open.lock().map(|open| open.get(id).is_some_and(|ends| *ends > now_secs())).unwrap_or(false)
    }

    fn close(&self, id: &str) {
        if let Ok(mut open) = self.open.lock() {
            if open.remove(id).is_some() {
                self.save(&open);
            }
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

fn session_cookie(app: &App, id: &str) -> String {
    let secure = if app.secure_cookies { "; Secure" } else { "" };
    format!("{COOKIE}={id}; Path=/; HttpOnly; SameSite=Strict; Max-Age={}{secure}", SESSION_DAYS * 24 * 3600)
}

fn peer_of(request_extensions: &axum::http::Extensions) -> Option<IpAddr> {
    request_extensions.get::<ConnectInfo<SocketAddr>>().map(|ConnectInfo(peer)| peer.ip().to_canonical())
}

/// No reverse proxy in between: one on this machine makes everyone look local, and names whom it
/// forwards.
fn proxied(headers: &HeaderMap) -> bool {
    ["forwarded", "x-forwarded-for", "x-real-ip"].iter().any(|name| headers.contains_key(*name))
}

/// Whether a page is being opened by someone at this very machine, who can read the server's data
/// directory anyway: asking them for anything is all cost. All of it must hold —
///
/// - the connection comes from loopback, through no proxy;
/// - the browser asked for this machine by a loopback name (`Host`), which a DNS-rebinding page
///   cannot fake: its requests carry the attacker's name;
/// - it is a page typed or opened here, not a request another site's page made (`Sec-Fetch-Site`).
///
/// The API is never let in this way, only the page: the session it gets is a `SameSite=Strict`
/// cookie, which a request from another site does not carry, so no other site can use it.
fn opened_here(request: &Request) -> bool {
    let headers = request.headers();
    let host = headers.get(header::HOST).and_then(|v| v.to_str().ok()).unwrap_or("");
    let site = headers.get("sec-fetch-site").and_then(|v| v.to_str().ok());
    let from_here = matches!(site, None | Some("none") | Some("same-origin"));
    peer_of(request.extensions()).is_some_and(|ip| ip.is_loopback())
        && !proxied(headers)
        && loopback_host(host)
        && from_here
        && !request.uri().path().starts_with("/api/")
}

/// `127.0.0.1:8484`, `localhost`, `[::1]:8484`: a loopback name, with or without a port.
fn loopback_host(host: &str) -> bool {
    let name = if let Some(rest) = host.strip_prefix('[') {
        rest.split(']').next().unwrap_or("")
    } else {
        host.rsplit_once(':').map_or(host, |(name, _)| name)
    };
    name.eq_ignore_ascii_case("localhost") || name.parse::<IpAddr>().is_ok_and(|ip| ip.is_loopback())
}

/// The home network: who may make the account when there is none. Someone out on the internet
/// reaching a fresh server first would otherwise own it.
fn home_network(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => v4.is_loopback() || v4.is_private() || v4.is_link_local(),
        IpAddr::V6(v6) => {
            let first = v6.segments()[0];
            v6.is_loopback() || (first & 0xfe00) == 0xfc00 || (first & 0xffc0) == 0xfe80
        }
    }
}

fn may_make_account(extensions: &axum::http::Extensions, headers: &HeaderMap) -> bool {
    !proxied(headers) && peer_of(extensions).is_some_and(home_network)
}

/// Lets through what needs no session (`/healthz`, `/login`, `/drive-callback.html`) and what has one, and gives one to a
/// page opened at this machine (unless `--password-on-this-machine`); the API answers 401 to
/// anything else and a page is sent to the login.
pub async fn require(State(app): State<App>, request: Request, next: Next) -> Response {
    let path = request.uri().path().to_string();
    // drive-callback.html is a static page with nothing in it but a script that hands Google's answer
    // to the tab that asked; it arrives from the https redirect page, a navigation from another
    // site, which carries no SameSite=Strict cookie.
    if path == "/healthz" || path == "/login" || path == "/drive-callback.html" {
        return next.run(request).await;
    }
    if session_of(request.headers()).is_some_and(|id| app.sessions.contains(&id)) {
        return next.run(request).await;
    }
    if app.trust_this_machine && opened_here(&request) {
        if let Ok(id) = app.sessions.open() {
            let cookie = HeaderValue::from_str(&session_cookie(&app, &id));
            let mut response = next.run(request).await;
            if let Ok(cookie) = cookie {
                response.headers_mut().append(header::SET_COOKIE, cookie);
            }
            return response;
        }
    }
    if path.starts_with("/api/") {
        StatusCode::UNAUTHORIZED.into_response()
    } else {
        (StatusCode::SEE_OTHER, [(header::LOCATION, "/login")]).into_response()
    }
}

/// The page's language, from what the browser asks for: the interface's three.
fn language(headers: &HeaderMap) -> Lang {
    let wanted = headers.get(header::ACCEPT_LANGUAGE).and_then(|v| v.to_str().ok()).unwrap_or("");
    for tag in wanted.split(',') {
        match tag.trim().get(..2).map(|t| t.to_ascii_lowercase()).as_deref() {
            Some("es") => return Lang::Es,
            Some("ja") => return Lang::Ja,
            Some("en") => return Lang::En,
            _ => {}
        }
    }
    Lang::En
}

pub async fn login_page(State(app): State<App>, request: Request) -> Response {
    let lang = language(request.headers());
    let form = if app.account.exists() {
        Screen::SignIn
    } else if may_make_account(request.extensions(), request.headers()) {
        Screen::Make
    } else {
        Screen::OnlyAtHome
    };
    Html(page(lang, form, None)).into_response()
}

pub async fn login(
    State(app): State<App>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    Form(form): Form<HashMap<String, String>>,
) -> Response {
    let lang = language(&headers);
    let user = form.get("user").map(|u| u.trim()).unwrap_or("");
    let password = form.get("password").map(String::as_str).unwrap_or("");
    let token = form.get("token").map(|t| t.trim()).unwrap_or("");
    let by_token = app.token.as_deref().is_some_and(|t| !token.is_empty() && same(token.as_bytes(), t.as_bytes()));

    let admitted = if by_token {
        true
    } else if !app.account.exists() {
        // The first visit makes the account, from the home network only.
        let mut extensions = axum::http::Extensions::new();
        extensions.insert(ConnectInfo(peer));
        if !may_make_account(&extensions, &headers) {
            return (StatusCode::FORBIDDEN, Html(page(lang, Screen::OnlyAtHome, None))).into_response();
        }
        let repeated = form.get("repeat").map(String::as_str).unwrap_or("");
        if user.is_empty() || password.chars().count() < MIN_PASSWORD {
            return (StatusCode::BAD_REQUEST, Html(page(lang, Screen::Make, Some(Problem::TooShort)))).into_response();
        }
        if password != repeated {
            return (StatusCode::BAD_REQUEST, Html(page(lang, Screen::Make, Some(Problem::NotRepeated)))).into_response();
        }
        match app.account.make(user, password) {
            Ok(made) => made,
            Err(e) => {
                tracing::warn!("could not keep the account: {e}");
                return StatusCode::INTERNAL_SERVER_ERROR.into_response();
            }
        }
    } else {
        app.account.check(user, password)
    };
    if !admitted {
        // Slows guessing down without a counter to keep.
        tokio::time::sleep(Duration::from_millis(500)).await;
        return (StatusCode::UNAUTHORIZED, Html(page(lang, Screen::SignIn, Some(Problem::Wrong)))).into_response();
    }
    let Ok(id) = app.sessions.open() else {
        return StatusCode::INTERNAL_SERVER_ERROR.into_response();
    };
    let cookie = session_cookie(&app, &id);
    (StatusCode::SEE_OTHER, [(header::LOCATION, "/".to_string()), (header::SET_COOKIE, cookie)]).into_response()
}

pub async fn logout(State(app): State<App>, headers: HeaderMap) -> Response {
    if let Some(id) = session_of(&headers) {
        app.sessions.close(&id);
    }
    let cookie = format!("{COOKIE}=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
    (StatusCode::SEE_OTHER, [(header::LOCATION, "/login".to_string()), (header::SET_COOKIE, cookie)]).into_response()
}

#[derive(Clone, Copy)]
enum Lang {
    En,
    Es,
    Ja,
}

#[derive(Clone, Copy, PartialEq)]
enum Screen {
    /// There is an account: its name and password.
    SignIn,
    /// There is none yet: make it.
    Make,
    /// There is none, and this visit comes from outside the home network.
    OnlyAtHome,
}

#[derive(Clone, Copy)]
enum Problem {
    Wrong,
    TooShort,
    NotRepeated,
}

struct Words {
    html_lang: &'static str,
    user: &'static str,
    password: &'static str,
    repeat: &'static str,
    enter: &'static str,
    make: &'static str,
    make_intro: &'static str,
    only_at_home: &'static str,
    wrong: &'static str,
    too_short: &'static str,
    not_repeated: &'static str,
}

fn words(lang: Lang) -> Words {
    match lang {
        Lang::En => Words {
            html_lang: "en",
            user: "Name",
            password: "Password",
            repeat: "Password again",
            enter: "Sign in",
            make: "Create the account",
            make_intro: "First time here: choose a name and a password. You will be asked for them once on each other phone or computer; on this one, never.",
            only_at_home: "This server has no account yet. Create it from a device on the same network as the server.",
            wrong: "That name and password do not match.",
            too_short: "Choose a name, and a password of at least 6 characters.",
            not_repeated: "The two passwords are not the same.",
        },
        Lang::Es => Words {
            html_lang: "es",
            user: "Nombre",
            password: "Contraseña",
            repeat: "Repite la contraseña",
            enter: "Entrar",
            make: "Crear la cuenta",
            make_intro: "Primera vez: elige un nombre y una contraseña. Se pedirán una sola vez en cada otro móvil u ordenador; en este, nunca.",
            only_at_home: "Este servidor aún no tiene cuenta. Créala desde un dispositivo en la misma red que el servidor.",
            wrong: "Ese nombre y esa contraseña no coinciden.",
            too_short: "Elige un nombre y una contraseña de al menos 6 caracteres.",
            not_repeated: "Las dos contraseñas no son iguales.",
        },
        Lang::Ja => Words {
            html_lang: "ja",
            user: "名前",
            password: "パスワード",
            repeat: "パスワード（確認）",
            enter: "ログイン",
            make: "アカウントを作成",
            make_intro: "初めての方へ: 名前とパスワードを決めてください。ほかのスマホやパソコンでは一度だけ聞かれます。このパソコンでは聞かれません。",
            only_at_home: "このサーバーにはまだアカウントがありません。サーバーと同じネットワークの端末から作成してください。",
            wrong: "名前とパスワードが一致しません。",
            too_short: "名前と、6 文字以上のパスワードを決めてください。",
            not_repeated: "2 つのパスワードが一致しません。",
        },
    }
}

fn page(lang: Lang, form: Screen, problem: Option<Problem>) -> String {
    let w = words(lang);
    let error = match problem {
        None => String::new(),
        Some(Problem::Wrong) => format!("<p class=\"error\">{}</p>", w.wrong),
        Some(Problem::TooShort) => format!("<p class=\"error\">{}</p>", w.too_short),
        Some(Problem::NotRepeated) => format!("<p class=\"error\">{}</p>", w.not_repeated),
    };
    let body = match form {
        Screen::OnlyAtHome => format!("<p>{}</p>", w.only_at_home),
        Screen::SignIn => format!(
            r#"<input name="user" autocomplete="username" placeholder="{user}" autofocus required>
<input name="password" type="password" autocomplete="current-password" placeholder="{password}" required>
<button type="submit">{enter}</button>"#,
            user = w.user,
            password = w.password,
            enter = w.enter,
        ),
        Screen::Make => format!(
            r#"<small>{intro}</small>
<input name="user" autocomplete="username" placeholder="{user}" autofocus required>
<input name="password" type="password" autocomplete="new-password" placeholder="{password}" minlength="{min}" required>
<input name="repeat" type="password" autocomplete="new-password" placeholder="{repeat}" minlength="{min}" required>
<button type="submit">{make}</button>"#,
            intro = w.make_intro,
            user = w.user,
            password = w.password,
            repeat = w.repeat,
            make = w.make,
            min = MIN_PASSWORD,
        ),
    };
    format!(
        r#"<!doctype html>
<html lang="{lang}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Rustify</title>
<link rel="icon" href="/icon-192.png" type="image/png">
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
{body}
</form></body></html>"#,
        lang = w.html_lang,
    )
}

#[cfg(test)]
mod tests {
    use super::*;

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

    fn temp_dir(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("rustify-server-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn a_closed_session_no_longer_opens_and_an_open_one_survives_a_restart() {
        let dir = temp_dir("sessions");
        let sessions = Sessions::load(&dir);
        let kept = sessions.open().unwrap();
        let closed = sessions.open().unwrap();
        sessions.close(&closed);
        let restarted = Sessions::load(&dir);
        assert!(restarted.contains(&kept));
        assert!(!restarted.contains(&closed));
        assert!(!restarted.contains("made-up"));
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn the_account_is_made_once_and_its_password_is_checked() {
        let account = Account::in_memory();
        assert!(!account.exists());
        assert!(account.make("Pablo", "hells bells").unwrap());
        assert!(!account.make("Someone", "else's password").unwrap(), "a second account must not replace the first");
        assert!(account.check("pablo", "hells bells"));
        assert!(!account.check("pablo", "hells belle"));
        assert!(!account.check("someone", "hells bells"));
    }

    #[test]
    fn the_account_is_kept_hashed() {
        let dir = temp_dir("account");
        assert!(Account::load(&dir).make("pablo", "hells bells").unwrap());
        let text = std::fs::read_to_string(dir.join(ACCOUNT_FILE)).unwrap();
        assert!(!text.contains("hells bells"));
        assert!(Account::load(&dir).check("pablo", "hells bells"));
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn loopback_names_with_or_without_a_port() {
        for host in ["127.0.0.1:8484", "127.0.0.1", "localhost:8484", "LOCALHOST", "[::1]:8484", "[::1]"] {
            assert!(loopback_host(host), "{host}");
        }
        for host in ["", "192.168.1.10:8484", "evil.example:8484", "localhost.evil.example", "127.0.0.1.nip.io"] {
            assert!(!loopback_host(host), "{host}");
        }
    }

    fn page_request(peer: &str, host: &str, extra: &[(&str, &str)], path: &str) -> Request {
        let mut builder = axum::http::Request::builder().uri(path).header(header::HOST, host);
        for (name, value) in extra {
            builder = builder.header(*name, *value);
        }
        let mut request = builder.body(axum::body::Body::empty()).unwrap();
        request.extensions_mut().insert(ConnectInfo(peer.parse::<SocketAddr>().unwrap()));
        request
    }

    #[test]
    fn only_a_page_opened_at_this_machine_is_let_in_as_it_is() {
        let here = "127.0.0.1:50000";
        assert!(opened_here(&page_request(here, "127.0.0.1:8484", &[], "/")));
        assert!(opened_here(&page_request(here, "localhost:8484", &[("sec-fetch-site", "none")], "/")));
        // From the network, through a proxy, by another name, from another site's page, or the API.
        assert!(!opened_here(&page_request("192.168.1.20:50000", "127.0.0.1:8484", &[], "/")));
        assert!(!opened_here(&page_request(here, "127.0.0.1:8484", &[("x-forwarded-for", "192.168.1.20")], "/")));
        assert!(!opened_here(&page_request(here, "evil.example:8484", &[], "/")));
        assert!(!opened_here(&page_request(here, "127.0.0.1:8484", &[("sec-fetch-site", "cross-site")], "/")));
        assert!(!opened_here(&page_request(here, "127.0.0.1:8484", &[], "/api/call/getUserProfile")));
    }

    #[test]
    fn the_account_is_made_from_home_only() {
        for ip in ["127.0.0.1", "192.168.1.20", "10.0.0.5", "172.16.3.4", "169.254.1.1", "::1", "fd00::1", "fe80::1", "::ffff:192.168.1.20"] {
            assert!(home_network(ip.parse::<IpAddr>().unwrap().to_canonical()), "{ip}");
        }
        for ip in ["8.8.8.8", "2001:4860:4860::8888", "100.64.0.1"] {
            assert!(!home_network(ip.parse::<IpAddr>().unwrap().to_canonical()), "{ip}");
        }
    }

    #[test]
    fn the_page_speaks_the_browsers_language() {
        let mut headers = HeaderMap::new();
        headers.insert(header::ACCEPT_LANGUAGE, HeaderValue::from_static("es-ES,es;q=0.9,en;q=0.8"));
        assert!(page(language(&headers), Screen::SignIn, None).contains("Contraseña"));
        headers.insert(header::ACCEPT_LANGUAGE, HeaderValue::from_static("fr-FR,ja;q=0.8"));
        assert!(page(language(&headers), Screen::SignIn, None).contains("パスワード"));
        assert!(page(language(&HeaderMap::new()), Screen::SignIn, None).contains("Password"));
    }
}
