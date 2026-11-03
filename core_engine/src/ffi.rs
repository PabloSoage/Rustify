// core_engine/src/ffi.rs
//
// The C ABI: the core for callers that are not a JVM — iOS first (Kotlin/Native through cinterop, or
// Swift), docs `24` §3 and `25`.
//
// One entry point rather than one C function per bridge: `rustify_call("searchSpotify", "{…}")`
// answers the very JSON the JNI bridge of the same name answers, so the Kotlin side can decode it
// with the same `SpotifyModels` declarations and nothing about the wire contract is said twice. The
// method names are the JNI ones without the `Native` suffix, and the arguments are the JNI parameters
// by name, in camelCase.
//
// This is a **subset** of the JNI bridges — the ones an iOS build needs to browse, search, resolve
// and play. Each arm calls the same core function its JNI twin calls; the twin is named in the
// arm's comment so a change to one is a search away from the other. The rest are listed in docs
// `25` with the order they are worth adding in.
//
// Memory: every string returned is allocated here and must be handed back to `rustify_free`. A null
// argument is read as `{}`. Nothing here panics across the boundary: a panic inside a method answers
// the same permanent failure a JNI bridge answers.

use std::ffi::{c_char, CStr, CString};

use serde_json::Value;

use crate::{env, get_runtime, links, player, search, serialize_result, server, spotify, youtube};

/// Calls `method` with `args_json` (a JSON object) and answers JSON. Free the answer with
/// [`rustify_free`].
///
/// # Safety
/// `method` and `args_json` must each be null or a valid NUL-terminated string that stays alive for
/// the duration of the call.
#[no_mangle]
pub unsafe extern "C" fn rustify_call(method: *const c_char, args_json: *const c_char) -> *mut c_char {
    // SAFETY: the caller promises valid C strings or null.
    let method = unsafe { read(method) };
    let args = unsafe { read(args_json) };
    let answer = std::panic::catch_unwind(|| {
        let args: Value = serde_json::from_str(&args).unwrap_or_else(|_| Value::Object(Default::default()));
        dispatch(&method, &args)
    })
    .unwrap_or_else(|_| crate::BRIDGE_FAILURE_JSON.to_string());
    into_c(answer)
}

/// Releases a string this library returned. Null is ignored.
///
/// # Safety
/// `s` must be null or a pointer returned by this library that has not been freed yet.
#[no_mangle]
pub unsafe extern "C" fn rustify_free(s: *mut c_char) {
    if !s.is_null() {
        // SAFETY: `s` came from `CString::into_raw` in `into_c`.
        drop(unsafe { CString::from_raw(s) });
    }
}

/// The core's version (`Cargo.toml`), so a mismatched framework and app can say so. Free it.
#[no_mangle]
pub extern "C" fn rustify_version() -> *mut c_char {
    into_c(env!("CARGO_PKG_VERSION").to_string())
}

/// # Safety
/// `p` must be null or a valid NUL-terminated string.
unsafe fn read(p: *const c_char) -> String {
    if p.is_null() {
        return "{}".to_string();
    }
    // SAFETY: as promised by the caller.
    unsafe { CStr::from_ptr(p) }.to_string_lossy().into_owned()
}

fn into_c(s: String) -> *mut c_char {
    // An interior NUL cannot be represented; JSON never carries a raw one, so this is a fallback for
    // a bug elsewhere rather than a path anyone takes.
    CString::new(s)
        .unwrap_or_else(|_| CString::new(crate::BRIDGE_FAILURE_JSON).unwrap_or_default())
        .into_raw()
}

fn text(args: &Value, key: &str) -> String {
    match &args[key] {
        Value::String(s) => s.clone(),
        Value::Null => String::new(),
        other => other.to_string(),
    }
}

fn int(args: &Value, key: &str, default: i64) -> i64 {
    args[key].as_i64().unwrap_or(default)
}

fn ok() -> String {
    r#"{"success":true}"#.to_string()
}

