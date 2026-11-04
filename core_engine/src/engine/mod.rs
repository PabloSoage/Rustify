// core_engine/src/engine/mod.rs
//
// The audio engine for the platforms that have no Media3: Windows, Linux and macOS, and later iOS.
//
// Android keeps ExoPlayer (docs `22` §2, `24` §2): it already has the buffer, the system codecs, the
// DSP offload, Android Auto and the media session, and nothing here would beat it on a phone. What a
// desktop has instead is nothing at all — Spotube asks for VLC to be installed or ships ~100 MB of
// it — so this is the part of the core the desktop app plays through:
//
//   source (file / HTTP via `Env`) → decode (Symphonia) → resample + remix → track gain
//        → ring → [pause · volume · fade, in the device callback] → output (cpal / null)
//
// Behind the `engine` feature, so the APK's `.so` does not carry a decoder it never calls; the cpal
// device is behind `desktop-audio` on top of it, so the tests (and CI, which has no sound card) run
// the whole pipeline against `NullOutput`.
//
// The queue is not here. `player::reduce` decides what plays next, and the caller turns its
// `Effect::Play` into a `Load` (and the track after it into a `Preload`, for gapless) — the same
// split Android has between `PlayerQueue` and ExoPlayer.

pub mod decode;
pub mod effects;
pub mod output;
mod player;
pub mod resample;
pub mod ring;
pub mod source;

use std::fmt;
use std::sync::mpsc::{self, Receiver, Sender};
use std::sync::Mutex;
use std::thread::JoinHandle;
use std::time::Duration;

use serde::{Deserialize, Serialize};

use crate::env::Env;
use crate::errors::{Classify, ErrorKind};

use output::AudioOutput;
use player::{Msg, Player};

/// The caller's name for a track, echoed back in every event about it. The engine never interprets
/// it; the desktop app uses the queue index's generation so a late event about a skipped track can be
/// recognised and dropped.
pub type Token = u64;

/// What the caller can ask for. Deserialised from `{"type":"load",…}` by the bridges.
#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(tag = "type", rename_all = "camelCase", rename_all_fields = "camelCase")]
pub enum Command {
    /// Replace what plays with `uri`, from `start_ms`. `gain_db` is the track's normalisation
    /// (loudness or ReplayGain, decided by the caller); `play` false loads it paused.
    Load {
        token: Token,
        uri: String,
        #[serde(default)]
        start_ms: u64,
        #[serde(default)]
        gain_db: f32,
        #[serde(default)]
        play: bool,
    },
    /// Open `uri` now and start it the sample after the current track ends.
    Preload {
        token: Token,
        uri: String,
        #[serde(default)]
        gain_db: f32,
    },
    /// Forget the preloaded track (the queue changed under it).
    ClearNext,
    Play,
    Pause,
    Seek { position_ms: u64 },
    Stop,
    /// 0.0..=1.0, applied in the device callback.
    SetVolume { volume: f32 },
    /// The fade on pause, resume and volume changes; 0 turns it off.
    SetFadeMs { ms: u32 },
    /// Ends the player thread. Not something a bridge sends: dropping the [`Engine`] does it.
    #[serde(skip)]
    Shutdown,
}

/// What happened. Serialised as `{"type":"ready",…}` for the bridges.
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(tag = "type", rename_all = "camelCase")]
pub enum Event {
    #[serde(rename_all = "camelCase")]
    Loading { token: Token },
    #[serde(rename_all = "camelCase")]
    Ready {
        token: Token,
        duration_ms: Option<u64>,
    },
    #[serde(rename_all = "camelCase")]
    Preloaded {
        token: Token,
        duration_ms: Option<u64>,
    },
    State { playing: bool },
    #[serde(rename_all = "camelCase")]
    Position { token: Token, position_ms: u64 },
    /// The device moved from one track to the preloaded one with no gap.
    Transitioned { from: Token, to: Token },
    /// The track was heard to its last sample and nothing followed it.
    Ended { token: Token },
    Error {
        token: Option<Token>,
        /// `open`, `http`, `unsupported`, `decode` or `output` — what failed.
        code: &'static str,
        /// The usual four (`errors::ErrorKind`): whether trying again could help.
        kind: &'static str,
        message: String,
    },
}

