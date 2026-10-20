// core_engine/src/metrics/stats.rs
//
// The numbers on the Metrics screen, computed where the events are.
//
// They were computed in Kotlin over a `List<JSONObject>` of every event, which was tolerable while
// the history could not exceed 5 000 entries. Without that ceiling — the whole point of 4.0 — a real
// Extended history is a few hundred thousand objects on the UI's heap. Here it is a pass over a
// `Vec<Event>` and what crosses JNI is the summary.
//
// ## Local time without a time-zone database
//
// Hours, weekdays and days are local-time questions, and the core has no tz database. The caller
// passes its zone as a list of offset changes (`ZoneRules` on Android), and the answer is a binary
// search. That keeps DST right for the whole history instead of applying today's offset to 2016.
//
// ## One artist, one row
//
// An imported Spotify history names artists and tracks; the app's own plays carry ids. Keyed as
// given, "Björk" from the import and `7w29UYBi0qsHi5RTcv3lmA` from the app were two rows of the same
// person. Names are folded with `search::fold` and resolved to the id when any play carries one.

use super::Event;
use crate::search::fold;
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, HashMap, HashSet};

const DAY_MS: i64 = 86_400_000;
const TOP_LIMIT: usize = 50;
const RECENT_LIMIT: usize = 40;

/// A zone as the offsets it has had: `(from_ms, offset_ms)`, sorted by `from_ms`.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct Zone(pub Vec<(i64, i64)>);

impl Zone {
    pub fn offset_at(&self, ms: i64) -> i64 {
        match self.0.partition_point(|(from, _)| *from <= ms) {
            0 => self.0.first().map(|(_, o)| *o).unwrap_or(0),
            i => self.0[i - 1].1,
        }
    }

    fn local(&self, ms: i64) -> i64 {
        ms + self.offset_at(ms)
    }

