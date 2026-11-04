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
// It answers every `NativeEngine` bridge an iOS build has a use for, so the shared screens can reach
// the same core there, plus the desktop's audio engine (`engine*`) and credential store
// (`secret*`). Each arm calls the same core function its JNI twin calls; the twin is named in the
// arm's comment so a change to one is a search away from the other. Where the twin answers
// something that is not JSON, this answers it as JSON: a bare string as a JSON string, a `long` as
// a number, a `boolean` as `true`/`false`, nothing as `{"success":true}`. Left out on purpose:
//
// - `adblock*` (LoadRules, Matches, IsReady, Clear): they filter the requests of the in-app Spotify
//   Web Player, which is an Android WebView.
// - `dsp*` (Analyze, Info, Bench, Tonal, MatBench): the audio lab, which measures the kernels picked
//   by the CPU's ARM extensions. Those exist only in Android builds (`rustify_arm_kernels`), and the
//   lab's frames come from the phone's audio session.
// - `SystemMediaControls*`: the desktop's media controls (feature `media-controls`, not in the iOS
//   build). On iOS, Now Playing and the remote commands are MPNowPlayingInfoCenter and
//   MPRemoteCommandCenter, which the app drives itself.
// - `WindowStyle.darkFrame`: a desktop window's title bar.
//
// Memory: every string returned is allocated here and must be handed back to `rustify_free`. A null
// argument is read as `{}`. Nothing here panics across the boundary: a panic inside a method answers
// the same permanent failure a JNI bridge answers.

use std::ffi::{c_char, CStr, CString};

use serde_json::Value;

use crate::{
    addon, audio, autoeq, calendar, env, export, get_runtime, jam, links, listened, matcher, metrics,
    player, scrobble, search, serialize_result, server, spotify, subsonic, tempo, youtube,
};

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

fn flag(args: &Value, key: &str) -> bool {
    args[key].as_bool().unwrap_or(false)
}

/// A JSON array of strings, where the JNI twin takes the same array as a string of JSON.
fn strings(args: &Value, key: &str) -> Vec<String> {
    serde_json::from_value(args[key].clone()).unwrap_or_default()
}

fn ok() -> String {
    r#"{"success":true}"#.to_string()
}