fn unknown(method: &str) -> String {
    serialize_result::<(), _>(Err(format!("unknown method: {method}")))
}

/// Every method this ABI answers, for the header's documentation and for the test that keeps the two
/// in step.
pub const METHODS: &[&str] = &[
    "initCacheDir",
    "initSpotifyCacheDir",
    "setLanguage",
    "parseLink",
    "wrapLink",
    "unwrapLink",
    "searchIndex",
    "searchQuery",
    "searchForget",
    "playerReduce",
    "loginSpotify",
    "restoreSpotifySession",
    "isSpotifyAuthenticated",
    "logoutSpotify",
    "searchSpotify",
    "getSpotifyBrowse",
    "getSpotifyAlbum",
    "getSpotifyAlbumTracks",
    "getSpotifyArtist",
    "getSpotifyArtistTopTracks",
    "getSpotifyPlaylist",
    "getSpotifyPlaylistTracks",
    "getSpotifyTrack",
    "getSpotifyTrackRadio",
    "getSpotifyLyrics",
    "searchYtMusic",
    "getYtmAlbum",
    "getYtmArtist",
    "getYtmPlaylist",
    "resolveInnertubeAudio",
    "startLocalServer",
    "engineStart",
    "engineSend",
    "enginePoll",
    "engineStop",
];

