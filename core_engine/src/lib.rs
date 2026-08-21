// core_engine/src/lib.rs
//
// JNI bridge layer: exposes Rust engine functions to the Android JVM via C ABI.
// Each function follows the pattern:
//   1. Extract args from JNI
//   2. Execute async Rust operation on the Tokio runtime
//   3. Serialize result to JSON string
//   4. Return as Java String

pub mod adblock_engine;
pub mod addon;
pub mod audio;
pub mod calendar;
pub mod env;
pub mod export;
pub mod links;
pub mod listened;
pub mod matcher;
pub mod player;
pub mod search;
pub mod server;
pub mod spotify;
pub mod types;
pub mod youtube;

use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::{EnvUnowned, Outcome};
use std::sync::OnceLock;
use tokio::runtime::Runtime;

// Native Rust 1.70+ thread-safe lazy initialization for the asynchronous runtime.
static RUNTIME: OnceLock<Runtime> = OnceLock::new();

/// Helper function to initialize and retrieve the Tokio Runtime securely.
///
/// `pub(crate)` since 3.0: `env::android::AndroidEnv::exec_concurrent` needs *this* runtime. A bare
/// `tokio::spawn` panics when no runtime is in scope, and JNI calls arrive on JVM threads.
pub(crate) fn get_runtime() -> &'static Runtime {
    RUNTIME.get_or_init(|| Runtime::new().expect("Failed to initialize Tokio Runtime"))
}

/// Generic helper to serialize results to JSON and handle errors.
/// Uses serde_json for proper escaping of error messages (which may contain
/// quotes, newlines, and nested JSON from API error responses).
fn serialize_result<T: serde::Serialize, E: std::fmt::Display>(result: Result<T, E>) -> String {
    match result {
        Ok(data) => serde_json::to_string(&data).unwrap_or_else(|_| "[]".to_string()),
        Err(e) => {
            eprintln!("Rust Engine Error: {}", e);
            let error_response = spotify::models::OperationResult::err(e.to_string());
            serde_json::to_string(&error_response)
                .unwrap_or_else(|_| r#"{"success":false,"error":"Unknown error"}"#.to_string())
        }
    }
}

/// Helper macro to reduce boilerplate in JNI bridge functions.
/// Handles the EnvUnowned → Env upgrade, string conversion, and error handling.
macro_rules! jni_bridge {
    // Variant for functions that return a jstring
    ($env_unowned:ident, |$env:ident| $body:expr) => {{
        let env_outcome = $env_unowned.with_env(|$env| -> jni::errors::Result<jstring> {
            let response_str: String = $body;
            let output = $env.new_string(response_str)?;
            Ok(output.into_raw())
        });
        match env_outcome.into_outcome() {
            Outcome::Ok(j_str) => j_str,
            _ => std::ptr::null_mut(),
        }
    }};
}



// =============================================================================
// CONTINUE LISTENING (point G)
// =============================================================================

/// JNI Bridge: record where playback is in a context, so Home can offer it back.
///
/// The whole [`player::session::Session`] as JSON. Anything not worth resuming — barely started, or
/// finished — **removes** the entry rather than being ignored, so an album you completed stops being
/// offered instead of freezing at its last twenty seconds.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_recordListeningNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    session_json: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let raw = session_json.mutf8_chars(env)?.to_string();
        match serde_json::from_str::<player::session::Session>(&raw) {
            Err(e) => serialize_result::<(), _>(Err(e)),
            Ok(session) => {
                let outcome = get_runtime()
                    .block_on(player::session::record::<env::android::AndroidEnv>(session));
                serialize_result(outcome.map(|_| spotify::models::OperationResult {
                    success: true,
                    error: None,
                }))
            }
        }
    })
}

/// JNI Bridge: the contexts to offer, newest first, as a JSON array.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_listContinueListeningNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |_env| {
        let sessions =
            get_runtime().block_on(player::session::list::<env::android::AndroidEnv>());
        serde_json::to_string(&sessions).unwrap_or_else(|_| "[]".to_string())
    })
}

/// JNI Bridge: drop one context, or every one when `id` is empty.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_forgetListeningNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    id: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let id_str = id.mutf8_chars(env)?.to_string();
        let outcome = get_runtime().block_on(async {
            if id_str.is_empty() {
                player::session::clear::<env::android::AndroidEnv>().await
            } else {
                player::session::forget::<env::android::AndroidEnv>(&id_str).await
            }
        });
        serialize_result(outcome.map(|_| spotify::models::OperationResult {
            success: true,
            error: None,
        }))
    })
}

// =============================================================================
// LINKS (point F)
// =============================================================================

/// JNI Bridge: read any link Rustify understands out of arbitrary text.
///
/// Pure and in-memory — no `block_on`, no network — so it is safe from any thread, which matters
/// because the deep-link handler runs on the main one.
///
/// @return `{"kind":"track","id":"…","token":"track:…","url":"…","scheme":"rustify://…"}`, or `{}`
///   when the text is not one of our links. `{}` means "not ours", never "unsafe".
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_parseLinkNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    text: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let raw = text.mutf8_chars(env)?.to_string();
        match links::parse(&raw) {
            None => "{}".to_string(),
            Some(target) => {
                let answer = serde_json::json!({
                    "kind": target.kind(),
                    "id": target.id(),
                    "token": target.deep_link_token(),
                    "url": target.canonical_url(),
                    "scheme": target.rustify_scheme(),
                });
                answer.to_string()
            }
        }
    })
}

/// JNI Bridge: wrap a link so that tapping it opens Rustify.
///
/// An empty `host` means "no verified host configured", which falls back to the `rustify://` scheme.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_wrapLinkNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    url: JString<'local>,
    host: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let url_str = url.mutf8_chars(env)?.to_string();
        let host_str = host.mutf8_chars(env)?.to_string();
        links::wrap(
            &url_str,
            if host_str.trim().is_empty() {
                None
            } else {
                Some(host_str.as_str())
            },
        )
    })
}

/// JNI Bridge: recover the link inside a wrapper URL, or `""` if it is not one of ours.
///
/// `knownHostsJson` is a JSON array of the hosts the manifest actually verifies. Checked rather than
/// trusted: an arbitrary site must not be able to hand the app a link and have it treated as if it
/// came from the user's own domain.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_unwrapLinkNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    url: JString<'local>,
    known_hosts_json: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let url_str = url.mutf8_chars(env)?.to_string();
        let hosts_raw = known_hosts_json.mutf8_chars(env)?.to_string();
        let hosts: Vec<String> = serde_json::from_str(&hosts_raw).unwrap_or_default();
        let refs: Vec<&str> = hosts.iter().map(String::as_str).collect();
        links::unwrap_wrapper(&url_str, &refs).unwrap_or_default()
    })
}

// =============================================================================
// YOUTUBE ENGINE
// =============================================================================

/// JNI Bridge: Search YouTube Music
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_searchYouTubeNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    query: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let mutf8 = query.mutf8_chars(env)?;
        let rust_query = mutf8.to_string();
        let async_result = get_runtime().block_on(async {
            let scraper = youtube::scraper::YouTubeScraper::new();
            scraper.search(&rust_query).await
        });
        serialize_result(async_result)
    })
}

/// JNI Bridge: Initialize the YouTube resolver cache directory + load persisted mappings.
/// Replaces the former `startAudioServerNative` (the loopback HTTP server was dead code, E11).
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_initCacheDirNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    cache_dir: JString<'local>,
) {
    let _ = env_unowned.with_env(|env| -> jni::errors::Result<()> {
        let mutf8 = cache_dir.mutf8_chars(env)?;
        let cache_dir_str = mutf8.to_string();
        youtube::server::init_cache_dir(&cache_dir_str);
        // Same directory: `Env` keys live under `store/` inside it.
        env::android::init_storage_dir(&cache_dir_str);
        // Awaited, not spawned: a resolve that ran before the mappings landed would ignore an
        // override the user set by hand. This only touches local files, never the network.
        get_runtime().block_on(youtube::server::init_mappings::<env::android::AndroidEnv>(
            &cache_dir_str,
        ));
        Ok(())
    });
}

