// core_engine/src/engine/effects.rs
//
// What Android does with Media3's processors and `audiofx`, done here on the samples the engine
// already has: interleaved `f32`, at the device's rate once they leave the resampler.
//
// Per track, in the track's own time (so a fade knows where in the song it is):
//
//   decode → skip silence → level (ReplayGain or normalisation) × fade between songs
//          → resample (also the pitch) → remix → [crossfade with the next track]
//
// Then once, over whatever the device is about to hear:
//
//   equaliser + AutoEq (one biquad cascade) → stereo tools → time-stretch (speed) → ring
//
// The pause fade and the anti-click ramps live in the ring (`ring::Consumer::fill`), because they
// have to act on what is already queued, not on what is decoded next.
//
// Nothing here allocates per sample, and nothing is recomputed per sample that only changes when
// the settings do: coefficients are designed on `Chain::configure`, and only if the curve changed.
// What does change with the settings is ramped over [`SMOOTH_MS`], so moving a slider is not a click.

use std::f64::consts::{FRAC_PI_2, PI};

use serde::{Deserialize, Serialize};

use crate::autoeq;

/// How long a change of gain, filter or stereo setting takes to arrive.
pub const SMOOTH_MS: u32 = 20;

/// The Q of the desktop's standard five bands. Android's bands are the device's own; the reference
/// implementation's (AOSP's bundled equaliser) are this wide.
pub const DEFAULT_Q: f32 = 0.96;

// =============================================================================
// CONFIGURATION — what crosses the bridge, `{"type":"setEffects","effects":{…}}`
// =============================================================================

/// Every effect, as one document. The caller sends all of it on any change; the engine works out
/// what actually changed and leaves the rest alone.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct EffectsConfig {
    pub eq: EqConfig,
    pub auto_eq: AutoEqConfig,
    pub level: LevelConfig,
    pub stereo: StereoConfig,
    pub fades: FadeConfig,
    /// Playback rate without a change of pitch. Android's slider goes 0.5 to 2; the engine takes
    /// 0.25 to 4.
    pub speed: f32,
    /// Pitch in semitones, independent of the speed. Android's goes -6 to 6; the engine takes ±12.
    pub pitch_semitones: f32,
    /// Android's "skip silence": long silent stretches are cut short (see [`SilenceSkipper`]).
    pub skip_silence: bool,
}

impl Default for EffectsConfig {
    fn default() -> Self {
        EffectsConfig {
            eq: EqConfig::default(),
            auto_eq: AutoEqConfig::default(),
            level: LevelConfig::default(),
            stereo: StereoConfig::default(),
            fades: FadeConfig::default(),
            speed: 1.0,
            pitch_semitones: 0.0,
            skip_silence: false,
        }
    }
}

impl EffectsConfig {
    /// The same settings with every number inside what the engine can do: a NaN from a slider or a
    /// band far past Nyquist must not reach a filter.
    pub fn sanitized(mut self) -> Self {
        self.speed = finite_or(self.speed, 1.0).clamp(0.25, 4.0);
        self.pitch_semitones = finite_or(self.pitch_semitones, 0.0).clamp(-12.0, 12.0);
        self.eq.preamp_db = finite_or(self.eq.preamp_db, 0.0).clamp(-24.0, 24.0);
        for band in &mut self.eq.bands {
            band.freq_hz = finite_or(band.freq_hz, 1000.0).clamp(10.0, 40_000.0);
            band.gain_db = finite_or(band.gain_db, 0.0).clamp(-24.0, 24.0);
            band.q = finite_or(band.q, DEFAULT_Q).clamp(0.05, 50.0);
        }
        for band in &mut self.auto_eq.bands {
            band.cutoff_hz = finite_or(band.cutoff_hz, 1000.0).clamp(10.0, 40_000.0);
            band.gain_db = finite_or(band.gain_db, 0.0).clamp(-24.0, 24.0);
        }
        self.level.preamp_db = finite_or(self.level.preamp_db, 4.0).clamp(-24.0, 24.0);
        self.stereo.balance = self.stereo.balance.clamp(-100, 100);
        self.stereo.width = finite_or(self.stereo.width, 1.0).clamp(0.0, 2.0);
        self.fades.pause_ms = self.fades.pause_ms.min(5_000);
        self.fades.between_songs_ms = self.fades.between_songs_ms.min(12_000);
        self.fades.crossfade_ms = self.fades.crossfade_ms.min(12_000);
        self
    }
}

fn finite_or(x: f32, default: f32) -> f32 {
    if x.is_finite() {
        x
    } else {
        default
    }
}

/// The user's equaliser: any number of bands, each a peaking or shelving biquad, and a pre-amp.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct EqConfig {
    pub enabled: bool,
    pub preamp_db: f32,
    pub bands: Vec<EqBand>,
}

#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct EqBand {
    pub kind: FilterKind,
    /// The centre of a peak, the corner of a shelf.
    pub freq_hz: f32,
    pub gain_db: f32,
    pub q: f32,
}

impl Default for EqBand {
    fn default() -> Self {
        EqBand {
            kind: FilterKind::Peak,
            freq_hz: 1000.0,
            gain_db: 0.0,
            q: DEFAULT_Q,
        }
    }
}

/// The three RBJ cookbook shapes AutoEq uses too. `lowshelf`/`highshelf` (how `autoeq::Kind`
/// spells them) are read as well.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum FilterKind {
    #[default]
    Peak,
    #[serde(alias = "lowshelf")]
    LowShelf,
    #[serde(alias = "highshelf")]
    HighShelf,
}

impl From<autoeq::Kind> for FilterKind {
    fn from(kind: autoeq::Kind) -> Self {
        match kind {
            autoeq::Kind::Peak => FilterKind::Peak,
            autoeq::Kind::LowShelf => FilterKind::LowShelf,
            autoeq::Kind::HighShelf => FilterKind::HighShelf,
        }
    }
}

/// The headphone correction. `peq` is AutoEq's own `ParametricEQ.txt` (what Android keeps per device
/// in `autoeq_profiles`), applied filter by filter at the device's rate. When it is empty, `bands` is
/// used instead: exactly what the core's `autoEqBands` answers, a gain per band given by its upper
/// edge — the shape Android hands `DynamicsProcessing`.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct AutoEqConfig {
    pub enabled: bool,
    pub peq: String,
    pub bands: Vec<GraphicBand>,
}

/// One band of `autoeq::bands`: `{"cutoffHz":…,"gainDb":…}`.
#[derive(Debug, Clone, Copy, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct GraphicBand {
    pub cutoff_hz: f32,
    pub gain_db: f32,
}

/// How loud each track plays: Android's `normalize_volume` and `replaygain_*` settings.
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct LevelConfig {
    /// Apply the caller's `gainDb` (YouTube's loudness). Off plays every stream as it comes.
    pub normalize: bool,
    pub replay_gain: ReplayGainMode,
    /// Added to the ReplayGain, as Android's `replaygain_preamp_db` (4 dB by default: ReplayGain aims
    /// at -18 LUFS and YouTube at about -14).
    pub preamp_db: f32,
}

impl Default for LevelConfig {
    fn default() -> Self {
        LevelConfig {
            normalize: true,
            replay_gain: ReplayGainMode::Auto,
            preamp_db: 4.0,
        }
    }
}

/// Android's `LocalReplayGain.Mode`. `auto` is album gain while the queue runs through an album
/// (the caller says so with `albumRun` on the load) and track gain otherwise.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ReplayGainMode {
    Off,
    Track,
    Album,
    #[default]
    Auto,
}

/// Android's stereo tools (`StereoTools`), plus a width it does not have.
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct StereoConfig {
    /// -100 (left only) … 0 … 100 (right only): the side turned away from goes down.
    pub balance: i32,
    pub mono: bool,
    /// A little of each channel, low-passed, in the other ear (Bauer/bs2b, 700 Hz, -4.5 dB).
    pub crossfeed: bool,
    /// The centre cancelled and the bass kept, for karaoke.
    pub voice_reduction: bool,
    /// Mid/side: 0 is mono, 1 as recorded, 2 twice as wide.
    pub width: f32,
}