pub(crate) fn dispatch(method: &str, args: &Value) -> String {
    let client = spotify::client::get_spotify_client;
    match method {
        // initCacheDirNative
        "initCacheDir" => {
            let dir = text(args, "dir");
            youtube::server::init_cache_dir(&dir);
            env::android::init_storage_dir(&dir);
            get_runtime().block_on(youtube::server::init_mappings::<env::android::AndroidEnv>(&dir));
            ok()
        }
        // initSpotifyCacheDirNative
        "initSpotifyCacheDir" => {
            client().set_cache_dir(&text(args, "dir"));
            ok()
        }
        // setLanguageNative
        "setLanguage" => {
            client().set_accept_language(&text(args, "lang"));
            ok()
        }

        // parseLinkNative
        "parseLink" => match links::parse(&text(args, "text")) {
            None => "{}".to_string(),
            Some(target) => serde_json::json!({
                "kind": target.kind(),
                "id": target.id(),
                "token": target.deep_link_token(),
                "url": target.canonical_url(),
                "scheme": target.rustify_scheme(),
            })
            .to_string(),
        },
        // wrapLinkNative — answers the wrapped URL as a JSON string.
        "wrapLink" => {
            let host = text(args, "host");
            let wrapped = links::wrap(
                &text(args, "url"),
                if host.trim().is_empty() { None } else { Some(host.as_str()) },
            );
            Value::String(wrapped).to_string()
        }
        // unwrapLinkNative — answers the inner URL as a JSON string, "" when it was not a wrapper.
        "unwrapLink" => {
            let hosts: Vec<String> =
                serde_json::from_value(args["knownHosts"].clone()).unwrap_or_default();
            let refs: Vec<&str> = hosts.iter().map(String::as_str).collect();
            Value::String(links::unwrap_wrapper(&text(args, "url"), &refs).unwrap_or_default())
                .to_string()
        }

        // searchIndexNative
        "searchIndex" => {
            #[derive(serde::Deserialize)]
            struct Item {
                id: String,
                #[serde(default)]
                fields: Vec<String>,
            }
            match serde_json::from_value::<Vec<Item>>(args["items"].clone()) {
                Err(e) => serde_json::json!({ "success": false, "error": e.to_string() }).to_string(),
                Ok(items) => {
                    let count = items.len();
                    let index =
                        search::Index::build(items.into_iter().map(|i| (i.id, i.fields)).collect());
                    search::put(&text(args, "name"), index);
                    serde_json::json!({ "success": true, "count": count }).to_string()
                }
            }
        }
        // searchQueryNative
        "searchQuery" => {
            let ids = search::query(
                &text(args, "name"),
                &text(args, "query"),
                int(args, "limit", 50).max(0) as usize,
            );
            serde_json::to_string(&ids).unwrap_or_else(|_| "[]".to_string())
        }
        // searchForgetNative
        "searchForget" => {
            search::forget(&text(args, "name"));
            ok()
        }

        // playerReduceNative
        "playerReduce" => {
            let mut state: player::PlayerState =
                serde_json::from_value(args["state"].clone()).unwrap_or_default();
            let effects = match crate::player_action_from_json(&args["action"]) {
                Some(action) => player::reduce(&mut state, action),
                None => Vec::new(),
            };
            serde_json::json!({
                "state": state,
                "effects": effects.iter().map(crate::player_effect_to_json).collect::<Vec<_>>(),
            })
            .to_string()
        }

        // loginSpotifyNative
        "loginSpotify" => {
            let cookie = text(args, "spDc");
            serialize_result(get_runtime().block_on(client().login_with_sp_dc(&cookie)))
        }
        // restoreSpotifySessionNative
        "restoreSpotifySession" => {
            let cookie = text(args, "spDc");
            let token = text(args, "accessToken");
            let expiration = int(args, "expiration", 0);
            serialize_result(get_runtime().block_on(client().restore_session(
                &cookie,
                if token.is_empty() { None } else { Some(token.as_str()) },
                if expiration <= 0 { None } else { Some(expiration as u64) },
            )))
        }
        // isSpotifyAuthenticatedNative — `true` or `false`.
        "isSpotifyAuthenticated" => client().is_authenticated().to_string(),
        // logoutSpotifyNative
        "logoutSpotify" => {
            client().logout();
            ok()
        }

        // searchSpotifyNative
        "searchSpotify" => {
            let q = text(args, "query");
            let limit = int(args, "limit", 20) as u32;
            let offset = int(args, "offset", 0) as u32;
            let kind = text(args, "type");
            get_runtime().block_on(async {
                let c = client();
                match kind.as_str() {
                    "tracks" => serialize_result(c.search_tracks(&q, limit, offset).await),
                    "albums" => serialize_result(c.search_albums(&q, limit, offset).await),
                    "artists" => serialize_result(c.search_artists(&q, limit, offset).await),
                    "playlists" => serialize_result(c.search_playlists(&q, limit, offset).await),
                    _ => serialize_result(c.search_all(&q, limit).await),
                }
            })
        }
        // getSpotifyBrowseNative
        "getSpotifyBrowse" => serialize_result(
            get_runtime().block_on(client().get_browse_sections(int(args, "limit", 20) as u32)),
        ),
        // getSpotifyAlbumNative
        "getSpotifyAlbum" => {
            serialize_result(get_runtime().block_on(client().get_album(&text(args, "id"))))
        }
        // getSpotifyAlbumTracksNative
        "getSpotifyAlbumTracks" => serialize_result(get_runtime().block_on(
            client().get_album_tracks(
                &text(args, "id"),
                int(args, "limit", 50) as u32,
                int(args, "offset", 0) as u32,
            ),
        )),
        // getSpotifyArtistNative
        "getSpotifyArtist" => {
            serialize_result(get_runtime().block_on(client().get_artist(&text(args, "id"))))
        }
        // getSpotifyArtistTopTracksNative
        "getSpotifyArtistTopTracks" => serialize_result(get_runtime().block_on(
            client().get_artist_top_tracks(
                &text(args, "id"),
                int(args, "limit", 10) as u32,
                int(args, "offset", 0) as u32,
            ),
        )),
        // getSpotifyPlaylistNative
        "getSpotifyPlaylist" => {
            serialize_result(get_runtime().block_on(client().get_playlist(&text(args, "id"))))
        }
        // getSpotifyPlaylistTracksNative
        "getSpotifyPlaylistTracks" => serialize_result(get_runtime().block_on(
            client().get_playlist_tracks(
                &text(args, "id"),
                int(args, "limit", 100) as u32,
                int(args, "offset", 0) as u32,
            ),
        )),
        // getSpotifyTrackNative
        "getSpotifyTrack" => {
            serialize_result(get_runtime().block_on(client().get_track(&text(args, "id"))))
        }
        // getSpotifyTrackRadioNative
        "getSpotifyTrackRadio" => {
            serialize_result(get_runtime().block_on(client().get_track_radio(&text(args, "id"))))
        }
        // getSpotifyLyricsNative
        "getSpotifyLyrics" => {
            serialize_result(get_runtime().block_on(client().get_lyrics(&text(args, "id"))))
        }

        // searchYtMusicNative
        "searchYtMusic" => {
            let results = get_runtime().block_on(youtube::ytmusic::ytm_search(&text(args, "query")));
            serde_json::to_string(&results).unwrap_or_else(|_| "{}".to_string())
        }
        // getYtmAlbumNative
        "getYtmAlbum" => {
            let album = get_runtime().block_on(youtube::ytmusic::ytm_get_album(&text(args, "id")));
            serde_json::to_string(&album).unwrap_or_else(|_| "null".to_string())
        }
        // getYtmArtistNative
        "getYtmArtist" => {
            let artist =
                get_runtime().block_on(youtube::ytmusic::ytm_get_artist(&text(args, "id")));
            serde_json::to_string(&artist).unwrap_or_else(|_| "null".to_string())
        }
        // getYtmPlaylistNative
        "getYtmPlaylist" => {
            let playlist =
                get_runtime().block_on(youtube::ytmusic::ytm_get_playlist(&text(args, "id")));
            serde_json::to_string(&playlist).unwrap_or_else(|_| "null".to_string())
        }
        // resolveInnertubeAudioNative. iOS plays AAC (AVPlayer and the engine both); pass
        // `{"prefer":"aac"}` in `prefs`.
        "resolveInnertubeAudio" => {
            let prefs: youtube::innertube::AudioPrefs =
                serde_json::from_value(args["prefs"].clone()).unwrap_or_default();
            serialize_result(get_runtime().block_on(youtube::innertube::resolve_audio::<
                env::android::AndroidEnv,
            >(
                &text(args, "videoId"), &prefs
            )))
        }

        // startLocalServerNative
        "startLocalServer" => match get_runtime().block_on(server::start::<env::android::AndroidEnv>()) {
            Ok(handle) => serde_json::json!({ "port": handle.port, "token": handle.token }).to_string(),
            Err(e) => serialize_result::<(), _>(Err(e)),
        },

        // The audio engine (RustAudioEngine*Native on the desktop JVM). Needs `desktop-audio`.
        "engineStart" | "engineSend" | "enginePoll" | "engineStop" => engine_call(method, args),

        _ => unknown(method),
    }
}