/// JNI Bridge: Register track metadata in Rust memory
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_registerTrackMetadataNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    id: JString<'local>,
    name: JString<'local>,
    artists_json: JString<'local>,
    duration_ms: jint,
    isrc: JString<'local>,
) {
    let _ = env_unowned.with_env(|env| -> jni::errors::Result<()> {
        let id_str = id.mutf8_chars(env)?.to_string();
        let name_str = name.mutf8_chars(env)?.to_string();
        let artists_json_str = artists_json.mutf8_chars(env)?.to_string();
        let isrc_str = isrc.mutf8_chars(env)?.to_string();
        
        let artists: Vec<String> = serde_json::from_str(&artists_json_str).unwrap_or_default();
        
        youtube::server::register_track_meta(id_str, name_str, artists, duration_ms as u32, isrc_str);
        Ok(())
    });
}

/// JNI Bridge: Set manual alternative YouTube Video ID override
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_setAlternativeTrackNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    spotify_id: JString<'local>,
    youtube_id: JString<'local>,
) {
    let _ = env_unowned.with_env(|env| -> jni::errors::Result<()> {
        let s_id = spotify_id.mutf8_chars(env)?.to_string();
        let y_id = youtube_id.mutf8_chars(env)?.to_string();
        youtube::server::set_alternative_track::<env::android::AndroidEnv>(s_id, y_id);
        Ok(())
    });
}

// =============================================================================
// ADDONS (installable audio backends)
// =============================================================================

/// JNI Bridge: Install an addon by URL. Returns the addon as JSON, or an error object.
///
/// Fetches and validates the manifest first, so an addon that cannot be reached, is served over
/// plain http, points at a private address, or does not describe itself properly never reaches the
/// installed list. See `addon::security` for what is refused and why.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_installAddonNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    url: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let url_str = url.mutf8_chars(env)?.to_string();
        let result = get_runtime()
            .block_on(addon::registry::install::<env::android::AndroidEnv>(&url_str));
        serialize_result(result)
    })
}

/// JNI Bridge: The installed addons, in the order they are tried. JSON array.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_listAddonsNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |_env| {
        let addons = get_runtime().block_on(addon::registry::list::<env::android::AndroidEnv>());
        serde_json::to_string(&addons).unwrap_or_else(|_| "[]".to_string())
    })
}

/// JNI Bridge: Remove an addon.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_uninstallAddonNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    id: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let id_str = id.mutf8_chars(env)?.to_string();
        let result =
            get_runtime().block_on(addon::registry::uninstall::<env::android::AndroidEnv>(&id_str));
        serialize_result(result.map(|_| spotify::models::OperationResult::ok()))
    })
}

/// JNI Bridge: Turn an addon off without uninstalling it.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_setAddonEnabledNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    id: JString<'local>,
    enabled: jboolean,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let id_str = id.mutf8_chars(env)?.to_string();
        let result = get_runtime().block_on(addon::registry::set_enabled::<
            env::android::AndroidEnv,
        >(&id_str, enabled == JNI_TRUE));
        serialize_result(result.map(|_| spotify::models::OperationResult::ok()))
    })
}

/// JNI Bridge: Reorder the fallback chain. `idsJson` is a JSON array of addon ids.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_reorderAddonsNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    ids_json: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let json = ids_json.mutf8_chars(env)?.to_string();
        let ids: Vec<String> = serde_json::from_str(&json).unwrap_or_default();
        let result =
            get_runtime().block_on(addon::registry::reorder::<env::android::AndroidEnv>(&ids));
        serialize_result(result.map(|_| spotify::models::OperationResult::ok()))
    })
}

/// JNI Bridge: Ask one addon to resolve a track.
///
/// Returns the answer as JSON, `{}` when the addon does not have the track, or an error object.
/// `{}` is a normal outcome and means "move on to the next provider".
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_resolveViaAddonNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    addon_id: JString<'local>,
    query_json: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let id_str = addon_id.mutf8_chars(env)?.to_string();
        let query_str = query_json.mutf8_chars(env)?.to_string();

        match serde_json::from_str::<addon::TrackQuery>(&query_str) {
            Err(e) => serialize_result::<(), _>(Err(e)),
            Ok(query) => get_runtime().block_on(async {
                let installed = addon::registry::list::<env::android::AndroidEnv>().await;
                match installed.iter().find(|a| a.manifest.id == id_str) {
                    None => serialize_result::<(), _>(Err(addon::AddonError::Refused(
                        "no such addon".into(),
                    ))),
                    Some(found) => {
                        match addon::transport::resolve_stream::<env::android::AndroidEnv>(
                            found, &query,
                        )
                        .await
                        {
                            // A normal outcome, not a failure: move on to the next provider.
                            Ok(None) => "{}".to_string(),
                            Ok(Some(answer)) => {
                                serde_json::to_string(&answer).unwrap_or_else(|_| "{}".to_string())
                            }
                            Err(e) => serialize_result::<(), _>(Err(e)),
                        }
                    }
                }
            }),
        }
    })
}

// =============================================================================
// LOCAL STREAMING SERVER
// =============================================================================

/// JNI Bridge: Start the loopback streaming server (idempotent).
///
/// Returns `{"port":N,"token":"..."}` on success, or `{"success":false,"error":"..."}`.
/// The token is what makes the server usable only by this app: on Android any installed app can
/// reach `127.0.0.1`, so binding to loopback is necessary and not sufficient.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_startLocalServerNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |_env| {
        let started = get_runtime().block_on(server::start::<env::android::AndroidEnv>());
        match started {
            Ok(handle) => format!(
                r#"{{"port":{},"token":{}}}"#,
                handle.port,
                serde_json::to_string(&handle.token).unwrap_or_else(|_| "\"\"".to_string())
            ),
            Err(e) => serialize_result::<(), _>(Err(e)),
        }
    })
}

/// What Kotlin sends to [`Java_com_varuna_rustify_bridge_NativeEngine_registerLocalStreamNative`].
///
/// JSON rather than six positional JNI arguments: this grew from four fields to six in one release,
/// and each of those would otherwise have been a signature change on both sides of the bridge —
/// exactly the kind of edit that compiles happily and then throws `NoSuchMethodError` at run time.
#[derive(serde::Deserialize)]
#[serde(rename_all = "camelCase")]
struct RegisterStreamRequest {
    /// Where the bytes come from. Must be `http(s)`: local music is deliberately not routed.
    #[serde(default)]
    upstream_url: String,
    /// Stable per track, per backend and per format. Not used in `"proxy"` mode.
    #[serde(default)]
    cache_key: String,
    #[serde(default)]
    cache_root: String,
    #[serde(default)]
    mime: String,
    /// Set when the bytes are Deezer-encrypted, so the fill knows to decrypt them on the way in —
    /// and, in `"proxy"` mode, so the server knows what key to decrypt them *with*.
    #[serde(default)]
    deezer_sng_id: String,
    #[serde(default)]
    ttl_ms: i64,
    /// `"cache"` (the default) asks whether the track is already on disk. `"proxy"` asks for a URL
    /// that streams and decrypts straight from the CDN — for a track nothing has stored yet.
    #[serde(default)]
    mode: String,
}

#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
struct RegisterStreamAnswer {
    /// `"ready"`, `"notCached"` or `"refused"`.
    status: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    url: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    reason: Option<&'static str>,
}

