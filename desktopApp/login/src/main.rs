// desktopApp/login/src/main.rs
//
// rustify-login: the desktop's Spotify sign-in, done the way the phone does it. The login page opens
// in the system's own webview (WebView2, WKWebView, WebKitGTK) and the `sp_dc` cookie it leaves is
// the whole answer — the same cookie `SpotifyLoginWebView` reads on Android.
//
// A process of its own, not a library inside the JVM: a webview needs the main thread's event loop,
// and in the app that thread belongs to AWT and Compose. Here nothing else wants it.
//
//   rustify-login [--lang en] [--title "Rustify — Spotify"] [--watch-stdin]
//
// What it answers, on standard output, is exactly one line or nothing:
//
//   {"sp_dc":"…"}    exit 0   signed in
//   (nothing)        exit 1   the window was closed (or, with --watch-stdin, stdin reached its end)
//   {"error":"…"}    exit 2   it could not start: bad arguments, no webview, no display
//
// --watch-stdin is how the app cancels it, and how it does not outlive the app: the app keeps the
// pipe open and closes it to say "stop"; an app that dies closes it too.

#![cfg_attr(windows, windows_subsystem = "windows")]

use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::OnceLock;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use tao::dpi::LogicalSize;
use tao::event::{Event, StartCause, WindowEvent};
use tao::event_loop::{ControlFlow, EventLoopBuilder};
use tao::window::WindowBuilder;
use wry::{NewWindowResponse, PageLoadEvent, WebContext, WebView, WebViewBuilder};

/// The phone's user agent (`SpotifyLoginWebView`): a desktop Chrome, so the login page is the one
/// Spotify serves a browser rather than its "this browser is not supported" for an embedded one.
const USER_AGENT: &str = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

/// How often the cookies are read besides each finished page load: the last step of the login is a
/// script on open.spotify.com, which sets `sp_dc` without a page load anyone is told about.
const POLL: Duration = Duration::from_secs(1);

/// The per-run data folders, under the system's temporary folder.
const DATA_PREFIX: &str = "rustify-login-";

/// A folder older than this belongs to a run that was killed before it could clean up.
const STALE_AFTER: Duration = Duration::from_secs(60 * 60);

static ANSWERED: AtomicBool = AtomicBool::new(false);
static SIGNED_IN: AtomicBool = AtomicBool::new(false);
static DATA_DIR: OnceLock<PathBuf> = OnceLock::new();

#[derive(Debug)]
enum UserEvent {
    /// A page finished loading: worth a look at the cookies now rather than at the next poll.
    Check,
    /// Standard input ended (--watch-stdin): the app cancelled, or is gone.
    Cancel,
}

struct Args {
    lang: String,
    title: String,
    watch_stdin: bool,
}

