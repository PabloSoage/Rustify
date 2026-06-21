use crate::youtube::models::YouTubeTrack;
// core_engine/src/youtube/server.rs
use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;

macro_rules! log_info {
    ($($arg:tt)*) => {{
        let msg = format!($($arg)*);
        #[cfg(target_os = "android")]
        {
            use std::ffi::CString;
            extern "C" {
                pub fn __android_log_write(
                    prio: std::os::raw::c_int,
                    tag: *const std::os::raw::c_char,
                    text: *const std::os::raw::c_char,
                ) -> std::os::raw::c_int;
            }
            if let (Ok(tag_c), Ok(msg_c)) = (CString::new("RustProxy"), CString::new(msg.clone())) {
                unsafe {
                    __android_log_write(4, tag_c.as_ptr(), msg_c.as_ptr());
                }
            }
        }
        eprintln!("{}", msg);
    }};
}

// Spotify track metadata registered from JNI
#[derive(Clone, Debug)]
pub struct SpotifyTrackMeta {
    pub name: String,
    pub artists: Vec<String>,
    pub duration_ms: u32,
    pub isrc: String,
}

static TRACK_METADATA: OnceLock<Mutex<HashMap<String, SpotifyTrackMeta>>> = OnceLock::new();
static YOUTUBE_MAPPINGS: OnceLock<Mutex<HashMap<String, String>>> = OnceLock::new();
static CACHE_DIR: OnceLock<String> = OnceLock::new();
static SERVER_PORT: OnceLock<u16> = OnceLock::new();

pub fn init_cache_dir(dir: &str) {
    let _ = CACHE_DIR.set(dir.to_string());
    // Load existing mappings
    let map = load_mappings_from_disk(dir);
    let mappings_lock = YOUTUBE_MAPPINGS.get_or_init(|| Mutex::new(HashMap::new()));
    if let Ok(mut lock) = mappings_lock.lock() {
        *lock = map;
    }
}

pub fn get_server_port() -> u16 {
    *SERVER_PORT.get().unwrap_or(&0)
}

pub fn register_track_meta(id: String, name: String, artists: Vec<String>, duration_ms: u32, isrc: String) {
    let meta_lock = TRACK_METADATA.get_or_init(|| Mutex::new(HashMap::new()));
    if let Ok(mut lock) = meta_lock.lock() {
        lock.insert(id, SpotifyTrackMeta { name, artists, duration_ms, isrc });
    }
}

pub fn set_alternative_track(spotify_id: String, youtube_id: String) {
    let mappings_lock = YOUTUBE_MAPPINGS.get_or_init(|| Mutex::new(HashMap::new()));
    if let Ok(mut lock) = mappings_lock.lock() {
        lock.insert(spotify_id.clone(), youtube_id.clone());
        if let Some(cache_dir) = CACHE_DIR.get() {
            save_mappings_to_disk(cache_dir, &lock);
        }
    }
}

pub fn get_alternative_track(spotify_id: &str) -> Option<String> {
    let mappings_lock = YOUTUBE_MAPPINGS.get_or_init(|| Mutex::new(HashMap::new()));
    if let Ok(lock) = mappings_lock.lock() {
        return lock.get(spotify_id).cloned();
    }
    None
}

// Background queue updates (pre-buffering task)
pub fn update_playback_queue(track_ids: Vec<String>) {
    let cache_dir = match CACHE_DIR.get() {
        Some(dir) => dir.clone(),
        None => return,
    };

    tokio::spawn(async move {
        // Pre-buffer up to next 3 tracks in queue
        let tracks_to_buffer = track_ids.iter().take(3);
        for track_id in tracks_to_buffer {
            // Resolve YouTube URL to cache the mapping
            let _ = resolve_youtube_id(track_id, &cache_dir).await;
        }
    });
}

// Start the HTTP server in background tokio task
pub async fn start_server(cache_dir: String) -> Result<u16, Box<dyn std::error::Error>> {
    init_cache_dir(&cache_dir);

    // Bind to 127.0.0.1 on port 0 (OS allocates free port), fallback to 0.0.0.0 if IPv4 loopback fails
    let listener = match TcpListener::bind("127.0.0.1:0").await {
        Ok(l) => l,
        Err(_) => TcpListener::bind("0.0.0.0:0").await?,
    };
    let port = listener.local_addr()?.port();
    let _ = SERVER_PORT.set(port);

    tokio::spawn(async move {
        loop {
            match listener.accept().await {
                Ok((mut socket, _)) => {
                    let cache = cache_dir.clone();
                    tokio::spawn(async move {
                        if let Err(e) = handle_connection(&mut socket, &cache).await {
                            eprintln!("Proxy Connection error: {}", e);
                        }
                    });
                }
                Err(e) => {
                    eprintln!("Proxy Server accept error: {}", e);
                }
            }
        }
    });

    Ok(port)
}