/// JNI Bridge: register a track with the local server and get back the URL to play — or a plain
/// "not cached", which means **play the upstream URL yourself**, exactly as before this server
/// existed.
///
/// Never touches the network. A track that is not on disk yet starts a background fill and this
/// returns immediately; that fill is what makes a later play a file read.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_registerLocalStreamNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    request_json: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let raw = request_json.mutf8_chars(env)?.to_string();
        match serde_json::from_str::<RegisterStreamRequest>(&raw) {
            Err(_) => r#"{"status":"refused","reason":"bad request"}"#.to_string(),
            Ok(request) if request.mode == "proxy" => {
                let answer = match server::register_proxy::<env::android::AndroidEnv>(
                    &request.upstream_url,
                    &request.deezer_sng_id,
                    if request.mime.is_empty() {
                        None
                    } else {
                        Some(request.mime)
                    },
                    if request.ttl_ms > 0 {
                        Some(request.ttl_ms)
                    } else {
                        None
                    },
                    // Where a copy lands as it plays. Absent when the user has storing off, which
                    // is the same switch that governs everything else the server does.
                    if request.cache_root.is_empty() || request.cache_key.is_empty() {
                        None
                    } else {
                        Some((request.cache_root.as_str(), request.cache_key.as_str()))
                    },
                ) {
                    Some(url) => RegisterStreamAnswer {
                        status: "ready",
                        url: Some(url),
                        reason: None,
                    },
                    // The server is not up, or the request was not something to proxy. Either way
                    // the caller falls back to playing it the way it did before.
                    None => RegisterStreamAnswer {
                        status: "notCached",
                        url: None,
                        reason: None,
                    },
                };
                serde_json::to_string(&answer)
                    .unwrap_or_else(|_| r#"{"status":"notCached"}"#.to_string())
            }
            Ok(request) => {
                let cache_root = request.cache_root;
                let registration = server::Registration {
                    upstream_url: request.upstream_url,
                    cache_key: request.cache_key,
                    mime: if request.mime.is_empty() {
                        None
                    } else {
                        Some(request.mime)
                    },
                    deezer_sng_id: if request.deezer_sng_id.is_empty() {
                        None
                    } else {
                        Some(request.deezer_sng_id)
                    },
                    ttl_ms: if request.ttl_ms > 0 {
                        Some(request.ttl_ms)
                    } else {
                        None
                    },
                };

                let answer =
                    match server::register::<env::android::AndroidEnv>(registration, &cache_root) {
                        server::Registered::Ready(url) => RegisterStreamAnswer {
                            status: "ready",
                            url: Some(url),
                            reason: None,
                        },
                        server::Registered::NotCached => RegisterStreamAnswer {
                            status: "notCached",
                            url: None,
                            reason: None,
                        },
                        server::Registered::Refused(reason) => RegisterStreamAnswer {
                            status: "refused",
                            url: None,
                            reason: Some(reason),
                        },
                    };
                // The fallback is `notCached` and not an error object: whatever went wrong here,
                // the caller's correct move is to play the upstream URL.
                serde_json::to_string(&answer)
                    .unwrap_or_else(|_| r#"{"status":"notCached"}"#.to_string())
            }
        }
    })
}

/// JNI Bridge: how many bytes the stream cache is using. For the Settings screen.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_streamCacheSizeNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    cache_root: JString<'local>,
) -> jlong {
    // An atomic rather than a captured `let mut`: it works whether `with_env` wants the closure by
    // value or by reference, and the difference is not worth a compile error to find out.
    let size = std::sync::atomic::AtomicI64::new(0);
    let _ = env_unowned.with_env(|env| -> jni::errors::Result<()> {
        let root = cache_root.mutf8_chars(env)?.to_string();
        let bytes = get_runtime().block_on(server::cache::size(&root)) as jlong;
        size.store(bytes, std::sync::atomic::Ordering::Relaxed);
        Ok(())
    });
    size.load(std::sync::atomic::Ordering::Relaxed)
}

/// JNI Bridge: empty the stream cache. Returns the number of bytes freed.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_clearStreamCacheNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    cache_root: JString<'local>,
) -> jlong {
    let freed = std::sync::atomic::AtomicI64::new(0);
    let _ = env_unowned.with_env(|env| -> jni::errors::Result<()> {
        let root = cache_root.mutf8_chars(env)?.to_string();
        let bytes = get_runtime().block_on(server::cache::clear(&root)) as jlong;
        freed.store(bytes, std::sync::atomic::Ordering::Relaxed);
        Ok(())
    });
    freed.load(std::sync::atomic::Ordering::Relaxed)
}

/// JNI Bridge: Drop a registration once the player is done with it.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_forgetLocalStreamNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: JString<'local>,
) {
    let _ = env_unowned.with_env(|env| -> jni::errors::Result<()> {
        let handle_str = handle.mutf8_chars(env)?.to_string();
        server::handles::forget(&handle_str);
        Ok(())
    });
}

/// JNI Bridge: Set language for Spotify Client
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_setLanguageNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    lang: JString<'local>,
) {
    let _ = env_unowned.with_env(|env| -> jni::errors::Result<()> {
        let lang_str = lang.mutf8_chars(env)?.to_string();
        let client = spotify::client::get_spotify_client();
        client.set_accept_language(&lang_str);
        Ok(())
    });
}

/// JNI Bridge: Get current alternative YouTube Video ID override if exists
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_getAlternativeTrackNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    spotify_id: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let mutf8 = spotify_id.mutf8_chars(env)?;
        let s_id = mutf8.to_string();
        let alt_id = youtube::server::get_alternative_track(&s_id).unwrap_or_default();
        alt_id
    })
}

/// JNI Bridge: Resolve Spotify Track ID to YouTube Video ID directly without local HTTP proxy
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_resolveYouTubeIdNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    track_id: JString<'local>,
    youtube_id: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let tid_mutf8 = track_id.mutf8_chars(env)?;
        let tid = tid_mutf8.to_string();

        let yid_mutf8 = youtube_id.mutf8_chars(env)?;
        let yid_str = yid_mutf8.to_string();
        let yid_opt = if yid_str.is_empty() { None } else { Some(yid_str) };

        let async_result = get_runtime().block_on(async {
            let cache_dir = match youtube::server::get_cache_dir() {
                Some(dir) => dir,
                None => "/data/data/com.varuna.rustify/cache".to_string(),
            };
            youtube::server::resolve_youtube_id_direct::<env::android::AndroidEnv>(&tid, yid_opt.as_deref(), &cache_dir).await
        });
        async_result.unwrap_or_default()
    })
}
/// JNI Bridge: Send queue of track IDs for caching/buffering
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_updateQueueNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    track_ids_json: JString<'local>,
) {
    let _ = env_unowned.with_env(|env| -> jni::errors::Result<()> {
        let json_str = track_ids_json.mutf8_chars(env)?.to_string();
        let track_ids: Vec<String> = serde_json::from_str(&json_str).unwrap_or_default();
        youtube::server::update_playback_queue::<env::android::AndroidEnv>(track_ids);
        Ok(())
    });
}

// =============================================================================
// ADBLOCK — network filtering for the in-app Spotify Web Player
// =============================================================================

/// JNI Bridge: compile filter lists (uBO/EasyList syntax) into the blocking engine.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_adblockLoadRulesNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    rules: JString<'local>,
) -> jboolean {
    let mut ok = false;
    let _ = env_unowned.with_env(|env| -> jni::errors::Result<()> {
        let rules_str = rules.mutf8_chars(env)?.to_string();
        ok = adblock_engine::load_rules(&rules_str);
        Ok(())
    });
    if ok { JNI_TRUE } else { JNI_FALSE }
}

