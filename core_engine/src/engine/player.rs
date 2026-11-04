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
//
// The effects (`effects.rs`) run here too, on each block before it reaches the ring: per track what
// depends on where in the song it is (level, fades, silence, pitch), then once over the mix the
// equaliser, the stereo tools and the time-stretch. With a crossfade set, the end of the current
// track is decoded alongside the start of the preloaded one and the two are mixed.

use std::collections::VecDeque;
use std::sync::mpsc::{Receiver, RecvTimeoutError, Sender};
use std::time::{Duration, Instant};

use crate::env::Env;

use super::decode::Decoder;
use super::effects::{
    self, Chain, Crossfader, EffectsConfig, Level, LevelConfig, Ramp, SilenceSkipper, TrackGains,
};
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
        level: Level,
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

/// The ramps in the device callback that keep a seek, a skip, a stop or an unfaded pause from
/// clicking (`ring::Transport::set_declick_samples`).
const DECLICK_MS: u64 = 5;

struct Track {
    token: Token,
    uri: String,
    decoder: Decoder,
    level: Level,
    /// `level`, as a linear gain that moves smoothly when the settings change.
    gain: Ramp,
    resampler: Option<Resampler>,
    /// Where the decoder is in the track's own time: `base_ms` plus `frames` at its rate.
    base_ms: u64,
    frames: u64,
    silence: SilenceSkipper,
}

impl Track {
    fn new(
        token: Token,
        uri: String,
        decoder: Decoder,
        level: Level,
        start_ms: u64,
        cfg: &LevelConfig,
    ) -> Self {
        Track {
            token,
            uri,
            decoder,
            gain: Ramp::at(level.linear(cfg)),
            level,
            resampler: None,
            base_ms: start_ms,
            frames: 0,
            silence: SilenceSkipper::default(),
        }
    }

    fn position_ms(&self) -> f64 {
        self.base_ms as f64
            + self.frames as f64 * 1000.0 / f64::from(self.decoder.sample_rate().max(1))
    }
}

/// Where in the ring a stretch of a track's audio starts, so what the device has consumed can be
/// turned back into "which track, and how far in". A track starts one; a skipped silence starts
/// another for the same track, since past it the samples no longer follow the clock.
#[derive(Debug, Clone)]
struct Segment {
    token: Token,
    uri: String,
    level: Level,
    start_sample: usize,
    base_ms: u64,
    /// Milliseconds of the track per millisecond heard: the playback speed when it was queued.
    speed: f64,
}

/// What the per-track stage needs from the settings.
#[derive(Debug, Clone, Copy)]
struct Shaping {
    pitch: f64,
    between_ms: u32,
    skip_silence: bool,
    level: LevelConfig,
}

/// One decoded block, converted to the device's format.
struct Produced {
    samples: Vec<f32>,
    /// Some of it was silence and was dropped: the clock jumped.
    skipped: bool,
}

