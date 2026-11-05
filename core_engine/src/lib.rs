// core_engine/src/lib.rs
//
// JNI bridge layer: exposes Rust engine functions to the Android JVM via C ABI.
// Each function follows the pattern:
//   1. Extract args from JNI
//   2. Execute async Rust operation on the Tokio runtime
//   3. Serialize result to JSON string
//   4. Return as Java String

// Tests hold `env::mock::lock_and_reset()` for their whole body, awaits included: that guard is what
// serialises the tests sharing the global mock. The production rule — never a lock across an
// `.await` — is checked on the library, where clippy is clean.
#![cfg_attr(test, allow(clippy::await_holding_lock))]

pub mod adblock_engine;
pub mod addon;
pub mod audio;
pub mod autoeq;
pub mod dsp;
#[cfg(feature = "engine")]
pub mod engine;
#[cfg(feature = "c-api")]
pub mod ffi;
#[cfg(feature = "media-controls")]
pub mod media_controls;
#[cfg(feature = "window-style")]
pub mod window_style;
#[cfg(feature = "secure-store")]
pub mod secrets;
pub mod tempo;
pub mod tonal;
pub mod calendar;
pub mod env;
pub mod errors;
pub mod export;
pub mod links;
pub mod listened;
pub mod matcher;
pub mod metrics;
pub mod player;
pub mod scrobble;
pub mod jam;
pub mod search;
pub mod server;
pub mod spotify;
pub mod subsonic;
pub mod types;
pub mod youtube;

/// The wire contract between this crate and `bridge/SpotifyModels.kt`, written down as checked-in
/// JSON. Test-only: it produces nothing the app links against.
#[cfg(test)]
mod wire_fixtures;

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

/// The failure half of the bridge's answer.
///
/// Its own struct rather than a field on `OperationResult` because seventeen call sites build that
/// one as a literal, and because these two are genuinely different shapes: a hand-written refusal
/// has no error *type* behind it to classify, while everything arriving here does.
#[derive(serde::Serialize)]
struct FailedOperation {
    success: bool,
    error: String,
    /// What kind of failure — `auth`, `rateLimited`, `transient`, `permanent`. The caller decides
    /// whether to retry from this and not from [`Self::error`]. See [`errors`].
    kind: &'static str,
}

/// Generic helper to serialize results to JSON and handle errors.
///
/// Uses serde_json for proper escaping of error messages (which may contain quotes, newlines, and
/// nested JSON from API error responses).
///
/// The error path also states the **kind** of failure. That used to be re-derived on the Kotlin side
/// by matching substrings of this very message — `"401"`, `"expired"`, `"not authenticated"` — which
/// meant the difference between "refresh the session and retry" and "give up" hung on the wording of
/// a `Display` impl. The type knew all along; now it says so. See `errors::Classify`.
fn serialize_result<T: serde::Serialize, E: std::fmt::Display + errors::Classify>(
    result: Result<T, E>,
) -> String {
    match result {
        Ok(data) => serde_json::to_string(&data).unwrap_or_else(|_| "[]".to_string()),
        Err(e) => {
            // Through `Env` rather than `eprintln!`, which on Android goes nowhere: this is the one
            // line printed for every failed bridge call, and it was invisible in logcat.
            <env::android::AndroidEnv as env::Env>::log(
                env::LogLevel::Warn,
                "Engine",
                &format!("{} ({})", e, e.kind()),
            );
            let error_response = FailedOperation {
                success: false,
                error: e.to_string(),
                kind: e.kind().as_str(),
            };
            serde_json::to_string(&error_response).unwrap_or_else(|_| {
                r#"{"success":false,"error":"Unknown error","kind":"permanent"}"#.to_string()
            })
        }
    }
}

/// What a bridge answers when its body panicked or a JNI call inside it failed.
///
/// `with_env` already catches the panic — jni 0.22 wraps the closure in `catch_unwind` — so the
/// process survives. What it used to hand Kotlin afterwards was `null`, into an `external fun` declared
/// to return `String`: the crash just moved to the first line that read the answer. A failure in the
/// shape every other failure has lets `classifyError` treat it like one.
const BRIDGE_FAILURE_JSON: &str =
    r#"{"success":false,"error":"internal engine error","kind":"permanent"}"#;

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
            _ => {
                <env::android::AndroidEnv as env::Env>::log(
                    env::LogLevel::Error,
                    "Engine",
                    "bridge failed or panicked; answering with a permanent failure",
                );
                let fallback = $env_unowned.with_env(|env| -> jni::errors::Result<jstring> {
                    Ok(env.new_string(BRIDGE_FAILURE_JSON)?.into_raw())
                });
                match fallback.into_outcome() {
                    Outcome::Ok(j_str) => j_str,
                    _ => std::ptr::null_mut(),
                }
            }
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

// =============================================================================
// LISTENING HISTORY (4.0) — every play, no ceiling
// =============================================================================

/// JNI Bridge: append one of the app's own plays to the history.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_recordPlayNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    event_json: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let raw = event_json.mutf8_chars(env)?.to_string();
        match serde_json::from_str::<metrics::Event>(&raw) {
            Err(e) => serialize_result::<(), _>(Err(e)),
            Ok(event) => {
                let outcome = get_runtime().block_on(metrics::record::<env::android::AndroidEnv>(event));
                serialize_result(outcome.map(|_| spotify::models::OperationResult { success: true, error: None }))
            }
        }
    })
}

/// JNI Bridge: import a history file (Rustify, Spotify Extended or legacy). Answers an
/// `ImportReport` — read, added, duplicates.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_importPlayHistoryNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    history_json: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let raw = history_json.mutf8_chars(env)?.to_string();
        serialize_result(get_runtime().block_on(metrics::import::<env::android::AndroidEnv>(&raw)))
    })
}