/// JNI Bridge: should this request be blocked? Called once per WebView request, so it must be cheap
/// and must never panic — every failure path answers "don't block".
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_adblockMatchesNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    url: JString<'local>,
    source_url: JString<'local>,
    resource_type: JString<'local>,
) -> jboolean {
    let mut blocked = false;
    let _ = env_unowned.with_env(|env| -> jni::errors::Result<()> {
        let url_s = url.mutf8_chars(env)?.to_string();
        let src_s = source_url.mutf8_chars(env)?.to_string();
        let type_s = resource_type.mutf8_chars(env)?.to_string();
        blocked = adblock_engine::matches(&url_s, &src_s, &type_s);
        Ok(())
    });
    if blocked { JNI_TRUE } else { JNI_FALSE }
}

/// JNI Bridge: is a compiled engine available?
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_adblockIsReadyNative<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> jboolean {
    if adblock_engine::is_ready() { JNI_TRUE } else { JNI_FALSE }
}

/// JNI Bridge: release the compiled engine (web player closed).
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_adblockClearNative<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
) {
    adblock_engine::clear();
}

// =============================================================================
// YOUTUBE MUSIC (E40) — YTM browse APIs
// =============================================================================

/// JNI Bridge: Search YouTube Music (tracks, albums, artists, playlists)
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_searchYtMusicNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    query: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let mutf8 = query.mutf8_chars(env)?;
        let q = mutf8.to_string();
        let results = get_runtime().block_on(youtube::ytmusic::ytm_search(&q));
        serde_json::to_string(&results).unwrap_or_else(|_| "{}".to_string())
    })
}

/// JNI Bridge: Get YouTube Music album details
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_getYtmAlbumNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    browse_id: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let mutf8 = browse_id.mutf8_chars(env)?;
        let id = mutf8.to_string();
        let album = get_runtime().block_on(youtube::ytmusic::ytm_get_album(&id));
        serde_json::to_string(&album).unwrap_or_else(|_| "null".to_string())
    })
}

/// JNI Bridge: Get YouTube Music artist details
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_getYtmArtistNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    channel_id: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let mutf8 = channel_id.mutf8_chars(env)?;
        let id = mutf8.to_string();
        let artist = get_runtime().block_on(youtube::ytmusic::ytm_get_artist(&id));
        serde_json::to_string(&artist).unwrap_or_else(|_| "null".to_string())
    })
}

/// JNI Bridge: Get YouTube Music playlist details
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_getYtmPlaylistNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    playlist_id: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let mutf8 = playlist_id.mutf8_chars(env)?;
        let id = mutf8.to_string();
        let playlist = get_runtime().block_on(youtube::ytmusic::ytm_get_playlist(&id));
        serde_json::to_string(&playlist).unwrap_or_else(|_| "null".to_string())
    })
}

/// JNI Bridge: Get YouTube Music radio (related tracks)
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_getYtmRadioNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    video_id: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let mutf8 = video_id.mutf8_chars(env)?;
        let id = mutf8.to_string();
        let tracks = get_runtime().block_on(youtube::ytmusic::ytm_radio(&id));
        serde_json::to_string(&tracks).unwrap_or_else(|_| "[]".to_string())
    })
}

// =============================================================================
// SPOTIFY — AUTHENTICATION
// =============================================================================

/// JNI Bridge: Login with sp_dc cookie (full TOTP flow)
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_loginSpotifyNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    sp_dc_cookie: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let mutf8 = sp_dc_cookie.mutf8_chars(env)?;
        let cookie_str = mutf8.to_string();
        let async_result = get_runtime().block_on(async {
            let client = spotify::client::get_spotify_client();
            client.login_with_sp_dc(&cookie_str).await
        });
        serialize_result(async_result)
    })
}

/// JNI Bridge: Logout
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_logoutSpotifyNative<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
) {
    spotify::client::get_spotify_client().logout();
}

/// JNI Bridge: Refresh access token
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_refreshSpotifyTokenNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let async_result = get_runtime().block_on(async {
            let client = spotify::client::get_spotify_client();
            client.refresh_token().await
        });
        let result = spotify::models::OperationResult {
            success: async_result.is_ok(),
            error: async_result.err().map(|e| e.to_string()),
        };
        serde_json::to_string(&result).unwrap_or_else(|_| r#"{"success": false}"#.to_string())
    })
}

/// JNI Bridge: Check if authenticated
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_isSpotifyAuthenticatedNative<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> jboolean {
    // No lock to acquire since 3.1, which is the point: this is called from the main thread at
    // start-up and used to be able to sit behind a token refresh that was waiting on a socket.
    if spotify::client::get_spotify_client().is_authenticated() {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// JNI Bridge: Restore session from saved sp_dc cookie and cached token
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_restoreSpotifySessionNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    sp_dc_cookie: JString<'local>,
    access_token: JString<'local>,
    expiration: jlong,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let cookie_mutf8 = sp_dc_cookie.mutf8_chars(env)?;
        let cookie_str = cookie_mutf8.to_string();

        let token_mutf8 = access_token.mutf8_chars(env)?;
        let token_str = token_mutf8.to_string();

        let token_opt = if token_str.is_empty() { None } else { Some(token_str.as_str()) };
        let exp_opt = if expiration <= 0 { None } else { Some(expiration as u64) };

        let async_result = get_runtime().block_on(async {
            let client = spotify::client::get_spotify_client();
            client.restore_session(&cookie_str, token_opt, exp_opt).await
        });
        serialize_result(async_result)
    })
}

// =============================================================================
// SPOTIFY — USER / LIBRARY
// =============================================================================

/// JNI Bridge: Get current user profile
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_getSpotifyMeNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let async_result = get_runtime().block_on(async {
            let client = spotify::client::get_spotify_client();
            client.get_me().await
        });
        serialize_result(async_result)
    })
}

/// JNI Bridge: Get saved/liked tracks
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_getSpotifySavedTracksNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    limit: jint,
    offset: jint,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let async_result = get_runtime().block_on(async {
            let client = spotify::client::get_spotify_client();
            client.get_saved_tracks(limit as u32, offset as u32).await
        });
        serialize_result(async_result)
    })
}

/// JNI Bridge: Get saved albums
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_getSpotifySavedAlbumsNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    limit: jint,
    offset: jint,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let async_result = get_runtime().block_on(async {
            let client = spotify::client::get_spotify_client();
            client.get_saved_albums(limit as u32, offset as u32).await
        });
        serialize_result(async_result)
    })
}

/// JNI Bridge: Get saved/created playlists
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_getSpotifySavedPlaylistsNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    limit: jint,
    offset: jint,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let async_result = get_runtime().block_on(async {
            let client = spotify::client::get_spotify_client();
            client.get_saved_playlists(limit as u32, offset as u32).await
        });
        serialize_result(async_result)
    })
}

/// JNI Bridge: Get followed artists (now offset-based via GraphQL libraryV3)
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_getSpotifyFollowedArtistsNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    limit: jint,
    offset: jint,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let async_result = get_runtime().block_on(async {
            let client = spotify::client::get_spotify_client();
            client.get_followed_artists(limit as u32, offset as u32).await
        });
        serialize_result(async_result)
    })
}

// =============================================================================
// SPOTIFY — ALBUMS
// =============================================================================

/// JNI Bridge: Get album details
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_getSpotifyAlbumNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    album_id: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let mutf8 = album_id.mutf8_chars(env)?;
        let id = mutf8.to_string();
        let async_result = get_runtime().block_on(async {
            let client = spotify::client::get_spotify_client();
            client.get_album(&id).await
        });
        serialize_result(async_result)
    })
}

/// JNI Bridge: Get album tracks
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_getSpotifyAlbumTracksNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    album_id: JString<'local>,
    limit: jint,
    offset: jint,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let mutf8 = album_id.mutf8_chars(env)?;
        let id = mutf8.to_string();
        let async_result = get_runtime().block_on(async {
            let client = spotify::client::get_spotify_client();
            client.get_album_tracks(&id, limit as u32, offset as u32).await
        });
        serialize_result(async_result)
    })
}