impl Default for StereoConfig {
    fn default() -> Self {
        StereoConfig {
            balance: 0,
            mono: false,
            crossfeed: false,
            voice_reduction: false,
            width: 1.0,
        }
    }
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct FadeConfig {
    /// The fade on pause and resume (Android's `PauseFade`, 300 ms when on). The same thing
    /// `setFadeMs` sets; whichever arrives last wins.
    pub pause_ms: u32,
    /// Android's "fade between songs" (`TransitionFade`): each song fades in at its start and out at
    /// its end, each fade at most a third of the song. Not an overlap.
    pub between_songs_ms: u32,
    /// A true crossfade, which Android cannot do with one player: the end of a song is mixed with the
    /// start of the next one already preloaded. 0 keeps the plain gapless hand-over.
    pub crossfade_ms: u32,
}

/// The ReplayGain a file declares (`audio::replaygain`), as the caller or the engine read it.
#[derive(Debug, Clone, Copy, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct TrackGains {
    pub track_gain_db: Option<f64>,
    pub album_gain_db: Option<f64>,
    pub track_peak: Option<f64>,
    pub album_peak: Option<f64>,
}

impl From<crate::audio::replaygain::ReplayGain> for TrackGains {
    fn from(rg: crate::audio::replaygain::ReplayGain) -> Self {
        TrackGains {
            track_gain_db: rg.track_gain_db,
            album_gain_db: rg.album_gain_db,
            track_peak: rg.track_peak,
            album_peak: rg.album_peak,
        }
    }
}

// =============================================================================
// LEVEL — one gain per track
// =============================================================================

/// What decides one track's level: the caller's normalisation and the file's own ReplayGain.
#[derive(Debug, Clone, Copy, Default, PartialEq)]
pub struct Level {
    pub gain_db: f32,
    pub replay_gain: Option<TrackGains>,
    pub album_run: bool,
}

impl Level {
    /// Android's rule: a file's ReplayGain where it has one and it is on, the caller's
    /// normalisation otherwise.
    pub fn linear(&self, cfg: &LevelConfig) -> f32 {
        if let Some(v) = replay_gain_volume(
            self.replay_gain.as_ref(),
            cfg.replay_gain,
            cfg.preamp_db,
            self.album_run,
        ) {
            return v;
        }
        if cfg.normalize {
            super::player::db_to_linear(self.gain_db)
        } else {
            1.0
        }
    }
}

/// `LocalReplayGain.volumeFor`: the album gain in album mode and the track gain otherwise, each
/// falling back to the other, plus the pre-amp; capped by the peak (the clip guard) and at 1, floored
/// at 0.1 so a bad tag cannot make a song inaudible. `None` when there is nothing to apply.
pub fn replay_gain_volume(
    tags: Option<&TrackGains>,
    mode: ReplayGainMode,
    preamp_db: f32,
    album_run: bool,
) -> Option<f32> {
    let tags = tags?;
    if mode == ReplayGainMode::Off {
        return None;
    }
    let album = mode == ReplayGainMode::Album || (mode == ReplayGainMode::Auto && album_run);
    let (gain, peak) = if album {
        (
            tags.album_gain_db.or(tags.track_gain_db),
            tags.album_peak.or(tags.track_peak),
        )
    } else {
        (
            tags.track_gain_db.or(tags.album_gain_db),
            tags.track_peak.or(tags.album_peak),
        )
    };
    let mut linear = 10f64.powf((gain? + f64::from(preamp_db)) / 20.0);
    if let Some(p) = peak.filter(|p| *p > 0.0) {
        linear = linear.min(1.0 / p);
    }
    Some((linear as f32).clamp(0.1, 1.0))
}

/// `TransitionFade.gain`: the multiplier at `position_ms` of a song `duration_ms` long, fading over
/// `fade_ms` at each end. A song shorter than two fades gets fades of a third of its length, so it is
/// never silent in the middle. Unknown length: fade in only.
pub fn transition_gain(position_ms: f64, duration_ms: Option<f64>, fade_ms: f64) -> f32 {
    if fade_ms <= 0.0 {
        return 1.0;
    }
    let duration = duration_ms.filter(|d| *d > 0.0);
    let fade = match duration {
        Some(d) => fade_ms.min(d / 3.0),
        None => fade_ms,
    };
    if fade <= 0.0 {
        return 1.0;
    }
    let fade_in = (position_ms / fade).clamp(0.0, 1.0);
    let fade_out = duration.map_or(1.0, |d| ((d - position_ms) / fade).clamp(0.0, 1.0));
    fade_in.min(fade_out) as f32
}

/// A value that moves to its target over a number of frames instead of jumping.
#[derive(Debug, Clone, Copy)]
pub struct Ramp {
    value: f32,
    target: f32,
    step: f32,
}

impl Ramp {
    pub fn at(value: f32) -> Self {
        Ramp {
            value,
            target: value,
            step: 0.0,
        }
    }

    /// Heads for `target`, arriving in `frames`.
    pub fn set(&mut self, target: f32, frames: usize) {
        if target == self.target {
            return;
        }
        self.target = target;
        self.step = (target - self.value).abs() / frames.max(1) as f32;
    }

    /// One frame on; the value for that frame.
    #[inline]
    pub fn tick(&mut self) -> f32 {
        if self.value < self.target {
            self.value = (self.value + self.step).min(self.target);
        } else if self.value > self.target {
            self.value = (self.value - self.step).max(self.target);
        }
        self.value
    }

    pub fn value(&self) -> f32 {
        self.value
    }

    pub fn is_settled(&self) -> bool {
        self.value == self.target
    }

    /// Jumps to the target: after a flush there is nothing to be smooth with.
    pub fn settle(&mut self) {
        self.value = self.target;
    }
}

/// [`SMOOTH_MS`] in frames at `rate`.
pub fn smooth_frames(rate: u32) -> usize {
    (rate as usize * SMOOTH_MS as usize / 1000).max(1)
}

// =============================================================================
// BIQUADS — the equaliser and AutoEq
// =============================================================================

/// A biquad's coefficients, normalised by `a0`.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Coeffs {
    b0: f64,
    b1: f64,
    b2: f64,
    a1: f64,
    a2: f64,
}

impl Coeffs {
    /// The filter for one band, or `None` when it would do nothing (0 dB is the identity for all
    /// three shapes, so it is left out of the cascade rather than computed).
    pub fn design(kind: FilterKind, freq_hz: f64, gain_db: f64, q: f64, rate: f64) -> Option<Coeffs> {
        if gain_db.abs() < 0.01 || !freq_hz.is_finite() || !gain_db.is_finite() || rate <= 0.0 {
            return None;
        }
        Some(Coeffs::rbj(kind, freq_hz, gain_db, q, rate))
    }

    /// The RBJ Audio-EQ-Cookbook formulas, as `autoeq.rs` evaluates them, at `rate`. The frequency
    /// is kept below Nyquist, where a band meant for 48 kHz lands on a 44.1 kHz device.
    pub fn rbj(kind: FilterKind, freq_hz: f64, gain_db: f64, q: f64, rate: f64) -> Coeffs {
        let fc = freq_hz.clamp(10.0, rate * 0.45);
        let q = q.clamp(0.05, 50.0);
        let a = 10f64.powf(gain_db / 40.0);
        let w0 = 2.0 * PI * fc / rate;
        let (sin, cos) = w0.sin_cos();
        let alpha = sin / (2.0 * q);
        let sa = 2.0 * a.sqrt() * alpha;
        let [b0, b1, b2, a0, a1, a2] = match kind {
            FilterKind::Peak => [
                1.0 + alpha * a,
                -2.0 * cos,
                1.0 - alpha * a,
                1.0 + alpha / a,
                -2.0 * cos,
                1.0 - alpha / a,
            ],
            FilterKind::LowShelf => [
                a * ((a + 1.0) - (a - 1.0) * cos + sa),
                2.0 * a * ((a - 1.0) - (a + 1.0) * cos),
                a * ((a + 1.0) - (a - 1.0) * cos - sa),
                (a + 1.0) + (a - 1.0) * cos + sa,
                -2.0 * ((a - 1.0) + (a + 1.0) * cos),
                (a + 1.0) + (a - 1.0) * cos - sa,
            ],
            FilterKind::HighShelf => [
                a * ((a + 1.0) + (a - 1.0) * cos + sa),
                -2.0 * a * ((a - 1.0) + (a + 1.0) * cos),
                a * ((a + 1.0) + (a - 1.0) * cos - sa),
                (a + 1.0) - (a - 1.0) * cos + sa,
                2.0 * ((a - 1.0) - (a + 1.0) * cos),
                (a + 1.0) - (a - 1.0) * cos - sa,
            ],
        };
        Coeffs {
            b0: b0 / a0,
            b1: b1 / a0,
            b2: b2 / a0,
            a1: a1 / a0,
            a2: a2 / a0,
        }
    }

