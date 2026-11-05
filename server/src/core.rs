// server/src/core.rs
//
// The core, called the way iOS calls it: `rustify_call(method, args)` answers the JSON the JNI bridge
// of the same name answers (core_engine/src/ffi.rs). The web adds no API of its own.
//
// Every call runs on a thread of its own, outside this server's tokio runtime. Each bridge in the
// core `block_on`s its own runtime, and doing that from a thread that belongs to another runtime —
// a `spawn_blocking` thread included — panics with "Cannot start a runtime from within a runtime".

use std::ffi::{CStr, CString};
use std::sync::Arc;

use tokio::sync::{oneshot, Semaphore};

/// Methods the browser may not call: the server calls the first three itself at start-up, and the
/// rest take a path on this machine or open a port on its network. The phone passes its own files
/// and its own address there; from a browser they would be any file and any address.
pub const RESERVED: &[&str] = &[
    "initCacheDir",
    "initSpotifyCacheDir",
    "startLocalServer",
    "readReplayGain",
    "readEmbeddedLyrics",
    "readTags",
    "readCover",
    "writeTags",
    "openCastListener",
    "closeCastListener",
    "bpmAnalyze",
];

/// Checks a browser's call before the core sees it. The stream cache's methods take the folder to
/// work in, and that folder is the server's: whatever the browser named is replaced by it.
pub fn vet(method: &str, args: String, stream_cache: &str) -> Result<String, &'static str> {
    if RESERVED.contains(&method) {
        return Err("the server calls this one itself, or it reaches outside the server's folders");
    }
    let pointer = match method {
        "registerLocalStream" => "/request/cacheRoot",
        "streamCacheSize" | "clearStreamCache" => "/cacheRoot",
        _ => return Ok(args),
    };
    let mut value: serde_json::Value = serde_json::from_str(&args).map_err(|_| "the arguments are not JSON")?;
    if !value.is_object() {
        return Err("the arguments are not an object");
    }
    if pointer == "/request/cacheRoot" {
        let request = &mut value["request"];
        // Sent as JSON text by a caller that mirrors the JNI signature: read it as the object it is.
        if let Some(text) = request.as_str().map(str::to_owned) {
            *request = serde_json::from_str(&text).map_err(|_| "the request is not JSON")?;
        }
        if !request.is_object() {
            return Err("the request is not an object");
        }
        request["cacheRoot"] = serde_json::Value::String(stream_cache.to_string());
    } else {
        value["cacheRoot"] = serde_json::Value::String(stream_cache.to_string());
    }
    Ok(value.to_string())
}

/// The answer when the core could not be asked at all, in the shape every bridge fails with.
pub const FAILURE: &str = r#"{"success":false,"error":"the core could not answer","kind":"permanent"}"#;

#[derive(Clone)]
pub struct Core {
    /// Bounds how many calls run at once, so a burst from the browser cannot become a burst of
    /// threads.
    slots: Arc<Semaphore>,
}

impl Core {
    pub fn new(parallel: usize) -> Self {
        Self { slots: Arc::new(Semaphore::new(parallel.max(1))) }
    }

    /// Calls `method` with `args` (a JSON object, as text) and answers the core's JSON.
    pub async fn call(&self, method: &str, args: String) -> String {
        let Ok(permit) = self.slots.clone().acquire_owned().await else {
            return FAILURE.to_string();
        };
        let (tx, rx) = oneshot::channel();
        let method = method.to_string();
        let spawned = std::thread::Builder::new().name("core-call".into()).spawn(move || {
            let answer = call_blocking(&method, &args);
            drop(permit);
            let _ = tx.send(answer);
        });
        if spawned.is_err() {
            return FAILURE.to_string();
        }
        rx.await.unwrap_or_else(|_| FAILURE.to_string())
    }
}

fn call_blocking(method: &str, args: &str) -> String {
    let (Ok(method), Ok(args)) = (CString::new(method), CString::new(args)) else {
        return FAILURE.to_string();
    };
    // SAFETY: both pointers are valid NUL-terminated strings that outlive the call. The answer is a
    // string the core allocated; it is read once and handed back to `rustify_free`, never reused.
    unsafe {
        let answer = core_engine::ffi::rustify_call(method.as_ptr(), args.as_ptr());
        if answer.is_null() {
            return FAILURE.to_string();
        }
        let text = CStr::from_ptr(answer).to_string_lossy().into_owned();
        core_engine::ffi::rustify_free(answer);
        text
    }
}

/// Whether an answer is a success: every bridge answers `"success":false` when it failed, and the
/// ones that answer data carry no `success` at all.
pub fn succeeded(answer: &str) -> bool {
    match serde_json::from_str::<serde_json::Value>(answer) {
        Ok(value) => value.get("success").and_then(|s| s.as_bool()) != Some(false),
        Err(_) => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_failure_is_read_as_one() {
        assert!(!succeeded(FAILURE));
        assert!(!succeeded("not json"));
        assert!(succeeded(r#"{"success":true}"#));
        assert!(succeeded(r#"{"port":41234,"token":"abc"}"#));
    }

    #[test]
    fn the_browser_cannot_point_the_core_elsewhere() {
        for method in [
            "initCacheDir",
            "initSpotifyCacheDir",
            "startLocalServer",
            "readEmbeddedLyrics",
            "readTags",
            "readCover",
            "writeTags",
            "openCastListener",
            "bpmAnalyze",
        ] {
            assert!(vet(method, "{}".into(), "/srv/cache").is_err());
        }
        assert_eq!(vet("searchSpotify", r#"{"query":"x"}"#.into(), "/srv/cache").unwrap(), r#"{"query":"x"}"#);
    }

    #[test]
    fn the_stream_cache_is_always_the_servers() {
        let vetted = vet("clearStreamCache", r#"{"cacheRoot":"/"}"#.into(), "/srv/cache").unwrap();
        assert_eq!(serde_json::from_str::<serde_json::Value>(&vetted).unwrap()["cacheRoot"], "/srv/cache");

        let vetted = vet(
            "registerLocalStream",
            r#"{"request":{"upstreamUrl":"https://cdn/x","cacheRoot":"/etc"}}"#.into(),
            "/srv/cache",
        )
        .unwrap();
        let value: serde_json::Value = serde_json::from_str(&vetted).unwrap();
        assert_eq!(value["request"]["cacheRoot"], "/srv/cache");
        assert_eq!(value["request"]["upstreamUrl"], "https://cdn/x");

        let as_text = vet("registerLocalStream", r#"{"request":"{\"cacheRoot\":\"/etc\"}"}"#.into(), "/srv/cache").unwrap();
        let value: serde_json::Value = serde_json::from_str(&as_text).unwrap();
        assert_eq!(value["request"]["cacheRoot"], "/srv/cache");

        assert!(vet("streamCacheSize", "[]".into(), "/srv/cache").is_err());
    }
}