/// JNI Bridge: every play, oldest first, as a JSON array. For export and backup — the screen asks
/// for [`Java_com_varuna_rustify_bridge_NativeEngine_playStatsNative`] instead.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_allPlaysNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |_env| {
        serialize_result(get_runtime().block_on(metrics::load_all::<env::android::AndroidEnv>()))
    })
}

/// JNI Bridge: the Metrics screen's numbers for `[from, to)`. `zone_json` is the device zone as
/// `[[fromMs, offsetMs], …]`.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_playStatsNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    from_ms: jlong,
    to_ms: jlong,
    zone_json: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let zone_raw = zone_json.mutf8_chars(env)?.to_string();
        let zone: metrics::stats::Zone = serde_json::from_str(&zone_raw).unwrap_or_default();
        let events = get_runtime().block_on(metrics::load_all::<env::android::AndroidEnv>());
        serialize_result(events.map(|events| metrics::stats::compute(&events, from_ms, to_ms, &zone)))
    })
}

/// JNI Bridge: the whole ranking of `[from, to)` as CSV. `kind` is `tracks`, `artists` or `albums`.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_topCsvNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    from_ms: jlong,
    to_ms: jlong,
    kind: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let kind_raw = kind.mutf8_chars(env)?.to_string();
        match serde_json::from_value::<metrics::stats::TopKind>(serde_json::Value::String(kind_raw)) {
            Err(e) => serialize_result::<(), _>(Err(e)),
            Ok(kind) => {
                let events = get_runtime().block_on(metrics::load_all::<env::android::AndroidEnv>());
                serialize_result(events.map(|events| metrics::stats::top_csv(&events, from_ms, to_ms, kind)))
            }
        }
    })
}

/// JNI Bridge: the ReplayGain tags of a local file. `path` is `/proc/self/fd/N` of an open SAF
/// descriptor. Answers a `ReplayGain`, every field null when the file has no tag.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_readReplayGainNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let path = path.mutf8_chars(env)?.to_string();
        serialize_result(audio::replaygain::read(&path))
    })
}

/// JNI Bridge: the lyrics embedded in a local file (`/proc/self/fd/N`), as a JSON string or `null`.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_readEmbeddedLyricsNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let path = path.mutf8_chars(env)?.to_string();
        serialize_result(audio::embedded_lyrics::read(&path))
    })
}

/// JNI Bridge: the tags and duration of a local file, for the desktop's local scan. Answers a
/// `LocalTags`.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_readTagsNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let path = path.mutf8_chars(env)?.to_string();
        serialize_result(audio::tags::read(&path))
    })
}

/// JNI Bridge: the embedded cover of a local file as `{"mimeType":…,"data":<base64>}`, or `null`.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_readCoverNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let path = path.mutf8_chars(env)?.to_string();
        serialize_result(audio::tags::read_cover(&path))
    })
}

/// JNI Bridge: writes a `TagsToWrite` (JSON) into the file at `path`, for a download.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_writeTagsNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
    tags_json: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let path = path.mutf8_chars(env)?.to_string();
        let raw = tags_json.mutf8_chars(env)?.to_string();
        match serde_json::from_str::<audio::tags::TagsToWrite>(&raw) {
            Err(e) => serialize_result::<(), _>(Err(e)),
            Ok(tags) => serialize_result(audio::tags::write(&path, &tags).map(|_| ok_result())),
        }
    })
}

/// JNI Bridge: queue one listen for ListenBrainz and send the queue. Answers a `scrobble::Report`.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_listenBrainzSubmitNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    token: JString<'local>,
    listen_json: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let token = token.mutf8_chars(env)?.to_string();
        let raw = listen_json.mutf8_chars(env)?.to_string();
        match serde_json::from_str::<scrobble::Listen>(&raw) {
            Err(e) => serialize_result::<(), _>(Err(e)),
            Ok(listen) => serialize_result(
                get_runtime().block_on(scrobble::submit::<env::android::AndroidEnv>(&token, listen)),
            ),
        }
    })
}

/// JNI Bridge: tell ListenBrainz what is playing now (best effort, never queued).
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_listenBrainzPlayingNowNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    token: JString<'local>,
    listen_json: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let token = token.mutf8_chars(env)?.to_string();
        let raw = listen_json.mutf8_chars(env)?.to_string();
        match serde_json::from_str::<scrobble::Listen>(&raw) {
            Err(e) => serialize_result::<(), _>(Err(e)),
            Ok(listen) => serialize_result(
                get_runtime()
                    .block_on(scrobble::playing_now::<env::android::AndroidEnv>(&token, &listen))
                    .map(|_| spotify::models::OperationResult { success: true, error: None }),
            ),
        }
    })
}

/// JNI Bridge: the ListenBrainz user a token belongs to, as a JSON string, or `null` if invalid.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_listenBrainzValidateNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    token: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let token = token.mutf8_chars(env)?.to_string();
        serialize_result(get_runtime().block_on(scrobble::validate::<env::android::AndroidEnv>(&token)))
    })
}

/// JNI Bridge: log in to Last.fm with the user's own API key; answers a `scrobble::lastfm::Session`.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_lastFmLoginNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    api_key: JString<'local>,
    secret: JString<'local>,
    user: JString<'local>,
    password: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let api_key = api_key.mutf8_chars(env)?.to_string();
        let secret = secret.mutf8_chars(env)?.to_string();
        let user = user.mutf8_chars(env)?.to_string();
        let password = password.mutf8_chars(env)?.to_string();
        serialize_result(get_runtime().block_on(scrobble::lastfm::login::<env::android::AndroidEnv>(
            &api_key, &secret, &user, &password,
        )))
    })
}