/// JNI Bridge: Get new releases
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_getSpotifyNewReleasesNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    limit: jint,
    offset: jint,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let async_result = get_runtime().block_on(async {
            let client = spotify::client::get_spotify_client();
            client.get_new_releases(limit as u32, offset as u32).await
        });
        serialize_result(async_result)
    })
}

/// JNI Bridge: Save albums to library
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_saveSpotifyAlbumsNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    ids_json: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let mutf8 = ids_json.mutf8_chars(env)?;
        let ids_str = mutf8.to_string();
        let ids: Vec<String> = serde_json::from_str(&ids_str).unwrap_or_default();
        let async_result = get_runtime().block_on(async {
            let client = spotify::client::get_spotify_client();
            client.save_albums(&ids).await
        });
        let result = spotify::models::OperationResult {
            success: async_result.is_ok(),
            error: async_result.err().map(|e| e.to_string()),
        };
        serde_json::to_string(&result).unwrap_or_else(|_| r#"{"success": false}"#.to_string())
    })
}

/// JNI Bridge: Remove albums from library
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_unsaveSpotifyAlbumsNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    ids_json: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let mutf8 = ids_json.mutf8_chars(env)?;
        let ids_str = mutf8.to_string();
        let ids: Vec<String> = serde_json::from_str(&ids_str).unwrap_or_default();
        let async_result = get_runtime().block_on(async {
            let client = spotify::client::get_spotify_client();
            client.unsave_albums(&ids).await
        });
        let result = spotify::models::OperationResult {
            success: async_result.is_ok(),
            error: async_result.err().map(|e| e.to_string()),
        };
        serde_json::to_string(&result).unwrap_or_else(|_| r#"{"success": false}"#.to_string())
    })
}

// =============================================================================
// SPOTIFY — ARTISTS
// =============================================================================

/// JNI Bridge: Get artist details
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_getSpotifyArtistNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    artist_id: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let mutf8 = artist_id.mutf8_chars(env)?;
        let id = mutf8.to_string();
        let async_result = get_runtime().block_on(async {
            let client = spotify::client::get_spotify_client();
            client.get_artist(&id).await
        });
        serialize_result(async_result)
    })
}

/// JNI Bridge: Get artist top tracks
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_getSpotifyArtistTopTracksNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    artist_id: JString<'local>,
    limit: jint,
    offset: jint,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let mutf8 = artist_id.mutf8_chars(env)?;
        let id = mutf8.to_string();
        let async_result = get_runtime().block_on(async {
            let client = spotify::client::get_spotify_client();
            client.get_artist_top_tracks(&id, limit as u32, offset as u32).await
        });
        serialize_result(async_result)
    })
}

/// JNI Bridge: Get artist albums
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_getSpotifyArtistAlbumsNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    artist_id: JString<'local>,
    limit: jint,
    offset: jint,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let mutf8 = artist_id.mutf8_chars(env)?;
        let id = mutf8.to_string();
        let async_result = get_runtime().block_on(async {
            let client = spotify::client::get_spotify_client();
            client.get_artist_albums(&id, limit as u32, offset as u32).await
        });
        serialize_result(async_result)
    })
}

/// JNI Bridge: Get related artists
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_getSpotifyRelatedArtistsNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    artist_id: JString<'local>,
    limit: jint,
    offset: jint,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let mutf8 = artist_id.mutf8_chars(env)?;
        let id = mutf8.to_string();
        let async_result = get_runtime().block_on(async {
            let client = spotify::client::get_spotify_client();
            client.get_related_artists(&id, limit as u32, offset as u32).await
        });
        serialize_result(async_result)
    })
}

/// JNI Bridge: Follow artists
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_followSpotifyArtistsNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    ids_json: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let mutf8 = ids_json.mutf8_chars(env)?;
        let ids_str = mutf8.to_string();
        let ids: Vec<String> = serde_json::from_str(&ids_str).unwrap_or_default();
        let async_result = get_runtime().block_on(async {
            let client = spotify::client::get_spotify_client();
            client.follow_artists(&ids).await
        });
        let result = spotify::models::OperationResult {
            success: async_result.is_ok(),
            error: async_result.err().map(|e| e.to_string()),
        };
        serde_json::to_string(&result).unwrap_or_else(|_| r#"{"success": false}"#.to_string())
    })
}

/// JNI Bridge: Unfollow artists
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_unfollowSpotifyArtistsNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    ids_json: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let mutf8 = ids_json.mutf8_chars(env)?;
        let ids_str = mutf8.to_string();
        let ids: Vec<String> = serde_json::from_str(&ids_str).unwrap_or_default();
        let async_result = get_runtime().block_on(async {
            let client = spotify::client::get_spotify_client();
            client.unfollow_artists(&ids).await
        });
        let result = spotify::models::OperationResult {
            success: async_result.is_ok(),
            error: async_result.err().map(|e| e.to_string()),
        };
        serde_json::to_string(&result).unwrap_or_else(|_| r#"{"success": false}"#.to_string())
    })
}

// =============================================================================
// SPOTIFY — PLAYLISTS
// =============================================================================

/// JNI Bridge: Get playlist details
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_getSpotifyPlaylistNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    playlist_id: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let mutf8 = playlist_id.mutf8_chars(env)?;
        let id = mutf8.to_string();
        let async_result = get_runtime().block_on(async {
            let client = spotify::client::get_spotify_client();
            client.get_playlist(&id).await
        });
        serialize_result(async_result)
    })
}

/// JNI Bridge: Get playlist tracks
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_getSpotifyPlaylistTracksNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    playlist_id: JString<'local>,
    limit: jint,
    offset: jint,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let mutf8 = playlist_id.mutf8_chars(env)?;
        let id = mutf8.to_string();
        let async_result = get_runtime().block_on(async {
            let client = spotify::client::get_spotify_client();
            client.get_playlist_tracks(&id, limit as u32, offset as u32).await
        });
        serialize_result(async_result)
    })
}

/// JNI Bridge: Create a new playlist
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_createSpotifyPlaylistNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    user_id: JString<'local>,
    name: JString<'local>,
    description: JString<'local>,
    public: jboolean,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let uid = user_id.mutf8_chars(env)?.to_string();
        let n = name.mutf8_chars(env)?.to_string();
        let d = description.mutf8_chars(env)?.to_string();
        let is_public = public != JNI_FALSE;

        let async_result = get_runtime().block_on(async {
            let client = spotify::client::get_spotify_client();
            client.create_playlist(&uid, &n, &d, is_public).await
        });
        serialize_result(async_result)
    })
}

/// JNI Bridge: Update playlist details
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_updateSpotifyPlaylistNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    playlist_id: JString<'local>,
    name: JString<'local>,
    description: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let id = playlist_id.mutf8_chars(env)?.to_string();
        let n = name.mutf8_chars(env)?.to_string();
        let d = description.mutf8_chars(env)?.to_string();
        let name_opt = if n.is_empty() { None } else { Some(n.as_str()) };
        let desc_opt = if d.is_empty() { None } else { Some(d.as_str()) };

        let async_result = get_runtime().block_on(async {
            let client = spotify::client::get_spotify_client();
            client.update_playlist(&id, name_opt, desc_opt, None).await
        });
        let result = spotify::models::OperationResult {
            success: async_result.is_ok(),
            error: async_result.err().map(|e| e.to_string()),
        };
        serde_json::to_string(&result).unwrap_or_else(|_| r#"{"success": false}"#.to_string())
    })
}