fn main() {
    install_panic_hook();

    let args = parse_args().unwrap_or_else(|e| fail(&e));
    let data_dir = make_data_dir()
        .unwrap_or_else(|e| fail(&format!("cannot create the browser's data folder: {e}")));
    let _ = DATA_DIR.set(data_dir.clone());

    let event_loop = EventLoopBuilder::<UserEvent>::with_user_event().build();
    let proxy = event_loop.create_proxy();

    let window = WindowBuilder::new()
        .with_title(args.title.clone())
        .with_inner_size(LogicalSize::new(480.0, 760.0))
        .with_min_inner_size(LogicalSize::new(360.0, 480.0))
        .build(&event_loop)
        .unwrap_or_else(|e| fail(&format!("cannot open the window: {e}")));

    // A clean cookie jar every time, so a session left over from an earlier sign-in cannot log in on
    // its own (on the phone that is what `onLoginFailed` clears). Incognito is what makes it clean on
    // macOS and Linux: WKWebView ignores a data folder and keeps one store per app, and WebKitGTK's
    // ephemeral context writes nothing. The folder is for WebView2, which otherwise writes beside the
    // executable — read-only under Program Files — and it is deleted on the way out.
    let mut context = WebContext::new(Some(data_dir.clone()));
    let on_load = proxy.clone();
    let builder = WebViewBuilder::new_with_web_context(&mut context)
        .with_url(login_url(&args.lang))
        .with_user_agent(USER_AGENT)
        .with_incognito(true)
        // A password manager's paste.
        .with_clipboard(true)
        // The challenge step and the "continue with" providers can open a second window; refused,
        // that tap does nothing and the page reads as frozen (the phone allows them too).
        .with_new_window_req_handler(|_url, _features| NewWindowResponse::Allow)
        .with_on_page_load_handler(move |event, _url| {
            if let PageLoadEvent::Finished = event {
                let _ = on_load.send_event(UserEvent::Check);
            }
        });

    #[cfg(any(target_os = "windows", target_os = "macos", target_os = "ios", target_os = "android"))]
    let built = builder.build(&window);
    #[cfg(not(any(target_os = "windows", target_os = "macos", target_os = "ios", target_os = "android")))]
    let built = {
        use tao::platform::unix::WindowExtUnix;
        use wry::WebViewBuilderExtUnix;
        match window.default_vbox() {
            Some(vbox) => builder.build_gtk(vbox),
            None => fail("the window has no GTK container for the webview"),
        }
    };
    let webview = built.unwrap_or_else(|e| fail(&format!("cannot start the system's webview: {e}")));

    // Launched from the app, the window may otherwise open behind it.
    window.set_focus();

    if args.watch_stdin {
        let cancel = proxy.clone();
        std::thread::spawn(move || {
            let _ = std::io::copy(&mut std::io::stdin().lock(), &mut std::io::sink());
            let _ = cancel.send_event(UserEvent::Cancel);
        });
    }

    let mut webview: Option<WebView> = Some(webview);
    let mut window = Some(window);
    let mut next_check = Instant::now() + POLL;

    event_loop.run(move |event, _target, control_flow| {
        // Kept for as long as the webview: dropping it early loses what it holds on some systems.
        let _ = &context;

        let check = match event {
            Event::NewEvents(StartCause::ResumeTimeReached { .. }) => true,
            Event::UserEvent(UserEvent::Check) => true,
            Event::UserEvent(UserEvent::Cancel)
            | Event::WindowEvent { event: WindowEvent::CloseRequested, .. } => {
                *control_flow = ControlFlow::ExitWithCode(1);
                false
            }
            Event::LoopDestroyed => {
                // The webview first: WebView2's processes hold the data folder until it is gone.
                drop(webview.take());
                drop(window.take());
                if let Some(dir) = DATA_DIR.get() {
                    remove_data_dir(dir);
                }
                return;
            }
            _ => false,
        };

        // Not once an exit was asked for: the code is sticky, and a closed window must not answer.
        let exiting = matches!(*control_flow, ControlFlow::ExitWithCode(_));
        if check && !exiting && !SIGNED_IN.load(Ordering::SeqCst) {
            if let Some(sp_dc) = webview.as_ref().and_then(find_sp_dc) {
                SIGNED_IN.store(true, Ordering::SeqCst);
                answer(&format!("{{\"sp_dc\":{}}}", json_string(&sp_dc)));
                if let Some(w) = window.as_ref() {
                    w.set_visible(false);
                }
                *control_flow = ControlFlow::ExitWithCode(0);
                return;
            }
            next_check = Instant::now() + POLL;
        }

        // Otherwise sleep until the next look at the cookies.
        if !exiting {
            *control_flow = ControlFlow::WaitUntil(next_check);
        }
    });
}

fn parse_args() -> Result<Args, String> {
    let mut args = Args {
        lang: "en".to_string(),
        title: "Rustify — Spotify".to_string(),
        watch_stdin: false,
    };
    let mut it = std::env::args().skip(1);
    while let Some(arg) = it.next() {
        match arg.as_str() {
            "--lang" => args.lang = it.next().ok_or("--lang needs a language code")?,
            "--title" => args.title = it.next().ok_or("--title needs a title")?,
            "--watch-stdin" => args.watch_stdin = true,
            other => return Err(format!("unknown argument: {other}")),
        }
    }
    // It goes into the URL's path, so only what a language tag is made of.
    let tag_ok = !args.lang.is_empty()
        && args.lang.len() <= 12
        && args.lang.chars().all(|c| c.is_ascii_alphanumeric() || c == '-');
    if !tag_ok {
        return Err(format!("not a language code: {}", args.lang));
    }
    Ok(args)
}

