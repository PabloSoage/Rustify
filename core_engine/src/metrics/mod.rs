// core_engine/src/metrics/mod.rs
//
// The listening history: every play, kept, in segments that only grow.
//
// It lived in Kotlin as one `metrics.json`, rewritten whole on every play and cut to the last 5 000
// entries — also after an import. That cut is the reason this module exists. An Extended Streaming
// History from Spotify is tens or hundreds of thousands of plays: it was imported, reported as
// "added N", and reduced to 5 000. And because the cut went by insertion order rather than by date,
// an import appended *behind* the app's own plays and kept the tail of the import — so importing an
// old history deleted the most recent listening, which is the part nobody has a copy of.
//
// ## Shape on disk
//
// One key per calendar year (UTC) of `startedAt`, `metrics.<year>.jsonl`, one event per line, plus
// `metrics.years` listing the years present (storage has no "list keys"). A play is an append to
// one segment. A line that fails to parse is skipped on read — that is what a process death in the
// middle of an append leaves behind, and it must not cost the lines around it.
//
// ## Two timestamps that were wrong, and why fixing them needs care
//
// Spotify's exports say when a stream **ended**: `ts` in the Extended format and `endTime` in the
// legacy one, both UTC. The Kotlin reader stored them as `startedAt`, and read `endTime` in the
// phone's time zone. Both are corrected here — `startedAt = end − played` — and that moves every
// imported event. Re-importing a file that was imported before the fix would then duplicate all of
// it, so the deduplication also recognises events in the old interpretation; see [`ImportKeys`].

pub mod stats;

use crate::env::{Env, EnvError};
use crate::links::{self, LinkTarget};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::{BTreeMap, BTreeSet, HashSet};

const YEARS_KEY: &str = "metrics.years";

/// A play counts towards statistics at 30 s or half the track, whichever comes first — the rule the
/// Kotlin tracker applies to the app's own plays, and the 30 s Spotify applies to its exports.
pub const COUNT_THRESHOLD_MS: i64 = 30_000;

/// Where an imported event came from. Absent on the app's own plays and on anything imported before
/// 4.0, which is exactly what lets the deduplication tell the old interpretation apart.
pub const ORIGIN_EXTENDED: &str = "spotify-extended";
pub const ORIGIN_LEGACY: &str = "spotify-legacy";

/// One play. Field names are the ones `metrics.json` always had, so old files read unchanged.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase", default)]
pub struct Event {
    pub track_id: String,
    pub isrc: String,
    pub track_name: String,
    pub artist_ids: Vec<String>,
    pub artist_names: Vec<String>,
    pub album_id: String,
    pub album_name: String,
    #[serde(skip_serializing_if = "String::is_empty")]
    pub image_url: String,
    pub duration_ms: i64,
    pub started_at: i64,
    pub listened_ms: i64,
    pub counted: bool,
    pub completed: bool,
    pub source: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub origin: Option<String>,
}

impl Event {
    fn identity(&self) -> &str {
        if self.track_id.is_empty() {
            &self.track_name
        } else {
            &self.track_id
        }
    }

    fn key_at(&self, started_at: i64) -> String {
        format!("{}|{}|{}", self.identity(), started_at, self.listened_ms)
    }

    /// The key every event has had since the Kotlin tracker: identity, start, time played.
    fn key(&self) -> String {
        self.key_at(self.started_at)
    }

    /// An import made before 4.0: from Spotify, with nothing but what the export carried.
    fn is_pre_4_import(&self) -> bool {
        self.origin.is_none()
            && self.source == "spotify"
            && self.duration_ms == 0
            && self.isrc.is_empty()
            && self.album_id.is_empty()
            && self.artist_ids.is_empty()
    }
}

/// What `import` did, for the message the user reads.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct ImportReport {
    /// Events read from the file that were plays at all.
    pub read: usize,
    pub added: usize,
    /// Already present — the same file twice, or overlapping exports.
    pub duplicates: usize,
}

fn segment_key(year: i64) -> String {
    format!("metrics.{year}.jsonl")
}