/// JNI Bridge: Add tracks to a playlist
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_addTracksToPlaylistNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    playlist_id: JString<'local>,
    track_ids_json: JString<'local>,
    position: jint,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let id = playlist_id.mutf8_chars(env)?.to_string();
        let ids_str = track_ids_json.mutf8_chars(env)?.to_string();
        let track_ids: Vec<String> = serde_json::from_str(&ids_str).unwrap_or_default();
        let pos = if position < 0 { None } else { Some(position as u32) };

        let async_result = get_runtime().block_on(async {
            let client = spotify::client::get_spotify_client();
            client.add_tracks_to_playlist(&id, &track_ids, pos).await
        });
        let result = spotify::models::OperationResult {
            success: async_result.is_ok(),
            error: async_result.err().map(|e| e.to_string()),
        };
        serde_json::to_string(&result).unwrap_or_else(|_| r#"{"success": false}"#.to_string())
    })
}

/// JNI Bridge: Remove tracks from a playlist
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_removeTracksFromPlaylistNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    playlist_id: JString<'local>,
    track_ids_json: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let id = playlist_id.mutf8_chars(env)?.to_string();
        let ids_str = track_ids_json.mutf8_chars(env)?.to_string();
        let track_ids: Vec<String> = serde_json::from_str(&ids_str).unwrap_or_default();

        let async_result = get_runtime().block_on(async {
            let client = spotify::client::get_spotify_client();
            client.remove_tracks_from_playlist(&id, &track_ids).await
        });
        let result = spotify::models::OperationResult {
            success: async_result.is_ok(),
            error: async_result.err().map(|e| e.to_string()),
        };
        serde_json::to_string(&result).unwrap_or_else(|_| r#"{"success": false}"#.to_string())
    })
}

/// JNI Bridge: Follow/save a playlist
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_followPlaylistNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    playlist_id: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let id = playlist_id.mutf8_chars(env)?.to_string();
        let async_result = get_runtime().block_on(async {
            let client = spotify::client::get_spotify_client();
            client.follow_playlist(&id).await
        });
        let result = spotify::models::OperationResult {
            success: async_result.is_ok(),
            error: async_result.err().map(|e| e.to_string()),
        };
        serde_json::to_string(&result).unwrap_or_else(|_| r#"{"success": false}"#.to_string())
    })
}

/// JNI Bridge: Unfollow/unsave a playlist
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_unfollowPlaylistNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    playlist_id: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let id = playlist_id.mutf8_chars(env)?.to_string();
        let async_result = get_runtime().block_on(async {
            let client = spotify::client::get_spotify_client();
            client.unfollow_playlist(&id).await
        });
        let result = spotify::models::OperationResult {
            success: async_result.is_ok(),
            error: async_result.err().map(|e| e.to_string()),
        };
        serde_json::to_string(&result).unwrap_or_else(|_| r#"{"success": false}"#.to_string())
    })
}

// =============================================================================
// SPOTIFY — TRACKS
// =============================================================================

/// JNI Bridge: Get track details
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_getSpotifyTrackNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    track_id: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let mutf8 = track_id.mutf8_chars(env)?;
        let id = mutf8.to_string();
        let async_result = get_runtime().block_on(async {
            let client = spotify::client::get_spotify_client();
            client.get_track(&id).await
        });
        serialize_result(async_result)
    })
}

/// JNI Bridge: Save tracks to library
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_saveSpotifyTracksNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    ids_json: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let mutf8 = ids_json.mutf8_chars(env)?;
        let ids_str = mutf8.to_string();
        let ids: Vec<String> = serde_json::from_str(&ids_str).unwrap_or_default();
        let async_result = get_runtime().block_on(async {
            let client = spotify::client::get_spotify_client();
            client.save_tracks(&ids).await
        });
        let result = spotify::models::OperationResult {
            success: async_result.is_ok(),
            error: async_result.err().map(|e| e.to_string()),
        };
        serde_json::to_string(&result).unwrap_or_else(|_| r#"{"success": false}"#.to_string())
    })
}

/// JNI Bridge: Remove tracks from library
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_unsaveSpotifyTracksNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    ids_json: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let mutf8 = ids_json.mutf8_chars(env)?;
        let ids_str = mutf8.to_string();
        let ids: Vec<String> = serde_json::from_str(&ids_str).unwrap_or_default();
        let async_result = get_runtime().block_on(async {
            let client = spotify::client::get_spotify_client();
            client.unsave_tracks(&ids).await
        });
        let result = spotify::models::OperationResult {
            success: async_result.is_ok(),
            error: async_result.err().map(|e| e.to_string()),
        };
        serde_json::to_string(&result).unwrap_or_else(|_| r#"{"success": false}"#.to_string())
    })
}

/// JNI Bridge: Get track radio
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_getSpotifyTrackRadioNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    track_id: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let mutf8 = track_id.mutf8_chars(env)?;
        let id = mutf8.to_string();
        let async_result = get_runtime().block_on(async {
            let client = spotify::client::get_spotify_client();
            client.get_track_radio(&id).await
        });
        serialize_result(async_result)
    })
}

/// JNI Bridge: Get the Spotify Canvas (looping mp4 url) for a track.
/// Accepts a track id or a full `spotify:track:<id>` uri.
/// Returns a JSON object: `{"url":"<mp4>"}` when a canvas exists,
/// `{"url":null}` when the track has no canvas, or
/// `{"success":false,"error":"..."}` on failure.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_getSpotifyCanvasNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    track_uri: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let mutf8 = track_uri.mutf8_chars(env)?;
        let uri = mutf8.to_string();
        let async_result = get_runtime().block_on(async {
            let client = spotify::client::get_spotify_client();
            client.get_track_canvas(&uri).await
        });
        match async_result {
            Ok(url_opt) => {
                serde_json::json!({ "url": url_opt }).to_string()
            }
            Err(e) => {
                eprintln!("Rust Engine Error (canvas): {}", e);
                let error_response = spotify::models::OperationResult::err(e.to_string());
                serde_json::to_string(&error_response)
                    .unwrap_or_else(|_| r#"{"success":false,"error":"canvas error"}"#.to_string())
            }
        }
    })
}

/// JNI Bridge: Initialize cache directory
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_initSpotifyCacheDirNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    cache_dir: JString<'local>,
) {
    let _ = jni_bridge!(env_unowned, |env| {
        if let Ok(mutf8) = cache_dir.mutf8_chars(env) {
            let path = mutf8.to_string();
            let client = spotify::client::get_spotify_client();
            client.set_cache_dir(&path);
        }
        "".to_string()
    });
}

/// JNI Bridge: Warm up GQL hashes in the background
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_warmupSpotifyHashesNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
) {
    let _ = jni_bridge!(env_unowned, |env| {
        get_runtime().spawn(async {
            eprintln!("[Spotify] Background hash warmup started...");
            // No longer borrows the client's HTTP handle first: the scraper takes the platform as a
            // type parameter, so nothing has to be cloned out of the lock to make the call.
            match spotify::client::scrape_gql_hashes::<env::android::AndroidEnv>().await {
                Ok(new_hashes) => {
                    let client = spotify::client::get_spotify_client();
                    client.update_gql_hashes(new_hashes);
                    eprintln!("[Spotify] Background hash warmup completed successfully.");
                }
                Err(e) => {
                    eprintln!("[Spotify] Background hash warmup failed: {}", e);
                }
            }
        });
        "".to_string()
    });
}

/// JNI Bridge: Return a JSON object with all current GQL operation hashes.
/// Returns: `{"operationName": "sha256hash", ...}` or `{}` if none cached yet.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_getSpotifyHashesNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let client = spotify::client::get_spotify_client();
        let snapshot = client.get_gql_hashes_snapshot();
        serde_json::to_string(&snapshot).unwrap_or_else(|_| "{}".to_string())
    })
}





/// JNI Bridge: Check if tracks are saved
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_checkSpotifySavedTracksNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    ids_json: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let mutf8 = ids_json.mutf8_chars(env)?;
        let ids_str = mutf8.to_string();
        let ids: Vec<String> = serde_json::from_str(&ids_str).unwrap_or_default();
        let async_result = get_runtime().block_on(async {
            let client = spotify::client::get_spotify_client();
            client.check_saved_tracks(&ids).await
        });
        serialize_result(async_result)
    })
}

