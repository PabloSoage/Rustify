// core_engine/tests/end_to_end.rs
//
// Integration tests: the seams.
//
// Every module here already has unit tests, and they are the ones that catch most things. What they
// cannot catch is the class of bug where **every part is right and the whole is wrong** — the
// decryption test passes, the server test passes, and together they serve noise because a stripe
// index did not survive a chunk boundary.
//
// So each test here crosses at least two modules and asserts something a user would notice.

use core_engine::env::mock::{self, MockEnv};
use core_engine::env::Env;

// ================================================================================================
// Links round trip -- point F
// ================================================================================================

#[test]
fn every_link_we_can_produce_is_a_link_we_can_read() {
    // The property that keeps the two halves from drifting: whatever `canonical_url` writes,
    // `parse` must read back as the same thing. Four Kotlin implementations diverged precisely
    // because nothing tied writing to reading.
    let samples = [
        "https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT",
        "https://open.spotify.com/intl-es/track/4cOdK2wGLETKBW3PvgPWqT",
        "https://open.spotify.com/album/2noRn2Aes5aoNVsU6iWThc?si=abc123",
        "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M",
        "https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=PLabc",
        "https://youtu.be/dQw4w9WgXcQ",
    ];
    for raw in samples {
        let parsed = core_engine::links::parse(raw)
            .unwrap_or_else(|| panic!("{raw} should be a link we understand"));
        let canonical = parsed.canonical_url();
        let reparsed = core_engine::links::parse(&canonical)
            .unwrap_or_else(|| panic!("{canonical} came from us and must parse"));
        assert_eq!(
            parsed.id(),
            reparsed.id(),
            "{raw} -> {canonical} lost its identity on the way back"
        );
    }
}

#[test]
fn a_wrapped_link_survives_being_wrapped_and_unwrapped() {
    let original = "https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT";
    let wrapped = core_engine::links::wrap(original, Some("rustify-music.github.io"));
    let hosts = ["rustify-music.github.io"];
    let recovered = core_engine::links::unwrap_wrapper(&wrapped, &hosts)
        .expect("we wrapped it, so we must be able to unwrap it");
    // `id()` borrows from the target, so it has to be owned before the target is dropped at the end
    // of the closure.
    assert_eq!(
        core_engine::links::parse(&recovered).map(|t| t.id().to_string()),
        core_engine::links::parse(original).map(|t| t.id().to_string())
    );
}

// ================================================================================================
// Search over what listening actually produces -- point J
// ================================================================================================

#[test]
fn a_library_is_searchable_by_anything_a_person_half_remembers() {
    let index = core_engine::search::Index::build(vec![
        (
            "t1".into(),
            vec!["Corazón Espinado".into(), "Santana".into(), "Supernatural".into()],
        ),
        (
            "t2".into(),
            vec!["Smooth".into(), "Santana".into(), "Supernatural".into()],
        ),
        (
            "t3".into(),
            vec!["Jóga".into(), "Björk".into(), "Homogenic".into()],
        ),
    ]);

    // Without an accent on the keyboard.
    assert_eq!(index.query("corazon", 0), vec!["t1"]);
    assert_eq!(index.query("bjork", 0), vec!["t3"]);
    // By the album, which the old `contains` over name+artist could not do at all.
    assert_eq!(index.query("supernatural", 0).len(), 2);
    // Out of order, and across two fields at once.
    assert_eq!(index.query("santana smooth", 0), vec!["t2"]);
    // And a query that matches nothing matches nothing, rather than half the library.
    assert!(index.query("banana", 0).is_empty());
}

// ================================================================================================
// A whole listening session: play, mark, resume -- points G and I
// ================================================================================================

