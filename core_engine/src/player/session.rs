// core_engine/src/player/session.rs
//
// "Continue listening" — point G.
//
// Rustify already remembered where you were: `playback_state.json` restores the queue and position
// when the app reopens. What it could not do is remember more than one place at a time. Start an
// album, switch to a playlist, and the album is gone.
//
// This is the same idea kept per **context**: an album, a playlist, an artist radio. Each keeps its
// own queue and position, and Home offers the recent ones back. The evaluation called it "a product
// idea, cheap — the infrastructure already exists", which is right: the state is [`PlayerState`],
// the storage is `Env`, and this file is the small amount in between.
//
// Two rules that make it useful rather than noisy:
//
//   * **A session that barely started is not a session.** Ten seconds in, you did not "leave off"
//     anywhere; you skipped past it.
//   * **A session that finished is done.** Offering to resume the last twenty seconds of an album
//     you completed is offering nothing.

use super::PlayerState;
use crate::env::storage;
use crate::env::{Env, EnvError};
use serde::{Deserialize, Serialize};

/// Where the list lives. Versioned by `env::schema` like everything else persisted.
const STORAGE_KEY: &str = "continue_listening";

/// How many contexts are kept. A row on a home screen, not a history.
const MAX_SESSIONS: usize = 12;

/// Below this, nothing was really started.
const MIN_PROGRESS_MS: u64 = 15_000;

/// Within this much of the end, it was finished rather than left.
const FINISHED_MARGIN_MS: u64 = 30_000;

/// One thing you were listening to.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Session {
    /// What was being played, as a stable string: `"album:ID"`, `"playlist:ID"`, `"radio:ID"`.
    ///
    /// The id is what makes this per-context rather than per-track: coming back to the same album
    /// updates the entry you already had instead of adding another.
    pub id: String,
    /// What to show. Held here rather than looked up, so the row draws with no network at all —
    /// which is the difference between a row that appears and a row that appears eventually.
    pub label: String,
    #[serde(default)]
    pub subtitle: String,
    #[serde(default)]
    pub image_url: String,
    pub state: PlayerState,
    /// Duration of the track that was playing, when it is known. Used to tell "left off" from
    /// "finished"; zero means "not known", which is treated as not finished.
    #[serde(default)]
    pub duration_ms: u64,
    pub updated_at_ms: i64,
}

impl Session {
    /// Is this worth offering back?
    pub fn is_resumable(&self) -> bool {
        if self.state.is_empty() || self.id.is_empty() {
            return false;
        }
        // Barely started -- and "barely" is about the **context**, not about the track playing right
        // now. Three seconds into track five is twenty minutes of listening, not a skip. Reading it
        // per track made the row blink out of existence at every track change and come back at the
        // next write, because a fresh track is always at zero.
        if self.state.index == 0 && self.state.position_ms < MIN_PROGRESS_MS {
            return false;
        }
        // Finished the last track of the queue: there is nothing to come back to.
        let on_last = self.state.index + 1 >= self.state.queue.len();
        if on_last && self.duration_ms > 0 {
            let remaining = self.duration_ms.saturating_sub(self.state.position_ms);
            if remaining <= FINISHED_MARGIN_MS {
                return false;
            }
        }
        true
    }
}

/// Records progress in a context, replacing whatever was there for the same [`Session::id`].
///
/// Anything not worth resuming is **removed** rather than skipped: finishing an album should make
/// its row disappear, not leave the old halfway position sitting there.
pub async fn record<E: Env>(session: Session) -> Result<(), EnvError> {
    let mut sessions = list::<E>().await;
    sessions.retain(|s| s.id != session.id);
    if session.is_resumable() {
        sessions.push(session);
    }
    // Newest first, then truncated: the cap is on what is kept, not on what is written.
    sessions.sort_by(|a, b| b.updated_at_ms.cmp(&a.updated_at_ms));
    sessions.truncate(MAX_SESSIONS);
    storage::set_json::<E, _>(STORAGE_KEY, Some(&sessions)).await
}

/// The contexts to offer, newest first. Never fails: a home screen row is not worth an error path.
pub async fn list<E: Env>() -> Vec<Session> {
    let mut sessions: Vec<Session> = storage::get_json_or_default::<E, _>(STORAGE_KEY).await;
    // Filtered on the way out as well as on the way in, because the rules can change between the
    // release that wrote an entry and the one that reads it.
    sessions.retain(Session::is_resumable);
    sessions.sort_by(|a, b| b.updated_at_ms.cmp(&a.updated_at_ms));
    sessions
}

/// One context by id, for resuming it.
pub async fn get<E: Env>(id: &str) -> Option<Session> {
    list::<E>().await.into_iter().find(|s| s.id == id)
}

/// Drops a context, because the user said so.
pub async fn forget<E: Env>(id: &str) -> Result<(), EnvError> {
    let mut sessions = list::<E>().await;
    sessions.retain(|s| s.id != id);
    storage::set_json::<E, _>(STORAGE_KEY, Some(&sessions)).await
}

