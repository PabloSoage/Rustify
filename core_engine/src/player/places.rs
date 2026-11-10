// core_engine/src/player/places.rs
//
// Where you left off, carried between your devices (5.x).
//
// `session` keeps a place per context ("Continue listening") and `bookmark` one per long track. Both
// live on one device. This turns them into a block that travels inside the backup (Drive, or a file)
// and back: start an audiobook on the phone, pick it up on the desktop at the same minute.
//
// Three things make that harder than copying two lists:
//
//   * **A file on this device is not a file on that one.** A local track's id is its URI here
//     (`local:content://…` on Android, a path on the desktop), meaningless elsewhere. It travels as
//     its tags instead, and the other device looks for the same song in its own library with the
//     rule the app already uses to match a local file to a Spotify track (`matcher::local`): ISRC,
//     then title, duration and artist. A device that does not have the song does not get the place —
//     it would offer something it cannot play — but the place stays in the block for one that does.
//   * **Newest wins, per place.** Two devices edit the same context; the later write is the one the
//     person remembers.
//   * **A finish or a dismissal is an edit too.** Without a record of it, the other device's older
//     copy comes back on the next sync and the row you dismissed reappears. So removing a place
//     leaves a dated tombstone, and a place older than its tombstone is dropped.

use super::bookmark::{self, Bookmark};
use super::session::{self, Session};
use crate::env::storage;
use crate::env::{Env, EnvError};
use crate::matcher::local::{self as local_match, Candidate};
use crate::types::TrackId;
use serde::{Deserialize, Serialize};

/// Removals, kept so they travel (see the top of the file).
const FORGOTTEN_KEY: &str = "places_forgotten";

/// How many removals are remembered, newest first: far more than the places themselves.
const MAX_FORGOTTEN: usize = 1_000;

/// How many contexts the block carries; each device still keeps only its own row's worth.
const MAX_BLOCK_SESSIONS: usize = 50;

/// A track as it travels: its id, or — for a file on the device — what it is.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct PortableTrack {
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub local: Option<Candidate>,
}

/// One context: the session's own fields, with its queue as [PortableTrack]s.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PortableSession {
    /// The session with an empty queue; [tracks] is the queue.
    pub session: Session,
    pub tracks: Vec<PortableTrack>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PortableBookmark {
    pub track: PortableTrack,
    pub position_ms: u64,
    pub duration_ms: u64,
    pub updated_at_ms: i64,
}

/// A place removed at [at]: a context by [id], or a long track's.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Forgotten {
    /// `"session"` or `"bookmark"`.
    pub kind: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub track: Option<PortableTrack>,
    pub at: i64,
}

/// What travels in the backup.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct Block {
    #[serde(default)]
    pub sessions: Vec<PortableSession>,
    #[serde(default)]
    pub bookmarks: Vec<PortableBookmark>,
    #[serde(default)]
    pub forgotten: Vec<Forgotten>,
}

/// What applying a block did, for the sync screen and the log.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct Applied {
    pub sessions: usize,
    pub bookmarks: usize,
    /// Places another device has for songs this one does not have.
    pub skipped: usize,
}

// ── Removals ─────────────────────────────────────────────────────────────────────────────────────

/// Notes that a place was removed now (`session` and `bookmark` call this).
pub(crate) async fn forgotten_now<E: Env>(kind: &str, id: &str) -> Result<(), EnvError> {
    let mut list: Vec<Forgotten> = storage::get_json_or_default::<E, _>(FORGOTTEN_KEY).await;
    list.retain(|f| !(f.kind == kind && f.id == id));
    list.push(Forgotten {
        kind: kind.to_string(),
        id: id.to_string(),
        track: None,
        at: E::now_ms(),
    });
    store_forgotten::<E>(list).await
}

async fn store_forgotten<E: Env>(mut list: Vec<Forgotten>) -> Result<(), EnvError> {
    list.sort_by_key(|f| std::cmp::Reverse(f.at));
    list.truncate(MAX_FORGOTTEN);
    storage::set_json::<E, _>(FORGOTTEN_KEY, Some(&list)).await
}