#[tokio::test]
async fn a_session_recorded_while_playing_comes_back_as_something_resumable() {
    let _guard = mock::lock_and_reset();

    let queue: Vec<String> = (1..=12).map(|i| format!("track{i}")).collect();
    let session = core_engine::player::session::Session {
        id: "album:abc".into(),
        label: "Supernatural".into(),
        subtitle: "Santana".into(),
        image_url: "https://example.invalid/cover.jpg".into(),
        track_title: "Smooth".into(),
        track_artist: "Santana".into(),
        state: core_engine::player::PlayerState {
            queue: queue.clone(),
            index: 3,
            position_ms: 61_000,
            ..Default::default()
        },
        duration_ms: 300_000,
        updated_at_ms: 1_000,
    };
    core_engine::player::session::record::<MockEnv>(session)
        .await
        .unwrap();

    // Four tracks heard through.
    for id in queue.iter().take(4) {
        core_engine::listened::mark::<MockEnv>("album:abc", &queue, id)
            .await
            .unwrap();
    }

    let offered = core_engine::player::session::list::<MockEnv>().await;
    assert_eq!(offered.len(), 1);
    let it = &offered[0];
    assert_eq!(it.state.index, 3);
    assert_eq!(it.state.position_ms, 61_000);
    assert_eq!(it.track_title, "Smooth", "the row has to be able to name the track");

    let marks = core_engine::listened::state::<MockEnv>("album:abc", &queue).await;
    assert_eq!(marks.len(), 12);
    assert_eq!(marks.iter().filter(|m| **m).count(), 4);
    assert!(marks[..4].iter().all(|m| *m));
    assert!(marks[4..].iter().all(|m| !*m));
}

#[tokio::test]
async fn finishing_an_album_takes_its_row_away_and_leaves_the_ticks() {
    // Two stores, two rules, and they must not interfere: "there is nothing to come back to" is not
    // "you never heard this".
    let _guard = mock::lock_and_reset();
    let queue: Vec<String> = vec!["a".into(), "b".into()];

    for id in &queue {
        core_engine::listened::mark::<MockEnv>("album:done", &queue, id)
            .await
            .unwrap();
    }
    let finished = core_engine::player::session::Session {
        id: "album:done".into(),
        label: "Done".into(),
        subtitle: String::new(),
        image_url: String::new(),
        track_title: "b".into(),
        track_artist: String::new(),
        state: core_engine::player::PlayerState {
            queue: queue.clone(),
            index: 1,
            position_ms: 179_000,
            ..Default::default()
        },
        duration_ms: 180_000,
        updated_at_ms: 2_000,
    };
    core_engine::player::session::record::<MockEnv>(finished)
        .await
        .unwrap();

    assert!(
        core_engine::player::session::list::<MockEnv>().await.is_empty(),
        "an album you finished must stop being offered"
    );
    assert_eq!(
        core_engine::listened::state::<MockEnv>("album:done", &queue).await,
        vec![true, true],
        "and you have still heard it"
    );
}

// ================================================================================================
// Export carries the whole state and no credential -- point L
// ================================================================================================

#[tokio::test]
async fn an_export_carries_a_real_session_back_and_still_leaks_nothing() {
    let _guard = mock::lock_and_reset();

    MockEnv::set_storage("spotify_sp_dc", Some("THE-SESSION-COOKIE"))
        .await
        .unwrap();
    let queue: Vec<String> = vec!["x".into(), "y".into(), "z".into()];
    core_engine::listened::mark::<MockEnv>("playlist:p", &queue, "y")
        .await
        .unwrap();
    core_engine::player::session::record::<MockEnv>(core_engine::player::session::Session {
        id: "playlist:p".into(),
        label: "A playlist".into(),
        subtitle: String::new(),
        image_url: String::new(),
        track_title: "y".into(),
        track_artist: String::new(),
        state: core_engine::player::PlayerState {
            queue: queue.clone(),
            index: 1,
            position_ms: 40_000,
            ..Default::default()
        },
        duration_ms: 200_000,
        updated_at_ms: 5,
    })
    .await
    .unwrap();

    let json = core_engine::export::to_json::<MockEnv>("integration").await.unwrap();
    assert!(
        !json.contains("THE-SESSION-COOKIE"),
        "an export must never carry a session"
    );

    // Wipe both stores, then restore from the file alone.
    core_engine::player::session::clear::<MockEnv>().await.unwrap();
    core_engine::listened::clear::<MockEnv>().await.unwrap();
    assert!(core_engine::player::session::list::<MockEnv>().await.is_empty());

    core_engine::export::restore::<MockEnv>(&json).await.unwrap();

    let back = core_engine::player::session::list::<MockEnv>().await;
    assert_eq!(back.len(), 1);
    assert_eq!(back[0].state.position_ms, 40_000);
    assert_eq!(
        core_engine::listened::state::<MockEnv>("playlist:p", &queue).await,
        vec![false, true, false]
    );
}

