// core_engine/src/engine/player.rs
//
// The player thread: decodes the current track, converts it to the device's format, keeps the ring
// topped up, and says what happened.
//
// It decides nothing about the queue. What plays next is `player::reduce`'s call, made by whoever
// drives this — the desktop app — exactly as Android's `PlayerQueue` drives ExoPlayer. This only
// knows "the current track" and, optionally, "the one after it", which is what gapless needs: the
// next track's first sample goes into the ring right after the current one's last, so the device
// never sees the seam.
//
// Opening a track is slow (a network request, a probe) and happens on a thread of its own, so a
// pause pressed while a song is loading is answered at once rather than after the CDN.

use std::collections::VecDeque;
use std::sync::mpsc::{Receiver, RecvTimeoutError, Sender};
use std::time::{Duration, Instant};

use crate::env::Env;

use super::decode::Decoder;
use super::output::{AudioOutput, Format};
use super::resample::{remix, Resampler};
use super::ring::{ring, Producer};
use super::{source, Command, EngineError, Event, Token};

/// Messages into the player thread: the caller's commands and the opener threads' answers.
pub(crate) enum Msg {
    Cmd(Command),
    Opened {
        token: Token,
        role: Role,
        result: Result<Decoder, EngineError>,
        uri: String,
        gain_db: f32,
        start_ms: u64,
    },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Role {
    /// Replaces whatever plays now.
    Current { play: bool },
    /// Queued behind the current track, for a gapless hand-over.
    Next,
}

/// How often a position is reported while playing. The UI's progress bar; not a clock anyone syncs to.
const POSITION_EVERY: Duration = Duration::from_millis(250);

/// How long to wait for a message when there is nothing to decode.
const IDLE_WAIT: Duration = Duration::from_millis(20);

/// Ring size in seconds of audio. Small enough that a flush (seek, skip) is not a long silence,
/// large enough to ride out a decode hiccup. Pause and volume do not wait on it: they act in the
/// device callback (`ring::Consumer::fill`).
const RING_SECONDS: f64 = 0.5;

struct Track {
    token: Token,
    uri: String,
    decoder: Decoder,
    gain: f32,
    gain_db: f32,
    resampler: Option<Resampler>,
}

/// Where in the ring a track's audio starts, so what the device has consumed can be turned back into
/// "which track, and how far in".
#[derive(Debug, Clone)]
struct Segment {
    token: Token,
    uri: String,
    gain_db: f32,
    start_sample: usize,
    base_ms: u64,
}

pub(crate) struct Player<E: Env> {
    rx: Receiver<Msg>,
    tx: Sender<Msg>,
    events: Sender<Event>,
    output: Box<dyn AudioOutput>,
    producer: Option<Producer>,
    format: Option<Format>,
    current: Option<Track>,
    next: Option<Track>,
    /// The current track ran out and nothing followed: the sample count at which the device has
    /// heard all of it.
    end_at: Option<usize>,
    segments: VecDeque<Segment>,
    /// Converted samples waiting for room in the ring.
    pending: Vec<f32>,
    pending_from: usize,
    /// The most recent `Load`; an opener answering for any other token is stale and ignored.
    loading: Option<Token>,
    /// The most recent `Preload`, likewise.
    preloading: Option<Token>,
    wants_play: bool,
    volume: f32,
    fade_ms: u32,
    last_position: Instant,
    _env: std::marker::PhantomData<E>,
}

impl<E: Env> Player<E> {
    pub(crate) fn new(
        rx: Receiver<Msg>,
        tx: Sender<Msg>,
        events: Sender<Event>,
        output: Box<dyn AudioOutput>,
    ) -> Self {
        Player {
            rx,
            tx,
            events,
            output,
            producer: None,
            format: None,
            current: None,
            next: None,
            end_at: None,
            segments: VecDeque::new(),
            pending: Vec::new(),
            pending_from: 0,
            loading: None,
            preloading: None,
            wants_play: false,
            volume: 1.0,
            fade_ms: 0,
            last_position: Instant::now(),
            _env: std::marker::PhantomData,
        }
    }

