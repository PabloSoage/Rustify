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
use crate::youtube::models::{YouTubeTrack, AudioStream};

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
                let duration_sec = item.duration.unwrap_or(0) as u32;
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

    /// Get audio streams for a YouTube video ID.
    /// Uses the default client (Ios) as it's the only one currently stable without PO Tokens.
    pub async fn get_audio_streams(&self, video_id: &str) -> Result<Vec<AudioStream>, Box<dyn std::error::Error>> {
        let rp = get_client(&self.cache_dir)?;

        // `player()` uses the default client (Ios) -> no PO token required
        let player = rp.query().player(video_id).await?;


        // Collect audio streams using the rustypipe API
        // AudioStream.format is AudioFormat enum: M4a or Webm
        // AudioStream.url is the final direct URL (deobfuscated, n-param handled)
        // AudioStream.bitrate is u32 (not Option)
        let mut streams: Vec<AudioStream> = player
            .audio_streams
            .into_iter()
            .filter_map(|fmt| {
                let url = fmt.url.clone();
                if url.is_empty() {
                    return None;
                }
                // Determine container from format enum
                use rustypipe::model::AudioFormat;
                let container = match fmt.format {
                    AudioFormat::Webm => "webm".to_string(),
                    AudioFormat::M4a | _ => "mp4".to_string(),
                };
                Some(AudioStream {
                    url,
                    container,
                    bitrate: fmt.bitrate,
                })
            })
            .collect();

        if streams.is_empty() {
            return Err("No direct audio streams available for this video".into());
        }

        // Prefer Opus/WebM (higher quality at same bitrate), then sort by bitrate descending
        streams.sort_by(|a, b| {
            // WebM first
            let a_webm = if a.container == "webm" { 1u32 } else { 0u32 };
            let b_webm = if b.container == "webm" { 1u32 } else { 0u32 };
            b_webm.cmp(&a_webm).then(b.bitrate.cmp(&a.bitrate))
        });

        Ok(streams)
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

    #[tokio::test]
    async fn test_yt_get_audio_streams() {
        let scraper = YouTubeScraper::new_with_cache("/tmp/rustify_test_cache");
        let video_id = "bSnlKl_PoQU"; // Bohemian Rhapsody
        println!("Fetching streams for: {}", video_id);
        match scraper.get_audio_streams(video_id).await {
            Ok(streams) => {
                println!("Streams count: {}", streams.len());
                for (i, s) in streams.iter().enumerate() {
                    println!(
                        "{}: Container={}, Bitrate={}, Url='{}...'",
                        i, s.container, s.bitrate,
                        &s.url[..s.url.len().min(80)]
                    );
                }
                assert!(!streams.is_empty(), "Streams list should not be empty!");
                assert!(
                    streams[0].url.contains("googlevideo.com") || streams[0].url.contains("youtube.com"),
                    "Stream URL should be a direct video link!"
                );
            }
            Err(e) => {
                panic!("Fetch streams failed: {:?}", e);
            }
        }
    }

    #[tokio::test]
    async fn test_iframe_api() {
        let client = reqwest::Client::new();
        let res = client.get("https://www.youtube.com/iframe_api").send().await.unwrap();
        let text = res.text().await.unwrap();
        println!("iframe_api response:\n{}", text);
    }

    #[tokio::test]
    async fn test_googlevideo_ranges() {
        let video_id = "1zORiYv3VmU";
        // Do not use cache to ensure fresh URLs
        let rp = rustypipe::client::RustyPipeBuilder::new().build().unwrap();

        // Helper to extract query param correctly
        let extract_param = |u: &str, param: &str| -> String {
            if let Some(query_start) = u.find('?') {
                let query = &u[query_start + 1..];
                for pair in query.split('&') {
                    let mut split = pair.splitn(2, '=');
                    if let (Some(key), Some(val)) = (split.next(), split.next()) {
                        if key == param {
                            return urlencoding::decode(val).unwrap().into_owned();
                        }
                    }
                }
            }
            String::new()
        };

        let player = rp.query().player_from_client(video_id, rustypipe::client::ClientType::Ios).await.unwrap();
        let url = &player.audio_streams[0].url;
        println!("Fresh URL: {}", &url[..url.len().min(150)]);

        let ip_param = extract_param(url, "ip");
        let is_ipv6 = ip_param.contains(':');
        let client_builder = reqwest::Client::builder();
        let client_builder = if is_ipv6 {
            client_builder.local_address(std::net::IpAddr::V6(std::net::Ipv6Addr::UNSPECIFIED))
        } else {
            client_builder.local_address(std::net::IpAddr::V4(std::net::Ipv4Addr::UNSPECIFIED))
        };
        let client = client_builder.build().unwrap();

        let client_param = extract_param(url, "c");
        let user_agent = match client_param.as_str() {
            "IOS" => "com.google.ios.youtube/20.03.02 (iPhone16,2; U; CPU iOS 18_2_1 like Mac OS X)",
            _ => "Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0",
        };

        // Test 1: 1MB starting at 0
        let res1 = client.get(url)
            .header(reqwest::header::RANGE, "bytes=0-1048575")
            .header(reqwest::header::USER_AGENT, user_agent)
            .send().await.unwrap();
        println!("bytes=0-1048575 status: {}", res1.status());

        // Test 2: 2MB starting at 0
        let res2 = client.get(url)
            .header(reqwest::header::RANGE, "bytes=0-2097151")
            .header(reqwest::header::USER_AGENT, user_agent)
            .send().await.unwrap();
        println!("bytes=0-2097151 status: {}", res2.status());

        // Test 3: open-ended starting at 0
        let res3 = client.get(url)
            .header(reqwest::header::RANGE, "bytes=0-")
            .header(reqwest::header::USER_AGENT, user_agent)
            .send().await.unwrap();
        println!("bytes=0- status: {}", res3.status());

        // Test 4: no Range header
        let res4 = client.get(url)
            .header(reqwest::header::USER_AGENT, user_agent)
            .send().await.unwrap();
        println!("No Range header status: {}", res4.status());
    }
}