// ================================================================================================
// The queue reducer against a real-shaped queue -- point H
// ================================================================================================

#[test]
fn walking_an_album_to_the_end_says_the_queue_ran_out_rather_than_stop() {
    use core_engine::player::{reduce, Action, Effect, PlayerState};

    let mut state = PlayerState {
        queue: (1..=3).map(|i| format!("t{i}")).collect(),
        ..Default::default()
    };
    // Two ordinary advances.
    for expected in ["t2", "t3"] {
        let effects = reduce(&mut state, Action::TrackEnded);
        let played = effects.iter().find_map(|e| match e {
            Effect::Play { track, .. } => Some(track.as_str()),
            _ => None,
        });
        assert_eq!(played, Some(expected));
    }
    // And the third is the end of the album, which Android answers with a radio.
    let end = reduce(&mut state, Action::TrackEnded);
    assert_eq!(
        end.first(),
        Some(&Effect::QueueExhausted { last: "t3".into() }),
        "the platform has to be able to tell 'nothing follows' from 'stop'"
    );
    assert!(end.contains(&Effect::Stop));
}

#[test]
fn repeat_one_replays_on_its_own_and_moves_on_when_asked() {
    use core_engine::player::{reduce, Action, Effect, PlayerState, Repeat};

    let mut state = PlayerState {
        queue: vec!["a".into(), "b".into()],
        repeat: Repeat::One,
        ..Default::default()
    };
    // Ending replays the same track...
    let ended = reduce(&mut state, Action::TrackEnded);
    assert!(ended.contains(&Effect::Play {
        track: "a".into(),
        position_ms: 0
    }));
    assert_eq!(state.index, 0);

    // ...and pressing next still moves on. This is the distinction that had two implementations.
    let pressed = reduce(&mut state, Action::Next);
    assert!(pressed.contains(&Effect::Play {
        track: "b".into(),
        position_ms: 0
    }));
    assert_eq!(state.index, 1);
}

// ================================================================================================
// The release calendar against dates as Spotify really sends them -- point K
// ================================================================================================

#[test]
fn a_feed_of_mixed_precision_dates_sorts_the_way_a_person_expects() {
    use core_engine::calendar::{arrange, Bucket, Entry};

    let entry = |id: &str, date: &str, precision: &str| Entry {
        id: id.into(),
        release_date: date.into(),
        release_date_precision: precision.into(),
    };
    let feed = vec![
        entry("classic", "1975", "year"),
        entry("upcoming", "2024-07-01", "day"),
        entry("reissue", "2024-01", "month"),
        entry("yesterday", "2024-06-14", "day"),
    ];
    // 2024-06-15, as a millisecond clock.
    let now_ms = 19_889i64 * 86_400_000;

    let placed = arrange(&feed, now_ms);
    let order: Vec<&str> = placed.iter().map(|p| p.id.as_str()).collect();
    assert_eq!(order, vec!["upcoming", "yesterday", "reissue", "classic"]);
    assert_eq!(placed[0].bucket, Bucket::Upcoming);
    assert_eq!(placed[1].bucket, Bucket::ThisWeek);
    assert_eq!(
        placed[3].bucket,
        Bucket::Older,
        "a year-precision release must not land on the 1st of January"
    );
}
