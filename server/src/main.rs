// server/src/main.rs
//
// rustify-server: Rustify for a NAS or a headless server (docs serie-4.x/28). The core behind an
// HTTP API that answers exactly what the C ABI answers, the web build of the shared interface as
// static files, and a session in front of both.
//
//   rustify-server                          # 127.0.0.1:8484, data in ~/.local/share/rustify-server
//   rustify-server --bind 0.0.0.0:8484      # the LAN: put it behind TLS (docs 28 §5)
//   RUSTIFY_DATA_DIR=/data RUSTIFY_WEB_ROOT=/web rustify-server   # what the Docker image runs

mod auth;
mod core;
mod stream;

use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::Arc;

use axum::body::Bytes;
use axum::extract::{DefaultBodyLimit, Path, State};
use axum::http::{header, StatusCode};
use axum::response::{Html, IntoResponse, Response};
use axum::routing::{get, post};
use axum::Router;
use clap::Parser;
use tower_http::compression::{Compression, CompressionLayer};
use tower_http::services::{ServeDir, ServeFile};

#[derive(Parser, Debug)]
#[command(name = "rustify-server", version, about = "Rustify for a NAS or a headless server")]
struct Args {
    /// Where to listen. Anything but loopback opens a door to the network (docs 28 §5).
    #[arg(long, env = "RUSTIFY_BIND", default_value = "127.0.0.1:8484")]
    bind: SocketAddr,
    /// Session, history, settings and caches. Default: $XDG_DATA_HOME/rustify-server.
    #[arg(long, env = "RUSTIFY_DATA_DIR")]
    data_dir: Option<PathBuf>,
    /// The web interface (the wasm build of :sharedUi). Without it the API still answers.
    #[arg(long, env = "RUSTIFY_WEB_ROOT")]
    web_root: Option<PathBuf>,
    /// The access token. Default: the one kept in the data directory, made on first start.
    #[arg(long, env = "RUSTIFY_TOKEN", hide_env_values = true)]
    token: Option<String>,
    /// Mark the session cookie Secure: set it when the browser reaches this through HTTPS.
    #[arg(long, env = "RUSTIFY_SECURE_COOKIES")]
    secure_cookies: bool,
    /// The language the core asks Spotify and YouTube for (`setLanguage`).
    #[arg(long, env = "RUSTIFY_LANG", default_value = "en")]
    lang: String,
    /// How many core calls may run at once.
    #[arg(long, env = "RUSTIFY_PARALLEL_CALLS", default_value_t = 16)]
    parallel_calls: usize,
}

/// The largest arguments a call takes: a backup (`restoreData`) with years of history in it is well
/// past axum's default of 2 MiB. Only a session gets this far.
const MAX_CALL_BYTES: usize = 64 * 1024 * 1024;

/// What every handler shares. Cheap to clone: everything behind it is shared.
#[derive(Clone)]
pub struct App(Arc<Inner>);

pub struct Inner {
    pub core: core::Core,
    pub token: String,
    pub sessions: auth::Sessions,
    pub secure_cookies: bool,
    /// The port of the core's stream server on 127.0.0.1, when it started.
    pub stream_port: Option<u16>,
    /// The stream cache's folder, the only one its methods may be pointed at.
    pub stream_cache: String,
    pub http: reqwest::Client,
}

impl std::ops::Deref for App {
    type Target = Inner;
    fn deref(&self) -> &Inner {
        &self.0
    }
}