    pub(crate) fn run(mut self) {
        loop {
            let wait = if self.has_work() {
                Duration::ZERO
            } else {
                IDLE_WAIT
            };
            match self.rx.recv_timeout(wait) {
                Ok(msg) => {
                    if !self.handle(msg) {
                        break;
                    }
                    // Drain whatever else is queued before decoding: commands come first.
                    while let Ok(msg) = self.rx.try_recv() {
                        if !self.handle(msg) {
                            self.shutdown();
                            return;
                        }
                    }
                }
                Err(RecvTimeoutError::Timeout) => {}
                Err(RecvTimeoutError::Disconnected) => break,
            }
            self.pump();
            self.track_progress();
        }
        self.shutdown();
    }

    fn shutdown(&mut self) {
        self.current = None;
        self.next = None;
        self.output.stop();
    }

    fn has_work(&self) -> bool {
        let room = self.producer.as_ref().is_some_and(|p| p.free() > 0);
        room && self.current.is_some() && self.end_at.is_none()
    }

    fn emit(&self, event: Event) {
        let _ = self.events.send(event);
    }

    /// Answers `false` when the thread should end.
    fn handle(&mut self, msg: Msg) -> bool {
        match msg {
            Msg::Cmd(Command::Load {
                token,
                uri,
                start_ms,
                gain_db,
                play,
            }) => {
                self.clear_playing();
                self.loading = Some(token);
                self.wants_play = play;
                // Silent until the new track is ready; `opened` resumes it when asked to play.
                if let Some(p) = &self.producer {
                    p.transport().set_paused(true);
                }
                self.emit(Event::Loading { token });
                self.open(token, uri, start_ms, gain_db, Role::Current { play });
            }
            Msg::Cmd(Command::Preload {
                token,
                uri,
                gain_db,
            }) => {
                self.next = None;
                self.preloading = Some(token);
                self.open(token, uri, 0, gain_db, Role::Next);
            }
            Msg::Cmd(Command::ClearNext) => {
                self.next = None;
                self.preloading = None;
            }
            Msg::Cmd(Command::Play) => {
                self.wants_play = true;
                if let Some(p) = &self.producer {
                    p.transport().set_paused(false);
                }
                self.emit(Event::State { playing: true });
            }
            Msg::Cmd(Command::Pause) => {
                self.wants_play = false;
                if let Some(p) = &self.producer {
                    p.transport().set_paused(true);
                }
                self.emit(Event::State { playing: false });
            }
            Msg::Cmd(Command::Seek { position_ms }) => self.seek(position_ms),
            Msg::Cmd(Command::Stop) => {
                self.clear_playing();
                self.loading = None;
                self.next = None;
                self.preloading = None;
                self.wants_play = false;
                if let Some(p) = &self.producer {
                    p.transport().set_paused(true);
                }
                self.emit(Event::State { playing: false });
            }
            Msg::Cmd(Command::SetVolume { volume }) => {
                self.volume = volume;
                if let Some(p) = &self.producer {
                    p.transport().set_volume(volume);
                }
            }
            Msg::Cmd(Command::SetFadeMs { ms }) => {
                self.fade_ms = ms;
                self.apply_fade();
            }
            Msg::Cmd(Command::Shutdown) => return false,
            Msg::Opened {
                token,
                role,
                result,
                uri,
                gain_db,
                start_ms,
            } => self.opened(token, role, result, uri, gain_db, start_ms),
        }
        true
    }

    /// Drops what is playing and everything queued in the ring.
    fn clear_playing(&mut self) {
        self.current = None;
        self.end_at = None;
        self.segments.clear();
        self.pending.clear();
        self.pending_from = 0;
        if let Some(p) = &mut self.producer {
            p.flush();
        }
    }

    fn open(&self, token: Token, uri: String, start_ms: u64, gain_db: f32, role: Role) {
        let tx = self.tx.clone();
        let spawned = std::thread::Builder::new()
            .name("rustify-audio-open".into())
            .spawn(move || {
                let result = source::open::<E>(&uri).and_then(|(source, hint)| {
                    let mut decoder = Decoder::open(source, hint.as_deref())?;
                    if start_ms > 0 {
                        decoder.seek(start_ms)?;
                    }
                    Ok(decoder)
                });
                let _ = tx.send(Msg::Opened {
                    token,
                    role,
                    result,
                    uri,
                    gain_db,
                    start_ms,
                });
            });
        if let Err(e) = spawned {
            self.emit(Event::error(Some(token), &EngineError::Open(e.to_string())));
        }
    }