// =============================================================================
// SPOTIFY — SEARCH
// =============================================================================

/// JNI Bridge: Search Spotify (all types or specific type)
/// When search_type is "all", returns NormalizedSearchResults.
/// Otherwise, returns PaginatedResponse of the specific type.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_searchSpotifyNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    query: JString<'local>,
    search_type: JString<'local>,
    limit: jint,
    offset: jint,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let q = query.mutf8_chars(env)?.to_string();
        let s_type = search_type.mutf8_chars(env)?.to_string();

        let result_json = get_runtime().block_on(async {
            let client = spotify::client::get_spotify_client();

            match s_type.as_str() {
                "all" => {
                    let r = client.search_all(&q, limit as u32).await;
                    serialize_result(r)
                }
                "tracks" => {
                    let r = client.search_tracks(&q, limit as u32, offset as u32).await;
                    serialize_result(r)
                }
                "albums" => {
                    let r = client.search_albums(&q, limit as u32, offset as u32).await;
                    serialize_result(r)
                }
                "artists" => {
                    let r = client.search_artists(&q, limit as u32, offset as u32).await;
                    serialize_result(r)
                }
                "playlists" => {
                    let r = client.search_playlists(&q, limit as u32, offset as u32).await;
                    serialize_result(r)
                }
                _ => {
                    let r = client.search_all(&q, limit as u32).await;
                    serialize_result(r)
                }
            }
        });

        result_json
    })
}

// =============================================================================
// SPOTIFY — BROWSE
// =============================================================================

/// JNI Bridge: Get browse/home sections
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_getSpotifyBrowseNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    limit: jint,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let async_result = get_runtime().block_on(async {
            let client = spotify::client::get_spotify_client();
            client.get_browse_sections(limit as u32).await
        });
        serialize_result(async_result)
    })
}

// =============================================================================
// LOCAL SEARCH (point J)
//
// Index once, query per keystroke. The whole reason for the split is that sending a library across
// JNI on every character would be slower than the `contains` this replaces.
// =============================================================================

/// JNI Bridge: hand a list over to be folded and kept.
///
/// `items_json` is `[{"id":"…","fields":["name","artist",…]}]`, fields in importance order.
/// Pure and in-memory: no `block_on`, no storage, safe from any thread.
///
/// @return `{"success":true,"count":N}`
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_searchIndexNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    name: JString<'local>,
    items_json: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let name_str = name.mutf8_chars(env)?.to_string();
        let raw = items_json.mutf8_chars(env)?.to_string();

        #[derive(serde::Deserialize)]
        struct Item {
            id: String,
            #[serde(default)]
            fields: Vec<String>,
        }

        match serde_json::from_str::<Vec<Item>>(&raw) {
            Err(e) => serde_json::json!({ "success": false, "error": e.to_string() }).to_string(),
            Ok(items) => {
                let count = items.len();
                let index =
                    search::Index::build(items.into_iter().map(|i| (i.id, i.fields)).collect());
                search::put(&name_str, index);
                serde_json::json!({ "success": true, "count": count }).to_string()
            }
        }
    })
}

/// JNI Bridge: matching ids from a named index, best first, as a JSON array.
///
/// `limit` of 0 means all of them. An empty query returns the list in its original order — an empty
/// search box is not a filter. Pure and in-memory.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_searchQueryNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    name: JString<'local>,
    query: JString<'local>,
    limit: jint,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let name_str = name.mutf8_chars(env)?.to_string();
        let query_str = query.mutf8_chars(env)?.to_string();
        let ids = search::query(&name_str, &query_str, limit.max(0) as usize);
        serde_json::to_string(&ids).unwrap_or_else(|_| "[]".to_string())
    })
}

/// JNI Bridge: drop a named index, for a screen that is going away. Pure and in-memory.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_searchForgetNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    name: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let name_str = name.mutf8_chars(env)?.to_string();
        search::forget(&name_str);
        "{\"success\":true}".to_string()
    })
}

// =============================================================================
// ALREADY LISTENED (point I)
// =============================================================================

/// JNI Bridge: mark a track listened within a context.
///
/// `queue_json` is the context's current track ids in order — passed every time so the field can
/// realign when a playlist has been edited underneath it. Touches storage: **blocking**.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_markListenedNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    context_id: JString<'local>,
    queue_json: JString<'local>,
    track_id: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let context = context_id.mutf8_chars(env)?.to_string();
        let raw_queue = queue_json.mutf8_chars(env)?.to_string();
        let track = track_id.mutf8_chars(env)?.to_string();
        let queue: Vec<String> = serde_json::from_str(&raw_queue).unwrap_or_default();
        let outcome = get_runtime().block_on(listened::mark::<env::android::AndroidEnv>(
            &context, &queue, &track,
        ));
        serialize_result(outcome.map(|_| spotify::models::OperationResult {
            success: true,
            error: None,
        }))
    })
}

/// JNI Bridge: one boolean per position in the queue, as a JSON array.
///
/// Touches storage: **blocking**.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_listenedStateNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    context_id: JString<'local>,
    queue_json: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let context = context_id.mutf8_chars(env)?.to_string();
        let raw_queue = queue_json.mutf8_chars(env)?.to_string();
        let queue: Vec<String> = serde_json::from_str(&raw_queue).unwrap_or_default();
        let marks =
            get_runtime().block_on(listened::state::<env::android::AndroidEnv>(&context, &queue));
        serde_json::to_string(&marks).unwrap_or_else(|_| "[]".to_string())
    })
}

/// JNI Bridge: drop one context's marks, or every one when `context_id` is empty.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_forgetListenedNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    context_id: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let context = context_id.mutf8_chars(env)?.to_string();
        let outcome = get_runtime().block_on(async {
            if context.is_empty() {
                listened::clear::<env::android::AndroidEnv>().await
            } else {
                listened::forget::<env::android::AndroidEnv>(&context).await
            }
        });
        serialize_result(outcome.map(|_| spotify::models::OperationResult {
            success: true,
            error: None,
        }))
    })
}

// =============================================================================
// DATA EXPORT (point L)
// =============================================================================

/// JNI Bridge: the whole export document as pretty-printed JSON text.
///
/// Never carries a credential — see `export::redacted`. Touches storage: **blocking**.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_exportDataNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    produced_by: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let by = produced_by.mutf8_chars(env)?.to_string();
        match get_runtime().block_on(export::to_json::<env::android::AndroidEnv>(&by)) {
            Ok(text) => text,
            Err(e) => serde_json::json!({ "error": e.message() }).to_string(),
        }
    })
}

/// JNI Bridge: restore a document produced by `exportDataNative`.
///
/// Replaces rather than merges. Touches storage: **blocking**.
///
/// @return `{"success":true,"restored":[…],"skipped":[…]}` or `{"success":false,"error":"…"}`
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_restoreDataNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    json: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let raw = json.mutf8_chars(env)?.to_string();
        match get_runtime().block_on(export::restore::<env::android::AndroidEnv>(&raw)) {
            Ok(done) => serde_json::json!({
                "success": true,
                "restored": done.keys,
                "skipped": done.skipped,
            })
            .to_string(),
            Err(e) => serde_json::json!({ "success": false, "error": e.message() }).to_string(),
        }
    })
}

// =============================================================================
// RELEASE CALENDAR (point K)
// =============================================================================