/// JNI Bridge: queue one listen for Last.fm and send the queue. Answers a `scrobble::Report`.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_lastFmSubmitNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    credentials_json: JString<'local>,
    listen_json: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let creds = credentials_json.mutf8_chars(env)?.to_string();
        let raw = listen_json.mutf8_chars(env)?.to_string();
        let parsed = serde_json::from_str::<scrobble::lastfm::Credentials>(&creds)
            .and_then(|c| serde_json::from_str::<scrobble::Listen>(&raw).map(|l| (c, l)));
        match parsed {
            Err(e) => serialize_result::<(), _>(Err(e)),
            Ok((c, listen)) => serialize_result(
                get_runtime().block_on(scrobble::lastfm::submit::<env::android::AndroidEnv>(&c, listen)),
            ),
        }
    })
}

/// JNI Bridge: tell Last.fm what is playing now (best effort, never queued).
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_lastFmNowPlayingNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    credentials_json: JString<'local>,
    listen_json: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let creds = credentials_json.mutf8_chars(env)?.to_string();
        let raw = listen_json.mutf8_chars(env)?.to_string();
        let parsed = serde_json::from_str::<scrobble::lastfm::Credentials>(&creds)
            .and_then(|c| serde_json::from_str::<scrobble::Listen>(&raw).map(|l| (c, l)));
        match parsed {
            Err(e) => serialize_result::<(), _>(Err(e)),
            Ok((c, listen)) => serialize_result(
                get_runtime()
                    .block_on(scrobble::lastfm::now_playing::<env::android::AndroidEnv>(&c, &listen))
                    .map(|_| spotify::models::OperationResult { success: true, error: None }),
            ),
        }
    })
}

fn ok_result() -> spotify::models::OperationResult {
    spotify::models::OperationResult { success: true, error: None }
}

/// JNI Bridge: a new Jam room code. Memory only.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_jamNewCode<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |_env| serialize_result::<_, String>(Ok(jam::new_code())))
}

/// JNI Bridge: join or open a Jam (`jam::session::Config` as JSON); blocks until the broker answers.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_jamJoinNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    config_json: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let raw = config_json.mutf8_chars(env)?.to_string();
        match serde_json::from_str::<jam::session::Config>(&raw) {
            Err(e) => serialize_result::<(), _>(Err(e)),
            Ok(cfg) => serialize_result(get_runtime().block_on(jam::session::join(cfg)).map(|_| ok_result())),
        }
    })
}

/// JNI Bridge: the room's events since the last call, waiting up to `timeout_ms` for one.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_jamPollNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    timeout_ms: jint,
) -> jstring {
    jni_bridge!(env_unowned, |_env| {
        serialize_result::<_, String>(Ok(get_runtime().block_on(jam::session::poll(timeout_ms.max(0) as u64))))
    })
}

/// JNI Bridge: host publishes the queue (`jam::State` as JSON).
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_jamPublishStateNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    state_json: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let raw = state_json.mutf8_chars(env)?.to_string();
        match serde_json::from_str::<jam::State>(&raw) {
            Err(e) => serialize_result::<(), _>(Err(e)),
            Ok(state) => {
                serialize_result(get_runtime().block_on(jam::session::publish_state(&state)).map(|_| ok_result()))
            }
        }
    })
}

/// JNI Bridge: guest asks the host for something (`jam::Command` as JSON).
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_jamSendNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    command_json: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let raw = command_json.mutf8_chars(env)?.to_string();
        match serde_json::from_str::<jam::Command>(&raw) {
            Err(e) => serialize_result::<(), _>(Err(e)),
            Ok(cmd) => serialize_result(get_runtime().block_on(jam::session::send(cmd)).map(|_| ok_result())),
        }
    })
}

/// JNI Bridge: host removes a guest.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_jamKickNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    id: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let id = id.mutf8_chars(env)?.to_string();
        serialize_result(get_runtime().block_on(jam::session::kick(&id)).map(|_| ok_result()))
    })
}

/// JNI Bridge: leave the Jam.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_jamLeaveNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |_env| {
        get_runtime().block_on(jam::session::leave());
        serialize_result::<_, String>(Ok(ok_result()))
    })
}

/// JNI Bridge: the tempo of mono PCM (-1..1) at `sample_rate`, as a JSON `tempo::Estimate`, or
/// `null` with under 6 s of audio. CPU only, no I/O; the app calls it off the main thread.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_tempoOfNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    samples: jni::objects::JFloatArray<'local>,
    sample_rate: jint,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let n = samples.len(env)?;
        let mut buf = vec![0f32; n];
        samples.get_region(env, 0, &mut buf)?;
        let mut t = tempo::Tempo::new(sample_rate.max(1) as u32);
        t.push(&buf);
        serialize_result::<_, String>(Ok(t.estimate()))
    })
}

/// JNI Bridge: the tempo of an audio file or stream at `path`, as a JSON `tempo::Estimate`, or `null`.
#[cfg(feature = "engine")]
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_bpmAnalyzeNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let path = path.mutf8_chars(env)?.to_string();
        serialize_result(engine::bpm::analyze::<env::android::AndroidEnv>(&path))
    })
}

#[cfg(not(feature = "engine"))]
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_bpmAnalyzeNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    _path: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |_env| {
        serialize_result::<Option<tempo::Estimate>, String>(Err("BPM analysis requires engine feature".into()))
    })
}

/// JNI Bridge: check a Subsonic server and its credentials; answers its name and version.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_subsonicPingNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    config_json: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let raw = config_json.mutf8_chars(env)?.to_string();
        match serde_json::from_str::<subsonic::Config>(&raw) {
            Err(e) => serialize_result::<(), _>(Err(e)),
            Ok(cfg) => serialize_result(get_runtime().block_on(subsonic::ping::<env::android::AndroidEnv>(&cfg))),
        }
    })
}

