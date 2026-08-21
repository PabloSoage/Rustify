use crate::youtube::models::YouTubeTrack;
// core_engine/src/youtube/server.rs
// NOTE: the standalone loopback HTTP server (`/resolve`, proxyPort) was removed (E11):
// it was dead code — Kotlin resolves YouTube IDs directly via JNI (`resolveYouTubeIdNative`)
// and ExoPlayer streams directly from googlevideo. Keeping it only added attack surface
// (the 0.0.0.0 fallback exposed /resolve to the LAN). The cache-dir + mappings helpers
// below are still required by the resolver.
use crate::env::{schema, storage, Env, LogLevel};
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
}

/// Brings persisted state up to date and loads the manual YouTube id mappings into memory.
///
/// Async because storage is, and awaited rather than spawned at startup: a resolve that runs before
/// the mappings land would silently ignore a mapping the user chose by hand, which reads as "my
/// override stopped working".
///
/// `legacy_dir` is the pre-3.0 cache directory, where `youtube_mappings.json` used to live. The
/// migration copies it and leaves the original alone — see [`schema::migrate_to_v1`].
pub async fn init_mappings<E: Env>(legacy_dir: &str) {
    if let Err(e) = schema::migrate::<E>(Some(legacy_dir)).await {
        E::log(
            LogLevel::Error,
            "Mappings",
            &format!("schema migration failed, keeping in-memory only: {e}"),
        );
    }
    let map = storage::get_json::<E, HashMap<String, String>>(schema::MAPPINGS_KEY)
        .await
        .unwrap_or(None)
        .unwrap_or_default();
    log_info!("[Mappings] loaded {} manual mappings", map.len());
    let mappings_lock = YOUTUBE_MAPPINGS.get_or_init(|| Mutex::new(HashMap::new()));
    if let Ok(mut lock) = mappings_lock.lock() {
        *lock = map;
    }
}

pub fn get_cache_dir() -> Option<String> {
    CACHE_DIR.get().cloned()
}

pub async fn resolve_youtube_id_direct<E: Env>(track_id: &str, youtube_id_opt: Option<&str>, cache_dir: &str) -> Option<String> {
    if let Some(yt_id) = youtube_id_opt {
        log_info!("[Resolver] Using explicit/hint YT id={} for spotify_id={}", yt_id, track_id);
        return Some(yt_id.to_string());
    }
    if let Some(mapped) = get_alternative_track(track_id) {
        log_info!("[Resolver] Using mapped YT id={} for spotify_id={} (user mapping wins over auto-resolve)", mapped, track_id);
        return Some(mapped);
    }
    resolve_youtube_id::<E>(track_id, cache_dir).await
}

pub fn register_track_meta(id: String, name: String, artists: Vec<String>, duration_ms: u32, isrc: String) {
    let meta_lock = TRACK_METADATA.get_or_init(|| Mutex::new(HashMap::new()));
    if let Ok(mut lock) = meta_lock.lock() {
        lock.insert(id, SpotifyTrackMeta { name, artists, duration_ms, isrc });
    }
}