/// The current track's end being mixed into the next one's start.
struct Crossfade {
    incoming: Track,
    mixer: Crossfader,
    /// The outgoing track's converted audio not mixed yet.
    outgoing: Vec<f32>,
    outgoing_done: bool,
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
    effects: EffectsConfig,
    /// What runs over the mix, at the device's format; built with the output.
    chain: Option<Chain>,
    xfade: Option<Crossfade>,
    last_position: Instant,
    // `fn() -> E` rather than `E`: the player is moved to its own thread, and `Env` does not ask
    // implementors to be `Send` (they are unit structs; this keeps it from mattering).
    _env: std::marker::PhantomData<fn() -> E>,
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
            effects: EffectsConfig::default(),
            chain: None,
            xfade: None,
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
            self.follow_output_format();
            self.pump();
            self.track_progress();
        }
        self.shutdown();
    }

    fn shutdown(&mut self) {
        self.current = None;
        self.next = None;
        self.xfade = None;
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
                replay_gain,
                album_run,
            }) => {
                self.clear_playing();
                self.loading = Some(token);
                self.wants_play = play;
                // Silent until the new track is ready; `opened` resumes it when asked to play.
                if let Some(p) = &self.producer {
                    p.transport().set_paused(true);
                }
                self.emit(Event::Loading { token });
                let level = Level {
                    gain_db,
                    replay_gain,
                    album_run,
                };
                self.open(token, uri, start_ms, level, Role::Current { play });
            }
            Msg::Cmd(Command::Preload {
                token,
                uri,
                gain_db,
                replay_gain,
                album_run,
            }) => {
                self.next = None;
                self.preloading = Some(token);
                let level = Level {
                    gain_db,
                    replay_gain,
                    album_run,
                };
                self.open(token, uri, 0, level, Role::Next);
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
            Msg::Cmd(Command::SetEffects { effects }) => self.set_effects(*effects),
            Msg::Cmd(Command::ReopenOutput) => self.output.reopen(),
            Msg::Cmd(Command::Shutdown) => return false,
            Msg::Opened {
                token,
                role,
                result,
                uri,
                level,
                start_ms,
            } => self.opened(token, role, result, uri, level, start_ms),
        }
        true
    }

    /// Drops what is playing and everything queued in the ring.
    fn clear_playing(&mut self) {
        self.current = None;
        self.xfade = None;
        self.end_at = None;
        self.segments.clear();
        self.pending.clear();
        self.pending_from = 0;
        if let Some(p) = &mut self.producer {
            p.flush();
        }
        if let Some(chain) = &mut self.chain {
            chain.reset();
        }
    }

    /// The settings changed. Everything but speed and pitch takes effect within
    /// [`effects::SMOOTH_MS`] from the next block; those two change how samples map to time, so what
    /// the ring holds is dropped and the track picked up again from where the listener is.
    fn set_effects(&mut self, effects: EffectsConfig) {
        let effects = effects.sanitized();
        let retime = effects.speed != self.effects.speed
            || effects.pitch_semitones != self.effects.pitch_semitones;
        self.fade_ms = effects.fades.pause_ms;
        self.effects = effects;
        if let Some(chain) = &mut self.chain {
            chain.configure(&self.effects);
        }
        self.apply_fade();
        if !retime || self.current.is_none() {
            return;
        }
        if let Some(heard) = self.format.and_then(|f| self.heard_ms(f)) {
            self.seek(heard);
        }
    }

    fn new_chain(&self, format: Format) -> Chain {
        let mut chain = Chain::new(format.sample_rate, format.channels);
        chain.configure(&self.effects);
        chain
    }

    fn speed(&self) -> f64 {
        f64::from(self.effects.speed)
    }

    fn shaping(&self) -> Shaping {
        Shaping {
            pitch: effects::pitch_factor(&self.effects),
            between_ms: self.effects.fades.between_songs_ms,
            skip_silence: self.effects.skip_silence,
            level: self.effects.level,
        }
    }

    /// The ring position the next converted sample will have: written, plus what waits to be.
    fn cursor(&self) -> usize {
        self.producer.as_ref().map_or(0, |p| p.written()) + (self.pending.len() - self.pending_from)
    }

    /// Where the listener is in the track they hear, by what the device has consumed.
    fn heard_ms(&self, format: Format) -> Option<u64> {
        let seg = self.segments.front()?;
        let played = self.producer.as_ref()?.played();
        Some(position_of(seg, played, format))
    }

    fn open(&self, token: Token, uri: String, start_ms: u64, level: Level, role: Role) {
        let tx = self.tx.clone();
        let spawned = std::thread::Builder::new()
            .name("rustify-audio-open".into())
            .spawn(move || {
                let mut level = level;
                if level.replay_gain.is_none() {
                    level.replay_gain = local_replay_gain(&uri);
                }
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
                    level,
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
        level: Level,
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
        let track = Track::new(token, uri, decoder, level, start_ms, &self.effects.level);
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
                let start = self.cursor();
                self.segments.clear();
                self.segments.push_back(Segment {
                    token,
                    uri: track.uri.clone(),
                    level,
                    start_sample: start,
                    base_ms: start_ms,
                    speed: self.speed(),
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
        self.chain = Some(self.new_chain(format));
        self.apply_fade();
        Ok(())
    }

    /// The output moved to a device at another rate (it reopens on its own when the device is lost
    /// or the default changes): convert to the new one from where the listener is. What the ring
    /// held was made for the old rate, so it is dropped and that place decoded again — the seek does
    /// both, and the resamplers rebuild themselves against the new format.
    fn follow_output_format(&mut self) {
        let (Some(now), Some(was)) = (self.output.current_format(), self.format) else {
            return;
        };
        if now == was {
            return;
        }
        let heard_ms = self.heard_ms(was).unwrap_or(0);
        self.format = Some(now);
        self.chain = Some(self.new_chain(now));
        self.apply_fade();
        if self.current.is_some() {
            self.seek(heard_ms);
        }
    }

    fn apply_fade(&self) {
        if let (Some(p), Some(f)) = (&self.producer, self.format) {
            let per_second = f.sample_rate as u64 * f.channels as u64;
            p.transport()
                .set_fade_samples((self.fade_ms as u64 * per_second / 1000) as u32);
            p.transport()
                .set_declick_samples((DECLICK_MS * per_second / 1000) as u32);
        }
    }

    fn seek(&mut self, position_ms: u64) {
        // A crossfade in progress ends here: whichever track the listener is in is the one sought.
        self.finish_crossfade();
        let heard = self.segments.front().cloned();
        let producing = self.current.as_ref().map(|t| t.token);
        match (heard, producing) {
            (Some(heard), Some(producing)) if heard.token != producing => {
                // The ring already holds the start of the next track (gapless), but the listener is
                // still in the previous one. Reopen that one where asked and queue this one again.
                let next_uri = self.current.as_ref().map(|t| t.uri.clone());
                let next_level = self.current.as_ref().map(|t| t.level).unwrap_or_default();
                self.clear_playing();
                self.loading = Some(heard.token);
                self.open(
                    heard.token,
                    heard.uri,
                    position_ms,
                    heard.level,
                    Role::Current {
                        play: self.wants_play,
                    },
                );
                if let Some(uri) = next_uri {
                    self.preloading = Some(producing);
                    self.open(producing, uri, 0, next_level, Role::Next);
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
                        track.base_ms = landed;
                        track.frames = 0;
                        track.silence.reset();
                        track.gain.settle();
                        let token = track.token;
                        let uri = track.uri.clone();
                        let level = track.level;
                        self.pending.clear();
                        self.pending_from = 0;
                        self.end_at = None;
                        if let Some(chain) = &mut self.chain {
                            chain.reset();
                        }
                        let start = match &mut self.producer {
                            Some(p) => {
                                p.flush();
                                p.written()
                            }
                            None => 0,
                        };
                        let speed = self.speed();
                        self.segments.clear();
                        self.segments.push_back(Segment {
                            token,
                            uri,
                            level,
                            start_sample: start,
                            base_ms: landed,
                            speed,
                        });
                        self.emit(Event::Position {
                            token,
                            position_ms: landed,
                        });
                    }
                    Err(e) => {
                        let token = track.token;
                        self.emit(Event::error(Some(token), &e));
                    }
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
            self.maybe_crossfade(format);
            let how = self.shaping();
            let producing = match &self.xfade {
                Some(x) => Some(x.incoming.token),
                None => self.current.as_ref().map(|t| t.token),
            };
            let step = match (self.current.as_mut(), self.xfade.as_mut()) {
                (Some(outgoing), Some(x)) => crossfade_step(outgoing, x, format, &how),
                (Some(track), None) => produce(track, format, &how),
                (None, _) => return,
            };
            match step {
                Ok(Some(block)) => {
                    self.pending = match &mut self.chain {
                        Some(chain) => chain.process(block.samples),
                        None => block.samples,
                    };
                    self.pending_from = 0;
                    if self.xfade.as_ref().is_some_and(|x| x.mixer.is_done()) {
                        self.finish_crossfade();
                    }
                    if block.skipped {
                        self.anchor();
                    }
                }
                // The incoming track ended inside the crossfade (it is shorter than one): it is the
                // current track now, and its end is met again next turn like any other's.
                Ok(None) if self.xfade.is_some() => self.finish_crossfade(),
                Ok(None) => {
                    if self.next.is_some() {
                        // Gapless: the next track's first sample follows, and the chain runs on.
                        self.hand_over();
                    } else {
                        // Nothing follows: what the time-stretch still holds is the last of it.
                        let mut tail = Vec::new();
                        if let Some(chain) = &mut self.chain {
                            chain.finish(&mut tail);
                        }
                        self.pending = tail;
                        self.pending_from = 0;
                        self.end_at = Some(self.cursor());
                    }
                }
                Err(e) => {
                    self.emit(Event::error(producing, &e));
                    self.finish_crossfade();
                    // Treat as the end of this track so the queue can move on.
                    self.end_at = Some(self.cursor());
                    self.hand_over();
                    return;
                }
            }
        }
    }

    /// Starts the crossfade when one is set, the next track is open and the current one is within
    /// that much of its end. Both lengths have to be known; otherwise the hand-over stays gapless.
    fn maybe_crossfade(&mut self, format: Format) {
        let wanted = f64::from(self.effects.fades.crossfade_ms);
        if wanted <= 0.0 || self.xfade.is_some() {
            return;
        }
        let (Some(current), Some(next)) = (self.current.as_ref(), self.next.as_ref()) else {
            return;
        };
        let (Some(out_ms), Some(in_ms)) = (current.decoder.duration_ms(), next.decoder.duration_ms())
        else {
            return;
        };
        // As with the fade between songs, never more than a third of either song.
        let length = wanted.min(out_ms as f64 / 3.0).min(in_ms as f64 / 3.0);
        let remaining = out_ms as f64 - current.position_ms();
        if remaining > length {
            return;
        }
        let Some(incoming) = self.next.take() else {
            return;
        };
        // In converted frames at the device's rate, which the pitch's resampling shortens.
        let frames = (remaining.max(0.0) / effects::pitch_factor(&self.effects)
            * f64::from(format.sample_rate)
            / 1000.0) as usize;
        // The listener is in the next track from the moment the crossfade starts.
        let segment = Segment {
            token: incoming.token,
            uri: incoming.uri.clone(),
            level: incoming.level,
            start_sample: self.cursor(),
            base_ms: 0,
            speed: self.speed(),
        };
        self.segments.push_back(segment);
        self.xfade = Some(Crossfade {
            incoming,
            mixer: Crossfader::new(frames, format.channels),
            outgoing: Vec::new(),
            outgoing_done: false,
        });
    }

    /// The crossfade is over, or has to be cut short: the incoming track is the current one.
    fn finish_crossfade(&mut self) {
        if let Some(x) = self.xfade.take() {
            self.current = Some(x.incoming);
        }
    }

    /// A silence was skipped: from here on the current track's clock restarts from where it now is.
    fn anchor(&mut self) {
        let Some(track) = self.current.as_ref() else {
            return;
        };
        let segment = Segment {
            token: track.token,
            uri: track.uri.clone(),
            level: track.level,
            start_sample: self.cursor(),
            base_ms: track.position_ms() as u64,
            speed: self.speed(),
        };
        // A long silence is dropped block after block with nothing kept in between: one anchor.
        let same_place = self.segments.back().is_some_and(|last| {
            last.token == segment.token && last.start_sample == segment.start_sample
        });
        if same_place {
            self.segments.pop_back();
        }
        self.segments.push_back(segment);
    }

    /// The current track has run out: if the next one is open, it starts in the very next sample.
    fn hand_over(&mut self) {
        let Some(next) = self.next.take() else {
            return;
        };
        let start = self.cursor();
        let speed = self.speed();
        self.segments.push_back(Segment {
            token: next.token,
            uri: next.uri.clone(),
            level: next.level,
            start_sample: start,
            base_ms: 0,
            speed,
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
                // A skipped silence starts a segment of the same track: no transition.
                if done.token != to {
                    self.emit(Event::Transitioned {
                        from: done.token,
                        to,
                    });
                }
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
                let position_ms = position_of(seg, played, format);
                self.emit(Event::Position {
                    token: seg.token,
                    position_ms,
                });
            }
        }
    }
}

/// The position in a segment's track once the device has consumed `played` samples.
fn position_of(seg: &Segment, played: usize, format: Format) -> u64 {
    let frames = played.saturating_sub(seg.start_sample) / format.channels.max(1);
    let rate = format.sample_rate.max(1);
    let heard_ms = if seg.speed == 1.0 {
        frames as u64 * 1000 / rate as u64
    } else {
        (frames as f64 * 1000.0 * seg.speed / f64::from(rate)) as u64
    };
    seg.base_ms + heard_ms
}

/// Decodes one block of `track` and runs it through the per-track stage: skipped silence, level and
/// fade in the track's own time, then the device's rate (and the pitch) and channel count.
fn produce(
    track: &mut Track,
    format: Format,
    how: &Shaping,
) -> Result<Option<Produced>, EngineError> {
    let Some(block) = track.decoder.next_block()? else {
        return Ok(None);
    };
    let rate = block.sample_rate.max(1);
    let channels = block.channels.max(1);
    let mut samples = block.samples;
    let frames = (samples.len() / channels) as u64;
    let dropped = if how.skip_silence {
        track.silence.process(&mut samples, channels, rate)
    } else {
        0
    };

    track
        .gain
        .set(track.level.linear(&how.level), effects::smooth_frames(rate));
    let fading = how.between_ms > 0;
    if fading || !track.gain.is_settled() || track.gain.value() != 1.0 {
        let start = track.position_ms();
        let ms_per_frame = 1000.0 / f64::from(rate);
        let duration = track.decoder.duration_ms().map(|d| d as f64);
        let fade = f64::from(how.between_ms);
        for (i, frame) in samples.chunks_exact_mut(channels).enumerate() {
            let mut g = track.gain.tick();
            if fading {
                g *= effects::transition_gain(start + i as f64 * ms_per_frame, duration, fade);
            }
            for x in frame.iter_mut() {
                *x *= g;
            }
        }
    }
    track.frames += frames;

    // The pitch: played as if it had been recorded at `rate × pitch`, which the resampler turns
    // into a higher (or lower) pitch and a shorter (or longer) stretch the time-stretch makes up.
    let in_rate = (f64::from(rate) * how.pitch).round().max(1.0) as u32;
    let stale = track
        .resampler
        .as_ref()
        .is_none_or(|r| !r_matches(r, in_rate, format.sample_rate, channels));
    if stale {
        track.resampler = None;
    }
    let resampler = track
        .resampler
        .get_or_insert_with(|| Resampler::new(in_rate, format.sample_rate, channels));
    let mut converted = Vec::with_capacity(samples.len() * 2);
    resampler.process(&samples, &mut converted);
    let mut mapped = Vec::with_capacity(converted.len() * 2);
    remix(&converted, channels, format.channels, &mut mapped);
    Ok(Some(Produced {
        samples: mapped,
        skipped: dropped > 0,
    }))
}

/// One step of a crossfade: a block of the incoming track, mixed with as much of the outgoing one.
/// `Ok(None)` when the incoming track has nothing more.
fn crossfade_step(
    outgoing: &mut Track,
    x: &mut Crossfade,
    format: Format,
    how: &Shaping,
) -> Result<Option<Produced>, EngineError> {
    // Neither skips silence while they overlap: their ends are what is being mixed.
    let plain = Shaping {
        skip_silence: false,
        ..*how
    };
    let Some(incoming) = produce(&mut x.incoming, format, &plain)? else {
        return Ok(None);
    };
    let mut mixed = incoming.samples;
    while !x.outgoing_done && x.outgoing.len() < mixed.len() {
        match produce(outgoing, format, &plain) {
            Ok(Some(block)) => x.outgoing.extend_from_slice(&block.samples),
            // Its end, or a failure there: the rest of the crossfade mixes in silence.
            Ok(None) | Err(_) => x.outgoing_done = true,
        }
    }
    let n = mixed.len().min(x.outgoing.len());
    x.mixer.mix(&x.outgoing[..n], &mut mixed);
    x.outgoing.drain(..n);
    Ok(Some(Produced {
        samples: mixed,
        skipped: false,
    }))
}

/// The ReplayGain a local file declares, read on the opener's thread. Streams have none; their level
/// is the caller's `gainDb`.
fn local_replay_gain(uri: &str) -> Option<TrackGains> {
    if uri.starts_with("http://") || uri.starts_with("https://") {
        return None;
    }
    let path = uri.strip_prefix("file://").unwrap_or(uri);
    let gains = TrackGains::from(crate::audio::replaygain::read(path).ok()?);
    (gains.track_gain_db.is_some() || gains.album_gain_db.is_some()).then_some(gains)
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