    /// The magnitude response at `hz`, in dB: |b(z)| / |a(z)| at z = e^{jw}.
    pub fn response_db(&self, hz: f64, rate: f64) -> f64 {
        let w = 2.0 * PI * hz / rate;
        let (s1, c1) = w.sin_cos();
        let (s2, c2) = (2.0 * w).sin_cos();
        let num_re = self.b0 + self.b1 * c1 + self.b2 * c2;
        let num_im = -(self.b1 * s1 + self.b2 * s2);
        let den_re = 1.0 + self.a1 * c1 + self.a2 * c2;
        let den_im = -(self.a1 * s1 + self.a2 * s2);
        10.0 * ((num_re * num_re + num_im * num_im) / (den_re * den_re + den_im * den_im)).log10()
    }
}

/// One biquad's memory for one channel (direct form I: past inputs and outputs). Being signals and
/// not coefficient-dependent, it can be handed to a redesigned filter without a jump.
#[derive(Debug, Clone, Copy, Default)]
pub struct BiquadState {
    x1: f64,
    x2: f64,
    y1: f64,
    y2: f64,
}

impl BiquadState {
    #[inline]
    pub fn run(&mut self, c: &Coeffs, x: f64) -> f64 {
        let y = c.b0 * x + c.b1 * self.x1 + c.b2 * self.x2 - c.a1 * self.y1 - c.a2 * self.y2;
        // A tail decaying into denormals is slow on x86 and inaudible everywhere.
        let y = if y.abs() < 1e-25 { 0.0 } else { y };
        self.x2 = self.x1;
        self.x1 = x;
        self.y2 = self.y1;
        self.y1 = y;
        y
    }
}

/// A chain of biquads with its own memory per channel.
#[derive(Debug, Clone)]
struct Cascade {
    coeffs: Vec<Coeffs>,
    /// `channels × coeffs.len()`, channel by channel.
    states: Vec<BiquadState>,
}

impl Cascade {
    fn new(coeffs: Vec<Coeffs>, channels: usize) -> Self {
        let states = vec![BiquadState::default(); coeffs.len() * channels];
        Cascade { coeffs, states }
    }

    #[inline]
    fn run(&mut self, channel: usize, x: f64) -> f64 {
        let n = self.coeffs.len();
        let states = &mut self.states[channel * n..(channel + 1) * n];
        let mut y = x;
        for (state, c) in states.iter_mut().zip(&self.coeffs) {
            y = state.run(c, y);
        }
        y
    }

    fn reset(&mut self) {
        self.states.fill(BiquadState::default());
    }
}

/// The equaliser and AutoEq, as one cascade with one pre-amp. A new curve does not replace the old
/// one at once: both run for [`SMOOTH_MS`] and the output crossfades from one to the other.
pub struct Equalizer {
    channels: usize,
    smooth: usize,
    current: Cascade,
    previous: Option<Cascade>,
    /// 0 → 1 while moving from `previous` to `current`.
    blend: Ramp,
    preamp: Ramp,
}

impl Equalizer {
    pub fn new(channels: usize, rate: u32) -> Self {
        let channels = channels.max(1);
        Equalizer {
            channels,
            smooth: smooth_frames(rate),
            current: Cascade::new(Vec::new(), channels),
            previous: None,
            blend: Ramp::at(1.0),
            preamp: Ramp::at(1.0),
        }
    }

    /// A new curve and pre-amp. The same curve again only moves the pre-amp.
    pub fn set(&mut self, coeffs: Vec<Coeffs>, preamp_db: f32) {
        self.preamp
            .set(super::player::db_to_linear(preamp_db), self.smooth);
        if coeffs == self.current.coeffs {
            return;
        }
        let mut next = Cascade::new(coeffs, self.channels);
        if next.coeffs.len() == self.current.coeffs.len() {
            next.states.clone_from(&self.current.states);
        }
        self.previous = Some(std::mem::replace(&mut self.current, next));
        self.blend = Ramp::at(0.0);
        self.blend.set(1.0, self.smooth);
    }

    fn is_bypassed(&self) -> bool {
        self.current.coeffs.is_empty()
            && self.previous.is_none()
            && self.preamp.is_settled()
            && self.preamp.value() == 1.0
    }

    pub fn process(&mut self, buf: &mut [f32]) {
        if self.is_bypassed() {
            return;
        }
        for frame in buf.chunks_exact_mut(self.channels) {
            let gain = f64::from(self.preamp.tick());
            let blend = if self.previous.is_some() {
                f64::from(self.blend.tick())
            } else {
                1.0
            };
            for (c, x) in frame.iter_mut().enumerate() {
                let input = f64::from(*x);
                let mut y = self.current.run(c, input);
                if let Some(old) = self.previous.as_mut() {
                    let was = old.run(c, input);
                    y = was + (y - was) * blend;
                }
                *x = (y * gain) as f32;
            }
            if self.previous.is_some() && self.blend.is_settled() {
                self.previous = None;
            }
        }
    }

    /// After a seek or a new track: no ringing from what came before, and no ramp left half done.
    pub fn reset(&mut self) {
        self.current.reset();
        self.previous = None;
        self.blend = Ramp::at(1.0);
        self.preamp.settle();
    }
}

/// Rounds of the fit in [`graphic_filters`], and how much of what is missing each round corrects.
const FIT_ROUNDS: usize = 16;
const FIT_STEP: f64 = 0.7;

/// AutoEq's graphic bands as peaking filters: each centred between its lower and upper edge and as
/// wide as the band, so that the cascade passes through every band's gain at that band's centre.
///
/// Neighbouring peaks overlap, so giving each filter its band's gain overshoots a broad bump by half
/// again (the classic graphic-equaliser error). The gains are therefore fitted: a few rounds of
/// correcting each one by what the whole cascade still misses at its centre. The bands' mean becomes
/// a pre-amp and the filters carry only the difference. Answers the filters and that pre-amp.
pub fn graphic_filters(bands: &[GraphicBand], rate: f64) -> (Vec<Coeffs>, f32) {
    if bands.is_empty() {
        return (Vec::new(), 0.0);
    }
    let mean = bands.iter().map(|b| b.gain_db).sum::<f32>() / bands.len() as f32;
    // (centre, Q, target): `autoeq::bands` starts its first band at 20 Hz.
    let mut lower = 20.0f64;
    let mut shape = Vec::with_capacity(bands.len());
    for band in bands {
        let upper = f64::from(band.cutoff_hz).max(lower * 1.01);
        let ratio = upper / lower;
        // A band `ratio` wide (2^N for N octaves) has Q = sqrt(2^N) / (2^N - 1).
        shape.push((
            (lower * upper).sqrt(),
            ratio.sqrt() / (ratio - 1.0),
            f64::from(band.gain_db - mean),
        ));
        lower = upper;
    }
    let build = |gains: &[f64]| -> Vec<Coeffs> {
        shape
            .iter()
            .zip(gains)
            .map(|((centre, q, _), g)| Coeffs::rbj(FilterKind::Peak, *centre, *g, *q, rate))
            .collect()
    };
    let mut gains: Vec<f64> = shape.iter().map(|s| s.2).collect();
    for _ in 0..FIT_ROUNDS {
        let filters = build(&gains);
        for (gain, (centre, _, target)) in gains.iter_mut().zip(&shape) {
            let got: f64 = filters.iter().map(|f| f.response_db(*centre, rate)).sum();
            *gain = (*gain + FIT_STEP * (target - got)).clamp(-30.0, 30.0);
        }
    }
    let out: Vec<Coeffs> = shape
        .iter()
        .zip(&gains)
        .filter_map(|((centre, q, _), g)| Coeffs::design(FilterKind::Peak, *centre, *g, *q, rate))
        .collect();
    (out, mean)
}