fn year_of(ms: i64) -> i64 {
    crate::calendar::civil_from_days(ms.div_euclid(86_400_000)).0
}

async fn years<E: Env>() -> Result<BTreeSet<i64>, EnvError> {
    Ok(match E::get_storage(YEARS_KEY).await? {
        Some(text) => serde_json::from_str(&text).unwrap_or_default(),
        None => BTreeSet::new(),
    })
}

/// Appends events, grouped by year, registering any year not seen before.
async fn append<E: Env>(events: &[Event]) -> Result<(), EnvError> {
    if events.is_empty() {
        return Ok(());
    }
    let mut by_year: BTreeMap<i64, String> = BTreeMap::new();
    for event in events {
        let line = serde_json::to_string(event)?;
        let chunk = by_year.entry(year_of(event.started_at)).or_default();
        chunk.push_str(&line);
        chunk.push('\n');
    }
    let mut known = years::<E>().await?;
    let before = known.len();
    for (year, chunk) in &by_year {
        E::append_storage(&segment_key(*year), chunk).await?;
        known.insert(*year);
    }
    // Registered after the data, so a death in between leaves a segment nobody lists rather than a
    // listed segment with nothing in it. The next append of that year lists it again.
    if known.len() != before {
        E::set_storage(YEARS_KEY, Some(&serde_json::to_string(&known)?)).await?;
    }
    Ok(())
}

/// Records one play of the app's own.
pub async fn record<E: Env>(event: Event) -> Result<(), EnvError> {
    append::<E>(std::slice::from_ref(&event)).await
}

/// Every event, oldest first.
pub async fn load_all<E: Env>() -> Result<Vec<Event>, EnvError> {
    let mut all = Vec::new();
    for year in years::<E>().await? {
        let Some(text) = E::get_storage(&segment_key(year)).await? else {
            continue;
        };
        all.extend(
            text.lines()
                .filter(|line| !line.trim().is_empty())
                .filter_map(|line| serde_json::from_str::<Event>(line).ok()),
        );
    }
    all.sort_by_key(|e| e.started_at);
    Ok(all)
}

/// How many events there are, without keeping them.
pub async fn count<E: Env>() -> Result<usize, EnvError> {
    Ok(load_all::<E>().await?.len())
}

/// The keys of what is already stored, in both the current interpretation and the pre-4.0 one.
struct ImportKeys {
    exact: HashSet<String>,
    /// `identity|end|played` of the events imported before 4.0 — which stored the *end* of the
    /// stream as `startedAt`, and for legacy files in the phone's time zone.
    pre_4: HashSet<String>,
}

impl ImportKeys {
    fn of(existing: &[Event]) -> Self {
        let mut keys = ImportKeys { exact: HashSet::new(), pre_4: HashSet::new() };
        for e in existing {
            keys.exact.insert(e.key());
            if e.is_pre_4_import() {
                keys.pre_4.insert(e.key());
            }
        }
        keys
    }

    fn contains(&self, event: &Event) -> bool {
        if self.exact.contains(&event.key()) {
            return true;
        }
        let end = event.started_at + event.listened_ms;
        match event.origin.as_deref() {
            // Pre-4.0 stored `ts` itself, in UTC: the end, exactly.
            Some(ORIGIN_EXTENDED) => self.pre_4.contains(&event.key_at(end)),
            // Pre-4.0 stored `endTime` read as local time, so the same end shifted by the phone's
            // offset — a whole number of half hours, between −12 h and +14 h.
            Some(ORIGIN_LEGACY) => (-24..=28)
                .any(|half_hours| self.pre_4.contains(&event.key_at(end + half_hours * 1_800_000))),
            _ => false,
        }
    }

    fn insert(&mut self, event: &Event) {
        self.exact.insert(event.key());
    }
}

