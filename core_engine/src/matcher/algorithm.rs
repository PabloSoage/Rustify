// core_engine/src/matcher/algorithm.rs
use serde::{Deserialize, Serialize};

/// Represents the clean metadata extracted from Spotify
#[derive(Debug, Serialize, Deserialize)]
pub struct SpotifyTrack {
    pub id: String,
    pub name: String,
    pub artists: Vec<String>,
    pub isrc: Option<String>,
    pub duration_ms: u32,
}

/// Generates the optimized search query for YouTube
pub fn build_youtube_query(track: &SpotifyTrack) -> String {
    // 1. If the ISRC code exists, it provides the most accurate search
    if let Some(isrc) = &track.isrc {
        if !isrc.is_empty() {
            // Example: "USUM71700966"
            return isrc.clone();
        }
    }

    // 2. Fallback: Concatenate "Song Name + Artist 1, Artist 2"
    let artists_joined = track.artists.join(", ");
    format!("{} {}", track.name, artists_joined)
}