/// JNI Bridge: place releases on the calendar and sort them newest first.
///
/// `entries_json` is `[{"id":"…","release_date":"…","release_date_precision":"…"}]`.
/// Pure and in-memory: the clock is passed in rather than read, which is what makes it testable.
///
/// @return `[{"id":"…","bucket":"this_week","day":19889}]`, newest first, undated last.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_arrangeReleasesNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    entries_json: JString<'local>,
    now_ms: jlong,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let raw = entries_json.mutf8_chars(env)?.to_string();
        match serde_json::from_str::<Vec<calendar::Entry>>(&raw) {
            Err(_) => "[]".to_string(),
            Ok(entries) => {
                let placed = calendar::arrange(&entries, now_ms);
                serde_json::to_string(&placed).unwrap_or_else(|_| "[]".to_string())
            }
        }
    })
}

// =============================================================================
// THE PLAYER REDUCER (point H)
//
// The decisions live here; the objects stay in Kotlin. What crosses is ids and a verb — what
// "next" means with repeat on, what "previous" means twenty seconds in, and where the index lands.
// Pure and in-memory: no `block_on`, no storage, safe to call from the main thread.
// =============================================================================

/// JNI Bridge: apply one action to the player state.
///
/// `state_json` is a `PlayerState`; `action_json` is `{"type":"next"}`, `{"type":"seek","ms":1000}`,
/// `{"type":"set_queue","tracks":[…],"start":0}`, `{"type":"set_repeat","repeat":"one"}`,
/// `{"type":"set_shuffle","on":true,"order":[…]}`, `{"type":"tick","ms":…}`, `{"type":"clear"}`,
/// `{"type":"previous"}` or `{"type":"track_ended"}`.
///
/// @return `{"state":{…},"effects":[…]}`. An unreadable action returns the state untouched and no
///   effects, which is the only answer that cannot make the player do something nobody asked for.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_playerReduceNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    state_json: JString<'local>,
    action_json: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let raw_state = state_json.mutf8_chars(env)?.to_string();
        let raw_action = action_json.mutf8_chars(env)?.to_string();

        let mut state: player::PlayerState = serde_json::from_str(&raw_state).unwrap_or_default();

        let action = serde_json::from_str::<serde_json::Value>(&raw_action)
            .ok()
            .and_then(|v| player_action_from_json(&v));

        let effects = match action {
            Some(action) => player::reduce(&mut state, action),
            None => Vec::new(),
        };

        serde_json::json!({
            "state": state,
            "effects": effects.iter().map(player_effect_to_json).collect::<Vec<_>>(),
        })
        .to_string()
    })
}

/// Reads the wire form of a [`player::Action`]. `None` for anything unrecognised.
fn player_action_from_json(v: &serde_json::Value) -> Option<player::Action> {
    let ms = v.get("ms").and_then(serde_json::Value::as_u64).unwrap_or(0);
    let list = |key: &str| -> Vec<String> {
        v.get(key)
            .and_then(serde_json::Value::as_array)
            .map(|a| {
                a.iter()
                    .filter_map(|t| t.as_str().map(str::to_owned))
                    .collect()
            })
            .unwrap_or_default()
    };
    match v.get("type").and_then(serde_json::Value::as_str)? {
        "set_queue" => Some(player::Action::SetQueue {
            tracks: list("tracks"),
            start: v
                .get("start")
                .and_then(serde_json::Value::as_u64)
                .unwrap_or(0) as usize,
        }),
        "next" => Some(player::Action::Next),
        "previous" => Some(player::Action::Previous),
        "seek" => Some(player::Action::Seek(ms)),
        "tick" => Some(player::Action::Tick(ms)),
        "track_ended" => Some(player::Action::TrackEnded),
        "set_repeat" => {
            let repeat = match v.get("repeat").and_then(serde_json::Value::as_str) {
                Some("all") => player::Repeat::All,
                Some("one") => player::Repeat::One,
                _ => player::Repeat::Off,
            };
            Some(player::Action::SetRepeat(repeat))
        }
        "set_shuffle" => Some(player::Action::SetShuffle {
            on: v
                .get("on")
                .and_then(serde_json::Value::as_bool)
                .unwrap_or(false),
            order: list("order"),
        }),
        "clear" => Some(player::Action::Clear),
        _ => None,
    }
}

/// The wire form of a [`player::Effect`].
fn player_effect_to_json(effect: &player::Effect) -> serde_json::Value {
    match effect {
        player::Effect::Play { track, position_ms } => serde_json::json!({
            "type": "play", "track": track, "positionMs": position_ms,
        }),
        player::Effect::SeekTo(ms) => serde_json::json!({ "type": "seek", "positionMs": ms }),
        player::Effect::Stop => serde_json::json!({ "type": "stop" }),
        player::Effect::Persist => serde_json::json!({ "type": "persist" }),
        player::Effect::QueueExhausted { last } => {
            serde_json::json!({ "type": "queue_exhausted", "last": last })
        }
    }
}

// =============================================================================
// CASTING (E16)
//
// The one place the local server stops being loopback-only. Everything about why, and the four
// layers that make it defensible, is in `server/lan.rs` and `docs/16-casting.md`.
// =============================================================================

/// JNI Bridge: open the local server to one interface, answering one device.
///
/// `bind_to` is the phone's own address on the wifi — **never** `0.0.0.0`, and the core refuses it.
/// `device` is the address of the thing being cast to; nothing else gets an answer, checked before a
/// byte of the request is read.
///
/// Binds a socket: **blocking**.
///
/// @return `{"success":true,"port":41234}` or `{"success":false,"error":"…"}`
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_openCastListenerNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    bind_to: JString<'local>,
    device: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let bind_str = bind_to.mutf8_chars(env)?.to_string();
        let device_str = device.mutf8_chars(env)?.to_string();

        let parsed = bind_str
            .parse::<std::net::IpAddr>()
            .map_err(|e| format!("{bind_str} is not an address: {e}"))
            .and_then(|b| {
                device_str
                    .parse::<std::net::IpAddr>()
                    .map_err(|e| format!("{device_str} is not an address: {e}"))
                    .map(|d| (b, d))
            });

        match parsed {
            Err(message) => serde_json::json!({ "success": false, "error": message }).to_string(),
            Ok((bind_ip, device_ip)) => {
                let outcome = get_runtime().block_on(server::lan::open::<
                    env::android::AndroidEnv,
                >(bind_ip, device_ip));
                match outcome {
                    Ok(port) => serde_json::json!({ "success": true, "port": port }).to_string(),
                    Err(e) => {
                        serde_json::json!({ "success": false, "error": e.message() }).to_string()
                    }
                }
            }
        }
    })
}

/// JNI Bridge: end the cast session.
///
/// Takes effect immediately — the allow-check reads the same slot, so a connection accepted a
/// microsecond earlier stops being answered too. Pure and in-memory.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_closeCastListenerNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |_env| {
        server::lan::close::<env::android::AndroidEnv>();
        "{\"success\":true}".to_string()
    })
}

/// JNI Bridge: the URL to hand a cast device for an already-registered handle.
///
/// `{}` when there is no session, which is the honest answer rather than a URL nobody can use.
/// Pure and in-memory.
///
/// @return `{"url":"http://192.168.1.34:41234/stream/<handle>?t=<token>"}` or `{}`
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_castUrlForNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let handle_str = handle.mutf8_chars(env)?.to_string();
        match server::lan::url_for(&handle_str) {
            Some(url) => serde_json::json!({ "url": url }).to_string(),
            None => "{}".to_string(),
        }
    })
}

/// JNI Bridge: the active cast session, for showing it and for deciding whether to close it.
///
/// Pure and in-memory.
///
/// @return `{"active":true,"boundTo":"192.168.1.34","port":41234,"device":"192.168.1.90"}`
///   or `{"active":false}`
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_castSessionNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |_env| {
        match server::lan::current() {
            None => "{\"active\":false}".to_string(),
            Some(session) => serde_json::json!({
                "active": true,
                "boundTo": session.bound_to.to_string(),
                "port": session.port,
                "device": session.device.to_string(),
            })
            .to_string(),
        }
    })
}