/// The whole cascade for `cfg` at `rate`: the equaliser's bands, then AutoEq's filters (its
/// parametric EQ when there is one, its graphic bands when not), and the sum of their pre-amps.
pub fn eq_filters(cfg: &EffectsConfig, rate: u32) -> (Vec<Coeffs>, f32) {
    let rate = f64::from(rate);
    let mut coeffs = Vec::new();
    let mut preamp = 0.0f32;
    if cfg.eq.enabled {
        preamp += cfg.eq.preamp_db;
        coeffs.extend(cfg.eq.bands.iter().filter_map(|b| {
            Coeffs::design(
                b.kind,
                f64::from(b.freq_hz),
                f64::from(b.gain_db),
                f64::from(b.q),
                rate,
            )
        }));
    }
    if cfg.auto_eq.enabled {
        match autoeq::parse(&cfg.auto_eq.peq) {
            Ok(peq) => {
                preamp += peq.preamp_db as f32;
                coeffs.extend(
                    peq.filters
                        .iter()
                        .filter_map(|f| Coeffs::design(f.kind.into(), f.fc, f.gain_db, f.q, rate)),
                );
            }
            // No profile text (or not one AutoEq wrote): the bands, when there are any.
            Err(_) => {
                let (filters, mean) = graphic_filters(&cfg.auto_eq.bands, rate);
                preamp += mean;
                coeffs.extend(filters);
            }
        }
    }
    (coeffs, preamp)
}

// =============================================================================
// STEREO TOOLS
// =============================================================================

/// Gains of (left, right) for a balance in -100…100, as Android's `StereoTools.balanceGains`.
pub fn balance_gains(balance: i32) -> (f32, f32) {
    let b = balance.clamp(-100, 100) as f32 / 100.0;
    let left = if b > 0.0 { 1.0 - b } else { 1.0 };
    let right = if b < 0.0 { 1.0 + b } else { 1.0 };
    (left, right)
}

/// One-pole low-pass coefficient for `cutoff_hz` at `rate`.
fn one_pole(cutoff_hz: f64, rate: u32) -> f32 {
    (-2.0 * PI * cutoff_hz / f64::from(rate.max(1))).exp() as f32
}

/// `StereoTools.process` over interleaved stereo, with each switch ramped rather than flipped: a
/// toggle fades its effect in or out over [`SMOOTH_MS`] instead of clicking.
pub struct Stereo {
    smooth: usize,
    a_cross: f32,
    a_bass: f32,
    cross_l: f32,
    cross_r: f32,
    bass: f32,
    voice: Ramp,
    mono: Ramp,
    crossfeed: Ramp,
    width: Ramp,
    left: Ramp,
    right: Ramp,
}

/// -4.5 dB, the crossfeed's level.
const CROSSFEED_GAIN: f32 = 0.595_662;

impl Stereo {
    pub fn new(rate: u32) -> Self {
        Stereo {
            smooth: smooth_frames(rate),
            a_cross: one_pole(700.0, rate),
            a_bass: one_pole(200.0, rate),
            cross_l: 0.0,
            cross_r: 0.0,
            bass: 0.0,
            voice: Ramp::at(0.0),
            mono: Ramp::at(0.0),
            crossfeed: Ramp::at(0.0),
            width: Ramp::at(1.0),
            left: Ramp::at(1.0),
            right: Ramp::at(1.0),
        }
    }

    pub fn set(&mut self, cfg: &StereoConfig) {
        let on = |b: bool| if b { 1.0 } else { 0.0 };
        let (left, right) = balance_gains(cfg.balance);
        let n = self.smooth;
        self.voice.set(on(cfg.voice_reduction), n);
        self.mono.set(on(cfg.mono), n);
        self.crossfeed.set(on(cfg.crossfeed), n);
        self.width.set(cfg.width, n);
        self.left.set(left, n);
        self.right.set(right, n);
    }

    fn is_bypassed(&self) -> bool {
        let at = |r: &Ramp, v: f32| r.is_settled() && r.value() == v;
        at(&self.voice, 0.0)
            && at(&self.mono, 0.0)
            && at(&self.crossfeed, 0.0)
            && at(&self.width, 1.0)
            && at(&self.left, 1.0)
            && at(&self.right, 1.0)
    }

    /// Processes interleaved stereo in place.
    pub fn process(&mut self, buf: &mut [f32]) {
        if self.is_bypassed() {
            return;
        }
        for frame in buf.as_chunks_mut::<2>().0 {
            let (mut l, mut r) = (frame[0], frame[1]);
            let voice = self.voice.tick();
            if voice > 0.0 {
                let mid = (l + r) * 0.5;
                self.bass = self.a_bass * self.bass + (1.0 - self.a_bass) * mid;
                let side = (l - r) * 0.5;
                l += (side + self.bass - l) * voice;
                r += (-side + self.bass - r) * voice;
            }
            let mono = self.mono.tick();
            if mono > 0.0 {
                let m = (l + r) * 0.5;
                l += (m - l) * mono;
                r += (m - r) * mono;
            }
            let width = self.width.tick();
            if width != 1.0 {
                let mid = (l + r) * 0.5;
                let side = (l - r) * 0.5 * width;
                l = mid + side;
                r = mid - side;
            }
            let crossfeed = self.crossfeed.tick();
            if crossfeed > 0.0 {
                self.cross_l = self.a_cross * self.cross_l + (1.0 - self.a_cross) * l;
                self.cross_r = self.a_cross * self.cross_r + (1.0 - self.a_cross) * r;
                // Normalised so a centred (mono) signal keeps its level at low frequencies.
                let nl = (l + CROSSFEED_GAIN * self.cross_r) / (1.0 + CROSSFEED_GAIN);
                let nr = (r + CROSSFEED_GAIN * self.cross_l) / (1.0 + CROSSFEED_GAIN);
                l += (nl - l) * crossfeed;
                r += (nr - r) * crossfeed;
            }
            frame[0] = l * self.left.tick();
            frame[1] = r * self.right.tick();
        }
    }

    pub fn reset(&mut self) {
        self.cross_l = 0.0;
        self.cross_r = 0.0;
        self.bass = 0.0;
        for ramp in [
            &mut self.voice,
            &mut self.mono,
            &mut self.crossfeed,
            &mut self.width,
            &mut self.left,
            &mut self.right,
        ] {
            ramp.settle();
        }
    }
}

// =============================================================================
// TIME-STRETCH — speed without pitch (WSOLA)
// =============================================================================

/// Length of each piece of input copied to the output.
const SEQUENCE_MS: f64 = 40.0;
/// How much of each piece is crossfaded with the end of the previous one.
const OVERLAP_MS: f64 = 10.0;
/// How far past its nominal place a piece may start, to line its waveform up with the previous.
const SEEK_MS: f64 = 15.0;
/// Offsets tried in the first pass of the search; the best is refined to the frame.
const SEEK_COARSE: usize = 4;

/// Waveform-similarity overlap-add: the output is built from pieces of the input, each taken a little
/// further along than the output has moved (`tempo` times as far), and each nudged to where its start
/// best continues the previous piece's end, so the joins are not heard as a warble. Duration changes
/// by `1 / tempo`; pitch does not change at all.
///
/// The same structure as SoundTouch's TDStretch, which is what most players use.
pub struct Stretch {
    channels: usize,
    tempo: f64,
    seq: usize,
    overlap: usize,
    seek: usize,
    /// Interleaved input not yet used up.
    input: Vec<f32>,
    /// Where the next piece nominally starts, in frames from the start of `input`.
    pos: f64,
    /// The last `overlap` frames of the previous piece, which the next crossfades out of.
    mid: Vec<f32>,
    /// Where in `input` the frame after `mid` is.
    mid_end: usize,
}