/// Imports a history file: Rustify's own events, Spotify's Extended Streaming History or the legacy
/// `StreamingHistory*.json`, as a JSON array or an object with an `events` array.
///
/// Nothing is ever dropped to make room. Duplicates — the same file twice, overlapping exports, or
/// the same plays imported before 4.0 — are skipped.
pub async fn import<E: Env>(json_text: &str) -> Result<ImportReport, EnvError> {
    let value: Value = serde_json::from_str(json_text)?;
    let items = match &value {
        Value::Array(items) => items.as_slice(),
        Value::Object(map) => match map.get("events") {
            Some(Value::Array(items)) => items.as_slice(),
            _ => return Err(EnvError::Storage("unrecognised history format".into())),
        },
        _ => return Err(EnvError::Storage("unrecognised history format".into())),
    };

    let existing = load_all::<E>().await?;
    let mut keys = ImportKeys::of(&existing);
    let mut report = ImportReport::default();
    let mut fresh = Vec::new();
    for item in items {
        let Some(event) = normalise(item) else { continue };
        report.read += 1;
        if keys.contains(&event) {
            report.duplicates += 1;
            continue;
        }
        keys.insert(&event);
        fresh.push(event);
    }
    report.added = fresh.len();
    append::<E>(&fresh).await?;
    Ok(report)
}

/// One history entry, in whichever of the three formats, as an [`Event`]. `None` for anything that is
/// not a track play — podcast episodes in the Extended export have no track name.
pub fn normalise(item: &Value) -> Option<Event> {
    let obj = item.as_object()?;
    let text = |name: &str| obj.get(name).and_then(Value::as_str).unwrap_or("").to_string();
    let number = |name: &str| obj.get(name).and_then(Value::as_i64).unwrap_or(0);

    // Rustify's own: `trackName` plus a field only Rustify writes. The legacy Spotify format also
    // has `trackName`, which is why the name alone does not decide.
    if obj.contains_key("trackName")
        && (obj.contains_key("startedAt") || obj.contains_key("counted") || obj.contains_key("source"))
    {
        let event: Event = serde_json::from_value(item.clone()).ok()?;
        return (!event.track_name.trim().is_empty()).then_some(event);
    }

    if obj.contains_key("endTime") || obj.contains_key("msPlayed") {
        let name = text("trackName");
        if name.trim().is_empty() {
            return None;
        }
        let played = number("msPlayed");
        let end = parse_legacy_end(&text("endTime"))?;
        return Some(spotify_event(name, text("artistName"), String::new(), String::new(), end, played, ORIGIN_LEGACY));
    }

    let name = text("master_metadata_track_name");
    if name.trim().is_empty() {
        return None;
    }
    let played = number("ms_played");
    let end = parse_iso_utc(&text("ts"))?;
    // Through the shared link parser, not a prefix strip: identity is never read by hand.
    let track_id = match links::parse(&text("spotify_track_uri")) {
        Some(LinkTarget::SpotifyTrack(id)) => id,
        _ => String::new(),
    };
    Some(spotify_event(
        name,
        text("master_metadata_album_artist_name"),
        text("master_metadata_album_album_name"),
        track_id,
        end,
        played,
        ORIGIN_EXTENDED,
    ))
}

fn spotify_event(
    track_name: String,
    artist: String,
    album: String,
    track_id: String,
    end_ms: i64,
    played_ms: i64,
    origin: &str,
) -> Event {
    Event {
        track_id,
        track_name,
        artist_names: if artist.trim().is_empty() { Vec::new() } else { vec![artist] },
        album_name: album,
        started_at: end_ms - played_ms,
        listened_ms: played_ms,
        counted: played_ms >= COUNT_THRESHOLD_MS,
        source: "spotify".into(),
        origin: Some(origin.into()),
        ..Event::default()
    }
}

/// `2024-05-01T12:34:56Z`, optionally with fractional seconds. The Extended export's `ts`.
fn parse_iso_utc(text: &str) -> Option<i64> {
    let text = text.trim().strip_suffix('Z')?;
    let (date, time) = text.split_once('T')?;
    let time = time.split('.').next()?;
    let mut t = time.split(':').map(|p| p.parse::<i64>().ok());
    let (h, m, s) = (t.next()??, t.next()??, t.next()??);
    Some(day_ms(date)? + ((h * 60 + m) * 60 + s) * 1000)
}

