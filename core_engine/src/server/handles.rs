// core_engine/src/server/handles.rs
//
// The table of things the local server is willing to serve.
//
// A handle is an **opaque token**, never a URL. That is the whole point: a server that takes the
// upstream URL in its query string is an open proxy, reachable by any app on the device that can
// guess the port. Callers register what they want served and get back a name for it.
//
// An entry is one of exactly two things, and the difference between them is the rule 3.1
// established and 3.2 keeps: **the server never waits for a download.**
//
// 3.1 removed an earlier shape where an entry could name an upstream URL to be fetched on first
// request, which meant a request could arrive for something not downloaded yet and had to be
// answered `503 Retry-After` while a fill ran in the background. That is gone and stays gone.
// [`Served::DeezerProxy`] is not a return to it: a proxy entry is created **deliberately, to play
// right now**, never as a fallback for a file that turned out to be missing. It streams — it does
// not download and then answer.

use crate::env::Env;
use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};

/// Where the bytes of a registered stream come from.
#[derive(Debug, Clone)]
pub enum Served {
    /// A file on this device, complete. Registration happens after the download, never before.
    File(String),
    /// Deezer's CDN, decrypted stripe by stripe on the way through — see [`super::proxy`].
    ///
    /// The URL is held here rather than in the request path for the reason the whole handle table
    /// exists: a server that accepts an upstream URL from its caller is an open proxy for every
    /// other app on the device.
    ///
    /// `cache_path` is where a copy goes as it plays. `None` means "do not cache" — the user turned
    /// storing off, or there is nowhere to put it.
    DeezerProxy {
        url: String,
        sng_id: String,
        cache_path: Option<String>,
    },
}

#[derive(Debug, Clone)]
pub struct StreamEntry {
    pub served: Served,
    pub mime: Option<String>,
    /// Epoch millis after which the entry is refused. `None` means it does not expire.
    ///
    /// Registrations are not permanent because nothing here is: the cache entry behind a handle can
    /// be swept, and a table that only grows is a leak with a nice name.
    pub expires_at_ms: Option<i64>,
}

static HANDLES: OnceLock<Mutex<HashMap<String, StreamEntry>>> = OnceLock::new();

fn table() -> &'static Mutex<HashMap<String, StreamEntry>> {
    HANDLES.get_or_init(|| Mutex::new(HashMap::new()))
}

/// Above this many entries, registering also drops the expired ones.
///
/// Nothing calls `forget` on the playback path — a track is registered per play and the handle is
/// simply abandoned — so without this the table keeps one small struct per song played, for as long
/// as the process lives. Sweeping on insert costs a walk of a few hundred entries once in a while,
/// which is cheaper than the timer it replaces.
const SWEEP_ABOVE: usize = 256;

/// Registers a stream and returns its handle.
pub fn register<E: Env>(entry: StreamEntry) -> String {
    let handle = super::random_hex(16);
    if let Ok(mut map) = table().lock() {
        if map.len() >= SWEEP_ABOVE {
            let now = E::now_ms();
            map.retain(|_, e| e.expires_at_ms.map_or(true, |expiry| expiry > now));
        }
        map.insert(handle.clone(), entry);
    }
    handle
}

/// Looks a handle up, refusing anything past its expiry.
///
/// Expired entries are dropped on the way out rather than swept periodically: the only moment the
/// answer matters is when someone asks.
pub fn resolve<E: Env>(handle: &str) -> Option<StreamEntry> {
    let now = E::now_ms();
    let mut map = table().lock().ok()?;
    let entry = map.get(handle)?.clone();
    match entry.expires_at_ms {
        Some(expiry) if expiry <= now => {
            map.remove(handle);
            None
        }
        _ => Some(entry),
    }
}

pub fn forget(handle: &str) {
    if let Ok(mut map) = table().lock() {
        map.remove(handle);
    }
}

/// How many entries are currently registered. Diagnostics only.
pub fn len() -> usize {
    table().lock().map(|m| m.len()).unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::env::mock::{self, MockEnv};

    fn entry(expires_at_ms: Option<i64>) -> StreamEntry {
        StreamEntry {
            served: Served::File("/tmp/a.mp3".into()),
            mime: Some("audio/mpeg".into()),
            expires_at_ms,
        }
    }

    #[test]
    fn a_handle_round_trips() {
        let _guard = mock::lock_and_reset();
        let handle = register::<MockEnv>(entry(None));
        assert!(resolve::<MockEnv>(&handle).is_some());
        forget(&handle);
        assert!(resolve::<MockEnv>(&handle).is_none());
    }

    #[test]
    fn handles_are_not_guessable_from_each_other() {
        let _guard = mock::lock_and_reset();
        let a = register::<MockEnv>(entry(None));
        let b = register::<MockEnv>(entry(None));
        assert_ne!(a, b);
        assert_eq!(a.len(), 32); // 16 bytes, hex
    }

    #[test]
    fn an_expired_handle_is_refused_and_dropped() {
        let _guard = mock::lock_and_reset();
        mock::state().now_ms = 1_000;
        let handle = register::<MockEnv>(entry(Some(2_000)));
        assert!(resolve::<MockEnv>(&handle).is_some());

        mock::state().now_ms = 2_001;
        assert!(resolve::<MockEnv>(&handle).is_none());
        // And really dropped, not merely hidden: winding the clock back does not bring it back.
        mock::state().now_ms = 1_000;
        assert!(resolve::<MockEnv>(&handle).is_none());
    }
}