impl Stretch {
    pub fn new(rate: u32, channels: usize, tempo: f64) -> Self {
        let channels = channels.max(1);
        let frames = |ms: f64| ((f64::from(rate) * ms / 1000.0) as usize).max(4);
        let overlap = frames(OVERLAP_MS);
        let seq = frames(SEQUENCE_MS).max(2 * overlap + 1);
        Stretch {
            channels,
            tempo: tempo.clamp(0.1, 10.0),
            seq,
            overlap,
            seek: frames(SEEK_MS),
            input: Vec::new(),
            pos: 0.0,
            mid: vec![0.0; overlap * channels],
            mid_end: 0,
        }
    }

    pub fn tempo(&self) -> f64 {
        self.tempo
    }

    pub fn reset(&mut self) {
        self.input.clear();
        self.pos = 0.0;
        self.mid.fill(0.0);
        self.mid_end = 0;
    }

    /// Takes `input` (interleaved) and appends what is ready to `out`. Holds back about
    /// `SEQUENCE_MS + SEEK_MS` of input until more arrives; [`Stretch::finish`] lets it go.
    pub fn process(&mut self, input: &[f32], out: &mut Vec<f32>) {
        let ch = self.channels;
        let ov = self.overlap;
        self.input.extend_from_slice(input);
        loop {
            let base = self.pos as usize;
            if base + self.seek + self.seq > self.input.len() / ch {
                break;
            }
            let start = base + self.best_offset(base);
            for i in 0..ov {
                // Linear crossfade, the previous piece's continuation out and this piece in.
                let w = (i as f32 + 0.5) / ov as f32;
                for c in 0..ch {
                    let a = self.mid[i * ch + c];
                    let b = self.input[(start + i) * ch + c];
                    out.push(a + (b - a) * w);
                }
            }
            out.extend_from_slice(&self.input[(start + ov) * ch..(start + self.seq - ov) * ch]);
            self.mid
                .copy_from_slice(&self.input[(start + self.seq - ov) * ch..(start + self.seq) * ch]);
            self.mid_end = start + self.seq;
            self.pos += (self.seq - ov) as f64 * self.tempo;
        }
        let used = (self.pos as usize).min(self.input.len() / ch);
        if used > 0 {
            self.input.drain(..used * ch);
            self.pos -= used as f64;
            self.mid_end = self.mid_end.saturating_sub(used);
        }
    }

    /// The end of the stream: what is held back, unstretched (a few tens of milliseconds).
    pub fn finish(&mut self, out: &mut Vec<f32>) {
        out.extend_from_slice(&self.mid);
        let from = self.mid_end.min(self.input.len() / self.channels) * self.channels;
        out.extend_from_slice(&self.input[from..]);
        self.reset();
    }

    /// The offset in `0..seek` at which the input best continues `mid`: normalised cross-correlation,
    /// coarse first and then to the frame around the best.
    fn best_offset(&self, base: usize) -> usize {
        let ch = self.channels;
        let len = self.overlap * ch;
        let score = |off: usize| -> f64 {
            let from = (base + off) * ch;
            let candidate = &self.input[from..from + len];
            let mut corr = 0.0f64;
            let mut energy = 0.0f64;
            for (a, b) in self.mid.iter().zip(candidate) {
                corr += f64::from(*a) * f64::from(*b);
                energy += f64::from(*b) * f64::from(*b);
            }
            if energy <= 1e-12 {
                0.0
            } else {
                corr / energy.sqrt()
            }
        };
        let mut best = 0usize;
        let mut best_score = f64::NEG_INFINITY;
        for off in (0..self.seek).step_by(SEEK_COARSE) {
            let s = score(off);
            if s > best_score {
                best_score = s;
                best = off;
            }
        }
        let lo = best.saturating_sub(SEEK_COARSE - 1);
        let hi = (best + SEEK_COARSE).min(self.seek);
        for off in lo..hi {
            let s = score(off);
            if s > best_score {
                best_score = s;
                best = off;
            }
        }
        best
    }
}

/// The pitch as a rate factor: a semitone is a twelfth of an octave.
pub fn pitch_factor(cfg: &EffectsConfig) -> f64 {
    2f64.powf(f64::from(cfg.pitch_semitones) / 12.0)
}

/// What the time-stretch has to do once the pitch has been made by resampling: resampling by `p`
/// already shortens by `p`, so the stretch makes up the rest of the speed.
pub fn tempo_of(cfg: &EffectsConfig) -> f64 {
    f64::from(cfg.speed) / pitch_factor(cfg)
}

// =============================================================================
// SKIP SILENCE
// =============================================================================

/// Below this on every sample of a chunk, the chunk is silent: media3's threshold (1024 in 16 bits,
/// about -30 dBFS), which is what Android's `skipSilenceEnabled` uses.
const SILENCE_LEVEL: f32 = 1024.0 / 32768.0;
/// The unit silence is judged in.
const SILENCE_CHUNK_MS: usize = 10;
/// A silence shorter than this is left alone: media3's `DEFAULT_MINIMUM_SILENCE_DURATION_US`.
const SILENCE_MINIMUM_MS: usize = 100;
/// One chunk in this many of what follows is kept: media3's `DEFAULT_SILENCE_RETENTION_RATIO`, 0.2.
const SILENCE_KEEP_ONE_IN: usize = 5;
/// The most of one silence kept: media3's `DEFAULT_MAX_SILENCE_TO_KEEP_DURATION_US`.
const SILENCE_KEEP_MAX_MS: usize = 2_000;

/// Shortens a silent stretch as Android's `skipSilenceEnabled` does: a fifth of it, at most two
/// seconds, so the same setting sounds the same on every platform. Dropping chunks of what is under
/// the threshold rather than media3's fades: what is taken out is too quiet for the joins to show.
#[derive(Debug, Clone, Default)]
pub struct SilenceSkipper {
    /// Frames of silence in a row so far, across blocks.
    silent: usize,
    /// Frames of this silence kept so far.
    kept: usize,
}

impl SilenceSkipper {
    /// Removes the frames to skip from `samples` (interleaved, `channels` wide) and says how many.
    pub fn process(&mut self, samples: &mut Vec<f32>, channels: usize, rate: u32) -> usize {
        let ch = channels.max(1);
        let chunk = (rate as usize * SILENCE_CHUNK_MS / 1000).max(1);
        let minimum = rate as usize * SILENCE_MINIMUM_MS / 1000;
        let most = rate as usize * SILENCE_KEEP_MAX_MS / 1000;
        let frames = samples.len() / ch;
        let mut write = 0usize;
        let mut dropped = 0usize;
        let mut f = 0usize;
        while f < frames {
            let n = chunk.min(frames - f);
            let range = f * ch..(f + n) * ch;
            let quiet = samples[range.clone()].iter().all(|x| x.abs() < SILENCE_LEVEL);
            let kept = if quiet {
                let past = self.silent.saturating_sub(minimum) / chunk;
                let kept = self.silent < minimum || (self.kept < most && past.is_multiple_of(SILENCE_KEEP_ONE_IN));
                self.silent += n;
                if kept {
                    self.kept += n;
                }
                kept
            } else {
                self.reset();
                true
            };
            if kept {
                samples.copy_within(range, write);
                write += n * ch;
            } else {
                dropped += n;
            }
            f += n;
        }
        samples.truncate(write);
        dropped
    }

    pub fn reset(&mut self) {
        self.silent = 0;
        self.kept = 0;
    }
}

// =============================================================================
// CROSSFADE
// =============================================================================

/// Mixes the end of one track into the start of the next over `len` frames, equal-power (cosine out,
/// sine in), so two unrelated songs keep their loudness through the middle.
#[derive(Debug, Clone)]
pub struct Crossfader {
    len: usize,
    done: usize,
    channels: usize,
}

impl Crossfader {
    pub fn new(len_frames: usize, channels: usize) -> Self {
        Crossfader {
            len: len_frames.max(1),
            done: 0,
            channels: channels.max(1),
        }
    }

    /// Mixes `outgoing` into `incoming` in place, frame by frame from where the last call stopped.
    /// Where `outgoing` is shorter (the old track ended early), it is silence.
    pub fn mix(&mut self, outgoing: &[f32], incoming: &mut [f32]) {
        let ch = self.channels;
        for (i, frame) in incoming.chunks_exact_mut(ch).enumerate() {
            let p = ((self.done as f64 + 0.5) / self.len as f64).min(1.0) * FRAC_PI_2;
            let (fade_out, fade_in) = (p.cos() as f32, p.sin() as f32);
            for (c, x) in frame.iter_mut().enumerate() {
                let old = outgoing.get(i * ch + c).copied().unwrap_or(0.0);
                *x = *x * fade_in + old * fade_out;
            }
            self.done += 1;
        }
    }

