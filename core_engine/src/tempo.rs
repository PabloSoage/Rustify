//! Tempo (BPM) of a song from its PCM — the first piece of on-device music analysis for the 5.x
//! (`16` §3.3), next to the key of [`crate::tonal`].
//!
//! The method is the one Mixxx uses through the Queen Mary DSP library (Davies & Plumbley, *Context-
//! dependent beat tracking of musical audio*, 2007), re-implemented here from the paper's ideas, not
//! from its code:
//!
//! 1. **Onset strength.** Every [`HOP_SECS`] (11.6 ms, as Mixxx), the log-magnitude spectrum of the
//!    last ~23 ms is compared with the previous one and the increases are summed (spectral flux). A
//!    drum hit or a new chord is a jump; a held note is not. The FFT is [`crate::dsp`]'s, so it runs on
//!    the fastest kernel the CPU has.
//! 2. **Periodicity.** The autocorrelation of that curve peaks at the beat period and its multiples.
//!    Each candidate period is scored by a comb over its first four multiples, so a period that also
//!    explains the bar wins over one that matches a single lag by chance.
//! 3. **A prior on tempo.** Scores are weighted by a Rayleigh curve that peaks at 120 BPM, as in the
//!    paper: between a period and its double — the octave ambiguity every tempo tracker has — the one
//!    closer to how people tap wins. The search runs from [`MIN_BPM`] to [`MAX_BPM`].
//!
//! The answer carries a confidence: how far the winning score stands above the average one.
//!
//! **Measured** on the ISMIR 2004 Ballroom set (698 excerpts, `examples/tempo_eval.rs`): Acc1 63.2 %,
//! Acc2 88.3 %, 146 ms per 30 s excerpt on the PC — level with the 2004 contest winner (63.2 / 90.9).
//! Most misses are octave errors (quickstep and jive read at half speed) and waltzes. A separate
//! triple-metre comb was tried and dropped: Acc1 64.2 % but Acc2 84.5 %, waltzes worse (`19` §2).

use crate::dsp::{self, Kernel};

pub const HOP_SECS: f32 = 0.01161;
pub const MIN_BPM: f32 = 60.0;
pub const MAX_BPM: f32 = 240.0;
/// Where the Rayleigh prior peaks.
const PRIOR_BPM: f32 = 120.0;
/// Multiples of the period summed by the comb.
const COMB: usize = 4;
/// Highest frequency in the onset curve: above it is mostly cymbal noise.
const MAX_HZ: f32 = 8_000.0;

/// What [`Tempo::estimate`] answers.
#[derive(Debug, Clone, Copy, PartialEq, serde::Serialize)]
pub struct Estimate {
    pub bpm: f32,
    /// The winning score over the mean score: around 1 is no beat at all; a clear beat is well above 2.
    pub confidence: f32,
}

/// Fed with mono PCM as it is decoded, then asked once.
pub struct Tempo {
    rate: u32,
    hop: usize,
    window: usize,
    pending: Vec<f32>,
    previous: Vec<f32>,
    onsets: Vec<f32>,
    kernel: Kernel,
}

impl Tempo {
    pub fn new(sample_rate: u32) -> Tempo {
        let rate = sample_rate.max(8_000);
        let hop = ((rate as f32 * HOP_SECS).round() as usize).max(1);
        // ~23 ms: long enough for a 50 Hz bin, as Mixxx sizes it.
        let window = ((rate / 50) as usize).next_power_of_two().max(64);
        Tempo { rate, hop, window, pending: Vec::new(), previous: Vec::new(), onsets: Vec::new(), kernel: Kernel::best() }
    }

    /// Adds mono samples in -1..1.
    pub fn push(&mut self, samples: &[f32]) {
        self.pending.extend_from_slice(samples);
        while self.pending.len() >= self.window {
            let spectrum = dsp::power_spectrum(self.kernel, &self.pending[..self.window]);
            let bins = ((MAX_HZ / (self.rate as f32 / self.window as f32)) as usize).clamp(1, spectrum.len());
            let current: Vec<f32> = spectrum[..bins].iter().map(|p| (1.0 + 1_000.0 * p.sqrt()).ln()).collect();
            let flux = if self.previous.len() == current.len() {
                current.iter().zip(&self.previous).map(|(c, p)| (c - p).max(0.0)).sum()
            } else {
                0.0
            };
            self.onsets.push(flux);
            self.previous = current;
            self.pending.drain(..self.hop);
        }
    }

    /// Seconds of audio analysed so far.
    pub fn seconds(&self) -> f32 {
        self.onsets.len() as f32 * self.hop as f32 / self.rate as f32
    }