/// The phone's login URL (`SPOTIFY_LOGIN_URL`), in [lang]'s language.
fn login_url(lang: &str) -> String {
    format!("https://accounts.spotify.com/{lang}/login?continue=https%3A%2F%2Fopen.spotify.com%2F")
}

/// `sp_dc`, if the login has set it. All the cookies and a filter of our own rather than
/// `cookies_for_url`: on macOS that one keeps only cookies whose domain is the URL's host exactly,
/// and `sp_dc` is set for the parent domain, `.spotify.com`.
fn find_sp_dc(webview: &WebView) -> Option<String> {
    let cookies = webview.cookies().ok()?;
    cookies
        .iter()
        .find(|c| {
            c.name() == "sp_dc"
                && !c.value().is_empty()
                && c.domain().is_none_or(|d| {
                    let d = d.trim_start_matches('.');
                    d == "spotify.com" || d.ends_with(".spotify.com")
                })
        })
        .map(|c| c.value().to_string())
}

/// The one line on standard output; a second call (a panic on the way out) writes nothing.
fn answer(line: &str) {
    if ANSWERED.swap(true, Ordering::SeqCst) {
        return;
    }
    let mut out = std::io::stdout().lock();
    let _ = writeln!(out, "{line}");
    let _ = out.flush();
}

/// A failure to start: `{"error":…}` and exit 2.
fn fail(message: &str) -> ! {
    answer(&format!("{{\"error\":{}}}", json_string(message)));
    if let Some(dir) = DATA_DIR.get() {
        remove_data_dir(dir);
    }
    std::process::exit(2)
}

/// GTK that cannot find a display, a webview that cannot start, anything else that panics: the app
/// gets `{"error":…}` instead of a line it cannot read.
fn install_panic_hook() {
    std::panic::set_hook(Box::new(|info| {
        let payload = info.payload();
        let message = payload
            .downcast_ref::<&str>()
            .map(|s| s.to_string())
            .or_else(|| payload.downcast_ref::<String>().cloned())
            .unwrap_or_else(|| "the sign-in window failed".to_string());
        answer(&format!("{{\"error\":{}}}", json_string(&message)));
        if let Some(dir) = DATA_DIR.get() {
            remove_data_dir(dir);
        }
        // Signed in and then failed on the way out: the answer already given stands.
        std::process::exit(if SIGNED_IN.load(Ordering::SeqCst) { 0 } else { 2 })
    }));
}

fn json_string(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 2);
    out.push('"');
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out.push('"');
    out
}

fn make_data_dir() -> std::io::Result<PathBuf> {
    let tmp = std::env::temp_dir();
    sweep_stale(&tmp);
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or(0);
    let dir = tmp.join(format!("{DATA_PREFIX}{}-{nanos}", std::process::id()));
    std::fs::create_dir_all(&dir)?;
    Ok(dir)
}

/// The folders of runs that were killed (the app's Cancel ends with a kill if asking is not enough).
fn sweep_stale(tmp: &Path) {
    let Ok(entries) = std::fs::read_dir(tmp) else { return };
    for entry in entries.flatten() {
        if !entry.file_name().to_string_lossy().starts_with(DATA_PREFIX) {
            continue;
        }
        let stale = entry
            .metadata()
            .and_then(|m| m.modified())
            .ok()
            .and_then(|t| t.elapsed().ok())
            .is_some_and(|age| age > STALE_AFTER);
        if stale {
            let _ = std::fs::remove_dir_all(entry.path());
        }
    }
}

/// WebView2's browser processes let go of the folder a moment after the webview is dropped, so a
/// few tries; elsewhere the first one works.
fn remove_data_dir(dir: &Path) {
    for _ in 0..20 {
        match std::fs::remove_dir_all(dir) {
            Ok(()) => return,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => return,
            Err(_) => std::thread::sleep(Duration::from_millis(100)),
        }
    }
}
