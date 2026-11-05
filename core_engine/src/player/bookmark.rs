// core_engine/src/player/bookmark.rs
//
// Where you were inside one long track (4.9) — an audiobook chapter, a podcast, a two-hour DJ set.
//
// `session` keeps a place per *context*; this keeps one per *track*, and only for long ones. Leave a
// ninety-minute set at 34:12 for something else, come back to it next week from anywhere — search,
// a playlist, the history — and the app offers 34:12 back. For a four-minute song the same offer
// would be noise, which is why there is a length below which nothing is kept.
//
// The same two rules as `session`, for the same reasons: a place barely started is not a place you
// left off, and a place at the very end is a finish, so it is removed rather than offered.

use crate::env::storage;
use crate::env::{Env, EnvError};
use serde::{Deserialize, Serialize};

pub(crate) const STORAGE_KEY: &str = "track_bookmarks";

/// Tracks shorter than this are songs; nobody wants "resume at 2:10" on a song.
pub const MIN_DURATION_MS: u64 = 20 * 60_000;

/// Less than this in, and nothing was really started.
const MIN_PROGRESS_MS: u64 = 60_000;

/// Within this much of the end, it was finished.
const FINISHED_MARGIN_MS: u64 = 30_000;

/// A cap on what is kept, oldest dropped first. Five hundred long tracks is years of podcasts.
pub(crate) const MAX_BOOKMARKS: usize = 500;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Bookmark {
    pub track_id: String,
    pub position_ms: u64,
    pub duration_ms: u64,
    pub updated_at_ms: i64,
}

/// Is this place worth offering back?
pub fn worth_keeping(position_ms: u64, duration_ms: u64) -> bool {
    duration_ms >= MIN_DURATION_MS
        && position_ms >= MIN_PROGRESS_MS
        && duration_ms.saturating_sub(position_ms) > FINISHED_MARGIN_MS
}

async fn load<E: Env>() -> Vec<Bookmark> {
    storage::get_json_or_default::<E, _>(STORAGE_KEY).await
}

/// Records the place in a track, or removes it when it is not worth keeping (finished, or barely
/// started) — finishing a chapter must stop it being offered, not freeze it at its last seconds.
pub async fn record<E: Env>(track_id: &str, position_ms: u64, duration_ms: u64) -> Result<(), EnvError> {
    if track_id.is_empty() {
        return Ok(());
    }
    let mut marks = load::<E>().await;
    let had = marks.iter().any(|m| m.track_id == track_id);
    marks.retain(|m| m.track_id != track_id);
    if worth_keeping(position_ms, duration_ms) {
        marks.push(Bookmark {
            track_id: track_id.to_string(),
            position_ms,
            duration_ms,
            updated_at_ms: E::now_ms(),
        });
    } else if !had {
        // Nothing stored and nothing to store: a song ending is the common case, and it must not
        // cost a write.
        return Ok(());
    } else {
        // Finished (or rewound to the start): the other devices must drop it too (`places`).
        super::places::forgotten_now::<E>("bookmark", track_id).await?;
    }
    marks.sort_by_key(|m| std::cmp::Reverse(m.updated_at_ms));
    marks.truncate(MAX_BOOKMARKS);
    storage::set_json::<E, _>(STORAGE_KEY, Some(&marks)).await
}

/// Where to offer to resume this track, if anywhere.
pub async fn get<E: Env>(track_id: &str) -> Option<u64> {
    load::<E>()
        .await
        .into_iter()
        .find(|m| m.track_id == track_id && worth_keeping(m.position_ms, m.duration_ms))
        .map(|m| m.position_ms)
}

/// Drops the place in one track, because the person chose to start it over.
pub async fn forget<E: Env>(track_id: &str) -> Result<(), EnvError> {
    let mut marks = load::<E>().await;
    let before = marks.len();
    marks.retain(|m| m.track_id != track_id);
    if marks.len() == before {
        return Ok(());
    }
    super::places::forgotten_now::<E>("bookmark", track_id).await?;
    storage::set_json::<E, _>(STORAGE_KEY, Some(&marks)).await
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::env::mock::{self, MockEnv};

    const HOUR: u64 = 60 * 60_000;

    #[tokio::test]
    async fn a_long_track_left_halfway_is_offered_back() {
        let _guard = mock::lock_and_reset();
        record::<MockEnv>("ep:1", 34 * 60_000, HOUR).await.unwrap();
        assert_eq!(get::<MockEnv>("ep:1").await, Some(34 * 60_000));
    }

    #[tokio::test]
    async fn a_song_is_never_bookmarked() {
        let _guard = mock::lock_and_reset();
        record::<MockEnv>("song", 120_000, 240_000).await.unwrap();
        assert_eq!(get::<MockEnv>("song").await, None);
    }

    #[tokio::test]
    async fn barely_started_is_not_a_place() {
        let _guard = mock::lock_and_reset();
        record::<MockEnv>("ep:1", 20_000, HOUR).await.unwrap();
        assert_eq!(get::<MockEnv>("ep:1").await, None);
    }

    #[tokio::test]
    async fn finishing_removes_the_place_instead_of_freezing_it() {
        let _guard = mock::lock_and_reset();
        record::<MockEnv>("ep:1", 30 * 60_000, HOUR).await.unwrap();
        record::<MockEnv>("ep:1", HOUR - 10_000, HOUR).await.unwrap();
        assert_eq!(get::<MockEnv>("ep:1").await, None);
    }

    #[tokio::test]
    async fn the_latest_place_wins_and_forget_drops_it() {
        let _guard = mock::lock_and_reset();
        record::<MockEnv>("ep:1", 10 * 60_000, HOUR).await.unwrap();
        record::<MockEnv>("ep:1", 40 * 60_000, HOUR).await.unwrap();
        assert_eq!(get::<MockEnv>("ep:1").await, Some(40 * 60_000));
        forget::<MockEnv>("ep:1").await.unwrap();
        assert_eq!(get::<MockEnv>("ep:1").await, None);
    }

    #[tokio::test]
    async fn the_list_is_capped() {
        let _guard = mock::lock_and_reset();
        for i in 0..(MAX_BOOKMARKS + 3) {
            record::<MockEnv>(&format!("ep:{i}"), 10 * 60_000, HOUR).await.unwrap();
        }
        assert_eq!(load::<MockEnv>().await.len(), MAX_BOOKMARKS);
    }
}