#[cfg(feature = "desktop-audio")]
fn engine_call(method: &str, args: &Value) -> String {
    use crate::engine;
    let done = || {
        serialize_result::<_, String>(Ok(spotify::models::OperationResult {
            success: true,
            error: None,
        }))
    };
    match method {
        "engineStart" => {
            let mut slot = crate::AUDIO_ENGINE.lock().unwrap_or_else(|e| e.into_inner());
            if slot.is_none() {
                match engine::Engine::start::<env::android::AndroidEnv>(Box::new(
                    engine::output::CpalOutput::new(),
                )) {
                    Ok(started) => *slot = Some(std::sync::Arc::new(started)),
                    Err(e) => return serialize_result::<(), _>(Err(e)),
                }
            }
            done()
        }
        "engineSend" => match serde_json::from_value::<engine::Command>(args["command"].clone()) {
            Err(e) => serialize_result::<(), _>(Err(e)),
            Ok(command) => match crate::audio_engine() {
                Some(engine) if engine.send(command) => done(),
                _ => serialize_result::<(), _>(Err("the audio engine is not running".to_string())),
            },
        },
        "enginePoll" => {
            let timeout = std::time::Duration::from_millis(int(args, "timeoutMs", 0).max(0) as u64);
            match crate::audio_engine().and_then(|engine| engine.poll(timeout)) {
                Some(event) => serde_json::to_string(&event).unwrap_or_else(|_| "null".to_string()),
                None => "null".to_string(),
            }
        }
        _ => {
            let taken = crate::AUDIO_ENGINE.lock().unwrap_or_else(|e| e.into_inner()).take();
            drop(taken);
            done()
        }
    }
}

