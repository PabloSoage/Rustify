// core_engine/src/types/mod.rs
//
// Shared domain types. Point D of docs/stremio-core/PLAN-3.x.md.
//
// What is imported from `stremio-core` here is the *modelling discipline* — identity as sum types
// rather than strings — not their types. `MetaItem`, `Video` and `Stream` describe video
// catalogues; nothing about them fits audio with Spotify metadata.

pub mod id;

pub use id::{IdError, PlaylistId, TrackId};
