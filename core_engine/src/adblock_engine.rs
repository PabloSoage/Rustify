//! Network filtering engine for the in-app Spotify Web Player.
//!
//! Android's WebView has no extension support, so uBlock Origin cannot be installed into it. What
//! *is* reusable is uBO's actual value: its filter lists. This module wraps `adblock` (Brave's
//! adblock-rust, MPL-2.0), which parses the same EasyList/uBO filter syntax, and exposes a single
//! `matches` check that `WebViewClient.shouldInterceptRequest` can call per request.
//!
//! Lists are downloaded, cached and refreshed on the Kotlin side; this module only compiles them
//! into an engine and answers match queries.

use adblock::request::Request;
use adblock::{Engine, FilterSet};
use std::sync::RwLock;

/// Compiled engine. `None` until the first successful [`load_rules`].
static ENGINE: RwLock<Option<Engine>> = RwLock::new(None);

/// Compiles `rules` (concatenated filter lists, one rule per line) into the active engine.
///
/// Rules that fail to parse are discarded by adblock-rust rather than aborting the whole list, so a
/// partially malformed list still yields a usable engine. Returns the engine's readiness.
pub fn load_rules(rules: &str) -> bool {
    let mut set = FilterSet::new(false);
    set.add_filter_list(rules.to_string(), Default::default());
    let engine = Engine::new_with_filter_set(set);

    match ENGINE.write() {
        Ok(mut guard) => {
            *guard = Some(engine);
            true
        }
        Err(_) => false,
    }
}

/// True when an engine has been compiled and can answer queries.
pub fn is_ready() -> bool {
    ENGINE.read().map(|g| g.is_some()).unwrap_or(false)
}

/// Drops the compiled engine, freeing its memory (the web player screen was closed).
pub fn clear() {
    if let Ok(mut guard) = ENGINE.write() {
        *guard = None;
    }
}

/// Should the request to `url`, made by the page at `source_url`, be blocked?
///
/// `resource_type` uses adblock-rust's vocabulary ("script", "image", "xmlhttprequest",
/// "sub_frame", "media", "stylesheet", "other"…). Anything that cannot be evaluated — no engine
/// loaded, unparseable URL — answers `false`, so a failure here can never block legitimate traffic
/// and break the page.
pub fn matches(url: &str, source_url: &str, resource_type: &str) -> bool {
    let guard = match ENGINE.read() {
        Ok(g) => g,
        Err(_) => return false,
    };
    let engine = match guard.as_ref() {
        Some(e) => e,
        None => return false,
    };
    let request = match Request::new(url, source_url, resource_type, "GET") {
        Ok(r) => r,
        Err(_) => return false,
    };
    // `BlockerResult` has no boolean of its own: a request is blocked when an `important` rule hit,
    // or a blocking filter matched with no exception overriding it. `should_block()` encodes exactly
    // that, so use it rather than reading the fields by hand.
    engine.check_network_request(&request).should_block()
}
