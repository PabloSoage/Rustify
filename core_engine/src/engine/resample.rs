// core_engine/src/engine/resample.rs
//
// Sample-rate conversion and channel mapping between a track and the output device.
//
// A windowed-sinc interpolator with a precomputed table, written here rather than taken from a
// crate: it is ninety lines, it streams (a block in, whatever is ready out, no fixed chunk size), and
// it has no API to drift under us between versions. When the rates match it is not in the path at
// all, so a 44.1 kHz file on a 44.1 kHz device is passed through bit for bit.
//
// Quality target: transparent for listening, not for measurement. 32 taps with a Blackman window
// keep aliasing around -80 dB; the cut-off sits at 95 % of the lower Nyquist so a downsample does
// not fold the top octave back in.

use std::f64::consts::PI;

/// Taps per output sample. Even, so the kernel is centred between two input samples.
const TAPS: usize = 32;
const HALF: isize = (TAPS / 2) as isize;
/// Fractional positions the kernel is tabulated at; between two of them it is interpolated.
const PHASES: usize = 256;

pub struct Resampler {
    in_rate: u32,
    out_rate: u32,
    channels: usize,
    /// Input frames per output frame.
    step: f64,
    /// `(PHASES + 1) × TAPS` weights, each row summing to 1.
    table: Vec<f32>,
    /// Interleaved input not yet fully used. Frame 0 is `HALF - 1` frames before `pos`'s floor.
    pending: Vec<f32>,
    /// Where the next output frame sits, in input frames from the start of `pending`.
    pos: f64,
}

impl Resampler {
    pub fn new(in_rate: u32, out_rate: u32, channels: usize) -> Self {
        let channels = channels.max(1);
        let step = in_rate as f64 / out_rate.max(1) as f64;
        let cutoff = (out_rate as f64 / in_rate.max(1) as f64).min(1.0) * 0.95;
        let mut table = vec![0f32; (PHASES + 1) * TAPS];
        for phase in 0..=PHASES {
            let frac = phase as f64 / PHASES as f64;
            let row = &mut table[phase * TAPS..(phase + 1) * TAPS];
            let mut sum = 0.0f64;
            let mut weights = [0f64; TAPS];
            for (j, w) in weights.iter_mut().enumerate() {
                // Input sample j sits at offset (j - (HALF - 1)) from floor(pos).
                let d = (j as isize - (HALF - 1)) as f64 - frac;
                let x = d * cutoff;
                let sinc = if x.abs() < 1e-9 { 1.0 } else { (PI * x).sin() / (PI * x) };
                // Blackman over [-HALF, HALF].
                let t = (d + HALF as f64) / (2.0 * HALF as f64);
                let window = if (0.0..=1.0).contains(&t) {
                    0.42 - 0.5 * (2.0 * PI * t).cos() + 0.08 * (4.0 * PI * t).cos()
                } else {
                    0.0
                };
                *w = sinc * window;
                sum += *w;
            }
            for (slot, w) in row.iter_mut().zip(weights) {
                *slot = if sum.abs() > 1e-12 { (w / sum) as f32 } else { 0.0 };
            }
        }
        let mut r = Resampler {
            in_rate,
            out_rate,
            channels,
            step,
            table,
            pending: Vec::new(),
            pos: 0.0,
        };
        r.reset();
        r
    }

    pub fn rates(&self) -> (u32, u32) {
        (self.in_rate, self.out_rate)
    }

    pub fn channels(&self) -> usize {
        self.channels
    }

    pub fn is_passthrough(&self) -> bool {
        self.in_rate == self.out_rate
    }

    /// Forgets what came before — after a seek, the old samples must not bleed into the new ones.
    pub fn reset(&mut self) {
        self.pending.clear();
        // Prime with silence so the first output frame has its left half of the kernel.
        self.pending
            .resize((HALF as usize - 1) * self.channels, 0.0);
        self.pos = (HALF - 1) as f64;
    }

    /// Converts `input` (interleaved, `channels` wide) and appends what is ready to `out`.
    pub fn process(&mut self, input: &[f32], out: &mut Vec<f32>) {
        if self.is_passthrough() {
            out.extend_from_slice(input);
            return;
        }
        let ch = self.channels;
        self.pending.extend_from_slice(input);
        let frames = self.pending.len() / ch;
        loop {
            let base = self.pos.floor() as isize;
            // The kernel needs input up to base + HALF.
            if base + HALF >= frames as isize {
                break;
            }
            let frac = self.pos - base as f64;
            let p = frac * PHASES as f64;
            let p0 = (p.floor() as usize).min(PHASES - 1);
            let mix = (p - p0 as f64) as f32;
            let row0 = &self.table[p0 * TAPS..(p0 + 1) * TAPS];
            let row1 = &self.table[(p0 + 1) * TAPS..(p0 + 2) * TAPS];
            let first = base - (HALF - 1);
            for c in 0..ch {
                let mut acc = 0f32;
                for (j, (w0, w1)) in row0.iter().zip(row1).enumerate() {
                    let idx = (first + j as isize) as usize * ch + c;
                    acc += self.pending[idx] * (w0 + (w1 - w0) * mix);
                }
                out.push(acc);
            }
            self.pos += self.step;
        }
        // Drop the frames no future output can reach.
        let keep_from = (self.pos.floor() as isize - (HALF - 1)).max(0) as usize;
        if keep_from > 0 {
            self.pending.drain(..keep_from * ch);
            self.pos -= keep_from as f64;
        }
    }
}