/// `2024-05-01 12:34`, UTC. The legacy export's `endTime`.
fn parse_legacy_end(text: &str) -> Option<i64> {
    let (date, time) = text.trim().split_once(' ')?;
    let (h, m) = time.split_once(':')?;
    Some(day_ms(date)? + (h.parse::<i64>().ok()? * 60 + m.parse::<i64>().ok()?) * 60_000)
}

fn day_ms(date: &str) -> Option<i64> {
    let mut p = date.split('-');
    let y = p.next()?.parse::<i64>().ok()?;
    let m = p.next()?.parse::<u32>().ok()?;
    let d = p.next()?.parse::<u32>().ok()?;
    if !(1..=12).contains(&m) || !(1..=31).contains(&d) {
        return None;
    }
    Some(crate::calendar::days_from_civil(y, m, d) * 86_400_000)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::env::mock::{self, MockEnv};

    fn app_play(id: &str, started_at: i64) -> Event {
        Event {
            track_id: id.into(),
            track_name: format!("song {id}"),
            started_at,
            listened_ms: 200_000,
            counted: true,
            source: "spotify".into(),
            artist_ids: vec!["a".into()],
            duration_ms: 200_000,
            ..Event::default()
        }
    }

    fn extended(uri: &str, ts: &str, ms: i64) -> Value {
        serde_json::json!({
            "ts": ts, "ms_played": ms,
            "master_metadata_track_name": "Joga",
            "master_metadata_album_artist_name": "Björk",
            "master_metadata_album_album_name": "Homogenic",
            "spotify_track_uri": uri,
        })
    }

    #[tokio::test]
    async fn nothing_is_dropped_to_make_room() {
        let _guard = mock::lock_and_reset();
        // Well past the 5 000 the Kotlin store kept.
        let history: Vec<Value> = (0..12_000)
            .map(|i| extended("spotify:track:abc", &format!("2019-01-01T00:00:{:02}Z", i % 60), 60_000 + i))
            .collect();
        let report = import::<MockEnv>(&serde_json::to_string(&history).unwrap()).await.unwrap();
        assert_eq!(report.added, 12_000);
        assert_eq!(count::<MockEnv>().await.unwrap(), 12_000);
    }

    #[tokio::test]
    async fn importing_an_old_history_keeps_the_recent_plays() {
        let _guard = mock::lock_and_reset();
        record::<MockEnv>(app_play("recent", 1_760_000_000_000)).await.unwrap();
        let history: Vec<Value> = (0..6_000)
            .map(|i| extended("spotify:track:old", &format!("2015-03-0{}T10:00:00Z", 1 + i % 9), 40_000 + i))
            .collect();
        import::<MockEnv>(&serde_json::to_string(&history).unwrap()).await.unwrap();
        let all = load_all::<MockEnv>().await.unwrap();
        assert!(all.iter().any(|e| e.track_id == "recent"));
        // Oldest first, whatever order they were written in.
        assert!(all.windows(2).all(|w| w[0].started_at <= w[1].started_at));
    }

    #[tokio::test]
    async fn the_same_file_twice_adds_nothing_the_second_time() {
        let _guard = mock::lock_and_reset();
        let file = serde_json::to_string(&vec![
            extended("spotify:track:a", "2024-05-01T12:00:00Z", 120_000),
            extended("spotify:track:b", "2024-05-01T12:05:00Z", 90_000),
        ])
        .unwrap();
        assert_eq!(import::<MockEnv>(&file).await.unwrap().added, 2);
        let again = import::<MockEnv>(&file).await.unwrap();
        assert_eq!((again.added, again.duplicates), (0, 2));
    }

    #[test]
    fn ts_is_when_the_stream_ended() {
        let event = normalise(&extended("spotify:track:x", "2024-05-01T12:00:00Z", 60_000)).unwrap();
        assert_eq!(event.started_at, parse_iso_utc("2024-05-01T11:59:00Z").unwrap());
        assert_eq!(event.track_id, "x");
        assert_eq!(event.artist_names, vec!["Björk".to_string()]);
    }

    #[test]
    fn legacy_end_time_is_utc() {
        let item = serde_json::json!({"endTime": "2021-02-03 04:05", "msPlayed": 60_000, "trackName": "T", "artistName": "A"});
        let event = normalise(&item).unwrap();
        assert_eq!(event.started_at, parse_iso_utc("2021-02-03T04:04:00Z").unwrap());
        assert_eq!(event.origin.as_deref(), Some(ORIGIN_LEGACY));
    }

    #[tokio::test]
    async fn an_extended_import_from_before_4_0_is_recognised() {
        let _guard = mock::lock_and_reset();
        // What the Kotlin importer wrote: `ts` as `startedAt`, no origin.
        let end = parse_iso_utc("2024-05-01T12:00:00Z").unwrap();
        let old = Event {
            track_id: "x".into(),
            track_name: "Joga".into(),
            started_at: end,
            listened_ms: 60_000,
            counted: true,
            source: "spotify".into(),
            ..Event::default()
        };
        record::<MockEnv>(old).await.unwrap();
        let file = serde_json::to_string(&vec![extended("spotify:track:x", "2024-05-01T12:00:00Z", 60_000)]).unwrap();
        let report = import::<MockEnv>(&file).await.unwrap();
        assert_eq!((report.added, report.duplicates), (0, 1));
    }

    #[tokio::test]
    async fn a_legacy_import_from_before_4_0_in_another_time_zone_is_recognised() {
        let _guard = mock::lock_and_reset();
        // Read as Madrid summer time (UTC+2) by the old importer.
        let end_utc = parse_legacy_end("2021-07-03 10:05").unwrap();
        let old = Event {
            track_name: "T".into(),
            artist_names: vec!["A".into()],
            started_at: end_utc - 2 * 3_600_000,
            listened_ms: 60_000,
            counted: true,
            source: "spotify".into(),
            ..Event::default()
        };
        record::<MockEnv>(old).await.unwrap();
        let item = serde_json::json!([{"endTime": "2021-07-03 10:05", "msPlayed": 60_000, "trackName": "T", "artistName": "A"}]);
        let report = import::<MockEnv>(&item.to_string()).await.unwrap();
        assert_eq!((report.added, report.duplicates), (0, 1));
    }

    #[tokio::test]
    async fn the_apps_own_repeat_plays_are_not_mistaken_for_duplicates() {
        let _guard = mock::lock_and_reset();
        // A song on repeat: same id, same time played, one after the other.
        let first = app_play("loop", 1_700_000_000_000);
        let second = app_play("loop", 1_700_000_000_000 + 200_000);
        let file = serde_json::to_string(&vec![first, second]).unwrap();
        assert_eq!(import::<MockEnv>(&file).await.unwrap().added, 2);
    }

    #[tokio::test]
    async fn a_torn_last_line_costs_only_itself() {
        let _guard = mock::lock_and_reset();
        record::<MockEnv>(app_play("kept", 1_700_000_000_000)).await.unwrap();
        MockEnv::append_storage(&segment_key(2023), "{\"trackId\":\"half").await.unwrap();
        let all = load_all::<MockEnv>().await.unwrap();
        assert_eq!(all.len(), 1);
        assert_eq!(all[0].track_id, "kept");
    }

    #[tokio::test]
    async fn plays_land_in_the_segment_of_their_year() {
        let _guard = mock::lock_and_reset();
        record::<MockEnv>(app_play("a", parse_iso_utc("2023-12-31T23:59:59Z").unwrap())).await.unwrap();
        record::<MockEnv>(app_play("b", parse_iso_utc("2024-01-01T00:00:00Z").unwrap())).await.unwrap();
        let state = mock::state();
        assert!(state.storage.get(&segment_key(2023)).unwrap().contains("\"a\""));
        assert!(state.storage.get(&segment_key(2024)).unwrap().contains("\"b\""));
    }

    #[test]
    fn podcast_episodes_are_not_track_plays() {
        let episode = serde_json::json!({"ts": "2024-05-01T12:00:00Z", "ms_played": 1000,
            "master_metadata_track_name": null, "episode_name": "Ep"});
        assert!(normalise(&episode).is_none());
    }
}