/// What the Spotify writes' JNI twins answer, built the way they build it: `{"success":true}` or
/// `{"success":false,"error":"…"}`, with no `kind`.
fn operation<T, E: std::fmt::Display>(result: Result<T, E>) -> String {
    let answer = spotify::models::OperationResult {
        success: result.is_ok(),
        error: result.err().map(|e| e.to_string()),
    };
    serde_json::to_string(&answer).unwrap_or_else(|_| r#"{"success": false}"#.to_string())
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
    "indexLocalTracks",
    "findLocalMatch",
    "playerReduce",
    "arrangeReleases",
    "recordListening",
    "listContinueListening",
    "forgetListening",
    "recordTrackPlace",
    "trackPlace",
    "forgetTrackPlace",
    "markListened",
    "listenedState",
    "forgetListened",
    "recordPlay",
    "importPlayHistory",
    "allPlays",
    "playStats",
    "topCsv",
    "exportData",
    "restoreData",
    "listenBrainzSubmit",
    "listenBrainzPlayingNow",
    "listenBrainzValidate",
    "lastFmLogin",
    "lastFmSubmit",
    "lastFmNowPlaying",
    "readReplayGain",
    "readEmbeddedLyrics",
    "readTags",
    "readCover",
    "writeTags",
    "tempoOf",
    "autoEqSearch",
    "autoEqProfile",
    "autoEqBands",
    "subsonicPing",
    "subsonicFind",
    "loginSpotify",
    "restoreSpotifySession",
    "refreshSpotifyToken",
    "isSpotifyAuthenticated",
    "logoutSpotify",
    "warmupSpotifyHashes",
    "getSpotifyHashes",
    "getSpotifyMe",
    "getSpotifySavedTracks",
    "getSpotifySavedAlbums",
    "getSpotifySavedPlaylists",
    "getSpotifyFollowedArtists",
    "searchSpotify",
    "getSpotifyBrowse",
    "getSpotifyAlbum",
    "getSpotifyAlbumTracks",
    "getSpotifyNewReleases",
    "saveSpotifyAlbums",
    "unsaveSpotifyAlbums",
    "getSpotifyArtist",
    "getSpotifyArtistTopTracks",
    "getSpotifyArtistAlbums",
    "getSpotifyRelatedArtists",
    "followSpotifyArtists",
    "unfollowSpotifyArtists",
    "getArtistAbout",
    "getSpotifyPlaylist",
    "getSpotifyPlaylistTracks",
    "createSpotifyPlaylist",
    "updateSpotifyPlaylist",
    "addTracksToPlaylist",
    "removeTracksFromPlaylist",
    "followPlaylist",
    "unfollowPlaylist",
    "extendPlaylist",
    "getSpotifyTrack",
    "saveSpotifyTracks",
    "unsaveSpotifyTracks",
    "getSpotifyTrackRadio",
    "getSpotifyLyrics",
    "getTrackCredits",
    "getSpotifyCanvas",
    "searchYtMusic",
    "getYtmAlbum",
    "getYtmArtist",
    "getYtmPlaylist",
    "searchYouTube",
    "resolveInnertubeAudio",
    "setInnertubeClients",
    "registerTrackMetadata",
    "resolveYouTubeId",
    "setAlternativeTrack",
    "getAlternativeTrack",
    "updateQueue",
    "installAddon",
    "listAddons",
    "uninstallAddon",
    "setAddonEnabled",
    "reorderAddons",
    "resolveViaAddon",
    "jamNewCode",
    "jamJoin",
    "jamPoll",
    "jamPublishState",
    "jamSend",
    "jamKick",
    "jamLeave",
    "startLocalServer",
    "registerLocalStream",
    "forgetLocalStream",
    "streamCacheSize",
    "clearStreamCache",
    "openCastListener",
    "closeCastListener",
    "castUrlFor",
    "castSession",
    "engineStart",
    "engineSend",
    "enginePoll",
    "engineStop",
    "secretGet",
    "secretSet",
    "secretRemove",
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

        // indexLocalTracksNative — `items` is `[{id, name, artists, isrc, durationMs}]`.
        "indexLocalTracks" => {
            match serde_json::from_value::<Vec<matcher::local::Candidate>>(args["items"].clone()) {
                Err(e) => serde_json::json!({ "success": false, "error": e.to_string() }).to_string(),
                Ok(items) => {
                    let count = matcher::local::put(&items);
                    serde_json::json!({ "success": true, "count": count }).to_string()
                }
            }
        }
        // findLocalMatchNative — `track` is a `matcher::local::Candidate`; `{"id":…}` or `{}`.
        "findLocalMatch" => {
            match serde_json::from_value::<matcher::local::Candidate>(args["track"].clone()) {
                Err(e) => serde_json::json!({ "error": e.to_string() }).to_string(),
                Ok(track) => match matcher::local::find(&track) {
                    Some(id) => serde_json::json!({ "id": id }).to_string(),
                    None => "{}".to_string(),
                },
            }
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

        // arrangeReleasesNative — `entries` is `[{id, release_date, release_date_precision}]`.
        "arrangeReleases" => match serde_json::from_value::<Vec<calendar::Entry>>(args["entries"].clone()) {
            Err(_) => "[]".to_string(),
            Ok(entries) => serde_json::to_string(&calendar::arrange(&entries, int(args, "nowMs", 0)))
                .unwrap_or_else(|_| "[]".to_string()),
        },

        // recordListeningNative — `session` is a `player::session::Session`.
        "recordListening" => match serde_json::from_value::<player::session::Session>(args["session"].clone()) {
            Err(e) => serialize_result::<(), _>(Err(e)),
            Ok(session) => serialize_result(
                get_runtime()
                    .block_on(player::session::record::<env::android::AndroidEnv>(session))
                    .map(|_| spotify::models::OperationResult {
                        success: true,
                        error: None,
                    }),
            ),
        },
        // listContinueListeningNative — newest first.
        "listContinueListening" => {
            let sessions = get_runtime().block_on(player::session::list::<env::android::AndroidEnv>());
            serde_json::to_string(&sessions).unwrap_or_else(|_| "[]".to_string())
        }
        // forgetListeningNative — an empty `id` forgets every context.
        "forgetListening" => {
            let id = text(args, "id");
            let outcome = get_runtime().block_on(async {
                if id.is_empty() {
                    player::session::clear::<env::android::AndroidEnv>().await
                } else {
                    player::session::forget::<env::android::AndroidEnv>(&id).await
                }
            });
            serialize_result(outcome.map(|_| crate::ok_result()))
        }
        // recordTrackPlaceNative
        "recordTrackPlace" => serialize_result(
            get_runtime()
                .block_on(player::bookmark::record::<env::android::AndroidEnv>(
                    &text(args, "trackId"),
                    int(args, "positionMs", 0).max(0) as u64,
                    int(args, "durationMs", 0).max(0) as u64,
                ))
                .map(|_| crate::ok_result()),
        ),
        // trackPlaceNative — the place in ms as a JSON number, -1 when there is none to offer.
        "trackPlace" => get_runtime()
            .block_on(player::bookmark::get::<env::android::AndroidEnv>(&text(args, "trackId")))
            .map_or(-1, |place| place as i64)
            .to_string(),
        // forgetTrackPlaceNative
        "forgetTrackPlace" => serialize_result(
            get_runtime()
                .block_on(player::bookmark::forget::<env::android::AndroidEnv>(&text(args, "trackId")))
                .map(|_| crate::ok_result()),
        ),
        // markListenedNative — `queue` is the context's track ids in order, sent every time.
        "markListened" => serialize_result(
            get_runtime()
                .block_on(listened::mark::<env::android::AndroidEnv>(
                    &text(args, "contextId"),
                    &strings(args, "queue"),
                    &text(args, "trackId"),
                ))
                .map(|_| crate::ok_result()),
        ),
        // listenedStateNative — one boolean per position in `queue`.
        "listenedState" => {
            let marks = get_runtime().block_on(listened::state::<env::android::AndroidEnv>(
                &text(args, "contextId"),
                &strings(args, "queue"),
            ));
            serde_json::to_string(&marks).unwrap_or_else(|_| "[]".to_string())
        }
        // forgetListenedNative — an empty `contextId` forgets every context's marks.
        "forgetListened" => {
            let context = text(args, "contextId");
            let outcome = get_runtime().block_on(async {
                if context.is_empty() {
                    listened::clear::<env::android::AndroidEnv>().await
                } else {
                    listened::forget::<env::android::AndroidEnv>(&context).await
                }
            });
            serialize_result(outcome.map(|_| crate::ok_result()))
        }
        // recordPlayNative — `event` is a `metrics::Event`.
        "recordPlay" => match serde_json::from_value::<metrics::Event>(args["event"].clone()) {
            Err(e) => serialize_result::<(), _>(Err(e)),
            Ok(event) => serialize_result(
                get_runtime()
                    .block_on(metrics::record::<env::android::AndroidEnv>(event))
                    .map(|_| spotify::models::OperationResult {
                        success: true,
                        error: None,
                    }),
            ),
        },
        // importPlayHistoryNative — `history` is the file's text.
        "importPlayHistory" => serialize_result(
            get_runtime().block_on(metrics::import::<env::android::AndroidEnv>(&text(args, "history"))),
        ),
        // allPlaysNative
        "allPlays" => serialize_result(get_runtime().block_on(metrics::load_all::<env::android::AndroidEnv>())),
        // playStatsNative — `zone` is `[[fromMs, offsetMs], …]`.
        "playStats" => {
            let zone: metrics::stats::Zone = serde_json::from_value(args["zone"].clone()).unwrap_or_default();
            let (from, to) = (int(args, "fromMs", 0), int(args, "toMs", i64::MAX));
            let events = get_runtime().block_on(metrics::load_all::<env::android::AndroidEnv>());
            serialize_result(events.map(|events| metrics::stats::compute(&events, from, to, &zone)))
        }
        // topCsvNative — `kind` is `tracks`, `artists` or `albums`.
        "topCsv" => match serde_json::from_value::<metrics::stats::TopKind>(Value::String(text(args, "kind"))) {
            Err(e) => serialize_result::<(), _>(Err(e)),
            Ok(kind) => {
                let (from, to) = (int(args, "fromMs", 0), int(args, "toMs", i64::MAX));
                let events = get_runtime().block_on(metrics::load_all::<env::android::AndroidEnv>());
                serialize_result(events.map(|events| metrics::stats::top_csv(&events, from, to, kind)))
            }
        },
        // exportDataNative — the document's text itself, or `{"error":…}`.
        "exportData" => {
            match get_runtime().block_on(export::to_json::<env::android::AndroidEnv>(&text(args, "producedBy"))) {
                Ok(document) => document,
                Err(e) => serde_json::json!({ "error": e.message() }).to_string(),
            }
        }
        // restoreDataNative — `json` is the text `exportData` answered. Replaces rather than merges.
        "restoreData" => {
            match get_runtime().block_on(export::restore::<env::android::AndroidEnv>(&text(args, "json"))) {
                Ok(done) => serde_json::json!({
                    "success": true,
                    "restored": done.keys,
                    "skipped": done.skipped,
                })
                .to_string(),
                Err(e) => serde_json::json!({ "success": false, "error": e.message() }).to_string(),
            }
        }

        // listenBrainzSubmitNative — `listen` is a `scrobble::Listen`; answers a `scrobble::Report`.
        "listenBrainzSubmit" => match serde_json::from_value::<scrobble::Listen>(args["listen"].clone()) {
            Err(e) => serialize_result::<(), _>(Err(e)),
            Ok(listen) => serialize_result(get_runtime().block_on(
                scrobble::submit::<env::android::AndroidEnv>(&text(args, "token"), listen),
            )),
        },
        // listenBrainzPlayingNowNative — best effort, never queued.
        "listenBrainzPlayingNow" => match serde_json::from_value::<scrobble::Listen>(args["listen"].clone()) {
            Err(e) => serialize_result::<(), _>(Err(e)),
            Ok(listen) => serialize_result(
                get_runtime()
                    .block_on(scrobble::playing_now::<env::android::AndroidEnv>(&text(args, "token"), &listen))
                    .map(|_| crate::ok_result()),
            ),
        },
        // listenBrainzValidateNative — the token's user as a JSON string, `null` when it is not valid.
        "listenBrainzValidate" => serialize_result(
            get_runtime().block_on(scrobble::validate::<env::android::AndroidEnv>(&text(args, "token"))),
        ),
        // lastFmLoginNative — answers a `scrobble::lastfm::Session`.
        "lastFmLogin" => serialize_result(get_runtime().block_on(scrobble::lastfm::login::<
            env::android::AndroidEnv,
        >(
            &text(args, "apiKey"),
            &text(args, "secret"),
            &text(args, "user"),
            &text(args, "password"),
        ))),
        // lastFmSubmitNative — `credentials` is a `scrobble::lastfm::Credentials`, `listen` a
        // `scrobble::Listen`; answers a `scrobble::Report`.
        "lastFmSubmit" => {
            let parsed = serde_json::from_value::<scrobble::lastfm::Credentials>(args["credentials"].clone())
                .and_then(|c| serde_json::from_value::<scrobble::Listen>(args["listen"].clone()).map(|l| (c, l)));
            match parsed {
                Err(e) => serialize_result::<(), _>(Err(e)),
                Ok((c, listen)) => serialize_result(
                    get_runtime().block_on(scrobble::lastfm::submit::<env::android::AndroidEnv>(&c, listen)),
                ),
            }
        }
        // lastFmNowPlayingNative — best effort, never queued.
        "lastFmNowPlaying" => {
            let parsed = serde_json::from_value::<scrobble::lastfm::Credentials>(args["credentials"].clone())
                .and_then(|c| serde_json::from_value::<scrobble::Listen>(args["listen"].clone()).map(|l| (c, l)));
            match parsed {
                Err(e) => serialize_result::<(), _>(Err(e)),
                Ok((c, listen)) => serialize_result(
                    get_runtime()
                        .block_on(scrobble::lastfm::now_playing::<env::android::AndroidEnv>(&c, &listen))
                        .map(|_| crate::ok_result()),
                ),
            }
        }

        // readReplayGainNative — `path` is a file this process can open; answers a `ReplayGain`.
        "readReplayGain" => serialize_result(audio::replaygain::read(&text(args, "path"))),
        // readEmbeddedLyricsNative — the lyrics as a JSON string, or `null`.
        "readEmbeddedLyrics" => serialize_result(audio::embedded_lyrics::read(&text(args, "path"))),
        // readTagsNative — a `LocalTags`.
        "readTags" => serialize_result(audio::tags::read(&text(args, "path"))),
        // readCoverNative — `{"mimeType":…,"data":<base64>}`, or `null`.
        "readCover" => serialize_result(audio::tags::read_cover(&text(args, "path"))),
        // writeTagsNative — `tags` is a `TagsToWrite` object.
        "writeTags" => match serde_json::from_value::<audio::tags::TagsToWrite>(args["tags"].clone()) {
            Err(e) => serialize_result::<(), _>(Err(e)),
            Ok(tags) => serialize_result(audio::tags::write(&text(args, "path"), &tags).map(|_| crate::ok_result())),
        },
        // tempoOfNative — `samples` is mono PCM (-1..1) as a JSON array; a `tempo::Estimate`, or
        // `null` with under 6 s of audio. CPU only.
        "tempoOf" => {
            let samples: Vec<f32> = serde_json::from_value(args["samples"].clone()).unwrap_or_default();
            let mut t = tempo::Tempo::new(int(args, "sampleRate", 0).max(1) as u32);
            t.push(&samples);
            serialize_result::<_, String>(Ok(t.estimate()))
        }

        // autoEqSearchNative — `[{name, path, source}]`.
        "autoEqSearch" => serialize_result(
            get_runtime().block_on(autoeq::search_online::<env::android::AndroidEnv>(&text(args, "query"), 30)),
        ),
        // autoEqProfileNative — the profile's parametric EQ text, as a JSON string.
        "autoEqProfile" => serialize_result(
            get_runtime().block_on(autoeq::fetch_profile::<env::android::AndroidEnv>(&text(args, "path"))),
        ),
        // autoEqBandsNative — `n` bands `[{cutoffHz, gainDb}]`. Memory only.
        "autoEqBands" => serialize_result(
            autoeq::parse(&text(args, "peqText"))
                .map(|peq| autoeq::bands(&peq, int(args, "n", 10).max(2) as usize)),
        ),

        // subsonicPingNative — `config` is a `subsonic::Config`; the server's name and version.
        "subsonicPing" => match serde_json::from_value::<subsonic::Config>(args["config"].clone()) {
            Err(e) => serialize_result::<(), _>(Err(e)),
            Ok(cfg) => serialize_result(get_runtime().block_on(subsonic::ping::<env::android::AndroidEnv>(&cfg))),
        },
        // subsonicFindNative — `artists` is a JSON array of names; a `subsonic::Found` or `null`.
        "subsonicFind" => match serde_json::from_value::<subsonic::Config>(args["config"].clone()) {
            Err(e) => serialize_result::<(), _>(Err(e)),
            Ok(cfg) => serialize_result(get_runtime().block_on(subsonic::find::<env::android::AndroidEnv>(
                &cfg,
                &text(args, "title"),
                &strings(args, "artists"),
                int(args, "durationMs", 0),
            ))),
        },

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
        // refreshSpotifyTokenNative — `{"success":…,"error":…}`, no `kind`.
        "refreshSpotifyToken" => operation(get_runtime().block_on(client().refresh_token())),
        // isSpotifyAuthenticatedNative — `true` or `false`.
        "isSpotifyAuthenticated" => client().is_authenticated().to_string(),
        // logoutSpotifyNative
        "logoutSpotify" => {
            client().logout();
            ok()
        }
        // warmupSpotifyHashesNative — returns at once; the scrape runs on the core's runtime.
        "warmupSpotifyHashes" => {
            crate::warmup_spotify_hashes();
            ok()
        }
        // getSpotifyHashesNative — `{"operationName":"sha256", …}`, or `{}`.
        "getSpotifyHashes" => {
            serde_json::to_string(&client().get_gql_hashes_snapshot()).unwrap_or_else(|_| "{}".to_string())
        }

        // getSpotifyMeNative
        "getSpotifyMe" => serialize_result(get_runtime().block_on(client().get_me())),
        // getSpotifySavedTracksNative
        "getSpotifySavedTracks" => serialize_result(get_runtime().block_on(
            client().get_saved_tracks(int(args, "limit", 50) as u32, int(args, "offset", 0) as u32),
        )),
        // getSpotifySavedAlbumsNative
        "getSpotifySavedAlbums" => serialize_result(get_runtime().block_on(
            client().get_saved_albums(int(args, "limit", 50) as u32, int(args, "offset", 0) as u32),
        )),
        // getSpotifySavedPlaylistsNative
        "getSpotifySavedPlaylists" => serialize_result(get_runtime().block_on(
            client().get_saved_playlists(int(args, "limit", 50) as u32, int(args, "offset", 0) as u32),
        )),
        // getSpotifyFollowedArtistsNative
        "getSpotifyFollowedArtists" => serialize_result(get_runtime().block_on(
            client().get_followed_artists(int(args, "limit", 50) as u32, int(args, "offset", 0) as u32),
        )),

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
        // getSpotifyNewReleasesNative
        "getSpotifyNewReleases" => serialize_result(get_runtime().block_on(
            client().get_new_releases(int(args, "limit", 20) as u32, int(args, "offset", 0) as u32),
        )),
        // saveSpotifyAlbumsNative — `ids` is a JSON array; `{"success":…,"error":…}`, no `kind`.
        "saveSpotifyAlbums" => operation(get_runtime().block_on(client().save_albums(&strings(args, "ids")))),
        // unsaveSpotifyAlbumsNative
        "unsaveSpotifyAlbums" => {
            operation(get_runtime().block_on(client().unsave_albums(&strings(args, "ids"))))
        }
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
        // getSpotifyArtistAlbumsNative
        "getSpotifyArtistAlbums" => serialize_result(get_runtime().block_on(
            client().get_artist_albums(
                &text(args, "id"),
                int(args, "limit", 20) as u32,
                int(args, "offset", 0) as u32,
            ),
        )),
        // getSpotifyRelatedArtistsNative
        "getSpotifyRelatedArtists" => serialize_result(get_runtime().block_on(
            client().get_related_artists(
                &text(args, "id"),
                int(args, "limit", 20) as u32,
                int(args, "offset", 0) as u32,
            ),
        )),
        // followSpotifyArtistsNative — `ids` is a JSON array; `{"success":…,"error":…}`, no `kind`.
        "followSpotifyArtists" => {
            operation(get_runtime().block_on(client().follow_artists(&strings(args, "ids"))))
        }
        // unfollowSpotifyArtistsNative
        "unfollowSpotifyArtists" => {
            operation(get_runtime().block_on(client().unfollow_artists(&strings(args, "ids"))))
        }
        // getArtistAboutNative — no session needed.
        "getArtistAbout" => {
            serialize_result(get_runtime().block_on(client().get_artist_about(&text(args, "id"))))
        }
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
        // createSpotifyPlaylistNative — answers the new `FullPlaylist`.
        "createSpotifyPlaylist" => serialize_result(get_runtime().block_on(client().create_playlist(
            &text(args, "userId"),
            &text(args, "name"),
            &text(args, "description"),
            flag(args, "public"),
        ))),
        // updateSpotifyPlaylistNative — an empty `name` or `description` leaves that one alone.
        "updateSpotifyPlaylist" => {
            let (name, description) = (text(args, "name"), text(args, "description"));
            operation(get_runtime().block_on(client().update_playlist(
                &text(args, "id"),
                if name.is_empty() { None } else { Some(name.as_str()) },
                if description.is_empty() { None } else { Some(description.as_str()) },
                None,
            )))
        }
        // addTracksToPlaylistNative — `trackIds` is a JSON array; a negative `position` appends.
        "addTracksToPlaylist" => {
            let position = int(args, "position", -1);
            operation(get_runtime().block_on(client().add_tracks_to_playlist(
                &text(args, "id"),
                &strings(args, "trackIds"),
                if position < 0 { None } else { Some(position as u32) },
            )))
        }
        // removeTracksFromPlaylistNative
        "removeTracksFromPlaylist" => operation(get_runtime().block_on(
            client().remove_tracks_from_playlist(&text(args, "id"), &strings(args, "trackIds")),
        )),
        // followPlaylistNative
        "followPlaylist" => operation(get_runtime().block_on(client().follow_playlist(&text(args, "id")))),
        // unfollowPlaylistNative
        "unfollowPlaylist" => {
            operation(get_runtime().block_on(client().unfollow_playlist(&text(args, "id"))))
        }
        // extendPlaylistNative — `skip` is a JSON array of track ids; `count` is held to 1..=50.
        "extendPlaylist" => serialize_result(get_runtime().block_on(client().extend_playlist(
            &text(args, "id"),
            &strings(args, "skip"),
            int(args, "count", 20).clamp(1, 50) as u32,
        ))),
        // getSpotifyTrackNative
        "getSpotifyTrack" => {
            serialize_result(get_runtime().block_on(client().get_track(&text(args, "id"))))
        }
        // saveSpotifyTracksNative — `ids` is a JSON array; `{"success":…,"error":…}`, no `kind`.
        "saveSpotifyTracks" => operation(get_runtime().block_on(client().save_tracks(&strings(args, "ids")))),
        // unsaveSpotifyTracksNative
        "unsaveSpotifyTracks" => {
            operation(get_runtime().block_on(client().unsave_tracks(&strings(args, "ids"))))
        }
        // getSpotifyTrackRadioNative
        "getSpotifyTrackRadio" => {
            serialize_result(get_runtime().block_on(client().get_track_radio(&text(args, "id"))))
        }
        // getSpotifyLyricsNative
        "getSpotifyLyrics" => {
            serialize_result(get_runtime().block_on(client().get_lyrics(&text(args, "id"))))
        }
        // getTrackCreditsNative — no session needed.
        "getTrackCredits" => {
            serialize_result(get_runtime().block_on(client().get_track_credits(&text(args, "id"))))
        }
        // getSpotifyCanvasNative — `uri` is a track id or `spotify:track:<id>`. `{"url":…}`, null
        // when the track has none, or `{"success":false,"error":…}`.
        "getSpotifyCanvas" => {
            let uri = text(args, "uri");
            match get_runtime().block_on(client().get_track_canvas(&uri)) {
                Ok(url) => serde_json::json!({ "url": url }).to_string(),
                Err(e) => {
                    <env::android::AndroidEnv as env::Env>::log(
                        env::LogLevel::Warn,
                        "Canvas",
                        &format!("{}: {}", uri, e),
                    );
                    serde_json::to_string(&spotify::models::OperationResult::err(e.to_string()))
                        .unwrap_or_else(|_| r#"{"success":false,"error":"canvas error"}"#.to_string())
                }
            }
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
        // searchYouTubeNative — a list of `YouTubeTrack`.
        "searchYouTube" => {
            let query = text(args, "query");
            serialize_result(get_runtime().block_on(async {
                let scraper = youtube::scraper::YouTubeScraper::new();
                scraper.search(&query).await
            }))
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
        // setInnertubeClientsNative — `clients` is a JSON array of
        // `youtube::innertube::ClientProfile`; `[]` restores the built-in table.
        "setInnertubeClients" => {
            match serde_json::from_value::<Vec<youtube::innertube::ClientProfile>>(args["clients"].clone()) {
                Err(e) => serialize_result::<(), _>(Err(e)),
                Ok(list) => serialize_result(
                    get_runtime()
                        .block_on(youtube::innertube::set_clients::<env::android::AndroidEnv>(&list))
                        .map(|_| crate::ok_result()),
                ),
            }
        }

        // registerTrackMetadataNative — `artists` is a JSON array of names here, not a string of one.
        "registerTrackMetadata" => {
            let artists: Vec<String> =
                serde_json::from_value(args["artists"].clone()).unwrap_or_default();
            youtube::server::register_track_meta(
                text(args, "id"),
                text(args, "name"),
                artists,
                int(args, "durationMs", 0).max(0) as u32,
                text(args, "isrc"),
            );
            ok()
        }
        // resolveYouTubeIdNative — answers the video id as a JSON string, "" when none was found.
        "resolveYouTubeId" => {
            let spotify_id = text(args, "spotifyId");
            let hint = text(args, "youtubeId");
            let cache_dir = youtube::server::get_cache_dir().unwrap_or_default();
            let found = get_runtime().block_on(youtube::server::resolve_youtube_id_direct::<
                env::android::AndroidEnv,
            >(
                &spotify_id,
                if hint.is_empty() { None } else { Some(hint.as_str()) },
                &cache_dir,
            ));
            Value::String(found.unwrap_or_default()).to_string()
        }
        // setAlternativeTrackNative — the write to disk runs in the background.
        "setAlternativeTrack" => {
            youtube::server::set_alternative_track::<env::android::AndroidEnv>(
                text(args, "spotifyId"),
                text(args, "youtubeId"),
            );
            ok()
        }
        // getAlternativeTrackNative — the video id as a JSON string, "" when there is no override.
        "getAlternativeTrack" => Value::String(
            youtube::server::get_alternative_track(&text(args, "spotifyId")).unwrap_or_default(),
        )
        .to_string(),
        // updateQueueNative — `trackIds` is a JSON array, in queue order.
        "updateQueue" => {
            youtube::server::update_playback_queue::<env::android::AndroidEnv>(strings(args, "trackIds"));
            ok()
        }

        // installAddonNative — the installed add-on, or a failure.
        "installAddon" => serialize_result(
            get_runtime().block_on(addon::registry::install::<env::android::AndroidEnv>(&text(args, "url"))),
        ),
        // listAddonsNative — in the order they are tried.
        "listAddons" => {
            let addons = get_runtime().block_on(addon::registry::list::<env::android::AndroidEnv>());
            serde_json::to_string(&addons).unwrap_or_else(|_| "[]".to_string())
        }
        // uninstallAddonNative
        "uninstallAddon" => serialize_result(
            get_runtime()
                .block_on(addon::registry::uninstall::<env::android::AndroidEnv>(&text(args, "id")))
                .map(|_| spotify::models::OperationResult::ok()),
        ),
        // setAddonEnabledNative
        "setAddonEnabled" => serialize_result(
            get_runtime()
                .block_on(addon::registry::set_enabled::<env::android::AndroidEnv>(
                    &text(args, "id"),
                    flag(args, "enabled"),
                ))
                .map(|_| spotify::models::OperationResult::ok()),
        ),
        // reorderAddonsNative — `ids` is a JSON array of add-on ids.
        "reorderAddons" => serialize_result(
            get_runtime()
                .block_on(addon::registry::reorder::<env::android::AndroidEnv>(&strings(args, "ids")))
                .map(|_| spotify::models::OperationResult::ok()),
        ),
        // resolveViaAddonNative — `query` is an `addon::TrackQuery`. `{}` when the add-on does not
        // have the track, which means "ask the next provider", not a failure.
        "resolveViaAddon" => match serde_json::from_value::<addon::TrackQuery>(args["query"].clone()) {
            Err(e) => serialize_result::<(), _>(Err(e)),
            Ok(query) => {
                let id = text(args, "addonId");
                get_runtime().block_on(async {
                    let installed = addon::registry::list::<env::android::AndroidEnv>().await;
                    match installed.iter().find(|a| a.manifest.id == id) {
                        None => serialize_result::<(), _>(Err(addon::AddonError::Refused(
                            "no such addon".into(),
                        ))),
                        Some(found) => {
                            match addon::transport::resolve_stream::<env::android::AndroidEnv>(found, &query)
                                .await
                            {
                                Ok(None) => "{}".to_string(),
                                Ok(Some(answer)) => {
                                    serde_json::to_string(&answer).unwrap_or_else(|_| "{}".to_string())
                                }
                                Err(e) => serialize_result::<(), _>(Err(e)),
                            }
                        }
                    }
                })
            }
        },

        // jamNewCode — the code as a JSON string.
        "jamNewCode" => serialize_result::<_, String>(Ok(jam::new_code())),
        // jamJoinNative — `config` is a `jam::session::Config`; blocks until the broker answers.
        "jamJoin" => match serde_json::from_value::<jam::session::Config>(args["config"].clone()) {
            Err(e) => serialize_result::<(), _>(Err(e)),
            Ok(cfg) => serialize_result(get_runtime().block_on(jam::session::join(cfg)).map(|_| crate::ok_result())),
        },
        // jamPollNative — every event that arrived within `timeoutMs`, as a JSON array.
        "jamPoll" => serialize_result::<_, String>(Ok(get_runtime().block_on(jam::session::poll(
            int(args, "timeoutMs", 0).max(0) as u64,
        )))),
        // jamPublishStateNative — `state` is a `jam::State`.
        "jamPublishState" => match serde_json::from_value::<jam::State>(args["state"].clone()) {
            Err(e) => serialize_result::<(), _>(Err(e)),
            Ok(state) => serialize_result(
                get_runtime().block_on(jam::session::publish_state(&state)).map(|_| crate::ok_result()),
            ),
        },
        // jamSendNative — `command` is a `jam::Command`.
        "jamSend" => match serde_json::from_value::<jam::Command>(args["command"].clone()) {
            Err(e) => serialize_result::<(), _>(Err(e)),
            Ok(cmd) => serialize_result(get_runtime().block_on(jam::session::send(cmd)).map(|_| crate::ok_result())),
        },
        // jamKickNative
        "jamKick" => serialize_result(
            get_runtime().block_on(jam::session::kick(&text(args, "id"))).map(|_| crate::ok_result()),
        ),
        // jamLeaveNative
        "jamLeave" => {
            get_runtime().block_on(jam::session::leave());
            serialize_result::<_, String>(Ok(crate::ok_result()))
        }

        // startLocalServerNative
        "startLocalServer" => match get_runtime().block_on(server::start::<env::android::AndroidEnv>()) {
            Ok(handle) => serde_json::json!({ "port": handle.port, "token": handle.token }).to_string(),
            Err(e) => serialize_result::<(), _>(Err(e)),
        },
        // registerLocalStreamNative — `request` is `{upstreamUrl, cacheKey, cacheRoot, mime,
        // deezerSngId, ttlMs, mode}`. Never touches the network.
        "registerLocalStream" => match serde_json::from_value::<crate::RegisterStreamRequest>(args["request"].clone()) {
            Err(_) => r#"{"status":"refused","reason":"bad request"}"#.to_string(),
            Ok(request) => crate::register_local_stream(request),
        },
        // forgetLocalStreamNative
        "forgetLocalStream" => {
            server::handles::forget(&text(args, "handle"));
            ok()
        }
        // streamCacheSizeNative — bytes, as a JSON number.
        "streamCacheSize" => get_runtime().block_on(server::cache::size(&text(args, "cacheRoot"))).to_string(),
        // clearStreamCacheNative — the bytes freed, as a JSON number.
        "clearStreamCache" => get_runtime().block_on(server::cache::clear(&text(args, "cacheRoot"))).to_string(),

        // openCastListenerNative — `bindTo` is this device's address on the network (never
        // 0.0.0.0, which the core refuses); `device` is the one peer that gets an answer.
        "openCastListener" => {
            let (bind_str, device_str) = (text(args, "bindTo"), text(args, "device"));
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
                    match get_runtime().block_on(server::lan::open::<env::android::AndroidEnv>(bind_ip, device_ip)) {
                        Ok(port) => serde_json::json!({ "success": true, "port": port }).to_string(),
                        Err(e) => serde_json::json!({ "success": false, "error": e.message() }).to_string(),
                    }
                }
            }
        }
        // closeCastListenerNative
        "closeCastListener" => {
            server::lan::close::<env::android::AndroidEnv>();
            ok()
        }
        // castUrlForNative — `{"url":…}`, or `{}` when no session is open.
        "castUrlFor" => match server::lan::url_for(&text(args, "handle")) {
            Some(url) => serde_json::json!({ "url": url }).to_string(),
            None => "{}".to_string(),
        },
        // castSessionNative — `{"active":true,"boundTo":…,"port":…,"device":…}` or `{"active":false}`.
        "castSession" => match server::lan::current() {
            None => r#"{"active":false}"#.to_string(),
            Some(session) => serde_json::json!({
                "active": true,
                "boundTo": session.bound_to.to_string(),
                "port": session.port,
                "device": session.device.to_string(),
            })
            .to_string(),
        },

        // The audio engine (RustAudioEngine*Native on the desktop JVM). Needs `desktop-audio`.
        "engineStart" | "engineSend" | "enginePoll" | "engineStop" => engine_call(method, args),
        // The system's credential store (SecretStore *Native on the desktop JVM). Needs `secure-store`.
        "secretGet" | "secretSet" | "secretRemove" => secret_call(method, args),

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

#[cfg(feature = "secure-store")]
fn secret_call(method: &str, args: &Value) -> String {
    use crate::secrets;
    let key = text(args, "key");
    match method {
        // SecretStore getNative — the value as a JSON string, or `null`.
        "secretGet" => serialize_result(secrets::get(&key)),
        // SecretStore setNative
        "secretSet" => serialize_result(secrets::set(&key, &text(args, "value")).map(|_| crate::ok_result())),
        // SecretStore removeNative
        _ => serialize_result(secrets::remove(&key).map(|_| crate::ok_result())),
    }
}

#[cfg(not(feature = "secure-store"))]
fn secret_call(_method: &str, _args: &Value) -> String {
    serialize_result::<(), _>(Err(
        "this build has no credential store (feature secure-store)".to_string(),
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
        let offline = [
            "parseLink",
            "searchQuery",
            "searchForget",
            "isSpotifyAuthenticated",
            "getSpotifyHashes",
            "findLocalMatch",
            "arrangeReleases",
            "tempoOf",
            "autoEqBands",
            "getAlternativeTrack",
            "castUrlFor",
            "castSession",
        ];
        for method in offline {
            assert!(!call(method, "{}").contains("unknown method"), "{method}");
        }
        let mut seen = std::collections::HashSet::new();
        for method in METHODS {
            assert!(seen.insert(*method), "{method} listed twice");
        }
    }

    #[test]
    fn the_header_lists_every_method() {
        let header = include_str!("../include/rustify.h");
        for method in METHODS {
            assert!(header.contains(&format!(" {method} {{")), "{method} is not in rustify.h");
        }
    }

    #[test]
    fn a_stream_request_that_does_not_parse_is_refused_as_over_jni() {
        assert_eq!(
            call("registerLocalStream", "{}"),
            r#"{"status":"refused","reason":"bad request"}"#
        );
    }

    #[test]
    fn too_little_audio_has_no_tempo_as_over_jni() {
        assert_eq!(call("tempoOf", r#"{"samples":[0.0,0.5,-0.5],"sampleRate":44100}"#), "null");
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