// ── To and from this device ─────────────────────────────────────────────────────────────────────

/// A track id as it travels: a file on this device as its tags, when the library knows them.
fn portable(id: &str) -> PortableTrack {
    let on_device = matches!(id.parse::<TrackId>(), Ok(TrackId::Local(_)));
    match on_device.then(|| local_match::describe(id)).flatten() {
        Some(mut song) => {
            song.id.clear();
            PortableTrack {
                id: String::new(),
                local: Some(song),
            }
        }
        None => PortableTrack {
            id: id.to_string(),
            local: None,
        },
    }
}

/// A travelled track as an id here, or `None` when this device does not have it.
///
/// A file travels as its tags and is looked for in this library. One that travelled as a bare id
/// (its device had no tags for it) is taken only if this library has that very id — it was this
/// device's own.
fn here(track: &PortableTrack) -> Option<String> {
    if let Some(song) = &track.local {
        return local_match::find(song);
    }
    match track.id.parse::<TrackId>() {
        Ok(TrackId::Local(_)) => local_match::describe(&track.id).map(|_| track.id.clone()),
        Ok(_) => Some(track.id.clone()),
        Err(_) => None,
    }
}

/// Is this the same track, wherever each description came from?
fn same_track(a: &PortableTrack, b: &PortableTrack) -> bool {
    match (&a.local, &b.local) {
        (Some(x), Some(y)) => local_match::same_song(x, y),
        (None, None) => !a.id.is_empty() && a.id == b.id,
        _ => false,
    }
}

/// This device's places, as they travel.
pub async fn export<E: Env>() -> Block {
    let sessions: Vec<Session> = storage::get_json_or_default::<E, _>(session::STORAGE_KEY).await;
    let marks: Vec<Bookmark> = storage::get_json_or_default::<E, _>(bookmark::STORAGE_KEY).await;
    let forgotten: Vec<Forgotten> = storage::get_json_or_default::<E, _>(FORGOTTEN_KEY).await;
    Block {
        sessions: sessions
            .into_iter()
            .map(|mut s| {
                let tracks = s.state.queue.iter().map(|id| portable(id)).collect();
                s.state.queue.clear();
                PortableSession { session: s, tracks }
            })
            .collect(),
        bookmarks: marks
            .into_iter()
            .map(|m| PortableBookmark {
                track: portable(&m.track_id),
                position_ms: m.position_ms,
                duration_ms: m.duration_ms,
                updated_at_ms: m.updated_at_ms,
            })
            .collect(),
        forgotten: forgotten
            .into_iter()
            .map(|f| match f.kind.as_str() {
                "bookmark" => Forgotten {
                    track: Some(portable(&f.id)),
                    id: String::new(),
                    ..f
                },
                _ => f,
            })
            .collect(),
    }
}

/// Two blocks as one: per place the newest, and nothing older than its removal.
pub fn merge(a: Block, b: Block) -> Block {
    // Removals first: the newest of each.
    let mut forgotten: Vec<Forgotten> = Vec::new();
    for f in a.forgotten.into_iter().chain(b.forgotten) {
        match forgotten.iter_mut().find(|g| same_removal(g, &f)) {
            Some(g) if g.at >= f.at => {}
            Some(g) => *g = f,
            None => forgotten.push(f),
        }
    }

    let mut sessions: Vec<PortableSession> = Vec::new();
    for s in a.sessions.into_iter().chain(b.sessions) {
        match sessions.iter_mut().find(|t| t.session.id == s.session.id) {
            Some(t) if t.session.updated_at_ms >= s.session.updated_at_ms => {}
            Some(t) => *t = s,
            None => sessions.push(s),
        }
    }
    sessions.retain(|s| {
        !forgotten
            .iter()
            .any(|f| f.kind == "session" && f.id == s.session.id && f.at >= s.session.updated_at_ms)
    });
    sessions.sort_by_key(|s| std::cmp::Reverse(s.session.updated_at_ms));
    sessions.truncate(MAX_BLOCK_SESSIONS);

    let mut bookmarks: Vec<PortableBookmark> = Vec::new();
    for m in a.bookmarks.into_iter().chain(b.bookmarks) {
        match bookmarks
            .iter_mut()
            .find(|n| same_track(&n.track, &m.track))
        {
            Some(n) if n.updated_at_ms >= m.updated_at_ms => {}
            Some(n) => *n = m,
            None => bookmarks.push(m),
        }
    }
    bookmarks.retain(|m| {
        !forgotten.iter().any(|f| {
            f.kind == "bookmark"
                && f.track.as_ref().is_some_and(|t| same_track(t, &m.track))
                && f.at >= m.updated_at_ms
        })
    });
    bookmarks.sort_by_key(|m| std::cmp::Reverse(m.updated_at_ms));
    bookmarks.truncate(bookmark::MAX_BOOKMARKS);

    forgotten.sort_by_key(|f| std::cmp::Reverse(f.at));
    forgotten.truncate(MAX_FORGOTTEN);
    Block {
        sessions,
        bookmarks,
        forgotten,
    }
}