/// JNI Bridge: the Subsonic server's copy of a track, as a `subsonic::Found`, or `null`.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_subsonicFindNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    config_json: JString<'local>,
    title: JString<'local>,
    artists_json: JString<'local>,
    duration_ms: jlong,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let raw = config_json.mutf8_chars(env)?.to_string();
        let title = title.mutf8_chars(env)?.to_string();
        let artists: Vec<String> = serde_json::from_str(&artists_json.mutf8_chars(env)?.to_string()).unwrap_or_default();
        match serde_json::from_str::<subsonic::Config>(&raw) {
            Err(e) => serialize_result::<(), _>(Err(e)),
            Ok(cfg) => serialize_result(get_runtime().block_on(subsonic::find::<env::android::AndroidEnv>(
                &cfg,
                &title,
                &artists,
                duration_ms,
            ))),
        }
    })
}

/// JNI Bridge: AutoEq profiles whose name holds every word of `query`, as `[{name, path, source}]`.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_autoEqSearchNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    query: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let query = query.mutf8_chars(env)?.to_string();
        serialize_result(get_runtime().block_on(autoeq::search_online::<env::android::AndroidEnv>(&query, 30)))
    })
}

/// JNI Bridge: the parametric EQ text of the AutoEq profile at `path`, as a JSON string.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_autoEqProfileNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let path = path.mutf8_chars(env)?.to_string();
        serialize_result(get_runtime().block_on(autoeq::fetch_profile::<env::android::AndroidEnv>(&path)))
    })
}

/// JNI Bridge: a parametric EQ as `n` bands `[{cutoffHz, gainDb}]` for `DynamicsProcessing`. Pure.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_autoEqBandsNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    peq_text: JString<'local>,
    n: jint,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let text = peq_text.mutf8_chars(env)?.to_string();
        serialize_result(autoeq::parse(&text).map(|peq| autoeq::bands(&peq, n.max(2) as usize)))
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

/// JNI Bridge: remember the place in a long track (4.9), or drop it once finished or barely started.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_recordTrackPlaceNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    track_id: JString<'local>,
    position_ms: jlong,
    duration_ms: jlong,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let id = track_id.mutf8_chars(env)?.to_string();
        let outcome = get_runtime().block_on(player::bookmark::record::<env::android::AndroidEnv>(
            &id,
            position_ms.max(0) as u64,
            duration_ms.max(0) as u64,
        ));
        serialize_result(outcome.map(|_| spotify::models::OperationResult {
            success: true,
            error: None,
        }))
    })
}

/// JNI Bridge: where to offer to resume a long track, or -1 when there is no place to offer.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_trackPlaceNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    track_id: JString<'local>,
) -> jlong {
    // Same shape as `streamCacheSizeNative`: an atomic carries the answer out of `with_env`.
    let place = std::sync::atomic::AtomicI64::new(-1);
    let _ = env_unowned.with_env(|env| -> jni::errors::Result<()> {
        let id = track_id.mutf8_chars(env)?.to_string();
        if let Some(p) = get_runtime().block_on(player::bookmark::get::<env::android::AndroidEnv>(&id)) {
            place.store(p as jlong, std::sync::atomic::Ordering::Relaxed);
        }
        Ok(())
    });
    place.load(std::sync::atomic::Ordering::Relaxed)
}

/// JNI Bridge: forget the place in a long track, because the person chose to start it over.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_forgetTrackPlaceNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    track_id: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let id = track_id.mutf8_chars(env)?.to_string();
        let outcome =
            get_runtime().block_on(player::bookmark::forget::<env::android::AndroidEnv>(&id));
        serialize_result(outcome.map(|_| spotify::models::OperationResult {
            success: true,
            error: None,
        }))
    })
}

/// JNI Bridge: this device's places — contexts and long tracks, with their removals — as they
/// travel in the backup (`player::places`).
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_placesExportNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |_env| {
        let block = get_runtime().block_on(player::places::export::<env::android::AndroidEnv>());
        serde_json::to_string(&block).unwrap_or_else(|_| "{}".to_string())
    })
}

/// JNI Bridge: two backups' places as one, newest per place. A block that does not parse is none.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_placesMergeNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    a: JString<'local>,
    b: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let a = a.mutf8_chars(env)?.to_string();
        let b = b.mutf8_chars(env)?.to_string();
        let merged = player::places::merge(
            serde_json::from_str(&a).unwrap_or_default(),
            serde_json::from_str(&b).unwrap_or_default(),
        );
        serde_json::to_string(&merged).unwrap_or_else(|_| "{}".to_string())
    })
}

/// JNI Bridge: takes in a backup's places, in this device's ids; answers what it did.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_placesApplyNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    block: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let block = block.mutf8_chars(env)?.to_string();
        match serde_json::from_str::<player::places::Block>(&block) {
            Err(e) => serialize_result::<(), _>(Err(e)),
            Ok(block) => serialize_result(
                get_runtime().block_on(player::places::apply::<env::android::AndroidEnv>(block)),
            ),
        }
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
            Ok(request) => register_local_stream(request),
        }
    })
}

/// The answer to a request that parsed, for `registerLocalStreamNative` and the C ABI's
/// `registerLocalStream` alike.
fn register_local_stream(request: RegisterStreamRequest) -> String {
    if request.mode == "proxy" {
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
            // Where a copy lands as it plays. Absent when the user has storing off, which is the
            // same switch that governs everything else the server does.
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
            // The server is not up, or the request was not something to proxy. Either way the
            // caller falls back to playing it the way it did before.
            None => RegisterStreamAnswer {
                status: "notCached",
                url: None,
                reason: None,
            },
        };
        return serde_json::to_string(&answer)
            .unwrap_or_else(|_| r#"{"status":"notCached"}"#.to_string());
    }

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

    let answer = match server::register::<env::android::AndroidEnv>(registration, &cache_root) {
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
    // The fallback is `notCached` and not an error object: whatever went wrong here, the caller's
    // correct move is to play the upstream URL.
    serde_json::to_string(&answer).unwrap_or_else(|_| r#"{"status":"notCached"}"#.to_string())
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

        youtube::server::get_alternative_track(&s_id).unwrap_or_default()
    })
}

