// core_engine/src/server/handles.rs
//
// The table of things the local server is willing to serve.
//
// A handle is an **opaque token**, never a URL. That is the whole point: a server that takes the
// upstream URL in its query string is an open proxy, reachable by any app on the device that can
// guess the port. Callers register what they want served and get back a name for it.

use crate::env::Env;
use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};

/// Where the bytes of a registered stream come from.
#[derive(Debug, Clone)]
pub enum Source {
    /// Already on this device — local music, or a cache entry that is complete.
    File(String),
    /// Fetched on first request and written to `cache` if there is one.
    Upstream {
        url: String,
        cache: Option<String>,
    },
}

#[derive(Debug, Clone)]
pub struct StreamEntry {
    pub source: Source,
    pub mime: Option<String>,
    /// Epoch millis after which the entry is refused. `None` means it does not expire.
    ///
    /// Registrations are not permanent because upstream URLs are not: a googlevideo URL is good for
    /// about six hours, and serving a dead one is worse than refusing.
    pub expires_at_ms: Option<i64>,
}

static HANDLES: OnceLock<Mutex<HashMap<String, StreamEntry>>> = OnceLock::new();

fn table() -> &'static Mutex<HashMap<String, StreamEntry>> {
    HANDLES.get_or_init(|| Mutex::new(HashMap::new()))
}

/// Registers a stream and returns its handle.
pub fn register(entry: StreamEntry) -> String {
    let handle = super::random_hex(16);
    if let Ok(mut map) = table().lock() {
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

    fn upstream(expires_at_ms: Option<i64>) -> StreamEntry {
        StreamEntry {
            source: Source::Upstream {
                url: "https://example.test/a.mp3".into(),
                cache: None,
            },
            mime: Some("audio/mpeg".into()),
            expires_at_ms,
        }
    }

    #[test]
    fn a_handle_round_trips() {
        let _guard = mock::lock_and_reset();
        let handle = register(upstream(None));
        assert!(resolve::<MockEnv>(&handle).is_some());
        forget(&handle);
        assert!(resolve::<MockEnv>(&handle).is_none());
    }

    #[test]
    fn handles_are_not_guessable_from_each_other() {
        let _guard = mock::lock_and_reset();
        let a = register(upstream(None));
        let b = register(upstream(None));
        assert_ne!(a, b);
        assert_eq!(a.len(), 32); // 16 bytes, hex
    }

    #[test]
    fn an_expired_handle_is_refused_and_dropped() {
        let _guard = mock::lock_and_reset();
        mock::state().now_ms = 1_000;
        let handle = register(upstream(Some(2_000)));
        assert!(resolve::<MockEnv>(&handle).is_some());

        mock::state().now_ms = 2_001;
        assert!(resolve::<MockEnv>(&handle).is_none());
        // And really dropped, not merely hidden: winding the clock back does not bring it back.
        mock::state().now_ms = 1_000;
        assert!(resolve::<MockEnv>(&handle).is_none());
    }
}