    fn local_day(&self, ms: i64) -> i64 {
        self.local(ms).div_euclid(DAY_MS)
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TopRow {
    pub key: String,
    pub name: String,
    pub subtitle: String,
    pub image_url: String,
    pub plays: u32,
    pub ms: i64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct YearPlays {
    pub year: i64,
    pub plays: u32,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct Stats {
    pub total_events: usize,
    pub total_plays: u32,
    pub distinct_tracks: usize,
    pub distinct_artists: usize,
    pub distinct_albums: usize,
    pub total_ms: i64,
    pub days_listened: usize,
    pub avg_per_day_min: i64,
    pub completion_rate: u32,
    pub plays_by_hour: Vec<u32>,
    pub ms_by_hour: Vec<i64>,
    /// Monday first.
    pub plays_by_weekday: Vec<u32>,
    pub plays_by_month: Vec<u32>,
    pub plays_by_year: Vec<YearPlays>,
    pub cumulative_min: Vec<i64>,
    pub cumulative_plays: Vec<i64>,
    pub minutes_per_day: Vec<i64>,
    pub top_tracks: Vec<TopRow>,
    pub top_artists: Vec<TopRow>,
    pub top_albums: Vec<TopRow>,
    pub recent: Vec<Event>,
}

/// Resolves name-only artists and tracks to the id some other play carries for the same name.
struct Identities {
    artist_by_name: HashMap<String, String>,
    track_by_name: HashMap<String, String>,
}

impl Identities {
    fn of(events: &[Event]) -> Self {
        let mut ids = Identities { artist_by_name: HashMap::new(), track_by_name: HashMap::new() };
        for e in events {
            for (i, name) in e.artist_names.iter().enumerate() {
                if let Some(id) = e.artist_ids.get(i).filter(|id| !id.is_empty()) {
                    ids.artist_by_name.entry(fold(name)).or_insert_with(|| id.clone());
                }
            }
            if !e.track_id.is_empty() {
                ids.track_by_name.entry(Self::track_name_key(e)).or_insert_with(|| e.track_id.clone());
            }
        }
        ids
    }

    fn track_name_key(e: &Event) -> String {
        format!("{}|{}", fold(&e.track_name), e.artist_names.first().map(|a| fold(a)).unwrap_or_default())
    }

    fn track(&self, e: &Event) -> String {
        if !e.track_id.is_empty() {
            return e.track_id.clone();
        }
        let by_name = Self::track_name_key(e);
        self.track_by_name.get(&by_name).cloned().unwrap_or(format!("n:{by_name}"))
    }

    fn artists<'a>(&'a self, e: &'a Event) -> impl Iterator<Item = (String, &'a str)> + 'a {
        e.artist_names.iter().enumerate().map(move |(i, name)| {
            let key = match e.artist_ids.get(i).filter(|id| !id.is_empty()) {
                Some(id) => id.clone(),
                None => {
                    let folded = fold(name);
                    self.artist_by_name.get(&folded).cloned().unwrap_or(format!("n:{folded}"))
                }
            };
            (key, name.as_str())
        })
    }

    fn album(e: &Event) -> Option<String> {
        if !e.album_id.is_empty() {
            Some(e.album_id.clone())
        } else if !e.album_name.trim().is_empty() {
            Some(format!("n:{}", fold(&e.album_name)))
        } else {
            None
        }
    }
}

#[derive(Default)]
struct Acc {
    plays: u32,
    ms: i64,
    at: i64,
    name: String,
    subtitle: String,
    image_url: String,
}

impl Acc {
    fn add(&mut self, e: &Event, name: &str, subtitle: String) {
        self.plays += 1;
        self.ms += e.listened_ms;
        // The newest play names the row, so a renamed track shows its current title.
        if e.started_at >= self.at {
            self.at = e.started_at;
            self.name = name.to_string();
            self.subtitle = subtitle;
            if !e.image_url.is_empty() {
                self.image_url = e.image_url.clone();
            }
        } else if self.image_url.is_empty() {
            self.image_url = e.image_url.clone();
        }
    }
}

fn top(map: HashMap<String, Acc>) -> Vec<TopRow> {
    let mut rows: Vec<TopRow> = map
        .into_iter()
        .map(|(key, a)| TopRow { key, name: a.name, subtitle: a.subtitle, image_url: a.image_url, plays: a.plays, ms: a.ms })
        .collect();
    rows.sort_by(|a, b| b.plays.cmp(&a.plays).then(b.ms.cmp(&a.ms)).then(a.name.cmp(&b.name)));
    rows.truncate(TOP_LIMIT);
    rows
}

/// Statistics for `[from, to)` over `events` (oldest first, as [`super::load_all`] returns them).
/// The all-time series always cover every event, as the screen has always shown them.
pub fn compute(events: &[Event], from: i64, to: i64, zone: &Zone) -> Stats {
    let ids = Identities::of(events);
    let in_range: Vec<&Event> = events.iter().filter(|e| e.started_at >= from && e.started_at < to).collect();
    let counted: Vec<&Event> = in_range.iter().copied().filter(|e| e.counted).collect();

    let mut stats = Stats {
        total_events: events.len(),
        total_plays: counted.len() as u32,
        total_ms: in_range.iter().map(|e| e.listened_ms).sum(),
        plays_by_hour: vec![0; 24],
        ms_by_hour: vec![0; 24],
        plays_by_weekday: vec![0; 7],
        plays_by_month: vec![0; 12],
        ..Stats::default()
    };

    stats.distinct_tracks = counted.iter().map(|e| ids.track(e)).collect::<HashSet<_>>().len();
    stats.distinct_albums = counted.iter().filter_map(|e| Identities::album(e)).collect::<HashSet<_>>().len();
    stats.distinct_artists =
        counted.iter().flat_map(|e| ids.artists(e).map(|(k, _)| k)).collect::<HashSet<_>>().len();

    let days: HashSet<i64> =
        in_range.iter().filter(|e| e.listened_ms > 0).map(|e| zone.local_day(e.started_at)).collect();
    stats.days_listened = days.len();
    if !days.is_empty() {
        stats.avg_per_day_min = (stats.total_ms / 60_000) / days.len() as i64;
    }

    let mut years: BTreeMap<i64, u32> = BTreeMap::new();
    for e in &counted {
        let local = zone.local(e.started_at);
        let day = local.div_euclid(DAY_MS);
        let (year, month, _) = crate::calendar::civil_from_days(day);
        stats.plays_by_hour[(local.rem_euclid(DAY_MS) / 3_600_000) as usize] += 1;
        // 1970-01-01 was a Thursday: day 0 is index 3 with Monday as 0.
        stats.plays_by_weekday[(day + 3).rem_euclid(7) as usize] += 1;
        stats.plays_by_month[(month - 1) as usize] += 1;
        *years.entry(year).or_default() += 1;
    }
    for e in &in_range {
        let hour = (zone.local(e.started_at).rem_euclid(DAY_MS) / 3_600_000) as usize;
        stats.ms_by_hour[hour] += e.listened_ms;
    }
    stats.plays_by_year = years.into_iter().map(|(year, plays)| YearPlays { year, plays }).collect();

    let completed = counted.iter().filter(|e| e.completed).count() as u32;
    if stats.total_plays > 0 {
        stats.completion_rate = completed * 100 / stats.total_plays;
    }

    let mut per_day: BTreeMap<i64, (i64, i64)> = BTreeMap::new();
    for e in events {
        let entry = per_day.entry(zone.local_day(e.started_at)).or_default();
        entry.0 += e.listened_ms;
        if e.counted {
            entry.1 += 1;
        }
    }
    let (mut minutes, mut plays) = (0i64, 0i64);
    for (ms, n) in per_day.values() {
        stats.minutes_per_day.push(ms / 60_000);
        minutes += ms;
        plays += n;
        stats.cumulative_min.push(minutes / 60_000);
        stats.cumulative_plays.push(plays);
    }

    let mut tracks: HashMap<String, Acc> = HashMap::new();
    let mut artists: HashMap<String, Acc> = HashMap::new();
    let mut albums: HashMap<String, Acc> = HashMap::new();
    for e in &counted {
        tracks.entry(ids.track(e)).or_default().add(e, &e.track_name, e.artist_names.join(", "));
        for (key, name) in ids.artists(e) {
            let mut no_cover = (*e).clone();
            no_cover.image_url.clear();
            artists.entry(key).or_default().add(&no_cover, name, String::new());
        }
        if let Some(key) = Identities::album(e) {
            albums.entry(key).or_default().add(e, &e.album_name, String::new());
        }
    }
    stats.top_tracks = top(tracks);
    stats.top_artists = top(artists);
    stats.top_albums = top(albums);

    stats.recent = events.iter().rev().take(RECENT_LIMIT).cloned().collect();
    stats
}

#[cfg(test)]
mod tests {
    use super::*;

    fn play(track: &str, artist: (&str, &str), at: i64) -> Event {
        Event {
            track_id: track.into(),
            track_name: format!("Song {track}"),
            artist_ids: if artist.0.is_empty() { vec![] } else { vec![artist.0.into()] },
            artist_names: vec![artist.1.into()],
            started_at: at,
            listened_ms: 180_000,
            counted: true,
            source: "spotify".into(),
            ..Event::default()
        }
    }

    #[test]
    fn an_imported_artist_and_the_same_artist_with_an_id_are_one_row() {
        let events = vec![play("a", ("", "Bjork"), 0), play("b", ("7w29", "Björk"), 1)];
        let stats = compute(&events, 0, i64::MAX, &Zone::default());
        assert_eq!(stats.distinct_artists, 1);
        assert_eq!(stats.top_artists[0].plays, 2);
        assert_eq!(stats.top_artists[0].key, "7w29");
    }

    #[test]
    fn hours_and_weekdays_are_local_and_follow_dst() {
        // Madrid: +1 h in winter, +2 h from 2024-03-31T01:00Z.
        let dst = crate::calendar::days_from_civil(2024, 3, 31) * DAY_MS + 3_600_000;
        let zone = Zone(vec![(i64::MIN, 3_600_000), (dst, 7_200_000)]);
        let winter = crate::calendar::days_from_civil(2024, 1, 15) * DAY_MS + 22 * 3_600_000; // Mon 22:00Z
        let summer = crate::calendar::days_from_civil(2024, 7, 15) * DAY_MS + 22 * 3_600_000; // Mon 22:00Z
        let stats = compute(&[play("a", ("x", "X"), winter), play("b", ("x", "X"), summer)], 0, i64::MAX, &zone);
        assert_eq!(stats.plays_by_hour[23], 1, "winter: 22Z is 23 local");
        assert_eq!(stats.plays_by_hour[0], 1, "summer: 22Z is 00 local, the next day");
        assert_eq!(stats.plays_by_weekday[0], 1, "Monday");
        assert_eq!(stats.plays_by_weekday[1], 1, "Tuesday");
    }

    #[test]
    fn the_range_limits_the_totals_but_not_the_all_time_series() {
        let events = vec![play("a", ("x", "X"), 0), play("b", ("x", "X"), 10 * DAY_MS)];
        let stats = compute(&events, 5 * DAY_MS, i64::MAX, &Zone::default());
        assert_eq!(stats.total_plays, 1);
        assert_eq!(stats.cumulative_plays, vec![1, 2]);
        assert_eq!(stats.total_events, 2);
    }

    #[test]
    fn an_uncounted_skip_adds_time_but_not_a_play() {
        let mut skip = play("a", ("x", "X"), 0);
        skip.counted = false;
        skip.listened_ms = 5_000;
        let stats = compute(&[skip], 0, i64::MAX, &Zone::default());
        assert_eq!((stats.total_plays, stats.total_ms), (0, 5_000));
        assert!(stats.top_tracks.is_empty());
    }
}