/// JNI Bridge: a YouTube video with its sound, for the desktop's player. See `youtube::innertube`.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_resolveInnertubeVideoNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    video_id: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let video = video_id.mutf8_chars(env)?.to_string();
        serialize_result(get_runtime().block_on(youtube::innertube::resolve_video::<env::android::AndroidEnv>(&video)))
    })
}

/// JNI Bridge: a YouTube video's audio stream, with one request and no yt-dlp (4.1). `prefs_json` is
/// `{"prefer":"opus"|"aac"|"","maxKbps":0}`. See `youtube::innertube`.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_resolveInnertubeAudioNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    video_id: JString<'local>,
    prefs_json: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let video = video_id.mutf8_chars(env)?.to_string();
        let prefs: youtube::innertube::AudioPrefs =
            serde_json::from_str(&prefs_json.mutf8_chars(env)?.to_string()).unwrap_or_default();
        serialize_result(get_runtime().block_on(youtube::innertube::resolve_audio::<env::android::AndroidEnv>(&video, &prefs)))
    })
}

/// JNI Bridge: replace the InnerTube client table (`[]` restores the built-in one).
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_setInnertubeClientsNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    clients_json: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let raw = clients_json.mutf8_chars(env)?.to_string();
        match serde_json::from_str::<Vec<youtube::innertube::ClientProfile>>(&raw) {
            Err(e) => serialize_result::<(), _>(Err(e)),
            Ok(list) => {
                let outcome = get_runtime().block_on(youtube::innertube::set_clients::<env::android::AndroidEnv>(&list));
                serialize_result(outcome.map(|_| spotify::models::OperationResult { success: true, error: None }))
            }
        }
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
    if std::panic::catch_unwind(adblock_engine::is_ready).unwrap_or(false) { JNI_TRUE } else { JNI_FALSE }
}

/// JNI Bridge: release the compiled engine (web player closed).
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_adblockClearNative<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
) {
    let _ = std::panic::catch_unwind(adblock_engine::clear);
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
    // Outside `with_env`, so nothing would catch a panic here: one unwinding across `extern
    // "system"` aborts the process.
    let _ = std::panic::catch_unwind(|| spotify::client::get_spotify_client().logout());
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
    // Guarded for the same reason as `logoutSpotifyNative`: no `with_env` around it.
    let authenticated =
        std::panic::catch_unwind(|| spotify::client::get_spotify_client().is_authenticated())
            .unwrap_or(false);
    if authenticated {
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
                // `eprintln!` on Android goes to a stderr nobody reads, which is why a canvas that
                // stopped appearing left no trace at all. Through `Env` it reaches logcat.
                <env::android::AndroidEnv as env::Env>::log(
                    env::LogLevel::Warn,
                    "Canvas",
                    &format!("{}: {}", uri, e),
                );
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
        warmup_spotify_hashes();
        "".to_string()
    });
}

/// Starts the GQL hash scrape on the runtime and returns at once. `warmupSpotifyHashesNative` and
/// the C ABI's `warmupSpotifyHashes`.
fn warmup_spotify_hashes() {
    get_runtime().spawn(async {
        <env::android::AndroidEnv as env::Env>::log(env::LogLevel::Info, "Spotify", "Background hash warmup started...");
        // No longer borrows the client's HTTP handle first: the scraper takes the platform as a
        // type parameter, so nothing has to be cloned out of the lock to make the call.
        match spotify::client::scrape_gql_hashes::<env::android::AndroidEnv>().await {
            Ok(new_hashes) => {
                let client = spotify::client::get_spotify_client();
                client.update_gql_hashes(new_hashes);
                <env::android::AndroidEnv as env::Env>::log(env::LogLevel::Info, "Spotify", "Background hash warmup completed successfully.");
            }
            Err(e) => {
                <env::android::AndroidEnv as env::Env>::log(env::LogLevel::Info, "Spotify", &format!("Background hash warmup failed: {}", e));
            }
        }
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
// MATCHING A SPOTIFY TRACK TO A LOCAL FILE
// =============================================================================
//
// Split into register-then-ask for the same reason the search index is: a lookup runs over the whole
// local library, and a call across JNI per candidate would be slower than the Kotlin it replaces.
// Both are pure and in-memory, so both are plain calls on the Kotlin side.

/// JNI Bridge: hand the local library over to be folded once and kept.
///
/// `itemsJson` is `[{"id":…,"name":…,"artists":[…],"isrc":…,"durationMs":…}]`. Replaces whatever was
/// registered. Pure and in-memory.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_indexLocalTracksNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    items_json: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let raw = items_json.mutf8_chars(env)?.to_string();
        match serde_json::from_str::<Vec<matcher::local::Candidate>>(&raw) {
            Err(e) => serde_json::json!({ "success": false, "error": e.to_string() }).to_string(),
            Ok(items) => {
                let count = matcher::local::put(&items);
                serde_json::json!({ "success": true, "count": count }).to_string()
            }
        }
    })
}

/// JNI Bridge: the id of the local file that is this track, or `{}`.
///
/// `{}` covers both "nothing matches" and "no library registered", which are the same answer to the
/// caller: play it the way you would have anyway. Pure and in-memory.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_findLocalMatchNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    track_json: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let raw = track_json.mutf8_chars(env)?.to_string();
        match serde_json::from_str::<matcher::local::Candidate>(&raw) {
            Err(e) => serde_json::json!({ "error": e.to_string() }).to_string(),
            Ok(track) => match matcher::local::find(&track) {
                Some(id) => serde_json::json!({ "id": id }).to_string(),
                None => "{}".to_string(),
            },
        }
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
        "previous" if v.get("always").and_then(serde_json::Value::as_bool) == Some(true) => {
            Some(player::Action::PreviousTrack)
        }
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