    /// The tempo, or `None` with under 6 s of audio (too few beats to measure a period).
    pub fn estimate(&self) -> Option<Estimate> {
        let fps = self.rate as f32 / self.hop as f32;
        if self.seconds() < 6.0 {
            return None;
        }
        let df = normalise(&self.onsets);
        let max_lag = ((60.0 / MIN_BPM) * fps * COMB as f32).ceil() as usize + 2;
        let acf = autocorrelation(&df, max_lag.min(df.len().saturating_sub(1)));
        let prior_lag = 60.0 / PRIOR_BPM * fps;

        let score = |bpm: f32| -> f32 {
            let lag = 60.0 / bpm * fps;
            let comb: f32 = (1..=COMB).map(|k| interpolate(&acf, lag * k as f32) / k as f32).sum();
            comb * rayleigh(lag, prior_lag)
        };
        // 0.1 BPM steps, then a finer look around the winner.
        let mut best = (MIN_BPM, f32::MIN);
        let mut total = 0.0;
        let mut count = 0;
        let mut bpm = MIN_BPM;
        while bpm <= MAX_BPM {
            let s = score(bpm);
            total += s;
            count += 1;
            if s > best.1 {
                best = (bpm, s);
            }
            bpm += 0.1;
        }
        let mean = total / count as f32;
        let mut fine = best;
        let mut b = best.0 - 0.1;
        while b <= best.0 + 0.1 {
            let s = score(b);
            if s > fine.1 {
                fine = (b, s);
            }
            b += 0.01;
        }
        if !(mean > 0.0) || !fine.1.is_finite() {
            return None;
        }
        Some(Estimate { bpm: (fine.0 * 10.0).round() / 10.0, confidence: fine.1 / mean })
    }
}

/// The onset curve with its local mean taken away and the negative half dropped, so a loud
/// passage does not outweigh a quiet one with the same beat.
fn normalise(onsets: &[f32]) -> Vec<f32> {
    const HALF: usize = 8;
    (0..onsets.len())
        .map(|i| {
            let lo = i.saturating_sub(HALF);
            let hi = (i + HALF + 1).min(onsets.len());
            let mean = onsets[lo..hi].iter().sum::<f32>() / (hi - lo) as f32;
            (onsets[i] - mean).max(0.0)
        })
        .collect()
}

/// Unbiased autocorrelation for lags `0..=max_lag`.
fn autocorrelation(x: &[f32], max_lag: usize) -> Vec<f32> {
    (0..=max_lag)
        .map(|lag| {
            let n = x.len() - lag;
            if n == 0 {
                return 0.0;
            }
            x[..n].iter().zip(&x[lag..]).map(|(a, b)| a * b).sum::<f32>() / n as f32
        })
        .collect()
}

/// `acf` at a fractional lag, linearly; 0 past the end.
fn interpolate(acf: &[f32], lag: f32) -> f32 {
    let i = lag.floor() as usize;
    if i + 1 >= acf.len() {
        return 0.0;
    }
    let t = lag - i as f32;
    acf[i] * (1.0 - t) + acf[i + 1] * t
}

/// Rayleigh weighting with its mode at `mode` (in frames).
fn rayleigh(lag: f32, mode: f32) -> f32 {
    lag / (mode * mode) * (-(lag * lag) / (2.0 * mode * mode)).exp()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A drum pattern: a kick on every beat, a quieter hi-hat on every off-beat, a little noise.
    fn pattern(bpm: f32, seconds: f32, rate: u32) -> Vec<f32> {
        let n = (seconds * rate as f32) as usize;
        let beat = 60.0 / bpm * rate as f32;
        let mut seed = 12345u32;
        let mut noise = move || {
            seed = seed.wrapping_mul(1_103_515_245).wrapping_add(12345);
            (seed >> 16) as f32 / 32_768.0 - 1.0
        };
        let mut out = vec![0f32; n];
        for (i, s) in out.iter_mut().enumerate() {
            let phase = i as f32 % beat;
            let kick = if phase < 0.03 * rate as f32 {
                let t = phase / rate as f32;
                (2.0 * std::f32::consts::PI * 60.0 * t).sin() * (-t * 80.0).exp()
            } else {
                0.0
            };
            let off = (i as f32 + beat / 2.0) % beat;
            let hat = if off < 0.01 * rate as f32 { noise() * 0.3 * (-(off / rate as f32) * 300.0).exp() } else { 0.0 };
            *s = 0.8 * kick + hat + noise() * 0.01;
        }
        out
    }

    fn estimate(bpm: f32) -> Estimate {
        let rate = 44_100;
        let mut t = Tempo::new(rate);
        // Fed in odd-sized chunks, as a decoder hands them over.
        for chunk in pattern(bpm, 30.0, rate).chunks(3_001) {
            t.push(chunk);
        }
        t.estimate().expect("30 s is enough")
    }

    #[test]
    fn a_drum_pattern_is_measured_within_half_a_bpm() {
        for bpm in [90.0, 120.0, 128.0, 140.0, 174.0] {
            let e = estimate(bpm);
            assert!((e.bpm - bpm).abs() <= 0.5, "{bpm} BPM read as {} (confidence {})", e.bpm, e.confidence);
            assert!(e.confidence > 2.0, "{bpm}: confidence {}", e.confidence);
        }
    }

    #[test]
    fn noise_has_no_confident_beat_and_short_audio_has_no_answer() {
        let rate = 44_100;
        let mut seed = 7u32;
        let noise: Vec<f32> = (0..rate * 20)
            .map(|_| {
                seed = seed.wrapping_mul(1_103_515_245).wrapping_add(12345);
                ((seed >> 16) as f32 / 32_768.0 - 1.0) * 0.3
            })
            .collect();
        let mut t = Tempo::new(rate);
        t.push(&noise);
        assert!(t.estimate().unwrap().confidence < 1.5);

        let mut short = Tempo::new(rate);
        short.push(&pattern(120.0, 4.0, rate));
        assert_eq!(short.estimate(), None);
    }
}
