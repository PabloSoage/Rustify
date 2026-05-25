// core_engine/src/lib.rs
pub mod matcher;
pub mod spotify;
pub mod youtube;

use jni::objects::{JClass, JString};
use jni::sys::jstring;
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
/// This prevents code duplication across multiple JNI bridges.
fn serialize_result<T: serde::Serialize, E: std::fmt::Display>(result: Result<T, E>) -> String {
    match result {
        Ok(data) => serde_json::to_string(&data).unwrap_or_else(|_| "[]".to_string()),
        Err(e) => {
            eprintln!("Rust Engine Error: {}", e);
            "[]".to_string()
        }
    }
}

/// JNI Bridge 1: Search YouTube Music
/// Fetches tracks matching the query using the InnerTube API.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_searchYouTubeNative<'local>(
    mut env_unowned: EnvUnowned<'local>, // FFI-safe environment wrapper (JNI 0.22+)
    _class: JClass<'local>,
    query: JString<'local>,
) -> jstring {
    // Upgrade the unowned environment to a safe, mutable `Env` scope.
    // This closure automatically catches Rust panics to prevent JVM aborts.
    let env_outcome = env_unowned.with_env(|env| -> jni::errors::Result<jstring> {

        // Safe string extraction in 0.22+ (avoids `Env::get_string` deprecation/memory leaks).
        // Retrieves the modified UTF-8 bytes directly from the JVM heap.
        let mutf8 = query.mutf8_chars(env)?;
        let rust_query = mutf8.to_string();

        // Block the current JNI thread to execute the async task
        let async_result = get_runtime().block_on(async {
            let scraper = youtube::scraper::YouTubeScraper::new();
            scraper.search(&rust_query).await
        });

        // Use the generic serialization helper (DRY principle)
        let response_str = serialize_result(async_result);

        // Construct a new Java String and return its raw pointer
        let output = env.new_string(response_str)?;
        Ok(output.into_raw())
    });

    // Use `.into_outcome()` to safely extract the enum, then match with Outcome::Ok
    match env_outcome.into_outcome() {
        Outcome::Ok(j_str) => j_str,
        _ => std::ptr::null_mut(), // Return null pointer to Java on critical failure
    }
}

/// JNI Bridge 2: Extract Audio Streams
/// Retrieves direct stream URLs (.m4a/.webm) given a YouTube Video ID.
#[no_mangle]
pub extern "system" fn Java_com_varuna_rustify_bridge_NativeEngine_getAudioStreamsNative<'local>(
    mut env_unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    video_id: JString<'local>,
) -> jstring {
    let env_outcome = env_unowned.with_env(|env| -> jni::errors::Result<jstring> {

        let mutf8 = video_id.mutf8_chars(env)?;
        let rust_id = mutf8.to_string();

        let async_result = get_runtime().block_on(async {
            let scraper = youtube::scraper::YouTubeScraper::new();
            scraper.get_audio_streams(&rust_id).await
        });

        // Use the generic serialization helper (DRY principle)
        let response_str = serialize_result(async_result);

        let output = env.new_string(response_str)?;
        Ok(output.into_raw())
    });

    // Use `.into_outcome()` to safely extract the enum, then match with Outcome::Ok
    match env_outcome.into_outcome() {
        Outcome::Ok(j_str) => j_str,
        _ => std::ptr::null_mut(),
    }
}