/// JNI Bridge: Spotify's own lyrics for a track (4.3). Needs a session; without one it answers an
/// `auth` failure and the app moves on to its other lyrics providers.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_getSpotifyLyricsNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    track_id: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let id = track_id.mutf8_chars(env)?.to_string();
        serialize_result(get_runtime().block_on(spotify::client::get_spotify_client().get_lyrics(&id)))
    })
}

/// The credits of a track (4.6). Public catalogue: works without a session.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_getTrackCreditsNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    track_id: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let id = track_id.mutf8_chars(env)?.to_string();
        serialize_result(get_runtime().block_on(spotify::client::get_spotify_client().get_track_credits(&id)))
    })
}

/// Tracks that fit a playlist and are not in it (4.6). `skip_json` is a JSON array of track ids.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_extendPlaylistNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    playlist_id: JString<'local>,
    skip_json: JString<'local>,
    count: jni::sys::jint,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let id = playlist_id.mutf8_chars(env)?.to_string();
        let skip: Vec<String> = serde_json::from_str(&skip_json.mutf8_chars(env)?.to_string()).unwrap_or_default();
        serialize_result(get_runtime().block_on(
            spotify::client::get_spotify_client().extend_playlist(&id, &skip, count.clamp(1, 50) as u32),
        ))
    })
}

/// An artist page's "About" (4.7): biography, listeners, cities, concerts. No session needed.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_getArtistAboutNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    artist_id: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let id = artist_id.mutf8_chars(env)?.to_string();
        serialize_result(get_runtime().block_on(spotify::client::get_spotify_client().get_artist_about(&id)))
    })
}

// =============================================================================
// AUDIO LAB (4.7.1) — spectrum on kernels picked by the CPU's ARM extensions
// =============================================================================

/// JNI Bridge: analyse one frame of mono PCM. Answers `bands` dB values, then RMS and peak; an empty
/// array when the input is unusable (not a power of two, too short) or anything fails. `kernel` is a
/// [`dsp::Kernel`] id, or empty for the best this CPU has.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_dspAnalyzeNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    samples: jni::objects::JFloatArray<'local>,
    sample_rate: jint,
    bands: jint,
    kernel: JString<'local>,
) -> jni::sys::jfloatArray {
    let outcome = env_unowned.with_env(|env| -> jni::errors::Result<jni::sys::jfloatArray> {
        let n = samples.len(env)?;
        let mut buf = vec![0f32; n];
        samples.get_region(env, 0, &mut buf)?;
        let wanted = kernel.mutf8_chars(env)?.to_string();
        let k = dsp::Kernel::available()
            .into_iter()
            .find(|k| k.id() == wanted)
            .unwrap_or_else(dsp::Kernel::best);
        let values: Vec<f32> = if n >= 64 && n.is_power_of_two() && sample_rate > 0 {
            std::panic::catch_unwind(|| dsp::analyse(k, &buf, sample_rate as u32, bands.max(1) as usize))
                .map(|a| a.bands_db.into_iter().chain([a.rms, a.peak]).collect())
                .unwrap_or_default()
        } else {
            Vec::new()
        };
        let out = env.new_float_array(values.len())?;
        out.set_region(env, 0, &values)?;
        Ok(out.into_raw())
    });
    match outcome.into_outcome() {
        Outcome::Ok(arr) => arr,
        _ => std::ptr::null_mut(),
    }
}

/// JNI Bridge: the CPU's ARM extensions and the analysis kernels it can run, as JSON:
/// `{"features":[…],"kernels":[{"id","name"}],"best":"…","sve_bits":n?,"sme_bits":n?}` — `best` is
/// the fastest kernel measured on this CPU.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_dspInfoNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let _ = &env;
        serde_json::json!({
            "features": dsp::cpu_features(),
            "kernels": dsp::Kernel::available().iter().map(|k| serde_json::json!({"id": k.id(), "name": k.name()})).collect::<Vec<_>>(),
            "best": dsp::Kernel::best().id(),
            "sve_bits": dsp::vector_lengths().0,
            "sme_bits": dsp::vector_lengths().1,
            "mat_kernels": tonal::MatKernel::available().iter().map(|k| serde_json::json!({"id": k.id(), "name": k.name()})).collect::<Vec<_>>(),
            "mat_best": tonal::MatKernel::best().id(),
        })
        .to_string()
    })
}

/// JNI Bridge: nanoseconds per `n`-sample frame for each kernel this CPU has, as JSON
/// `[{"id","name","ns"}]` — the lab's "measure" button.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_dspBenchNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    n: jint,
    iterations: jint,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let _ = &env;
        let n = (n.max(64) as usize).next_power_of_two();
        let rows: Vec<serde_json::Value> = std::panic::catch_unwind(|| dsp::bench(n, iterations.max(1) as usize))
            .unwrap_or_default()
            .into_iter()
            .map(|(k, ns)| serde_json::json!({"id": k.id(), "name": k.name(), "ns": ns}))
            .collect();
        serde_json::Value::Array(rows).to_string()
    })
}

/// The lab's key and mel analysis, kept between calls (one lab screen at a time).
pub(crate) static TONAL: std::sync::Mutex<Option<tonal::Tonal>> = std::sync::Mutex::new(None);