    fn opened(
        &mut self,
        token: Token,
        role: Role,
        result: Result<Decoder, EngineError>,
        uri: String,
        gain_db: f32,
        start_ms: u64,
    ) {
        let expected = match role {
            Role::Current { .. } => self.loading,
            Role::Next => self.preloading,
        };
        if expected != Some(token) {
            return; // Superseded while it was opening.
        }
        let decoder = match result {
            Ok(decoder) => decoder,
            Err(e) => {
                match role {
                    Role::Current { .. } => self.loading = None,
                    Role::Next => self.preloading = None,
                }
                self.emit(Event::error(Some(token), &e));
                return;
            }
        };
        let duration_ms = decoder.duration_ms();
        let track = Track {
            token,
            uri,
            decoder,
            gain: db_to_linear(gain_db),
            gain_db,
            resampler: None,
        };
        match role {
            Role::Current { play } => {
                self.loading = None;
                if let Err(e) = self.ensure_output(Format {
                    sample_rate: track.decoder.sample_rate(),
                    channels: track.decoder.channels(),
                }) {
                    self.emit(Event::error(Some(token), &e));
                    return;
                }
                let start = self.producer.as_ref().map(|p| p.written()).unwrap_or(0);
                self.segments.clear();
                self.segments.push_back(Segment {
                    token,
                    uri: track.uri.clone(),
                    gain_db,
                    start_sample: start,
                    base_ms: start_ms,
                });
                self.current = Some(track);
                self.end_at = None;
                self.emit(Event::Ready { token, duration_ms });
                if play {
                    if let Some(p) = &self.producer {
                        p.transport().set_paused(false);
                    }
                    self.emit(Event::State { playing: true });
                }
            }
            Role::Next => {
                self.preloading = None;
                self.next = Some(track);
                self.emit(Event::Preloaded { token, duration_ms });
                // The current track may already have run out while this was opening.
                if self.end_at.is_some() {
                    self.hand_over();
                }
            }
        }
    }

    fn ensure_output(&mut self, wanted: Format) -> Result<(), EngineError> {
        if self.producer.is_some() {
            return Ok(());
        }
        let capacity = ((wanted.sample_rate.max(44_100) as f64)
            * wanted.channels.max(2) as f64
            * RING_SECONDS) as usize;
        let (producer, consumer) = ring(capacity);
        producer.transport().set_paused(!self.wants_play);
        producer.transport().set_volume(self.volume);
        let format = self.output.start(wanted, consumer)?;
        self.producer = Some(producer);
        self.format = Some(format);
        self.apply_fade();
        Ok(())
    }

    fn apply_fade(&self) {
        if let (Some(p), Some(f)) = (&self.producer, self.format) {
            let samples =
                (self.fade_ms as u64 * f.sample_rate as u64 * f.channels as u64 / 1000) as u32;
            p.transport().set_fade_samples(samples);
        }
    }

    fn seek(&mut self, position_ms: u64) {
        let heard = self.segments.front().cloned();
        let producing = self.current.as_ref().map(|t| t.token);
        match (heard, producing) {
            (Some(heard), Some(producing)) if heard.token != producing => {
                // The ring already holds the start of the next track (gapless), but the listener is
                // still in the previous one. Reopen that one where asked and queue this one again.
                let next_uri = self.current.as_ref().map(|t| t.uri.clone());
                let next_gain = self.current.as_ref().map(|t| t.gain_db).unwrap_or(0.0);
                self.clear_playing();
                self.loading = Some(heard.token);
                self.open(
                    heard.token,
                    heard.uri,
                    position_ms,
                    heard.gain_db,
                    Role::Current {
                        play: self.wants_play,
                    },
                );
                if let Some(uri) = next_uri {
                    self.preloading = Some(producing);
                    self.open(producing, uri, 0, next_gain, Role::Next);
                }
            }
            _ => {
                let Some(track) = self.current.as_mut() else {
                    return;
                };
                match track.decoder.seek(position_ms) {
                    Ok(landed) => {
                        if let Some(r) = track.resampler.as_mut() {
                            r.reset();
                        }
                        let token = track.token;
                        let uri = track.uri.clone();
                        let gain_db = track.gain_db;
                        self.pending.clear();
                        self.pending_from = 0;
                        self.end_at = None;
                        let start = match &mut self.producer {
                            Some(p) => {
                                p.flush();
                                p.written()
                            }
                            None => 0,
                        };
                        self.segments.clear();
                        self.segments.push_back(Segment {
                            token,
                            uri,
                            gain_db,
                            start_sample: start,
                            base_ms: landed,
                        });
                        self.emit(Event::Position {
                            token,
                            position_ms: landed,
                        });
                    }
                    Err(e) => self.emit(Event::error(Some(track.token), &e)),
                }
            }
        }
    }

