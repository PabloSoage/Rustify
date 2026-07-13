use crate::youtube::models::YouTubeTrack;
// core_engine/src/youtube/server.rs
// NOTE: the standalone loopback HTTP server (`/resolve`, proxyPort) was removed (E11):
// it was dead code — Kotlin resolves YouTube IDs directly via JNI (`resolveYouTubeIdNative`)
// and ExoPlayer streams directly from googlevideo. Keeping it only added attack surface
// (the 0.0.0.0 fallback exposed /resolve to the LAN). The cache-dir + mappings helpers
// below are still required by the resolver.
use std::collections::{HashMap, HashSet};
use std::sync::{Mutex, OnceLock};

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

pub fn init_cache_dir(dir: &str) {
    let _ = CACHE_DIR.set(dir.to_string());
    // Load existing mappings
    let map = load_mappings_from_disk(dir);
    let mappings_lock = YOUTUBE_MAPPINGS.get_or_init(|| Mutex::new(HashMap::new()));
    if let Ok(mut lock) = mappings_lock.lock() {
        *lock = map;
    }
}

pub fn get_cache_dir() -> Option<String> {
    CACHE_DIR.get().cloned()
}

pub async fn resolve_youtube_id_direct(track_id: &str, youtube_id_opt: Option<&str>, cache_dir: &str) -> Option<String> {
    // User-confirmed mapping ALWAYS takes priority over auto-matched hints
    if let Some(mapped) = get_alternative_track(track_id) {
        log_info!("[Resolver] Using mapped YT id={} for spotify_id={} (user mapping wins over hint)", mapped, track_id);
        return Some(mapped);
    }
    if let Some(yt_id) = youtube_id_opt {
        set_alternative_track(track_id.to_string(), yt_id.to_string());
        return Some(yt_id.to_string());
    }
    resolve_youtube_id(track_id, cache_dir).await
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
    match mappings_lock.lock() {
        Ok(lock) => lock.get(spotify_id).cloned(),
        Err(_) => {
            log_info!("[Mappings] CRITICAL: YOUTUBE_MAPPINGS mutex is poisoned — all user mappings are unavailable!");
            None
        }
    }
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

pub async fn resolve_youtube_id(track_id: &str, cache_dir: &str) -> Option<String> {
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
    // Pre-compute HashSet for O(1) word lookup (BUG-16 optimization)
    let spotify_words: std::collections::HashSet<&str> = clean_spotify_name.split_whitespace().collect();

    struct ArtistMeta {
        clean: String,
        words: Vec<String>,
    }
    let precomputed_artists: Vec<ArtistMeta> = meta.artists.iter().map(|artist| {
        let clean = clean_text(artist);
        let words = clean.split_whitespace().map(|s| s.to_string()).collect();
        ArtistMeta { clean, words }
    }).collect();

    let mut best_track: Option<YouTubeTrack> = None;
    let mut best_score = -1;

    for (i, yt_track) in results.iter().enumerate() {
        let mut score = 0;
        let clean_yt_title = clean_text(&yt_track.title);
        let yt_words: HashSet<&str> = clean_yt_title.split_whitespace().collect();

        // Exact match
        if clean_yt_title.contains(&clean_spotify_name) {
            score += 50;
        }

        // Word overlap for title (O(1) lookup with HashSet)
        for word in &spotify_words {
            if yt_words.contains(word) {
                score += 5;
            } else if clean_yt_title.contains(word) {
                score += 2;
            }
        }

        // Artist matching
        for artist_meta in &precomputed_artists {
            // Check if artist name is in title
            if clean_yt_title.contains(&artist_meta.clean) {
                score += 20;
            } else {
                for word in &artist_meta.words {
                    if yt_words.contains(word.as_str()) {
                        score += 3;
                    }
                }
            }

            // Check if artist name is in channel name
            let clean_author = clean_text(&yt_track.author);
            if clean_author.contains(&artist_meta.clean) {
                score += 20;
            } else {
                for word in &artist_meta.words {
                    if clean_author.contains(word.as_str()) {
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

        // Duration matching
        if meta.duration_ms > 0 && yt_track.duration_sec > 0 {
            let spotify_dur_sec = meta.duration_ms / 1000;
            let yt_dur_sec = yt_track.duration_sec;
            let diff = (spotify_dur_sec as i32 - yt_dur_sec as i32).abs();
            if diff <= 4 {
                score += 35;
            } else if diff <= 10 {
                score += 15;
            } else if diff > 60 {
                score -= 100;
            } else if diff > 30 {
                score -= 50;
            }
        }

        // Search rank baseline bonus
        if i == 0 {
            score += 20;
        } else if i == 1 {
            score += 10;
        } else if i == 2 {
            score += 5;
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