fn default_data_dir() -> PathBuf {
    if let Some(dir) = std::env::var_os("XDG_DATA_HOME") {
        return PathBuf::from(dir).join("rustify-server");
    }
    if let Some(home) = std::env::var_os("HOME") {
        return PathBuf::from(home).join(".local/share/rustify-server");
    }
    PathBuf::from("rustify-server-data")
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into()),
        )
        .init();
    let args = Args::parse();

    let data = args.data_dir.clone().unwrap_or_else(default_data_dir);
    let core_dir = data.join("core");
    let cache_dir = data.join("cache");
    let stream_cache = data.join("streams");
    for dir in [&core_dir, &cache_dir, &stream_cache] {
        std::fs::create_dir_all(dir)?;
    }

    let (token, made) = auth::load_or_create_token(&data, args.token.clone())?;
    if made {
        // Said once, on the start that made it; afterwards it is in the data directory.
        tracing::warn!("access token (also in {}): {token}", data.join("access-token").display());
    }

    let core = core::Core::new(args.parallel_calls);
    let dir_args = |dir: &std::path::Path| serde_json::json!({ "dir": dir.to_string_lossy() }).to_string();
    for (method, call_args) in [
        ("initCacheDir", dir_args(&core_dir)),
        ("initSpotifyCacheDir", dir_args(&cache_dir)),
        ("setLanguage", serde_json::json!({ "lang": args.lang }).to_string()),
    ] {
        let answer = core.call(method, call_args).await;
        if !core::succeeded(&answer) {
            return Err(format!("{method}: {answer}").into());
        }
    }
    let local = core.call("startLocalServer", "{}".to_string()).await;
    let stream_port = serde_json::from_str::<serde_json::Value>(&local)
        .ok()
        .and_then(|v| v.get("port")?.as_u64())
        .and_then(|p| u16::try_from(p).ok());
    if stream_port.is_none() {
        tracing::warn!("the core's stream server did not start ({local}); /api/stream is off");
    }

    let app = App(Arc::new(Inner {
        core,
        token,
        sessions: auth::Sessions::default(),
        secure_cookies: args.secure_cookies,
        stream_port,
        stream_cache: stream_cache.to_string_lossy().into_owned(),
        http: reqwest::Client::builder()
            .no_proxy()
            .redirect(reqwest::redirect::Policy::none())
            .build()?,
    }));

    let router = Router::new()
        .route("/healthz", get(|| async { "ok" }))
        .route("/login", get(auth::login_page).post(auth::login))
        .route("/logout", post(auth::logout))
        .route(
            "/api/call/{method}",
            post(call).layer(CompressionLayer::new()).layer(DefaultBodyLimit::max(MAX_CALL_BYTES)),
        )
        .route("/api/stream/{handle}", get(stream::forward));
    let router = match args.web_root.as_ref().filter(|root| root.join("index.html").is_file()) {
        Some(root) => {
            let files = ServeDir::new(root).fallback(ServeFile::new(root.join("index.html")));
            router.fallback_service(Compression::new(files))
        }
        None => {
            tracing::warn!("no web interface (--web-root); serving the API only");
            router.fallback(no_interface)
        }
    };
    let router = router
        .layer(axum::middleware::from_fn_with_state(app.clone(), auth::require))
        .with_state(app);

    if !args.bind.ip().is_loopback() {
        tracing::warn!("listening on {} — beyond this machine; serve it through HTTPS", args.bind);
    }
    let listener = tokio::net::TcpListener::bind(args.bind).await?;
    tracing::info!("rustify-server {} on http://{}", env!("CARGO_PKG_VERSION"), args.bind);
    axum::serve(listener, router).with_graceful_shutdown(shutdown()).await?;
    Ok(())
}

/// `POST /api/call/{method}`: the body is the arguments object, the answer is the core's.
async fn call(State(app): State<App>, Path(method): Path<String>, body: Bytes) -> Response {
    let args = if body.is_empty() {
        "{}".to_string()
    } else {
        match String::from_utf8(body.to_vec()) {
            Ok(args) => args,
            Err(_) => return (StatusCode::BAD_REQUEST, "the body is not UTF-8").into_response(),
        }
    };
    let args = match core::vet(&method, args, &app.stream_cache) {
        Ok(args) => args,
        Err(why) => return (StatusCode::FORBIDDEN, why).into_response(),
    };
    let answer = app.core.call(&method, args).await;
    ([(header::CONTENT_TYPE, "application/json")], answer).into_response()
}

async fn no_interface() -> Html<&'static str> {
    Html(
        "<!doctype html><meta charset=utf-8><meta name=viewport content=\"width=device-width\">\
         <title>Rustify</title><body style=\"font:16px system-ui;background:#121212;color:#eee;padding:24px\">\
         <h1>Rustify</h1><p>This server has no web interface installed (<code>--web-root</code>). \
         The API answers at <code>/api/call/{method}</code>.</p>",
    )
}

async fn shutdown() {
    let ctrl_c = async {
        let _ = tokio::signal::ctrl_c().await;
    };
    #[cfg(unix)]
    let terminate = async {
        if let Ok(mut signal) = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate()) {
            signal.recv().await;
        }
    };
    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();
    tokio::select! {
        _ = ctrl_c => {},
        _ = terminate => {},
    }
}