/// Records a manual mapping and persists it.
///
/// Memory is updated synchronously so the very next resolve sees it; the write goes out on a
/// background task, because the caller is a JNI bridge and a JNI call blocks the thread that made
/// it — including the UI thread, if that is where it came from.
pub fn set_alternative_track<E: Env>(spotify_id: String, youtube_id: String) {
    let mappings_lock = YOUTUBE_MAPPINGS.get_or_init(|| Mutex::new(HashMap::new()));
    let snapshot = match mappings_lock.lock() {
        Ok(mut lock) => {
            lock.insert(spotify_id, youtube_id);
            // Cloned under the lock: the write must not observe a half-applied later edit, and the
            // lock must not be held across an await.
            lock.clone()
        }
        Err(_) => {
            log_info!("[Mappings] CRITICAL: mutex poisoned — mapping not recorded");
            return;
        }
    };
    E::exec_concurrent(async move {
        if let Err(e) = storage::set_json::<E, _>(schema::MAPPINGS_KEY, Some(&snapshot)).await {
            E::log(
                LogLevel::Error,
                "Mappings",
                &format!("could not persist manual mappings: {e}"),
            );
        }
    });
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
pub fn update_playback_queue<E: Env>(track_ids: Vec<String>) {
    let cache_dir = match CACHE_DIR.get() {
        Some(dir) => dir.clone(),
        None => return,
    };

    E::exec_concurrent(async move {
        // Pre-buffer up to next 3 tracks in queue
        let tracks_to_buffer = track_ids.iter().take(3);
        for track_id in tracks_to_buffer {
            // Resolve YouTube URL to cache the mapping
            let _ = resolve_youtube_id::<E>(track_id, &cache_dir).await;
        }
    });
}

pub async fn resolve_youtube_id<E: Env>(track_id: &str, cache_dir: &str) -> Option<String> {
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

    // Cache the mapping in memory and storage
    set_alternative_track::<E>(track_id.to_string(), best_match.id.clone());

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

// `load_mappings_from_disk` / `save_mappings_to_disk` were removed in 3.0. Mappings now live under
// `env::schema::MAPPINGS_KEY` in versioned storage, with atomic writes; the one remaining read of
// the old loose file is the v0 → v1 migration in `env::schema`.

#[cfg(test)]
mod tests {
    use super::*;
    use crate::env::mock::{self, MockEnv};
    use crate::youtube::models::YouTubeTrack;

    // ============================================================================================
    // This file was at 0 %, and it holds the decision that picks WHICH VIDEO PLAYS. When the matcher
    // is wrong the app does not fail — it plays the wrong recording, or a live version, or a
    // fourteen-minute "extended mix" of a three-minute song. That is the least visible way for a
    // music player to be broken, and the only way to hold it still is a test.
    //
    // The mappings use process-wide statics, so every test here uses ids of its own rather than
    // relying on an order the test runner does not promise.
    // ============================================================================================

    fn yt(id: &str, title: &str, author: &str, seconds: u32) -> YouTubeTrack {
        YouTubeTrack {
            id: id.into(),
            title: title.into(),
            author: author.into(),
            duration_sec: seconds,
            thumbnail_url: String::new(),
        }
    }

    fn meta(name: &str, artists: &[&str], seconds: u32) -> SpotifyTrackMeta {
        SpotifyTrackMeta {
            name: name.into(),
            artists: artists.iter().map(|a| a.to_string()).collect(),
            duration_ms: seconds * 1000,
            isrc: String::new(),
        }
    }

    #[test]
    fn cleaning_keeps_the_words_and_drops_everything_else() {
        // Punctuation is what differs between "Song (Remastered)" and "Song - Remastered", and
        // comparing raw titles makes those two look like different songs.
        assert_eq!(clean_text("Don't Stop Me Now!"), "dont stop me now");
        assert_eq!(clean_text("Song — Remastered (2011)"), "song  remastered 2011");
        assert_eq!(clean_text("ABBA"), "abba");
    }

    #[test]
    fn nothing_to_choose_from_is_no_choice_rather_than_a_panic() {
        assert!(match_best_track(&meta("Whatever", &["Someone"], 200), &[]).is_none());
    }

    #[test]
    fn the_right_song_by_the_right_artist_wins_over_a_title_that_merely_contains_it() {
        let wanted = meta("Smooth", &["Santana"], 300);
        let results = vec![
            yt("wrong", "Smooth Criminal", "Michael Jackson", 250),
            yt("right", "Smooth", "Santana", 298),
        ];
        let picked = match_best_track(&wanted, &results).expect("something must be picked");
        assert_eq!(picked.id, "right");
    }

    #[test]
    fn a_duration_that_is_nowhere_near_loses_to_one_that_matches() {
        // The failure a person notices immediately: an hour-long "full album" upload that happens to
        // have the song's name in its title.
        let wanted = meta("Clocks", &["Coldplay"], 307);
        let results = vec![
            yt("album", "Coldplay - Clocks (Full Album)", "Coldplay", 3_100),
            yt("song", "Coldplay - Clocks", "Coldplay", 305),
        ];
        let picked = match_best_track(&wanted, &results).unwrap();
        assert_eq!(picked.id, "song");
    }

    #[test]
    fn a_choice_is_still_made_when_nothing_matches_well() {
        // Returning nothing means the track cannot play at all. A poor match that plays beats a
        // perfect match that does not exist, so the matcher must always answer with something when
        // it was given something.
        let wanted = meta("Something Very Specific", &["An Artist"], 200);
        let results = vec![yt("only", "Completely Unrelated", "Nobody", 999)];
        assert!(match_best_track(&wanted, &results).is_some());
    }

    #[tokio::test]
    async fn a_hint_beats_everything_because_the_caller_already_knows() {
        let _guard = mock::lock_and_reset();
        let answer =
            resolve_youtube_id_direct::<MockEnv>("spotify-hint", Some("HINTED_ID"), "/tmp").await;
        assert_eq!(answer.as_deref(), Some("HINTED_ID"));
    }

    #[tokio::test]
    async fn a_mapping_the_user_chose_by_hand_beats_the_automatic_match() {
        // The whole point of "pick a different version": if the resolver could override it, the
        // choice would look like it did nothing.
        let _guard = mock::lock_and_reset();
        set_alternative_track::<MockEnv>("spotify-manual".into(), "CHOSEN_BY_HAND".into());

        assert_eq!(
            get_alternative_track("spotify-manual").as_deref(),
            Some("CHOSEN_BY_HAND")
        );
        let answer = resolve_youtube_id_direct::<MockEnv>("spotify-manual", None, "/tmp").await;
        assert_eq!(answer.as_deref(), Some("CHOSEN_BY_HAND"));
    }

    #[test]
    fn a_track_nobody_mapped_has_no_mapping() {
        assert!(get_alternative_track("spotify-never-mapped").is_none());
    }

    #[test]
    fn registering_metadata_makes_a_track_resolvable_later() {
        register_track_meta(
            "spotify-meta".into(),
            "A Song".into(),
            vec!["An Artist".into()],
            210_000,
            "ISRC123".into(),
        );
        let store = TRACK_METADATA.get().expect("registering must create the store");
        let held = store.lock().unwrap();
        let entry = held.get("spotify-meta").expect("the track must be there");
        assert_eq!(entry.name, "A Song");
        assert_eq!(entry.duration_ms, 210_000);
        assert_eq!(entry.isrc, "ISRC123");
    }
}
