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

use rustypipe::client::RustyPipeBuilder;
use crate::youtube::models::YouTubeTrack;

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
        let rp = get_client(&self.cache_dir)?;

        // Use YouTube Music search (WEB_REMIX client)
        let results = rp.query().music_search_tracks(query).await?;

        let tracks: Vec<YouTubeTrack> = results
            .items
            .items
            .into_iter()
            .map(|item| {
                let duration_sec = item.duration.unwrap_or(0);
                let artist_names: Vec<String> = item
                    .artists
                    .iter()
                    .map(|a| a.name.clone())
                    .collect();
                let author = if artist_names.is_empty() {
                    "Unknown".to_string()
                } else {
                    artist_names.join(", ")
                };

                // cover is Vec<Thumbnail> — pick highest resolution thumbnail
                let thumbnail_url = item
                    .cover
                    .into_iter()
                    .max_by_key(|t| t.width * t.height)
                    .map(|t| t.url)
                    .unwrap_or_default();

                YouTubeTrack {
                    id: item.id,
                    title: item.name,
                    author,
                    duration_sec,
                    thumbnail_url,
                }
            })
            .collect();

        Ok(tracks)
    }


}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_yt_search() {
        let scraper = YouTubeScraper::new_with_cache("/tmp/rustify_test_cache");
        let query = "Queen Bohemian Rhapsody";
        println!("Searching for: {}", query);
        match scraper.search(query).await {
            Ok(results) => {
                println!("Results count: {}", results.len());
                for (i, r) in results.iter().enumerate() {
                    println!(
                        "{}: ID={}, Title='{}', Author='{}', DurationSec={}, Thumb='{}'",
                        i, r.id, r.title, r.author, r.duration_sec, r.thumbnail_url
                    );
                }
                assert!(!results.is_empty(), "Search results should not be empty!");
            }
            Err(e) => {
                panic!("Search failed: {:?}", e);
            }
        }
    }


}