/// Maps interleaved `input` from `from` channels to `to` channels, appending to `out`.
///
/// Mono is copied to every output channel; anything to mono is the average. Otherwise the first
/// `to` channels are kept, which for the orders decoders produce (front left, front right, …) is a
/// stereo fold that drops the centre and surrounds — acceptable for the music this app plays, and
/// stated so nobody mistakes it for a downmix.
pub fn remix(input: &[f32], from: usize, to: usize, out: &mut Vec<f32>) {
    let from = from.max(1);
    let to = to.max(1);
    if from == to {
        out.extend_from_slice(input);
        return;
    }
    for frame in input.chunks_exact(from) {
        if from == 1 {
            out.extend(std::iter::repeat_n(frame[0], to));
        } else if to == 1 {
            out.push(frame.iter().sum::<f32>() / from as f32);
        } else {
            for c in 0..to {
                out.push(frame.get(c).copied().unwrap_or(0.0));
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sine(rate: u32, hz: f64, seconds: f64) -> Vec<f32> {
        let n = (rate as f64 * seconds) as usize;
        (0..n)
            .map(|i| (2.0 * PI * hz * i as f64 / rate as f64).sin() as f32 * 0.5)
            .collect()
    }

    fn rising_zero_crossings(x: &[f32]) -> usize {
        x.windows(2).filter(|w| w[0] < 0.0 && w[1] >= 0.0).count()
    }

    #[test]
    fn equal_rates_pass_through_untouched() {
        let mut r = Resampler::new(48_000, 48_000, 2);
        let input = vec![0.1, -0.2, 0.3, -0.4];
        let mut out = Vec::new();
        r.process(&input, &mut out);
        assert_eq!(out, input);
    }

    #[test]
    fn upsampling_keeps_length_ratio_pitch_and_level() {
        let input = sine(44_100, 1000.0, 1.0);
        let mut r = Resampler::new(44_100, 48_000, 1);
        let mut out = Vec::new();
        // Fed in uneven blocks, as a decoder would.
        for block in input.chunks(1153) {
            r.process(block, &mut out);
        }
        let expected = 48_000.0;
        assert!(
            (out.len() as f64 - expected).abs() < TAPS as f64,
            "{} frames for one second at 48 kHz",
            out.len()
        );
        // 1 kHz stays 1 kHz: a thousand cycles a second, give or take the edges.
        let cycles = rising_zero_crossings(&out);
        assert!((998..=1001).contains(&cycles), "{cycles} cycles");
        let peak = out[1000..out.len() - 1000]
            .iter()
            .fold(0f32, |m, x| m.max(x.abs()));
        assert!((peak - 0.5).abs() < 0.01, "peak {peak}");
    }

    #[test]
    fn downsampling_does_too() {
        let input = sine(96_000, 440.0, 0.5);
        let mut r = Resampler::new(96_000, 44_100, 1);
        let mut out = Vec::new();
        r.process(&input, &mut out);
        assert!((out.len() as f64 - 22_050.0).abs() < TAPS as f64);
        let cycles = rising_zero_crossings(&out);
        assert!((218..=221).contains(&cycles), "{cycles} cycles");
    }

    #[test]
    fn stereo_channels_stay_apart() {
        // Left a sine, right silence: after resampling the right must still be silent.
        let left = sine(44_100, 500.0, 0.2);
        let mut interleaved = Vec::new();
        for x in &left {
            interleaved.push(*x);
            interleaved.push(0.0);
        }
        let mut r = Resampler::new(44_100, 48_000, 2);
        let mut out = Vec::new();
        r.process(&interleaved, &mut out);
        assert!(out.chunks_exact(2).all(|f| f[1].abs() < 1e-6));
        assert!(out.chunks_exact(2).any(|f| f[0].abs() > 0.4));
    }

    #[test]
    fn reset_forgets_the_previous_signal() {
        let mut r = Resampler::new(44_100, 48_000, 1);
        let mut out = Vec::new();
        r.process(&vec![1.0; 4410], &mut out);
        r.reset();
        out.clear();
        r.process(&vec![0.0; 4410], &mut out);
        assert!(out.iter().all(|x| x.abs() < 1e-6), "no ringing from before the reset");
    }

    #[test]
    fn remix_mono_to_stereo_and_back() {
        let mut out = Vec::new();
        remix(&[0.5, -0.5], 1, 2, &mut out);
        assert_eq!(out, [0.5, 0.5, -0.5, -0.5]);
        let mut mono = Vec::new();
        remix(&out, 2, 1, &mut mono);
        assert_eq!(mono, [0.5, -0.5]);
    }

    #[test]
    fn remix_surround_keeps_the_front_pair() {
        let mut out = Vec::new();
        remix(&[0.1, 0.2, 0.3, 0.4, 0.5, 0.6], 6, 2, &mut out);
        assert_eq!(out, [0.1, 0.2]);
    }
}