/// Drops all of them.
pub async fn clear<E: Env>() -> Result<(), EnvError> {
    storage::set_json::<E, _>(STORAGE_KEY, Some(&Vec::<Session>::new())).await
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::env::mock::{self, MockEnv};

    fn session(id: &str, position_ms: u64, at: i64) -> Session {
        Session {
            id: id.to_string(),
            label: format!("Label for {id}"),
            subtitle: String::new(),
            image_url: String::new(),
            state: PlayerState {
                queue: vec!["t0".into(), "t1".into(), "t2".into()],
                index: 1,
                position_ms,
                ..PlayerState::default()
            },
            duration_ms: 240_000,
            updated_at_ms: at,
        }
    }

    #[tokio::test]
    async fn coming_back_to_the_same_context_updates_it_rather_than_adding_another() {
        let _guard = mock::lock_and_reset();
        record::<MockEnv>(session("album:a", 60_000, 100)).await.unwrap();
        record::<MockEnv>(session("album:a", 120_000, 200)).await.unwrap();

        let sessions = list::<MockEnv>().await;
        assert_eq!(sessions.len(), 1);
        assert_eq!(sessions[0].state.position_ms, 120_000);
    }

    #[tokio::test]
    async fn a_context_you_skipped_past_is_not_something_you_left_off() {
        let _guard = mock::lock_and_reset();
        let mut skipped = session("album:a", 3_000, 100);
        skipped.state.index = 0; // still on the first track: nothing was really started
        record::<MockEnv>(skipped).await.unwrap();
        assert!(list::<MockEnv>().await.is_empty());
    }

    #[tokio::test]
    async fn a_track_change_does_not_take_the_row_away() {
        // The regression this rule was written wrong for: every new track starts at zero, so reading
        // "barely started" per track deleted the row on each change and re-created it twenty seconds
        // later. Past the first track, the context is plainly being listened to.
        let _guard = mock::lock_and_reset();
        record::<MockEnv>(session("album:a", 60_000, 100)).await.unwrap();

        let mut just_advanced = session("album:a", 400, 200);
        just_advanced.state.index = 2;
        record::<MockEnv>(just_advanced).await.unwrap();

        let sessions = list::<MockEnv>().await;
        assert_eq!(sessions.len(), 1, "the row must survive the track change");
        assert_eq!(sessions[0].state.index, 2);
    }

    #[tokio::test]
    async fn finishing_the_last_track_removes_the_row_instead_of_freezing_it() {
        // Otherwise an album you completed sits on the home screen forever offering its final
        // twenty seconds back.
        let _guard = mock::lock_and_reset();
        record::<MockEnv>(session("album:a", 60_000, 100)).await.unwrap();
        assert_eq!(list::<MockEnv>().await.len(), 1);

        let mut finished = session("album:a", 235_000, 200);
        finished.state.index = 2; // the last track
        record::<MockEnv>(finished).await.unwrap();
        assert!(list::<MockEnv>().await.is_empty());
    }

    #[tokio::test]
    async fn finishing_a_track_in_the_middle_of_a_queue_is_not_finishing_the_context() {
        let _guard = mock::lock_and_reset();
        let mut nearly = session("album:a", 235_000, 100);
        nearly.state.index = 1; // not the last of three
        record::<MockEnv>(nearly).await.unwrap();
        assert_eq!(list::<MockEnv>().await.len(), 1);
    }

    #[tokio::test]
    async fn the_newest_context_comes_first_and_the_list_is_capped() {
        let _guard = mock::lock_and_reset();
        for i in 0..(MAX_SESSIONS + 5) {
            record::<MockEnv>(session(&format!("album:{i}"), 60_000, i as i64))
                .await
                .unwrap();
        }
        let sessions = list::<MockEnv>().await;
        assert_eq!(sessions.len(), MAX_SESSIONS);
        assert_eq!(sessions[0].id, format!("album:{}", MAX_SESSIONS + 4));
        // And the oldest ones are the ones gone.
        assert!(!sessions.iter().any(|s| s.id == "album:0"));
    }

    #[tokio::test]
    async fn a_session_can_be_resumed_and_forgotten_by_id() {
        let _guard = mock::lock_and_reset();
        record::<MockEnv>(session("playlist:p", 60_000, 100)).await.unwrap();
        record::<MockEnv>(session("album:a", 60_000, 200)).await.unwrap();

        let resumed = get::<MockEnv>("playlist:p").await.expect("the playlist");
        assert_eq!(resumed.state.index, 1);
        assert_eq!(resumed.state.position_ms, 60_000);

        forget::<MockEnv>("playlist:p").await.unwrap();
        assert!(get::<MockEnv>("playlist:p").await.is_none());
        assert_eq!(list::<MockEnv>().await.len(), 1);
    }

    #[tokio::test]
    async fn nothing_stored_is_an_empty_list_and_not_an_error() {
        let _guard = mock::lock_and_reset();
        assert!(list::<MockEnv>().await.is_empty());
        assert!(get::<MockEnv>("album:a").await.is_none());
        // Forgetting something that is not there succeeds, as it should.
        assert!(forget::<MockEnv>("album:a").await.is_ok());
    }

    #[tokio::test]
    async fn a_stored_entry_that_stopped_qualifying_is_dropped_on_the_way_out() {
        // The rules can change between the release that wrote an entry and the one that reads it,
        // so the filter runs on both sides rather than trusting what is on disk.
        let _guard = mock::lock_and_reset();
        let stored = vec![session("album:a", 1_000, 100)];
        storage::set_json::<MockEnv, _>(STORAGE_KEY, Some(&stored))
            .await
            .unwrap();
        assert!(list::<MockEnv>().await.is_empty());
    }
}