    /// Decodes and pushes until the ring is full or the track runs out.
    fn pump(&mut self) {
        let Some(format) = self.format else {
            return;
        };
        loop {
            let Some(producer) = self.producer.as_mut() else {
                return;
            };
            if self.pending_from < self.pending.len() {
                let n = producer.push(&self.pending[self.pending_from..]);
                self.pending_from += n;
                if self.pending_from < self.pending.len() {
                    return; // Ring full; come back next turn.
                }
                self.pending.clear();
                self.pending_from = 0;
            }
            if self.end_at.is_some() {
                return;
            }
            let Some(track) = self.current.as_mut() else {
                return;
            };
            match track.decoder.next_block() {
                Ok(Some(block)) => {
                    let stale = track.resampler.as_ref().is_none_or(|r| {
                        !r_matches(r, block.sample_rate, format.sample_rate, block.channels)
                    });
                    if stale {
                        track.resampler = Some(Resampler::new(
                            block.sample_rate,
                            format.sample_rate,
                            block.channels,
                        ));
                    }
                    let Some(resampler) = track.resampler.as_mut() else {
                        return;
                    };
                    let mut converted = Vec::with_capacity(block.samples.len() * 2);
                    resampler.process(&block.samples, &mut converted);
                    let mut mapped = Vec::with_capacity(converted.len());
                    remix(&converted, block.channels, format.channels, &mut mapped);
                    if track.gain != 1.0 {
                        let g = track.gain;
                        mapped.iter_mut().for_each(|x| *x *= g);
                    }
                    self.pending = mapped;
                    self.pending_from = 0;
                }
                Ok(None) => {
                    self.end_at = Some(producer.written());
                    self.hand_over();
                    if self.end_at.is_some() {
                        return;
                    }
                }
                Err(e) => {
                    let token = track.token;
                    let written = producer.written();
                    self.emit(Event::error(Some(token), &e));
                    // Treat as the end of this track so the queue can move on.
                    self.end_at = Some(written);
                    self.hand_over();
                    return;
                }
            }
        }
    }

    /// The current track has run out: if the next one is open, it starts in the very next sample.
    fn hand_over(&mut self) {
        let Some(next) = self.next.take() else {
            return;
        };
        let start = self
            .producer
            .as_ref()
            .map(|p| p.written())
            .unwrap_or(0);
        self.segments.push_back(Segment {
            token: next.token,
            uri: next.uri.clone(),
            gain_db: next.gain_db,
            start_sample: start,
            base_ms: 0,
        });
        self.current = Some(next);
        self.end_at = None;
    }

    /// Turns what the device has consumed into events: transitions, the end, and the position.
    fn track_progress(&mut self) {
        let Some(producer) = self.producer.as_ref() else {
            return;
        };
        if producer.flush_pending() {
            return; // The device has not caught up with a seek or a skip yet.
        }
        let played = producer.played();
        while self.segments.len() > 1 && played >= self.segments[1].start_sample {
            if let Some(done) = self.segments.pop_front() {
                let to = self.segments[0].token;
                self.emit(Event::Transitioned {
                    from: done.token,
                    to,
                });
            }
        }
        if let Some(end) = self.end_at {
            if played >= end && self.next.is_none() && self.preloading.is_none() {
                if let Some(track) = self.current.take() {
                    self.emit(Event::Ended { token: track.token });
                }
                self.segments.clear();
                self.end_at = None;
                return;
            }
        }
        if self.last_position.elapsed() >= POSITION_EVERY {
            self.last_position = Instant::now();
            if let (Some(seg), Some(format)) = (self.segments.front(), self.format) {
                let frames = played.saturating_sub(seg.start_sample) / format.channels.max(1);
                let position_ms = seg.base_ms + frames as u64 * 1000 / format.sample_rate.max(1) as u64;
                self.emit(Event::Position {
                    token: seg.token,
                    position_ms,
                });
            }
        }
    }
}

fn r_matches(r: &Resampler, in_rate: u32, out_rate: u32, channels: usize) -> bool {
    r.rates() == (in_rate, out_rate) && r.channels() == channels
}

pub(crate) fn db_to_linear(db: f32) -> f32 {
    if db == 0.0 {
        1.0
    } else {
        10f32.powf(db / 20.0)
    }
}