/// JNI Bridge: push one frame into the key and mel analysis. Answers `[columns, key, r, chroma×12,
/// columns×52 mel dB]`: `columns` is 0, or 16 when this frame completed a batch (one matrix product);
/// `key` is 0–11 major on C…B, 12–23 minor, -1 when there is none yet; `r` its correlation. Empty
/// on unusable input. `reset` starts over (the lab opened, or the song changed).
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_dspTonalNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    samples: jni::objects::JFloatArray<'local>,
    sample_rate: jint,
    reset: jboolean,
) -> jni::sys::jfloatArray {
    let outcome = env_unowned.with_env(|env| -> jni::errors::Result<jni::sys::jfloatArray> {
        let n = samples.len(env)?;
        let mut buf = vec![0f32; n];
        samples.get_region(env, 0, &mut buf)?;
        let values: Vec<f32> = if n >= 64 && n.is_power_of_two() && sample_rate > 0 {
            let rate = sample_rate as u32;
            let mut slot = TONAL.lock().unwrap_or_else(|e| e.into_inner());
            if reset == JNI_TRUE || !slot.as_ref().is_some_and(|t| t.fits(n, rate)) {
                *slot = Some(tonal::Tonal::new(n, rate));
            }
            let t = slot.as_mut().expect("set above");
            std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| t.push(&buf, dsp::Kernel::best(), tonal::MatKernel::best())))
                .map(|u| {
                    let (key, r) = u.key.map(|(k, r)| (k as f32, r)).unwrap_or((-1.0, 0.0));
                    let mut v = vec![u.mel_db.len() as f32, key, r];
                    v.extend_from_slice(&u.chroma);
                    u.mel_db.iter().for_each(|c| v.extend_from_slice(c));
                    v
                })
                .unwrap_or_default()
        } else {
            Vec::new()
        };
        let out = env.new_float_array(values.len())?;
        out.set_region(env, 0, &values)?;
        Ok(out.into_raw())
    });
    match outcome.into_outcome() {
        Outcome::Ok(arr) => arr,
        _ => std::ptr::null_mut(),
    }
}

/// JNI Bridge: nanoseconds per 64×k by k×16 matrix product (k = bins of an `n`-point FFT) for each
/// kernel this CPU has, as JSON `[{"id","name","ns"}]`.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_dspMatBenchNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    n: jint,
    iterations: jint,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let _ = &env;
        let n = (n.max(64) as usize).next_power_of_two();
        let rows: Vec<serde_json::Value> = std::panic::catch_unwind(|| tonal::bench(n, iterations.max(1) as usize))
            .unwrap_or_default()
            .into_iter()
            .map(|(k, ns)| serde_json::json!({"id": k.id(), "name": k.name(), "ns": ns}))
            .collect();
        serde_json::Value::Array(rows).to_string()
    })
}


// =============================================================================
// DESKTOP AUDIO ENGINE (docs `24` §2) — the desktop JVM app only
// =============================================================================
//
// `com.varuna.rustify.engine.RustAudioEngine` in the shared module's desktop source set. Same JSON
// contract as every other bridge: commands in as `engine::Command`, events out as `engine::Event`.
// Nothing here blocks on the network: `send` queues, and the player thread does the work.

#[cfg(feature = "desktop-audio")]
static AUDIO_ENGINE: std::sync::Mutex<Option<std::sync::Arc<engine::Engine>>> =
    std::sync::Mutex::new(None);

/// The running engine, without holding the slot's lock — `poll` waits, and a command sent meanwhile
/// must not wait behind it.
#[cfg(feature = "desktop-audio")]
fn audio_engine() -> Option<std::sync::Arc<engine::Engine>> {
    AUDIO_ENGINE
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .clone()
}

/// JNI Bridge: opens the engine over the default output device. Idempotent.
#[cfg(feature = "desktop-audio")]
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_engine_RustAudioEngine_startNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |_env| {
        let mut slot = AUDIO_ENGINE.lock().unwrap_or_else(|e| e.into_inner());
        let started = if slot.is_some() {
            Ok(())
        } else {
            engine::Engine::start::<env::android::AndroidEnv>(Box::new(
                engine::output::CpalOutput::new(),
            ))
            .map(|started| *slot = Some(std::sync::Arc::new(started)))
        };
        serialize_result(started.map(|_| spotify::models::OperationResult {
            success: true,
            error: None,
        }))
    })
}

/// JNI Bridge: queues one `engine::Command` (JSON). Answers at once.
#[cfg(feature = "desktop-audio")]
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_engine_RustAudioEngine_sendNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    command_json: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let raw = command_json.mutf8_chars(env)?.to_string();
        match serde_json::from_str::<engine::Command>(&raw) {
            Err(e) => serialize_result::<(), _>(Err(e)),
            Ok(command) => match audio_engine() {
                Some(engine) if engine.send(command) => {
                    serialize_result::<_, String>(Ok(spotify::models::OperationResult {
                        success: true,
                        error: None,
                    }))
                }
                _ => serialize_result::<(), _>(Err("the audio engine is not running".to_string())),
            },
        }
    })
}

/// JNI Bridge: the next `engine::Event` as JSON, or `null` after `timeout_ms` with none. Blocks for
/// up to that long — an IO thread's call, never the UI's.
#[cfg(feature = "desktop-audio")]
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_engine_RustAudioEngine_pollNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    timeout_ms: jint,
) -> jstring {
    jni_bridge!(env_unowned, |_env| {
        let timeout = std::time::Duration::from_millis(timeout_ms.max(0) as u64);
        match audio_engine().and_then(|engine| engine.poll(timeout)) {
            Some(event) => serde_json::to_string(&event).unwrap_or_else(|_| "null".to_string()),
            None => "null".to_string(),
        }
    })
}

/// JNI Bridge: the name of the output the engine plays through, as a JSON string, or `null`.
#[cfg(feature = "desktop-audio")]
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_engine_RustAudioEngine_outputDeviceNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |_env| {
        serde_json::to_string(&engine::output::default_device_name())
            .unwrap_or_else(|_| "null".to_string())
    })
}

/// JNI Bridge: stops the engine and releases the device.
#[cfg(feature = "desktop-audio")]
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_engine_RustAudioEngine_stopNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |_env| {
        let taken = AUDIO_ENGINE.lock().unwrap_or_else(|e| e.into_inner()).take();
        drop(taken);
        serialize_result::<_, String>(Ok(spotify::models::OperationResult {
            success: true,
            error: None,
        }))
    })
}