impl Event {
    pub(crate) fn error(token: Option<Token>, e: &EngineError) -> Self {
        Event::Error {
            token,
            code: e.code(),
            kind: e.kind().as_str(),
            message: e.to_string(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum EngineError {
    /// The source could not be reached or read.
    Open(String),
    /// The server answered with this status.
    Http(u16),
    /// The bytes are not something this engine decodes (an Opus stream, for one — see `decode`).
    Unsupported(String),
    Decode(String),
    Output(String),
}

impl EngineError {
    /// The wire name of what failed. Stable: the app picks the next source on `unsupported`.
    pub fn code(&self) -> &'static str {
        match self {
            EngineError::Open(_) => "open",
            EngineError::Http(_) => "http",
            EngineError::Unsupported(_) => "unsupported",
            EngineError::Decode(_) => "decode",
            EngineError::Output(_) => "output",
        }
    }
}

impl fmt::Display for EngineError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            EngineError::Open(m) => write!(f, "could not open the source: {m}"),
            EngineError::Http(status) => write!(f, "the source answered {status}"),
            EngineError::Unsupported(m) => write!(f, "unsupported: {m}"),
            EngineError::Decode(m) => write!(f, "decode error: {m}"),
            EngineError::Output(m) => write!(f, "audio output: {m}"),
        }
    }
}

impl std::error::Error for EngineError {}

impl Classify for EngineError {
    fn kind(&self) -> ErrorKind {
        match self {
            EngineError::Open(_) => ErrorKind::Transient,
            EngineError::Http(429) => ErrorKind::RateLimited,
            EngineError::Http(s) if *s >= 500 => ErrorKind::Transient,
            EngineError::Http(_) => ErrorKind::Permanent,
            EngineError::Unsupported(_) | EngineError::Decode(_) => ErrorKind::Permanent,
            // A device that vanished (headphones unplugged) may come back.
            EngineError::Output(_) => ErrorKind::Transient,
        }
    }
}

/// A running engine: one player thread, one device.
pub struct Engine {
    tx: Sender<Msg>,
    events: Mutex<Receiver<Event>>,
    thread: Option<JoinHandle<()>>,
}

impl Engine {
    /// Starts the player thread over `output`. The device is opened on the first `Load`, at that
    /// track's format, so a format the device takes as-is is never resampled.
    pub fn start<E: Env>(output: Box<dyn AudioOutput>) -> Result<Self, EngineError> {
        let (tx, rx) = mpsc::channel();
        let (events_tx, events_rx) = mpsc::channel();
        let player = Player::<E>::new(rx, tx.clone(), events_tx, output);
        let thread = std::thread::Builder::new()
            .name("rustify-player".into())
            .spawn(move || player.run())
            .map_err(|e| EngineError::Output(e.to_string()))?;
        Ok(Engine {
            tx,
            events: Mutex::new(events_rx),
            thread: Some(thread),
        })
    }

    /// Queues a command. Never blocks; answers `false` if the engine has stopped.
    pub fn send(&self, command: Command) -> bool {
        self.tx.send(Msg::Cmd(command)).is_ok()
    }

    /// The next event, waiting up to `timeout`. One caller at a time (the app's event loop).
    pub fn poll(&self, timeout: Duration) -> Option<Event> {
        let rx = self.events.lock().ok()?;
        // A timeout and a stopped engine both mean "no event"; the caller polls again or stops.
        rx.recv_timeout(timeout).ok()
    }
}

impl Drop for Engine {
    fn drop(&mut self) {
        let _ = self.tx.send(Msg::Cmd(Command::Shutdown));
        if let Some(thread) = self.thread.take() {
            let _ = thread.join();
        }
    }
}

#[cfg(test)]
mod tests;
