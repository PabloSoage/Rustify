// core_engine/src/youtube/scraper.rs
//
// YouTube / YouTube Music backend powered by RustyPipe.
//
// RustyPipe handles:
//   - InnerTube API key management
//   - Client versioning (ANDROID_MUSIC)
//   - n-parameter deobfuscation (via embedded QuickJS)
//   - Stream URL extraction
//   - Caching of player JS / client versions on disk

use crate::youtube::models::YouTubeTrack;
use rustypipe::client::RustyPipeBuilder;

use std::sync::OnceLock;

/// Global singleton RustyPipe client. Built once on first use and cached.
/// This eliminates repeated disk cache reads, version fetches, and TLS setup overhead on every track resolution.
static RUSTYPIPE_CLIENT: OnceLock<rustypipe::client::RustyPipe> = OnceLock::new();

/// Get or initialise the global RustyPipe client.
/// On Android the cache directory is set by the JNI layer via `init_cache_dir`.
fn get_client(cache_dir: &str) -> Result<rustypipe::client::RustyPipe, Box<dyn std::error::Error>> {
    if let Some(rp) = RUSTYPIPE_CLIENT.get() {
        return Ok(rp.clone());
    }
    let rp = RustyPipeBuilder::new()
        .storage_dir(cache_dir)
        .build()?;
    // Ignore error if another thread won the race.
    let _ = RUSTYPIPE_CLIENT.set(rp.clone());
    Ok(rp)
}

pub struct YouTubeScraper {
    cache_dir: String,
}

impl YouTubeScraper {
    pub fn new() -> Self {
        // When constructed without a cache_dir (e.g. from server.rs), we use
        // a sensible fallback. The server always calls new_with_cache when available.
        Self {
            cache_dir: "/data/data/com.varuna.rustify/cache".to_string(),
        }
    }

    pub fn new_with_cache(cache_dir: &str) -> Self {
        Self {
            cache_dir: cache_dir.to_string(),
        }
    }

    /// Search YouTube Music for tracks matching `query`.
    /// Returns a list of `YouTubeTrack` metadata structs.
    pub async fn search(&self, query: &str) -> Result<Vec<YouTubeTrack>, Box<dyn std::error::Error>> {
        let is_id = query.len() == 11 && query.chars().all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-');
        if is_id {
            return Ok(vec![YouTubeTrack {
                id: query.to_string(),
                title: "URL Directa (YouTube ID)".to_string(),
                author: "Coincidencia Exacta".to_string(),
                duration_sec: 0,
                thumbnail_url: format!("https://i.ytimg.com/vi/{}/hqdefault.jpg", query),
            }]);
        }

        let rp = get_client(&self.cache_dir)?;

        let mut all_tracks = Vec::new();

        // 1. Fetch Tracks
        if let Ok(results) = rp.query().music_search_tracks(query).await {
            let tracks: Vec<YouTubeTrack> = results
                .items
                .items
                .into_iter()
                .map(|item| {
                    let duration_sec = item.duration.unwrap_or(0);
                    let artist_names: Vec<String> = item.artists.iter().map(|a| a.name.clone()).collect();
                    let author = if artist_names.is_empty() { "Unknown".to_string() } else { artist_names.join(", ") };
                    let thumbnail_url = item.cover.into_iter().max_by_key(|t| t.width * t.height).map(|t| t.url).unwrap_or_default();
                    YouTubeTrack { id: item.id, title: item.name, author, duration_sec, thumbnail_url }
                })
                .collect();
            all_tracks.extend(tracks);
        }

        // 2. Fetch Videos (often needed for OSTs or covers)
        if let Ok(results) = rp.query().music_search_videos(query).await {
            let videos: Vec<YouTubeTrack> = results
                .items
                .items
                .into_iter()
                .map(|item| {
                    let duration_sec = item.duration.unwrap_or(0);
                    let artist_names: Vec<String> = item.artists.iter().map(|a| a.name.clone()).collect();
                    let author = if artist_names.is_empty() { "Unknown".to_string() } else { artist_names.join(", ") };
                    let thumbnail_url = item.cover.into_iter().max_by_key(|t| t.width * t.height).map(|t| t.url).unwrap_or_default();
                    YouTubeTrack { id: item.id, title: item.name, author, duration_sec, thumbnail_url }
                })
                .collect();
            
            // Avoid adding duplicates (videos that have the same ID as tracks)
            for video in videos {
                if !all_tracks.iter().any(|t| t.id == video.id) {
                    all_tracks.push(video);
                }
            }
        }

        Ok(all_tracks)
    }


}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_yt_search() {
        let rp = get_client("/tmp/rustify_test_cache").unwrap();
        let query = "OST Song of the welkin moon: moonlit ballad of the night full ver. | gesnhin impact sub esp. de cattpuriasong";
        println!("Searching videos for: {}", query);
        match rp.query().music_search_videos(query).await {
            Ok(results) => {
                println!("Results count: {}", results.items.items.len());
                for (i, r) in results.items.items.iter().enumerate() {
                    println!(
                        "{}: ID={}, Title='{}', Author='{}'",
                        i, r.id, r.name, r.artists.first().map(|a| a.name.as_str()).unwrap_or("Unknown")
                    );
                }
            }
            Err(e) => {
                println!("music_search_videos failed: {:?}", e);
            }
        }
    }


}