/// JNI Bridge: latest mono audio samples (`{"sampleRate":44100,"samples":[...]}`).
#[cfg(feature = "desktop-audio")]
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_engineTapNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    n: jint,
) -> jstring {
    jni_bridge!(env_unowned, |_env| {
        let (sample_rate, samples) = engine::output::engine_tap((n.max(0)) as usize);
        serde_json::to_string(&serde_json::json!({
            "sampleRate": sample_rate,
            "samples": samples,
        }))
        .unwrap_or_else(|_| "{\"sampleRate\":0,\"samples\":[]}".to_string())
    })
}

#[cfg(not(feature = "desktop-audio"))]
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_engineTapNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    _n: jint,
) -> jstring {
    jni_bridge!(env_unowned, |_env| {
        "{\"sampleRate\":0,\"samples\":[]}".to_string()
    })
}


// =============================================================================
// SYSTEM MEDIA CONTROLS (docs `25` T2.2) — the desktop JVM app only
// =============================================================================
//
// `com.varuna.rustify.engine.SystemMediaControls`. Updates in as `media_controls::Update`, the
// system's requests out as `media_controls::Request`, polled like the engine's events.

#[cfg(feature = "media-controls")]
static MEDIA_CONTROLS: std::sync::Mutex<Option<std::sync::Arc<media_controls::Controls>>> =
    std::sync::Mutex::new(None);

#[cfg(feature = "media-controls")]
fn media_controls() -> Option<std::sync::Arc<media_controls::Controls>> {
    MEDIA_CONTROLS
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .clone()
}

/// JNI Bridge: registers with the system's media controls. `hwnd` is the window handle on Windows
/// (0 elsewhere). Idempotent.
#[cfg(feature = "media-controls")]
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_engine_SystemMediaControls_startNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    display_name: JString<'local>,
    hwnd: jlong,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let name = display_name.mutf8_chars(env)?.to_string();
        let mut slot = MEDIA_CONTROLS.lock().unwrap_or_else(|e| e.into_inner());
        let started = if slot.is_some() {
            Ok(())
        } else {
            let handle = if hwnd != 0 { Some(hwnd as usize) } else { None };
            media_controls::Controls::start(&name, handle)
                .map(|controls| *slot = Some(std::sync::Arc::new(controls)))
        };
        serialize_result(started.map(|_| spotify::models::OperationResult {
            success: true,
            error: None,
        }))
    })
}

/// JNI Bridge: one `media_controls::Update` (JSON). Queues it and answers at once.
#[cfg(feature = "media-controls")]
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_engine_SystemMediaControls_updateNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    update_json: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let raw = update_json.mutf8_chars(env)?.to_string();
        match serde_json::from_str::<media_controls::Update>(&raw) {
            Err(e) => serialize_result::<(), _>(Err(e)),
            Ok(update) => match media_controls() {
                Some(controls) if controls.update(update) => {
                    serialize_result::<_, String>(Ok(spotify::models::OperationResult {
                        success: true,
                        error: None,
                    }))
                }
                _ => serialize_result::<(), _>(Err("media controls are not running".to_string())),
            },
        }
    })
}

/// JNI Bridge: the next `media_controls::Request` as JSON, or `null` after `timeout_ms`.
#[cfg(feature = "media-controls")]
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_engine_SystemMediaControls_pollNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    timeout_ms: jint,
) -> jstring {
    jni_bridge!(env_unowned, |_env| {
        let timeout = std::time::Duration::from_millis(timeout_ms.max(0) as u64);
        match media_controls().and_then(|controls| controls.poll(timeout)) {
            Some(request) => serde_json::to_string(&request).unwrap_or_else(|_| "null".to_string()),
            None => "null".to_string(),
        }
    })
}

/// JNI Bridge: unregisters from the system's media controls.
#[cfg(feature = "media-controls")]
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_engine_SystemMediaControls_stopNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |_env| {
        let taken = MEDIA_CONTROLS.lock().unwrap_or_else(|e| e.into_inner()).take();
        drop(taken);
        serialize_result::<_, String>(Ok(spotify::models::OperationResult {
            success: true,
            error: None,
        }))
    })
}


// =============================================================================
// SECRETS (docs `25` T2.3) — the desktop JVM app only
// =============================================================================
//
// `com.varuna.rustify.platform.SecretStore`. Each answers the usual JSON: the value as a JSON string
// (or `null`), or `{"success":false,…}` when there is no credential store to use.

/// JNI Bridge: the secret under `key`.
#[cfg(feature = "secure-store")]
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_platform_SecretStore_getNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    key: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let key = key.mutf8_chars(env)?.to_string();
        serialize_result(secrets::get(&key))
    })
}

/// JNI Bridge: stores `value` under `key`.
#[cfg(feature = "secure-store")]
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_platform_SecretStore_setNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    key: JString<'local>,
    value: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let key = key.mutf8_chars(env)?.to_string();
        let value = value.mutf8_chars(env)?.to_string();
        serialize_result(secrets::set(&key, &value).map(|_| spotify::models::OperationResult {
            success: true,
            error: None,
        }))
    })
}

/// JNI Bridge: removes the secret under `key`.
#[cfg(feature = "secure-store")]
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_platform_SecretStore_removeNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    key: JString<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let key = key.mutf8_chars(env)?.to_string();
        serialize_result(secrets::remove(&key).map(|_| spotify::models::OperationResult {
            success: true,
            error: None,
        }))
    })
}


/// JNI Bridge: paints the desktop window's frame in the app's colours (a dark title bar on
/// Windows; nothing elsewhere). `hwnd` is the window's native handle. Answers whether it took.
#[cfg(feature = "window-style")]
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_engine_WindowStyle_darkFrameNative<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
    hwnd: jlong,
) -> jni::sys::jboolean {
    window_style::dark_frame(hwnd as isize)
}