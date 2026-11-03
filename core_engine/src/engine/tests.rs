// core_engine/src/engine/tests.rs
//
// The whole pipeline — open, decode, convert, ring, device — against `NullOutput`, with WAV files
// written for the test. What is asserted is the sequence of events, because that sequence is the
// contract the desktop app's queue is driven by.

use std::path::PathBuf;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::{Duration, Instant};

use super::decode::tests::wav;
use super::output::{Format, NullOutput, Pace};
use super::*;
use crate::env::mock::MockEnv;

static FILES: AtomicUsize = AtomicUsize::new(0);

/// A WAV on disk, removed when the test ends.
struct TempWav(PathBuf);

impl TempWav {
    fn new(rate: u32, channels: u16, seconds: f64) -> Self {
        let n = FILES.fetch_add(1, Ordering::Relaxed);
        let path = std::env::temp_dir().join(format!(
            "rustify-engine-{}-{n}.wav",
            std::process::id()
        ));
        std::fs::write(&path, wav(rate, channels, seconds, 440.0)).expect("temp file");
        TempWav(path)
    }

    fn uri(&self) -> String {
        self.0.to_string_lossy().into_owned()
    }
}

impl Drop for TempWav {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.0);
    }
}

fn engine(output: NullOutput) -> Engine {
    Engine::start::<MockEnv>(Box::new(output)).expect("engine starts")
}

/// Every event until `until` matches one, or panics after a few seconds.
fn collect_until(engine: &Engine, until: impl Fn(&Event) -> bool) -> Vec<Event> {
    let deadline = Instant::now() + Duration::from_secs(10);
    let mut seen = Vec::new();
    while Instant::now() < deadline {
        if let Some(event) = engine.poll(Duration::from_millis(50)) {
            let done = until(&event);
            seen.push(event);
            if done {
                return seen;
            }
        }
    }
    panic!("timed out; events so far: {seen:?}");
}

fn load(token: Token, uri: String, play: bool) -> Command {
    Command::Load {
        token,
        uri,
        start_ms: 0,
        gain_db: 0.0,
        play,
    }
}

#[test]
fn a_track_loads_plays_and_ends() {
    let track = TempWav::new(44_100, 2, 0.5);
    let engine = engine(NullOutput::new(Pace::Instant));
    assert!(engine.send(load(1, track.uri(), true)));
    let events = collect_until(&engine, |e| matches!(e, Event::Ended { .. }));
    assert!(events.contains(&Event::Loading { token: 1 }));
    assert!(events.contains(&Event::Ready {
        token: 1,
        duration_ms: Some(500)
    }));
    assert!(events.contains(&Event::State { playing: true }));
    assert_eq!(events.last(), Some(&Event::Ended { token: 1 }));
}

#[test]
fn a_preloaded_track_follows_without_an_end_in_between() {
    let first = TempWav::new(44_100, 2, 0.3);
    let second = TempWav::new(44_100, 2, 0.3);
    let engine = engine(NullOutput::new(Pace::Instant));
    engine.send(load(1, first.uri(), true));
    engine.send(Command::Preload {
        token: 2,
        uri: second.uri(),
        gain_db: 0.0,
    });
    let events = collect_until(&engine, |e| matches!(e, Event::Ended { token: 2 }));
    assert!(
        events.contains(&Event::Transitioned { from: 1, to: 2 }),
        "{events:?}"
    );
    assert!(
        !events.contains(&Event::Ended { token: 1 }),
        "a gapless hand-over is not an end: {events:?}"
    );
}

#[test]
fn a_different_rate_and_channel_count_still_plays_through() {
    // A mono 22.05 kHz file into a 48 kHz stereo "device": the conversion path end to end.
    let track = TempWav::new(22_050, 1, 0.4);
    let engine = engine(NullOutput::new(Pace::Instant).with_format(Format {
        sample_rate: 48_000,
        channels: 2,
    }));
    engine.send(load(7, track.uri(), true));
    let events = collect_until(&engine, |e| matches!(e, Event::Ended { .. }));
    assert_eq!(events.last(), Some(&Event::Ended { token: 7 }));
}

#[test]
fn starting_part_way_in_reports_from_there() {
    let track = TempWav::new(48_000, 2, 3.0);
    let engine = engine(NullOutput::new(Pace::RealTime { speed: 1.0 }));
    engine.send(Command::Load {
        token: 3,
        uri: track.uri(),
        start_ms: 2_000,
        gain_db: 0.0,
        play: true,
    });
    let events = collect_until(&engine, |e| matches!(e, Event::Position { .. }));
    match events.last() {
        Some(Event::Position { token, position_ms }) => {
            assert_eq!(*token, 3);
            assert!(*position_ms >= 2_000, "{position_ms}");
        }
        other => panic!("{other:?}"),
    }
}