fn same_removal(a: &Forgotten, b: &Forgotten) -> bool {
    if a.kind != b.kind {
        return false;
    }
    match (&a.track, &b.track) {
        (Some(x), Some(y)) => same_track(x, y),
        (None, None) => a.id == b.id,
        _ => false,
    }
}

/// Takes in another device's places: merged with this one's, then written here in this device's
/// ids. A place whose songs are not all here is left out — and stays in the block for a device that
/// has them.
pub async fn apply<E: Env>(remote: Block) -> Result<Applied, EnvError> {
    let mine = export::<E>().await;
    let own_sessions: Vec<Session> =
        storage::get_json_or_default::<E, _>(session::STORAGE_KEY).await;
    let own_marks: Vec<Bookmark> =
        storage::get_json_or_default::<E, _>(bookmark::STORAGE_KEY).await;
    let merged = merge(mine, remote);
    let mut applied = Applied::default();

    let mut sessions: Vec<Session> = Vec::new();
    for p in merged.sessions {
        let queue: Option<Vec<String>> = p.tracks.iter().map(here).collect();
        match queue {
            Some(queue) => {
                let mut s = p.session;
                s.state.queue = queue;
                sessions.push(s);
            }
            None => {
                applied.skipped += 1;
                // Not playable here: what this device had for the context stays.
                if let Some(own) = own_sessions.iter().find(|s| s.id == p.session.id) {
                    sessions.push(own.clone());
                }
            }
        }
    }
    sessions.retain(Session::is_resumable);
    sessions.sort_by_key(|s| std::cmp::Reverse(s.updated_at_ms));
    sessions.truncate(session::MAX_SESSIONS);
    applied.sessions = sessions.len();

    let mut marks: Vec<Bookmark> = Vec::new();
    for m in &merged.bookmarks {
        match here(&m.track) {
            Some(track_id) => {
                if !marks.iter().any(|b| b.track_id == track_id) {
                    marks.push(Bookmark {
                        track_id,
                        position_ms: m.position_ms,
                        duration_ms: m.duration_ms,
                        updated_at_ms: m.updated_at_ms,
                    });
                }
            }
            None => applied.skipped += 1,
        }
    }
    // A long track only this device has, and the other knew nothing of, is still this device's.
    for own in own_marks {
        if !marks.iter().any(|b| b.track_id == own.track_id)
            && !merged.forgotten.iter().any(|f| {
                f.kind == "bookmark"
                    && f.track.as_ref().and_then(here).as_deref() == Some(own.track_id.as_str())
                    && f.at >= own.updated_at_ms
            })
            && merged
                .bookmarks
                .iter()
                .all(|m| here(&m.track).as_deref() != Some(own.track_id.as_str()))
        {
            marks.push(own);
        }
    }
    marks.retain(|m| bookmark::worth_keeping(m.position_ms, m.duration_ms));
    marks.sort_by_key(|m| std::cmp::Reverse(m.updated_at_ms));
    marks.truncate(bookmark::MAX_BOOKMARKS);
    applied.bookmarks = marks.len();

    // Removals are kept in this device's ids where it has the song, so they travel again from here.
    let forgotten: Vec<Forgotten> = merged
        .forgotten
        .into_iter()
        .filter_map(|f| match f.kind.as_str() {
            "bookmark" => f.track.as_ref().and_then(here).map(|id| Forgotten {
                id,
                track: None,
                ..f.clone()
            }),
            _ => Some(f),
        })
        .collect();

    storage::set_json::<E, _>(session::STORAGE_KEY, Some(&sessions)).await?;
    storage::set_json::<E, _>(bookmark::STORAGE_KEY, Some(&marks)).await?;
    store_forgotten::<E>(forgotten).await?;
    Ok(applied)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::env::mock::{self, MockEnv};
    use crate::player::PlayerState;

    const HOUR: u64 = 60 * 60_000;

    fn session(id: &str, queue: &[&str], at: i64) -> Session {
        Session {
            id: id.to_string(),
            label: id.to_string(),
            subtitle: String::new(),
            image_url: String::new(),
            track_title: String::new(),
            track_artist: String::new(),
            track_image_url: String::new(),
            state: PlayerState {
                queue: queue.iter().map(|q| q.to_string()).collect(),
                index: 1,
                position_ms: 90_000,
                ..PlayerState::default()
            },
            duration_ms: 240_000,
            updated_at_ms: at,
        }
    }

    fn song(id: &str, name: &str, artist: &str, duration_ms: u32) -> Candidate {
        Candidate {
            id: id.to_string(),
            name: name.to_string(),
            artists: vec![artist.to_string()],
            isrc: String::new(),
            duration_ms,
        }
    }

    async fn put_sessions(list: Vec<Session>) {
        storage::set_json::<MockEnv, _>(session::STORAGE_KEY, Some(&list))
            .await
            .unwrap();
    }

    async fn sessions() -> Vec<Session> {
        storage::get_json_or_default::<MockEnv, _>(session::STORAGE_KEY).await
    }

    /// The phone's block for a context and a long track, local songs in it described by their tags.
    fn phone_block(at: i64) -> Block {
        let local = |name: &str, d: u32| PortableTrack {
            id: String::new(),
            local: Some(Candidate {
                id: String::new(),
                ..song("", name, "Björk", d)
            }),
        };
        let mut s = session("album:debut", &[], at);
        s.state.queue.clear();
        Block {
            sessions: vec![PortableSession {
                session: s,
                tracks: vec![
                    PortableTrack {
                        id: "4uLU6hMCjMI75M1A2tKUQC".into(),
                        local: None,
                    },
                    local("Jóga", 305_000),
                ],
            }],
            bookmarks: vec![PortableBookmark {
                track: local("Live at Shepherds Bush", 2 * HOUR as u32),
                position_ms: 34 * 60_000,
                duration_ms: 2 * HOUR,
                updated_at_ms: at,
            }],
            forgotten: vec![],
        }
    }

    #[tokio::test]
    async fn a_place_with_local_songs_lands_on_the_device_that_has_them() {
        let _guard = mock::lock_and_reset();
        let _library = local_match::test_lock();
        // This device's own library: the same songs, other paths, a second off in duration.
        local_match::put(&[
            song("local:/music/joga.flac", "Joga", "Bjork", 306_000),
            song(
                "local:/music/shepherds.m4a",
                "Live at Shepherds Bush",
                "Björk",
                2 * HOUR as u32 - 900,
            ),
        ]);
        let applied = apply::<MockEnv>(phone_block(500)).await.unwrap();
        assert_eq!(
            applied,
            Applied {
                sessions: 1,
                bookmarks: 1,
                skipped: 0
            }
        );
        let here = sessions().await;
        assert_eq!(
            here[0].state.queue,
            vec!["4uLU6hMCjMI75M1A2tKUQC", "local:/music/joga.flac"]
        );
        assert_eq!(
            bookmark::get::<MockEnv>("local:/music/shepherds.m4a").await,
            Some(34 * 60_000)
        );
    }

    #[tokio::test]
    async fn without_the_songs_the_place_is_not_offered_here() {
        let _guard = mock::lock_and_reset();
        let _library = local_match::test_lock();
        local_match::put(&[song(
            "local:/music/other.mp3",
            "Hyperballad",
            "Björk",
            321_000,
        )]);
        let applied = apply::<MockEnv>(phone_block(500)).await.unwrap();
        assert_eq!(
            applied,
            Applied {
                sessions: 0,
                bookmarks: 0,
                skipped: 2
            }
        );
        assert!(sessions().await.is_empty());
    }

    #[tokio::test]
    async fn the_newest_place_wins_on_either_side() {
        let _guard = mock::lock_and_reset();
        let _library = local_match::test_lock();
        local_match::put(&[]);
        put_sessions(vec![session("album:a", &["t0", "t1", "t2"], 300)]).await;
        let mut theirs = session("album:a", &[], 200);
        theirs.state.position_ms = 10_000;
        let older = Block {
            sessions: vec![PortableSession {
                session: theirs.clone(),
                tracks: ["t0", "t1", "t2"]
                    .iter()
                    .map(|t| PortableTrack {
                        id: t.to_string(),
                        local: None,
                    })
                    .collect(),
            }],
            ..Block::default()
        };
        apply::<MockEnv>(older).await.unwrap();
        assert_eq!(
            sessions().await[0].state.position_ms,
            90_000,
            "this device's later place stays"
        );

        theirs.updated_at_ms = 400;
        let newer = Block {
            sessions: vec![PortableSession {
                session: theirs,
                tracks: ["t0", "t1", "t2"]
                    .iter()
                    .map(|t| PortableTrack {
                        id: t.to_string(),
                        local: None,
                    })
                    .collect(),
            }],
            ..Block::default()
        };
        apply::<MockEnv>(newer).await.unwrap();
        assert_eq!(
            sessions().await[0].state.position_ms,
            10_000,
            "the other device's later place wins"
        );
    }

    #[tokio::test]
    async fn a_dismissed_row_does_not_come_back_from_the_other_device() {
        let _guard = mock::lock_and_reset();
        let _library = local_match::test_lock();
        local_match::put(&[]);
        put_sessions(vec![session("album:a", &["t0", "t1"], 100)]).await;
        let theirs = export::<MockEnv>().await;
        // Dismissed here after the other device last saw it.
        mock::state().now_ms = 200;
        session::forget::<MockEnv>("album:a").await.unwrap();
        apply::<MockEnv>(theirs).await.unwrap();
        assert!(sessions().await.is_empty());
        let out = export::<MockEnv>().await;
        assert!(
            out.forgotten
                .iter()
                .any(|f| f.kind == "session" && f.id == "album:a"),
            "and the removal travels on"
        );
    }

    #[tokio::test]
    async fn finishing_a_long_track_reaches_the_other_device() {
        let _guard = mock::lock_and_reset();
        let _library = local_match::test_lock();
        local_match::put(&[]);
        bookmark::record::<MockEnv>("ep:1", 30 * 60_000, HOUR)
            .await
            .unwrap();
        let before = export::<MockEnv>().await;
        mock::state().now_ms = 10;
        bookmark::record::<MockEnv>("ep:1", HOUR - 5_000, HOUR)
            .await
            .unwrap();
        let after = export::<MockEnv>().await;
        // The other device still had the old place; merging drops it.
        let merged = merge(before, after);
        assert!(merged.bookmarks.is_empty());
    }

    #[test]
    fn local_songs_are_the_same_place_by_their_tags() {
        let a = PortableTrack {
            id: String::new(),
            local: Some(song("", "Jóga", "Björk", 305_000)),
        };
        let b = PortableTrack {
            id: String::new(),
            local: Some(song("", "Joga", "Bjork", 304_000)),
        };
        let c = PortableTrack {
            id: "local:/x".into(),
            local: None,
        };
        assert!(same_track(&a, &b));
        assert!(!same_track(&a, &c));
        assert!(same_track(&c, &c.clone()));
    }

    #[test]
    fn the_block_reads_what_it_writes() {
        let block = phone_block(1);
        let text = serde_json::to_string(&block).unwrap();
        assert_eq!(serde_json::from_str::<Block>(&text).unwrap(), block);
        assert_eq!(
            serde_json::from_str::<Block>("{}").unwrap(),
            Block::default(),
            "an older backup has none"
        );
    }
}