async fn handle_connection(socket: &mut tokio::net::TcpStream, cache_dir: &str) -> Result<(), Box<dyn std::error::Error>> {
    let mut buf = [0u8; 4096];
    let n = socket.read(&mut buf).await?;
    if n == 0 {
        return Ok(());
    }

    let req_str = String::from_utf8_lossy(&buf[..n]);
    let first_line = req_str.lines().next().unwrap_or("");
    let parts: Vec<&str> = first_line.split_whitespace().collect();
    if parts.len() < 2 || parts[0] != "GET" {
        socket.write_all(b"HTTP/1.1 400 Bad Request\r\n\r\n").await?;
        return Ok(());
    }

    let path = parts[1].to_string();

    // --- /resolve endpoint: returns the YouTube ID as plain text ---
    // Called from Kotlin before starting ExoPlayer so we can resolve the Spotify ID to a YouTube ID
    if path.starts_with("/resolve") {
        let track_id = match extract_query_param(&path, "track_id") {
            Some(id) => id,
            None => {
                socket.write_all(b"HTTP/1.1 400 Missing track_id\r\n\r\n").await?;
                return Ok(());
            }
        };
        let youtube_id_opt = extract_query_param(&path, "youtube_id");
        log_info!("[Resolve] Resolving track_id={} youtube_id={:?}", track_id, youtube_id_opt);

        let final_yt_id = if let Some(yt_id) = youtube_id_opt {
            Some(yt_id)
        } else {
            resolve_youtube_id(&track_id, cache_dir).await
        };

        if let Some(yt_id) = final_yt_id {
            log_info!("[Resolve] OK youtube_id={}", yt_id);
            let resp = format!(
                "HTTP/1.1 200 OK\r\n\
                 Content-Type: text/plain; charset=utf-8\r\n\
                 Content-Length: {}\r\n\
                 Connection: close\r\n\r\n\
                 {}",
                yt_id.len(),
                yt_id
            );
            socket.write_all(resp.as_bytes()).await?;
        } else {
            log_info!("[Resolve] FAIL for track_id={}", track_id);
            socket.write_all(b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n").await?;
        }
        return Ok(());
    }

    socket.write_all(b"HTTP/1.1 404 Not Found\r\n\r\n").await?;
    Ok(())
}

async fn resolve_youtube_id(track_id: &str, cache_dir: &str) -> Option<String> {
    // Check if we already have an alternative YT ID mapped
    let alt = get_alternative_track(track_id);
    if let Some(yt_id) = alt {
        log_info!("[Resolver] Using mapped YT id={} for spotify_id={}", yt_id, track_id);
        return Some(yt_id);
    }

    // Find registered metadata
    let meta = {
        let meta_lock = TRACK_METADATA.get_or_init(|| Mutex::new(HashMap::new()));
        let lock = meta_lock.lock().ok()?;
        let m = lock.get(track_id).cloned();
        if m.is_none() {
            log_info!("[Resolver] No metadata registered for track_id={}", track_id);
        }
        m?
    };

    // Construct YouTube Music query — ISRC gives best precision
    let query = if !meta.isrc.is_empty() {
        log_info!("[Resolver] Searching by ISRC: {}", meta.isrc);
        meta.isrc.clone()
    } else {
        let q = format!("{} {}", meta.name, meta.artists.join(" "));
        log_info!("[Resolver] Searching by name: {}", q);
        q
    };

    log_info!("[Resolver] Initializing YouTubeScraper");
    let scraper = crate::youtube::scraper::YouTubeScraper::new_with_cache(cache_dir);
    log_info!("[Resolver] Calling scraper.search with query='{}'", query);
    let results = scraper.search(&query).await.ok()?;
    log_info!("[Resolver] Search returned {} results", results.len());

    let best_match = match_best_track(&meta, &results)?;
    log_info!("[Resolver] Best match: id={} title='{}'", best_match.id, best_match.title);

    // Cache the mapping in memory and disk
    set_alternative_track(track_id.to_string(), best_match.id.clone());

    log_info!("[Resolver] resolve_youtube_id finished successfully");
    Some(best_match.id)
}

fn match_best_track(meta: &SpotifyTrackMeta, results: &[YouTubeTrack]) -> Option<YouTubeTrack> {
    if results.is_empty() {
        return None;
    }

    let clean_spotify_name = clean_text(&meta.name);
    let spotify_words: Vec<&str> = clean_spotify_name.split_whitespace().collect();
    
    let mut best_track: Option<YouTubeTrack> = None;
    let mut best_score = -1;

    for yt_track in results {
        let mut score = 0;
        let clean_yt_title = clean_text(&yt_track.title);
        let yt_words: Vec<&str> = clean_yt_title.split_whitespace().collect();

        // Exact match
        if clean_yt_title.contains(&clean_spotify_name) {
            score += 50;
        }

        // Word overlap for title
        for word in &spotify_words {
            if yt_words.contains(word) {
                score += 5;
            } else if clean_yt_title.contains(word) {
                score += 2;
            }
        }

        // Artist matching
        for artist in &meta.artists {
            let clean_artist = clean_text(artist);
            let artist_words: Vec<&str> = clean_artist.split_whitespace().collect();
            
            // Check if artist name is in title
            if clean_yt_title.contains(&clean_artist) {
                score += 20;
            } else {
                for word in &artist_words {
                    if yt_words.contains(word) {
                        score += 3;
                    }
                }
            }
            
            // Check if artist name is in channel name
            let clean_author = clean_text(&yt_track.author);
            if clean_author.contains(&clean_artist) {
                score += 20;
            } else {
                for word in &artist_words {
                    if clean_author.contains(word) {
                        score += 3;
                    }
                }
            }
        }

        let is_official = clean_yt_title.contains("official") || 
                          clean_yt_title.contains("audio") || 
                          clean_yt_title.contains("lyric") ||
                          clean_yt_title.contains("music video");
        if is_official {
            score += 10;
        }

        // Penalty for things that are likely covers or karaoke if not in original name
        let is_cover = clean_yt_title.contains("cover") || clean_yt_title.contains("karaoke");
        let orig_has_cover = clean_spotify_name.contains("cover") || clean_spotify_name.contains("karaoke");
        if is_cover && !orig_has_cover {
            score -= 30;
        }

        // Penalty for 1-hour or extended versions
        let is_extended = clean_yt_title.contains("1 hour") || clean_yt_title.contains("extended") || clean_yt_title.contains("loop");
        let orig_has_extended = clean_spotify_name.contains("extended") || clean_spotify_name.contains("loop");
        if is_extended && !orig_has_extended {
            score -= 30;
        }
        
        log_info!("[Resolver] Scoring: '{}' by '{}' -> Score: {}", yt_track.title, yt_track.author, score);

        if score > best_score {
            best_score = score;
            best_track = Some(yt_track.clone());
        }
    }

    best_track.or_else(|| results.first().cloned())
}

fn clean_text(text: &str) -> String {
    text.to_lowercase()
        .chars()
        .filter(|c| c.is_alphanumeric() || c.is_whitespace())
        .collect::<String>()
}

// HTTP Helper parsers
fn extract_query_param(path: &str, param: &str) -> Option<String> {
    let query_start = path.find('?')?;
    let query = &path[query_start + 1..];
    for pair in query.split('&') {
        let mut split = pair.splitn(2, '=');
        let key = split.next()?;
        let val = split.next()?;
        if key == param {
            return Some(urlencoding::decode(val).ok()?.into_owned());
        }
    }
    None
}


fn load_mappings_from_disk(cache_dir: &str) -> HashMap<String, String> {
    let path = format!("{}/youtube_mappings.json", cache_dir);
    if let Ok(content) = std::fs::read_to_string(path) {
        if let Ok(map) = serde_json::from_str(&content) {
            return map;
        }
    }
    HashMap::new()
}

fn save_mappings_to_disk(cache_dir: &str, map: &HashMap<String, String>) {
    let path = format!("{}/youtube_mappings.json", cache_dir);
    if let Ok(content) = serde_json::to_string(map) {
        let _ = std::fs::write(path, content);
    }
}