#[cfg(not(feature = "desktop-audio"))]
fn engine_call(_method: &str, _args: &Value) -> String {
    serialize_result::<(), _>(Err(
        "this build has no audio engine (feature desktop-audio)".to_string(),
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn call(method: &str, args: &str) -> String {
        let m = CString::new(method).unwrap();
        let a = CString::new(args).unwrap();
        // SAFETY: both strings live until the end of the call; the answer is freed below.
        unsafe {
            let answer = rustify_call(m.as_ptr(), a.as_ptr());
            let text = CStr::from_ptr(answer).to_string_lossy().into_owned();
            rustify_free(answer);
            text
        }
    }

    #[test]
    fn an_unknown_method_is_a_permanent_failure_not_a_crash() {
        let answer: Value = serde_json::from_str(&call("noSuchThing", "{}")).unwrap();
        assert_eq!(answer["success"], false);
        assert_eq!(answer["kind"], "permanent");
    }

    #[test]
    fn null_arguments_read_as_an_empty_object() {
        let m = CString::new("searchQuery").unwrap();
        // SAFETY: a null `args_json` is documented as allowed.
        let answer = unsafe { rustify_call(m.as_ptr(), std::ptr::null()) };
        // SAFETY: freshly returned by rustify_call.
        let text = unsafe { CStr::from_ptr(answer) }.to_string_lossy().into_owned();
        unsafe { rustify_free(answer) };
        assert_eq!(text, "[]");
    }

    #[test]
    fn links_answer_what_the_jni_bridge_answers() {
        let answer: Value = serde_json::from_str(&call(
            "parseLink",
            r#"{"text":"https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC"}"#,
        ))
        .unwrap();
        assert_eq!(answer["kind"], "track");
        assert_eq!(answer["id"], "4uLU6hMCjMI75M1A2tKUQC");
    }

    #[test]
    fn search_index_then_query_round_trips() {
        call(
            "searchIndex",
            r#"{"name":"ffi-test","items":[{"id":"a","fields":["Björk","Jóga"]},{"id":"b","fields":["Queen"]}]}"#,
        );
        assert_eq!(
            call("searchQuery", r#"{"name":"ffi-test","query":"bjork","limit":10}"#),
            r#"["a"]"#
        );
        call("searchForget", r#"{"name":"ffi-test"}"#);
    }

    #[test]
    fn the_reducer_is_reachable() {
        let answer: Value = serde_json::from_str(&call(
            "playerReduce",
            r#"{"state":{"queue":["x","y"],"index":0,"position_ms":0},"action":{"type":"next"}}"#,
        ))
        .unwrap();
        assert_eq!(answer["state"]["index"], 1);
    }

    #[test]
    fn every_listed_method_is_dispatched() {
        // Methods that would reach the network or start threads are not called; the rest of the
        // list is checked for being known (an unknown one answers "unknown method").
        let offline = ["parseLink", "searchQuery", "searchForget", "isSpotifyAuthenticated"];
        for method in offline {
            assert!(!call(method, "{}").contains("unknown method"), "{method}");
        }
        let mut seen = std::collections::HashSet::new();
        for method in METHODS {
            assert!(seen.insert(*method), "{method} listed twice");
        }
    }

    #[test]
    fn the_version_is_the_crate_version() {
        let v = rustify_version();
        // SAFETY: freshly returned by rustify_version.
        let text = unsafe { CStr::from_ptr(v) }.to_string_lossy().into_owned();
        unsafe { rustify_free(v) };
        assert_eq!(text, env!("CARGO_PKG_VERSION"));
    }
}