#[test]
fn pausing_holds_the_position() {
    let track = TempWav::new(48_000, 2, 5.0);
    let engine = engine(NullOutput::new(Pace::RealTime { speed: 1.0 }));
    engine.send(load(4, track.uri(), true));
    collect_until(&engine, |e| matches!(e, Event::Position { .. }));
    engine.send(Command::Pause);
    collect_until(&engine, |e| *e == Event::State { playing: false });
    // Positions are reported every 250 ms whether playing or not. Throw away what queued up before
    // the pause took hold, then read two fresh ones a while apart.
    let fresh_position = |engine: &Engine| {
        while engine.poll(Duration::ZERO).is_some() {}
        loop {
            if let Some(Event::Position { position_ms, .. }) = engine.poll(Duration::from_secs(2)) {
                return position_ms;
            }
        }
    };
    std::thread::sleep(Duration::from_millis(300));
    let a = fresh_position(&engine);
    std::thread::sleep(Duration::from_millis(600));
    let b = fresh_position(&engine);
    assert_eq!(a, b, "the position moved while paused");
}

#[test]
fn something_undecodable_is_an_unsupported_error_for_that_token() {
    let path = std::env::temp_dir().join(format!("rustify-engine-{}-junk.bin", std::process::id()));
    std::fs::write(&path, vec![0x42u8; 8192]).expect("temp file");
    let engine = engine(NullOutput::new(Pace::Instant));
    engine.send(load(9, path.to_string_lossy().into_owned(), true));
    let events = collect_until(&engine, |e| matches!(e, Event::Error { .. }));
    let _ = std::fs::remove_file(&path);
    match events.last() {
        Some(Event::Error {
            token, code, kind, ..
        }) => {
            assert_eq!(*token, Some(9));
            assert_eq!(*code, "unsupported");
            assert_eq!(*kind, "permanent");
        }
        other => panic!("{other:?}"),
    }
}

#[test]
fn a_missing_file_is_an_open_error() {
    let engine = engine(NullOutput::new(Pace::Instant));
    engine.send(load(5, "/definitely/not/here.flac".into(), true));
    let events = collect_until(&engine, |e| matches!(e, Event::Error { .. }));
    assert!(matches!(
        events.last(),
        Some(Event::Error { code: "open", .. })
    ));
}

#[test]
fn a_newer_load_wins_over_one_still_opening() {
    let first = TempWav::new(44_100, 2, 2.0);
    let second = TempWav::new(44_100, 2, 0.2);
    let engine = engine(NullOutput::new(Pace::Instant));
    engine.send(load(1, first.uri(), true));
    engine.send(load(2, second.uri(), true));
    let events = collect_until(&engine, |e| matches!(e, Event::Ended { .. }));
    assert_eq!(events.last(), Some(&Event::Ended { token: 2 }));
}

#[test]
fn events_serialise_in_the_shape_the_bridges_send() {
    let json = serde_json::to_string(&Event::Ready {
        token: 12,
        duration_ms: Some(1000),
    })
    .unwrap();
    assert_eq!(json, r#"{"type":"ready","token":12,"durationMs":1000}"#);
    let json = serde_json::to_string(&Event::error(Some(1), &EngineError::Http(503))).unwrap();
    assert_eq!(
        json,
        r#"{"type":"error","token":1,"code":"http","kind":"transient","message":"the source answered 503"}"#
    );
}

#[test]
fn commands_deserialise_from_the_shape_the_app_sends() {
    let load: Command = serde_json::from_str(
        r#"{"type":"load","token":4,"uri":"https://x/a.m4a","startMs":1500,"gainDb":-3.5,"play":true}"#,
    )
    .unwrap();
    assert_eq!(
        load,
        Command::Load {
            token: 4,
            uri: "https://x/a.m4a".into(),
            start_ms: 1500,
            gain_db: -3.5,
            play: true
        }
    );
    let seek: Command = serde_json::from_str(r#"{"type":"seek","positionMs":30000}"#).unwrap();
    assert_eq!(seek, Command::Seek { position_ms: 30_000 });
    let pause: Command = serde_json::from_str(r#"{"type":"pause"}"#).unwrap();
    assert_eq!(pause, Command::Pause);
    assert!(
        serde_json::from_str::<Command>(r#"{"type":"shutdown"}"#).is_err(),
        "only dropping the engine ends it"
    );
}

#[test]
fn gain_in_decibels_is_the_usual_curve() {
    assert_eq!(player::db_to_linear(0.0), 1.0);
    assert!((player::db_to_linear(-6.0) - 0.501).abs() < 0.001);
    assert!((player::db_to_linear(6.0) - 1.995).abs() < 0.001);
}