    pub fn is_done(&self) -> bool {
        self.done >= self.len
    }
}

// =============================================================================
// THE CHAIN — what runs once over the mix
// =============================================================================

/// Equaliser, stereo tools and time-stretch, at the device's format.
pub struct Chain {
    rate: u32,
    channels: usize,
    eq: Equalizer,
    /// What the cascade was designed from, so the same settings again redesign nothing.
    eq_from: Option<(EqConfig, AutoEqConfig)>,
    stereo: Stereo,
    stretch: Option<Stretch>,
}

impl Chain {
    pub fn new(rate: u32, channels: usize) -> Self {
        let channels = channels.max(1);
        Chain {
            rate,
            channels,
            eq: Equalizer::new(channels, rate),
            eq_from: None,
            stereo: Stereo::new(rate),
            stretch: None,
        }
    }

    /// Takes the settings. Coefficients are designed only when the curve changed; a new tempo
    /// rebuilds the stretch (the player seeks right after, so nothing is lost).
    pub fn configure(&mut self, cfg: &EffectsConfig) {
        let from = (cfg.eq.clone(), cfg.auto_eq.clone());
        if self.eq_from.as_ref() != Some(&from) {
            let (coeffs, preamp_db) = eq_filters(cfg, self.rate);
            self.eq.set(coeffs, preamp_db);
            self.eq_from = Some(from);
        }
        self.stereo.set(&cfg.stereo);
        let tempo = tempo_of(cfg);
        let wanted = (tempo - 1.0).abs() > 1e-6;
        let same = match &self.stretch {
            Some(s) => wanted && (s.tempo() - tempo).abs() < 1e-9,
            None => !wanted,
        };
        if !same {
            self.stretch = wanted.then(|| Stretch::new(self.rate, self.channels, tempo));
        }
    }

    /// Runs `samples` (interleaved at the device's format) through everything; answers what is ready.
    pub fn process(&mut self, mut samples: Vec<f32>) -> Vec<f32> {
        self.eq.process(&mut samples);
        if self.channels == 2 {
            self.stereo.process(&mut samples);
        }
        match self.stretch.as_mut() {
            Some(stretch) => {
                let mut out = Vec::with_capacity(samples.len() * 2);
                stretch.process(&samples, &mut out);
                out
            }
            None => samples,
        }
    }

    /// The stream ended: whatever the stretch still holds.
    pub fn finish(&mut self, out: &mut Vec<f32>) {
        if let Some(stretch) = self.stretch.as_mut() {
            stretch.finish(out);
        }
    }

