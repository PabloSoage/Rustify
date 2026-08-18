// core_engine/src/addon/mod.rs
//
// Audio backends installable by URL.
//
// Point B of docs/stremio-core/PLAN-3.x.md, and the most transformative idea in `stremio-core` for
// this project: a backend stops being "code plus a release" and becomes a URL.
//
// What it unblocks, concretely:
//
//  * **iOS at the root.** `docs/ios/02` concluded that yt-dlp has to be replaced there by a server.
//    With addons that server *is* an addon, the iOS app only speaks HTTP, and there is no new
//    architecture to design for it.
//  * **The complaint from 2.11.2 / 2.11.6** — "Invidious does not work and neither does Deezer" —
//    stops needing a release from us. The user installs another backend.
//
// The protocol is ours, not Stremio's. Theirs models video catalogues (`MetaItem`, `Stream`,
// `Video`); what is imported is the shape of the idea — a declarative manifest at a URL, fetched
// over a uniform transport — not the types.
//
// **Two deliberate protocol choices, both about safety:**
//
//  1. **Fixed paths, no templating.** An addon is a base URL; the endpoints are `manifest.json` and
//     `stream`. A manifest that could name its own paths would be a manifest that can point the
//     client anywhere, and validating that is a much harder problem than not offering it.
//  2. **The query carries metadata and nothing else.** No Spotify token, no cookie, no account
//     identifier ever reaches an addon. See [`TrackQuery`], which is the complete list of what is
//     sent — written as a struct precisely so it can be read at a glance and shown to the user.

pub mod manifest;
pub mod registry;
pub mod security;
pub mod transport;

pub use manifest::{Manifest, Resource};
pub use registry::InstalledAddon;
pub use transport::{StreamAnswer, TrackQuery};

use crate::env::EnvError;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum AddonError {
    /// The URL did not parse.
    InvalidUrl(String),
    /// The URL parsed and we will not go there. See [`security`].
    Refused(String),
    /// The manifest was fetched and does not describe an addon we can use.
    InvalidManifest(String),
    /// Transport failure, or a status we cannot work with.
    Unreachable(String),
    /// The addon answered, and its answer breaks the protocol.
    Protocol(String),
    Storage(String),
}

impl std::fmt::Display for AddonError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            AddonError::InvalidUrl(m) => write!(f, "Invalid URL: {m}"),
            AddonError::Refused(m) => write!(f, "Refused: {m}"),
            AddonError::InvalidManifest(m) => write!(f, "Invalid manifest: {m}"),
            AddonError::Unreachable(m) => write!(f, "Addon unreachable: {m}"),
            AddonError::Protocol(m) => write!(f, "Addon protocol violation: {m}"),
            AddonError::Storage(m) => write!(f, "Storage error: {m}"),
        }
    }
}

impl std::error::Error for AddonError {}

impl From<EnvError> for AddonError {
    fn from(e: EnvError) -> Self {
        match e {
            EnvError::Fetch(m) => AddonError::Unreachable(m),
            EnvError::Serde(m) => AddonError::Protocol(m),
            EnvError::Storage(m) | EnvError::Other(m) => AddonError::Storage(m),
        }
    }
}
