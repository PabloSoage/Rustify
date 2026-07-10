// core_engine/src/lib.rs
//
// JNI bridge layer: exposes Rust engine functions to the Android JVM via C ABI.
// Each function follows the pattern:
//   1. Extract args from JNI
//   2. Execute async Rust operation on the Tokio runtime
//   3. Serialize result to JSON string
//   4. Return as Java String

pub mod matcher;
pub mod spotify;
pub mod youtube;

use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::{EnvUnowned, Outcome};
use std::sync::OnceLock;
use tokio::runtime::Runtime;

// Native Rust 1.70+ thread-safe lazy initialization for the asynchronous runtime.
static RUNTIME: OnceLock<Runtime> = OnceLock::new();

/// Helper function to initialize and retrieve the Tokio Runtime securely.
fn get_runtime() -> &'static Runtime {
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
        youtube::server::set_alternative_track(s_id, y_id);
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
        let client = spotify::client::get_spotify_client().read().unwrap();
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
            youtube::server::resolve_youtube_id_direct(&tid, yid_opt.as_deref(), &cache_dir).await
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
        youtube::server::update_playback_queue(track_ids);
        Ok(())
    });
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
            let mut client = spotify::client::get_spotify_client().write().unwrap();
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
    if let Ok(mut client) = spotify::client::get_spotify_client().write() {
        client.logout();
    }
}

/// JNI Bridge: Refresh access token
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_refreshSpotifyTokenNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> jstring {
    jni_bridge!(env_unowned, |env| {
        let async_result = get_runtime().block_on(async {
            let mut client = spotify::client::get_spotify_client().write().unwrap();
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
    if let Ok(client) = spotify::client::get_spotify_client().read() {
        if client.is_authenticated() { JNI_TRUE } else { JNI_FALSE }
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
            let mut client = spotify::client::get_spotify_client().write().unwrap();
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
            let client = spotify::client::get_spotify_client().read().unwrap();
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
            let client = spotify::client::get_spotify_client().read().unwrap();
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
            let client = spotify::client::get_spotify_client().read().unwrap();
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
            let client = spotify::client::get_spotify_client().read().unwrap();
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
            let client = spotify::client::get_spotify_client().read().unwrap();
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
            let client = spotify::client::get_spotify_client().read().unwrap();
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
            let client = spotify::client::get_spotify_client().read().unwrap();
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
            let client = spotify::client::get_spotify_client().read().unwrap();
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
            let client = spotify::client::get_spotify_client().read().unwrap();
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
            let client = spotify::client::get_spotify_client().read().unwrap();
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
            let client = spotify::client::get_spotify_client().read().unwrap();
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
            let client = spotify::client::get_spotify_client().read().unwrap();
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
            let client = spotify::client::get_spotify_client().read().unwrap();
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
            let client = spotify::client::get_spotify_client().read().unwrap();
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
            let client = spotify::client::get_spotify_client().read().unwrap();
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
            let client = spotify::client::get_spotify_client().read().unwrap();
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
            let client = spotify::client::get_spotify_client().read().unwrap();
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
            let client = spotify::client::get_spotify_client().read().unwrap();
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
            let client = spotify::client::get_spotify_client().read().unwrap();
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
            let client = spotify::client::get_spotify_client().read().unwrap();
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
            let client = spotify::client::get_spotify_client().read().unwrap();
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
            let client = spotify::client::get_spotify_client().read().unwrap();
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
            let client = spotify::client::get_spotify_client().read().unwrap();
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
            let client = spotify::client::get_spotify_client().read().unwrap();
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
            let client = spotify::client::get_spotify_client().read().unwrap();
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
            let client = spotify::client::get_spotify_client().read().unwrap();
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
            let client = spotify::client::get_spotify_client().read().unwrap();
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
            let client = spotify::client::get_spotify_client().read().unwrap();
            client.get_track_radio(&id).await
        });
        serialize_result(async_result)
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
            client.write().unwrap().set_cache_dir(&path);
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
            let client_clone = {
                let client = spotify::client::get_spotify_client().read().unwrap();
                client.clone_http()
            };
            match spotify::client::scrape_gql_hashes_with_client(&client_clone).await {
                Ok(new_hashes) => {
                    let client = spotify::client::get_spotify_client().read().unwrap();
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
            let client = spotify::client::get_spotify_client().read().unwrap();
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
            let client = spotify::client::get_spotify_client().read().unwrap();

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
            let client = spotify::client::get_spotify_client().read().unwrap();
            client.get_browse_sections(limit as u32).await
        });
        serialize_result(async_result)
    })
}