    /// A seek or a new track: forget the signal before it.
    pub fn reset(&mut self) {
        self.eq.reset();
        self.stereo.reset();
        if let Some(stretch) = self.stretch.as_mut() {
            stretch.reset();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const RATE: u32 = 48_000;

    fn sine(hz: f64, amplitude: f64, frames: usize) -> Vec<f32> {
        (0..frames)
            .map(|i| ((2.0 * PI * hz * i as f64 / f64::from(RATE)).sin() * amplitude) as f32)
            .collect()
    }

    fn peak(x: &[f32]) -> f32 {
        x.iter().fold(0f32, |m, v| m.max(v.abs()))
    }

    /// The level of a signal whatever its frequency: a sampled sine's peak is only its amplitude when
    /// a sample lands on the crest, which at 8 kHz and 48 kHz (six samples a cycle) none may.
    fn rms(x: &[f32]) -> f64 {
        (x.iter().map(|v| f64::from(*v) * f64::from(*v)).sum::<f64>() / x.len() as f64).sqrt()
    }

    fn rising_zero_crossings(x: &[f32]) -> usize {
        x.windows(2).filter(|w| w[0] < 0.0 && w[1] >= 0.0).count()
    }

    fn run_mono(c: &Coeffs, input: &[f32]) -> Vec<f32> {
        let mut state = BiquadState::default();
        input
            .iter()
            .map(|x| state.run(c, f64::from(*x)) as f32)
            .collect()
    }

    #[test]
    fn a_biquad_at_zero_db_is_the_identity() {
        let input = sine(440.0, 0.5, 4_800);
        for kind in [FilterKind::Peak, FilterKind::LowShelf, FilterKind::HighShelf] {
            let c = Coeffs::rbj(kind, 1000.0, 0.0, 0.96, f64::from(RATE));
            let out = run_mono(&c, &input);
            let worst = input
                .iter()
                .zip(&out)
                .fold(0f32, |m, (a, b)| m.max((a - b).abs()));
            assert!(worst < 1e-6, "{kind:?} at 0 dB changed the signal by {worst}");
            assert!(
                Coeffs::design(kind, 1000.0, 0.0, 0.96, f64::from(RATE)).is_none(),
                "a 0 dB band is left out of the cascade"
            );
        }
    }

    #[test]
    fn a_six_db_peak_doubles_a_sine_at_its_centre() {
        let c = Coeffs::design(FilterKind::Peak, 1000.0, 6.0, 1.0, f64::from(RATE)).expect("a filter");
        let out = run_mono(&c, &sine(1000.0, 0.25, RATE as usize));
        let settled = peak(&out[RATE as usize / 2..]);
        let ratio = settled / 0.25;
        assert!((ratio - 1.995).abs() < 0.03, "gain {ratio} at the centre");
        // And leaves a sine far from it alone.
        let far = run_mono(&c, &sine(60.0, 0.25, RATE as usize));
        let ratio = peak(&far[RATE as usize / 2..]) / 0.25;
        assert!((ratio - 1.0).abs() < 0.05, "gain {ratio} four octaves away");
    }

    #[test]
    fn shelves_lift_their_side_and_leave_the_other() {
        let rate = f64::from(RATE);
        let low = Coeffs::design(FilterKind::LowShelf, 200.0, 6.0, 0.7, rate).expect("a filter");
        let high = Coeffs::design(FilterKind::HighShelf, 2000.0, 6.0, 0.7, rate).expect("a filter");
        let half = RATE as usize / 2;
        let gain = |c: &Coeffs, hz: f64| {
            let input = sine(hz, 0.25, RATE as usize);
            rms(&run_mono(c, &input)[half..]) / rms(&input[half..])
        };
        assert!((gain(&low, 20.0) - 1.995).abs() < 0.1);
        assert!((gain(&low, 8000.0) - 1.0).abs() < 0.03);
        assert!((gain(&high, 16000.0) - 1.995).abs() < 0.1);
        assert!((gain(&high, 60.0) - 1.0).abs() < 0.03);
    }

    #[test]
    fn the_equaliser_keeps_channels_apart_and_moves_to_a_new_curve_without_a_jump() {
        let mut eq = Equalizer::new(2, RATE);
        let curve = |db: f64| vec![Coeffs::design(FilterKind::Peak, 1000.0, db, 1.0, f64::from(RATE)).expect("a filter")];
        eq.set(curve(6.0), 0.0);
        // Left a sine, right silence.
        let left = sine(1000.0, 0.25, RATE as usize);
        let mut buf: Vec<f32> = left.iter().flat_map(|x| [*x, 0.0]).collect();
        eq.process(&mut buf);
        let (frames, _) = buf.as_chunks::<2>();
        assert!(frames.iter().all(|f| f[1] == 0.0), "the right channel stayed silent");
        let settled: Vec<f32> = frames[frames.len() / 2..].iter().map(|f| f[0]).collect();
        assert!((peak(&settled) / 0.25 - 1.995).abs() < 0.03);

        // Back to flat: the level falls over the smoothing time, it does not step.
        eq.set(Vec::new(), 0.0);
        let mut more: Vec<f32> = sine(1000.0, 0.25, RATE as usize / 10).iter().flat_map(|x| [*x, 0.0]).collect();
        eq.process(&mut more);
        let lefts: Vec<f32> = more.iter().step_by(2).copied().collect();
        let biggest_step = lefts.windows(2).fold(0f32, |m, w| m.max((w[1] - w[0]).abs()));
        // The sine itself (1 kHz at 0.5 peak) moves up to 0.065 a sample at 48 kHz; switching the
        // curve at once would add a jump of up to 0.25 on top.
        assert!(biggest_step < 0.1, "step {biggest_step}");
        let tail = &lefts[lefts.len() / 2..];
        assert!((peak(tail) / 0.25 - 1.0).abs() < 0.03, "flat again");
    }

    #[test]
    fn autoeq_bands_apply_their_curve() {
        // A flat -6 dB curve, which is what a pre-amp alone looks like through `autoeq::bands`.
        let peq = autoeq::Peq { preamp_db: -6.0, filters: vec![autoeq::Filter { kind: autoeq::Kind::Peak, fc: 1000.0, gain_db: 0.0, q: 1.0 }] };
        let bands: Vec<GraphicBand> = autoeq::bands(&peq, 32)
            .into_iter()
            .map(|b| GraphicBand { cutoff_hz: b.cutoff_hz, gain_db: b.gain_db })
            .collect();
        let (filters, preamp) = graphic_filters(&bands, f64::from(RATE));
        assert!(filters.is_empty(), "a flat curve is all pre-amp");
        assert!((preamp + 6.0).abs() < 0.01);

        // A peak in the profile shows up where it was, through the bands.
        let peq = autoeq::Peq { preamp_db: 0.0, filters: vec![autoeq::Filter { kind: autoeq::Kind::Peak, fc: 1000.0, gain_db: 6.0, q: 1.0 }] };
        let bands: Vec<GraphicBand> = autoeq::bands(&peq, 32)
            .into_iter()
            .map(|b| GraphicBand { cutoff_hz: b.cutoff_hz, gain_db: b.gain_db })
            .collect();
        let cfg = EffectsConfig {
            auto_eq: AutoEqConfig { enabled: true, peq: String::new(), bands },
            ..Default::default()
        };
        let (filters, preamp) = eq_filters(&cfg, RATE);
        let mut eq = Equalizer::new(1, RATE);
        eq.set(filters, preamp);
        let gain_at = |eq: &mut Equalizer, hz: f64| {
            eq.reset();
            let mut buf = sine(hz, 0.1, RATE as usize);
            eq.process(&mut buf);
            peak(&buf[RATE as usize / 2..]) / 0.1
        };
        let centre = 20.0 * gain_at(&mut eq, 1000.0).log10();
        let far = 20.0 * gain_at(&mut eq, 60.0).log10();
        assert!((centre - 6.0).abs() < 1.0, "{centre} dB at the peak");
        assert!(far.abs() < 1.0, "{far} dB away from it");
    }

    #[test]
    fn a_parametric_profile_is_used_filter_by_filter() {
        let text = "Preamp: -3.0 dB\nFilter 1: ON PK Fc 1000 Hz Gain 6.0 dB Q 1.00\nFilter 2: ON LSC Fc 105 Hz Gain 0.0 dB Q 0.70";
        let cfg = EffectsConfig {
            auto_eq: AutoEqConfig { enabled: true, peq: text.into(), bands: Vec::new() },
            ..Default::default()
        };
        let (filters, preamp) = eq_filters(&cfg, RATE);
        assert_eq!(filters.len(), 1, "the 0 dB shelf is left out");
        assert_eq!(preamp, -3.0);
        assert_eq!(filters[0], Coeffs::rbj(FilterKind::Peak, 1000.0, 6.0, 1.0, f64::from(RATE)));
    }

    #[test]
    fn mono_of_opposite_channels_is_silence() {
        let mut stereo = Stereo::new(RATE);
        stereo.set(&StereoConfig { mono: true, ..Default::default() });
        stereo.reset(); // straight to the setting, no ramp
        let left = sine(440.0, 0.5, 4_800);
        let mut buf: Vec<f32> = left.iter().flat_map(|x| [*x, -*x]).collect();
        stereo.process(&mut buf);
        assert!(peak(&buf) < 1e-6, "L and -L summed to {}", peak(&buf));
    }

    #[test]
    fn balance_hard_left_mutes_the_right() {
        assert_eq!(balance_gains(-100), (1.0, 0.0));
        assert_eq!(balance_gains(100), (0.0, 1.0));
        assert_eq!(balance_gains(0), (1.0, 1.0));
        assert_eq!(balance_gains(50), (0.5, 1.0));
        let mut stereo = Stereo::new(RATE);
        stereo.set(&StereoConfig { balance: -100, ..Default::default() });
        stereo.reset();
        let mut buf: Vec<f32> = sine(440.0, 0.5, 4_800).iter().flat_map(|x| [*x, *x]).collect();
        stereo.process(&mut buf);
        let (frames, _) = buf.as_chunks::<2>();
        assert!(frames.iter().all(|f| f[1] == 0.0));
        assert!(frames.iter().any(|f| f[0].abs() > 0.4), "the left still plays");
    }

    #[test]
    fn width_zero_is_mono_and_one_leaves_the_signal_alone() {
        let input: Vec<f32> = sine(440.0, 0.5, 4_800).iter().flat_map(|x| [*x, 0.25 * *x]).collect();
        let mut stereo = Stereo::new(RATE);
        stereo.set(&StereoConfig { width: 0.0, ..Default::default() });
        stereo.reset();
        let mut buf = input.clone();
        stereo.process(&mut buf);
        let (frames, _) = buf.as_chunks::<2>();
        assert!(frames.iter().all(|f| (f[0] - f[1]).abs() < 1e-6));
        let mut neutral = Stereo::new(RATE);
        let mut same = input.clone();
        neutral.process(&mut same);
        assert_eq!(same, input);
    }

    #[test]
    fn a_stereo_toggle_ramps_in() {
        let mut stereo = Stereo::new(RATE);
        stereo.set(&StereoConfig { balance: -100, ..Default::default() });
        let mut buf = vec![1.0f32; 2 * smooth_frames(RATE) * 2];
        stereo.process(&mut buf);
        let rights: Vec<f32> = buf.iter().skip(1).step_by(2).copied().collect();
        assert!(rights.windows(2).all(|w| w[1] <= w[0]), "the right goes down, never up");
        assert!(rights[0] > 0.9, "starting from where it was");
        assert_eq!(*rights.last().expect("frames"), 0.0, "and ending muted");
    }

    #[test]
    fn the_fade_between_songs_ramps_in_and_out_monotonically() {
        let duration = Some(10_000.0);
        let gains: Vec<f32> = (0..=10_000).step_by(10).map(|ms| transition_gain(f64::from(ms), duration, 2_000.0)).collect();
        assert_eq!(gains[0], 0.0);
        assert_eq!(*gains.last().expect("gains"), 0.0);
        let middle = gains.len() / 2;
        assert!(gains[..middle].windows(2).all(|w| w[1] >= w[0]), "fade in rises");
        assert!(gains[middle..].windows(2).all(|w| w[1] <= w[0]), "fade out falls");
        assert_eq!(gains[middle], 1.0);
        // A song shorter than two fades: a third of it each, never silent in the middle.
        assert!((transition_gain(1_500.0, Some(3_000.0), 5_000.0) - 1.0).abs() < 1e-6);
        // No length known: fade in only.
        assert_eq!(transition_gain(60_000.0, None, 2_000.0), 1.0);
        assert_eq!(transition_gain(500.0, Some(10_000.0), 0.0), 1.0, "off");
    }

    #[test]
    fn replay_gain_follows_the_mode_and_never_clips_past_the_peak() {
        let tags = TrackGains { track_gain_db: Some(-8.0), album_gain_db: Some(-4.0), track_peak: Some(0.9), album_peak: Some(0.95) };
        let track = replay_gain_volume(Some(&tags), ReplayGainMode::Track, 0.0, true).expect("a gain");
        assert!((track - 0.398).abs() < 0.001, "{track}");
        let album = replay_gain_volume(Some(&tags), ReplayGainMode::Album, 0.0, false).expect("a gain");
        assert!((album - 0.631).abs() < 0.001, "{album}");
        let auto_in_album = replay_gain_volume(Some(&tags), ReplayGainMode::Auto, 0.0, true).expect("a gain");
        assert_eq!(auto_in_album, album);
        let auto_in_a_mix = replay_gain_volume(Some(&tags), ReplayGainMode::Auto, 0.0, false).expect("a gain");
        assert_eq!(auto_in_a_mix, track);
        // +12 dB of pre-amp would push it over: the peak (and 1.0) stop it.
        let loud = TrackGains { track_gain_db: Some(2.0), track_peak: Some(1.25), ..Default::default() };
        let capped = replay_gain_volume(Some(&loud), ReplayGainMode::Track, 12.0, false).expect("a gain");
        assert!((capped - 0.8).abs() < 1e-6, "{capped}");
        assert_eq!(replay_gain_volume(Some(&tags), ReplayGainMode::Off, 0.0, false), None);
        assert_eq!(replay_gain_volume(None, ReplayGainMode::Track, 0.0, false), None);
    }

    #[test]
    fn a_level_falls_back_to_the_callers_normalisation() {
        let cfg = LevelConfig::default();
        let stream = Level { gain_db: -6.0, ..Default::default() };
        assert!((stream.linear(&cfg) - 0.501).abs() < 0.001);
        let off = LevelConfig { normalize: false, ..cfg };
        assert_eq!(stream.linear(&off), 1.0);
        let tagged = Level { gain_db: -6.0, replay_gain: Some(TrackGains { track_gain_db: Some(-10.0), ..Default::default() }), album_run: false };
        // -10 dB + the 4 dB pre-amp.
        assert!((tagged.linear(&cfg) - 0.501).abs() < 0.001);
    }

    #[test]
    fn wsola_changes_the_duration_by_the_tempo_and_keeps_the_pitch() {
        let input = sine(440.0, 0.5, RATE as usize * 2);
        for tempo in [0.5, 0.8, 1.25, 2.0] {
            let mut stretch = Stretch::new(RATE, 1, tempo);
            let mut out = Vec::new();
            for block in input.chunks(1_153) {
                stretch.process(block, &mut out);
            }
            stretch.finish(&mut out);
            let expected = input.len() as f64 / tempo;
            let slack = 0.1 * f64::from(RATE); // the held-back window, at most
            assert!(
                (out.len() as f64 - expected).abs() < slack,
                "tempo {tempo}: {} frames for {expected}",
                out.len()
            );
            // 440 cycles per second of output, whatever its length.
            let body = &out[RATE as usize / 10..out.len() - RATE as usize / 10];
            let hz = rising_zero_crossings(body) as f64 / (body.len() as f64 / f64::from(RATE));
            assert!((hz - 440.0).abs() < 8.0, "tempo {tempo}: {hz} Hz");
        }
    }

    #[test]
    fn wsola_keeps_stereo_channels_apart() {
        let left = sine(300.0, 0.5, RATE as usize);
        let interleaved: Vec<f32> = left.iter().flat_map(|x| [*x, 0.0]).collect();
        let mut stretch = Stretch::new(RATE, 2, 1.5);
        let mut out = Vec::new();
        stretch.process(&interleaved, &mut out);
        let (frames, _) = out.as_chunks::<2>();
        assert!(frames.iter().all(|f| f[1] == 0.0));
        assert!(frames.iter().any(|f| f[0].abs() > 0.4));
    }

    #[test]
    fn the_tempo_makes_up_what_the_pitch_does_not() {
        let cfg = EffectsConfig { speed: 1.5, pitch_semitones: 12.0, ..Default::default() };
        assert!((pitch_factor(&cfg) - 2.0).abs() < 1e-9);
        assert!((tempo_of(&cfg) - 0.75).abs() < 1e-9, "resampling halves it; the stretch lengthens it back");
        assert!((tempo_of(&EffectsConfig::default()) - 1.0).abs() < 1e-12);
    }

    #[test]
    fn a_silence_keeps_a_fifth_of_itself_as_on_the_phone() {
        let mut skipper = SilenceSkipper::default();
        let sound = sine(440.0, 0.5, 4_800);
        let mut samples = sound.clone();
        samples.extend(std::iter::repeat_n(0.0f32, RATE as usize * 3)); // three seconds of nothing
        samples.extend_from_slice(&sound);
        let total = samples.len();
        let dropped = skipper.process(&mut samples, 1, RATE);
        assert_eq!(samples.len() + dropped, total);
        let gap_ms = (samples.len() - sound.len() * 2) * 1000 / RATE as usize;
        // The first 100 ms whole, then a fifth of the other 2900.
        assert!((660..=700).contains(&gap_ms), "{gap_ms} ms of the gap is left");
    }

    #[test]
    fn a_silence_under_the_minimum_is_left_alone() {
        let mut skipper = SilenceSkipper::default();
        let sound = sine(440.0, 0.5, 4_800);
        let mut short = sound.clone();
        short.extend(std::iter::repeat_n(0.0f32, RATE as usize / 20)); // 50 ms
        short.extend_from_slice(&sound);
        let before = short.len();
        assert_eq!(skipper.process(&mut short, 1, RATE), 0);
        assert_eq!(short.len(), before);
    }

    #[test]
    fn a_very_long_silence_keeps_at_most_two_seconds() {
        let mut skipper = SilenceSkipper::default();
        let mut samples = vec![0.0f32; RATE as usize * 60];
        skipper.process(&mut samples, 1, RATE);
        let kept_ms = samples.len() * 1000 / RATE as usize;
        assert!((2_000..=2_010).contains(&kept_ms), "{kept_ms} ms of a minute is left");
    }

    #[test]
    fn a_crossfade_is_as_long_as_the_incoming_audio_and_continuous() {
        const LEN: usize = 4_800;
        let mut fader = Crossfader::new(LEN, 1);
        // Both tracks hold the same level: equal-power rises in the middle but never jumps.
        let outgoing = [0.5f32; LEN];
        let mut incoming = [0.5f32; LEN + 1_000];
        fader.mix(&outgoing, &mut incoming);
        assert!(fader.is_done());
        assert!((incoming[0] - 0.5).abs() < 0.01, "starts at the old track");
        assert!((incoming[LEN - 1] - 0.5).abs() < 0.01, "ends at the new one");
        assert!(incoming[LEN..].iter().all(|x| *x == 0.5), "after it, the new track alone, all of it");
        let biggest_step = incoming.windows(2).fold(0f32, |m, w| m.max((w[1] - w[0]).abs()));
        assert!(biggest_step < 0.001, "step {biggest_step}");
        // In pieces, the same curve.
        let mut whole = [0.25f32; 1_000];
        Crossfader::new(1_000, 1).mix(&[0.75; 1_000], &mut whole);
        let mut pieces = [0.25f32; 1_000];
        let mut fader = Crossfader::new(1_000, 1);
        let (a, b) = pieces.split_at_mut(300);
        fader.mix(&[0.75; 300], a);
        fader.mix(&[0.75; 700], b);
        assert_eq!(whole, pieces);
    }

    #[test]
    fn the_chain_is_bit_exact_when_nothing_is_on() {
        let mut chain = Chain::new(RATE, 2);
        chain.configure(&EffectsConfig::default());
        let input: Vec<f32> = sine(440.0, 0.5, 4_800).iter().flat_map(|x| [*x, -0.5 * *x]).collect();
        assert_eq!(chain.process(input.clone()), input);
    }

    #[test]
    fn nonsense_settings_are_brought_into_range() {
        let cfg = EffectsConfig {
            speed: f32::NAN,
            pitch_semitones: 40.0,
            stereo: StereoConfig { balance: 900, width: -3.0, ..Default::default() },
            eq: EqConfig { enabled: true, preamp_db: f32::INFINITY, bands: vec![EqBand { gain_db: 90.0, ..Default::default() }] },
            ..Default::default()
        }
        .sanitized();
        assert_eq!(cfg.speed, 1.0);
        assert_eq!(cfg.pitch_semitones, 12.0);
        assert_eq!(cfg.stereo.balance, 100);
        assert_eq!(cfg.stereo.width, 0.0);
        assert_eq!(cfg.eq.preamp_db, 0.0);
        assert_eq!(cfg.eq.bands[0].gain_db, 24.0);
    }
}
