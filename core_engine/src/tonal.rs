//! Key and mel analysis for the audio lab (4.7.1): a batch of spectra times a filterbank.
//!
//! Every frame's spectrum (one column of B, `k` bins) is multiplied by a fixed filterbank (A, one
//! row per output): 12 chroma rows — how much of each pitch class C, C♯ … B there is — and 52 mel
//! bands for the lab's mel spectrogram. Batched [`FRAMES`] frames at a time, that is a 64×k by k×16
//! matrix product: the one shape in Rustify the matrix extensions were designed for.
//!
//! | kernel      | needs      | what it is                                                       |
//! |-------------|------------|------------------------------------------------------------------|
//! | `baseline`  | —          | Rust, vectorised by the compiler                                 |
//! | `neon`      | (aarch64)  | NEON FMLA by lane, armv8.0                                       |
//! | `bf16`      | bf16       | BFMMLA, armv8.6: a 2×4 by 4×2 bf16 product per instruction        |
//! | `sme`       | sme        | FMOPA, armv9.2: an outer product into a ZA tile per instruction  |
//! | `sme_bf16`  | sme        | BFMOPA, armv9.2: the same over bf16 pairs, twice the work each    |
//!
//! bf16 keeps 8 bits of mantissa: plenty to sum spectral energy into 64 bands (the tests allow 1%),
//! not enough for the FFT itself. As with the FFT, [`MatKernel::best`] measures and keeps the
//! fastest on this CPU.
//!
//! The key is the Krumhansl–Kessler profile (24 major and minor keys) that best correlates with the
//! chroma averaged over the last several seconds.

use crate::dsp::{self, Kernel};
use std::sync::OnceLock;

/// Frames per matrix product.
pub const FRAMES: usize = 16;
/// Chroma rows (pitch classes, C first).
pub const CHROMA: usize = 12;
/// Mel bands.
pub const MEL: usize = 52;
const ROWS: usize = CHROMA + MEL;

/// Which compiled matrix product runs.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MatKernel {
    Baseline,
    Neon,
    Bf16,
    Sme,
    SmeBf16,
}

impl MatKernel {
    pub fn name(self) -> &'static str {
        match self {
            MatKernel::Baseline => "armv8.0 (compiler)",
            MatKernel::Neon => "armv8.0 NEON FMLA (hand)",
            MatKernel::Bf16 => "armv8.6 BF16 BFMMLA",
            MatKernel::Sme => "armv9.2 SME FMOPA",
            MatKernel::SmeBf16 => "armv9.2 SME BFMOPA (bf16)",
        }
    }

    pub fn id(self) -> &'static str {
        match self {
            MatKernel::Baseline => "baseline",
            MatKernel::Neon => "neon",
            MatKernel::Bf16 => "bf16",
            MatKernel::Sme => "sme",
            MatKernel::SmeBf16 => "sme_bf16",
        }
    }

    /// The kernels this CPU can run.
    pub fn available() -> Vec<MatKernel> {
        #[allow(unused_mut)] // pushed to on aarch64 only
        let mut v = vec![MatKernel::Baseline];
        #[cfg(target_arch = "aarch64")]
        {
            v.push(MatKernel::Neon);
            if std::arch::is_aarch64_feature_detected!("bf16") {
                v.push(MatKernel::Bf16);
            }
            if dsp::has_sme() {
                v.push(MatKernel::Sme);
                v.push(MatKernel::SmeBf16);
            }
        }
        v
    }

    /// The fastest kernel on this CPU, measured once per process.
    pub fn best() -> MatKernel {
        static BEST: OnceLock<MatKernel> = OnceLock::new();
        *BEST.get_or_init(|| {
            bench(2048, 20)
                .into_iter()
                .min_by_key(|(_, ns)| *ns)
                .map(|(k, _)| k)
                .unwrap_or(MatKernel::Baseline)
        })
    }
}

#[cfg(target_arch = "aarch64")]
extern "C" {
    fn rf_mm_neon(at: *const f32, b: *const f32, c: *mut f32, m: usize, n: usize, k: usize);
    fn rf_mm_bf16(ap: *const u16, bp: *const u16, c: *mut f32, m: usize, n: usize, k: usize);
    fn rf_mm_sme(at: *const f32, b: *const f32, c: *mut f32, m: usize, n: usize, k: usize);
    fn rf_mm_sme_bf16(ap: *const u16, bp: *const u16, c: *mut f32, m: usize, n: usize, k: usize);
}

/// fp32 → bf16, rounding to nearest even (the values here are finite and non-negative).
fn bf16(x: f32) -> u16 {
    let b = x.to_bits();
    ((b + 0x7fff + ((b >> 16) & 1)) >> 16) as u16
}

/// A row-major `rows × k` matrix packed once for every kernel's layout.
#[cfg_attr(not(target_arch = "aarch64"), allow(dead_code))] // the bf16 layouts are read on ARM only
pub struct Packed {
    m: usize,
    k: usize,
    /// Aᵀ, k×m: column p of A contiguous (fp32 kernels).
    at: Vec<f32>,
    /// BFMMLA panels: per row pair, per 4 k, row 2i's four values then row 2i+1's.
    panels: Vec<u16>,
    /// BFMOPA pairs: `[p·2m + 2i + e] = A[i][2p + e]`.
    pairs: Vec<u16>,
}

impl Packed {
    /// `a` is row-major `m × k`; m a multiple of 4, k a multiple of 4.
    pub fn new(a: &[f32], m: usize, k: usize) -> Packed {
        assert!(m.is_multiple_of(4) && k.is_multiple_of(4) && a.len() == m * k);
        let at = (0..k * m).map(|x| a[(x % m) * k + x / m]).collect();
        Packed {
            m,
            k,
            at,
            panels: mmla_panels(m, k, |i, p| a[i * k + p]),
            pairs: mopa_pairs(m, k, |i, p| a[i * k + p]),
        }
    }
}

/// BFMMLA panels of an `rows × k` operand given by `get(row, p)`.
fn mmla_panels(rows: usize, k: usize, get: impl Fn(usize, usize) -> f32) -> Vec<u16> {
    let mut out = Vec::with_capacity(rows * k);
    for pair in 0..rows / 2 {
        for q in 0..k / 4 {
            for r in 0..2 {
                for e in 0..4 {
                    out.push(bf16(get(2 * pair + r, 4 * q + e)));
                }
            }
        }
    }
    out
}

/// BFMOPA pairs of an `rows × k` operand given by `get(row, p)`.
fn mopa_pairs(rows: usize, k: usize, get: impl Fn(usize, usize) -> f32) -> Vec<u16> {
    let mut out = Vec::with_capacity(rows * k);
    for q in 0..k / 2 {
        for i in 0..rows {
            out.push(bf16(get(i, 2 * q)));
            out.push(bf16(get(i, 2 * q + 1)));
        }
    }
    out
}

/// C = A·B with `kernel`: A packed, B row-major `k × n` (n a multiple of 16); C row-major `m × n`.
/// The bf16 kernels pack B themselves, and that is part of what they cost.
pub fn matmul(kernel: MatKernel, a: &Packed, b: &[f32], n: usize) -> Vec<f32> {
    let (m, k) = (a.m, a.k);
    assert!(n.is_multiple_of(16) && b.len() == k * n);
    let kernel = if MatKernel::available().contains(&kernel) {
        kernel
    } else {
        MatKernel::Baseline
    };
    let mut c = vec![0f32; m * n];
    match kernel {
        #[cfg(target_arch = "aarch64")]
        // SAFETY: shapes asserted above; each kernel reads m·k + k·n values and writes m·n.
        MatKernel::Neon => unsafe { rf_mm_neon(a.at.as_ptr(), b.as_ptr(), c.as_mut_ptr(), m, n, k) },
        #[cfg(target_arch = "aarch64")]
        // SAFETY: as above, and the CPU has BF16.
        MatKernel::Bf16 => {
            let bp = mmla_panels(n, k, |j, p| b[p * n + j]);
            unsafe { rf_mm_bf16(a.panels.as_ptr(), bp.as_ptr(), c.as_mut_ptr(), m, n, k) }
        }
        #[cfg(target_arch = "aarch64")]
        // SAFETY: as above, and the CPU has SME.
        MatKernel::Sme => unsafe { rf_mm_sme(a.at.as_ptr(), b.as_ptr(), c.as_mut_ptr(), m, n, k) },
        #[cfg(target_arch = "aarch64")]
        // SAFETY: as above, and the CPU has SME.
        MatKernel::SmeBf16 => {
            let bp = mopa_pairs(n, k, |j, p| b[p * n + j]);
            unsafe { rf_mm_sme_bf16(a.pairs.as_ptr(), bp.as_ptr(), c.as_mut_ptr(), m, n, k) }
        }
        _ => {
            // Row of B times a scalar of Aᵀ into a row of C: contiguous inner loop, vectorised.
            for p in 0..k {
                let brow = &b[p * n..(p + 1) * n];
                let acol = &a.at[p * m..(p + 1) * m];
                for (i, &av) in acol.iter().enumerate() {
                    for (cv, &bv) in c[i * n..(i + 1) * n].iter_mut().zip(brow) {
                        *cv += av * bv;
                    }
                }
            }
        }
    }
    c
}

/// Nanoseconds per 64×k by k×16 product for each kernel this CPU has (k = bins of an `fft`-point
/// frame), packing of B included.
pub fn bench(fft: usize, iterations: usize) -> Vec<(MatKernel, u64)> {
    let k = (fft / 2).max(4);
    let a = Packed::new(
        &(0..ROWS * k).map(|x| ((x * 7919) % 1000) as f32 / 1000.0).collect::<Vec<_>>(),
        ROWS,
        k,
    );
    let b: Vec<f32> = (0..k * FRAMES).map(|x| ((x * 104_729) % 997) as f32 / 997.0).collect();
    MatKernel::available()
        .into_iter()
        .map(|kern| {
            std::hint::black_box(matmul(kern, &a, &b, FRAMES)); // warm caches and, for SME, the unit
            let t = std::time::Instant::now();
            for _ in 0..iterations.max(1) {
                std::hint::black_box(matmul(kern, &a, std::hint::black_box(&b), FRAMES));
            }
            (kern, t.elapsed().as_nanos() as u64 / iterations.max(1) as u64)
        })
        .collect()
}

/// The filterbank for `k` bins of an FFT at `sample_rate`: 12 chroma rows, then 52 mel rows.
fn filterbank(k: usize, sample_rate: u32) -> Vec<f32> {
    let nyquist = sample_rate as f32 / 2.0;
    let bin_hz = nyquist / k as f32;
    let mut a = vec![0f32; ROWS * k];

    // Chroma: each bin between 150 Hz and 2.5 kHz goes to its nearest pitch class, weighted by how
    // close it is to the semitone (1 on it, 0 halfway to the next). Below 150 Hz a bin is wider
    // than a semitone and cannot tell them apart.
    for p in 1..k {
        let f = p as f32 * bin_hz;
        if !(150.0..=2_500.0).contains(&f) {
            continue;
        }
        let midi = 69.0 + 12.0 * (f / 440.0).log2();
        let nearest = midi.round();
        let w = 1.0 - 2.0 * (midi - nearest).abs();
        a[(nearest as i32).rem_euclid(12) as usize * k + p] = w;
    }

    // Mel: triangles evenly spaced on the mel scale from 40 Hz to 16 kHz (or Nyquist), each
    // normalised to sum 1, so a band reads the mean magnitude under it.
    let mel = |f: f32| 2595.0 * (1.0 + f / 700.0).log10();
    let hz = |m: f32| 700.0 * (10f32.powf(m / 2595.0) - 1.0);
    let (lo, hi) = (mel(40.0), mel(16_000f32.min(nyquist)));
    let edges: Vec<f32> = (0..MEL + 2).map(|i| hz(lo + (hi - lo) * i as f32 / (MEL + 1) as f32)).collect();
    for b in 0..MEL {
        let (f0, f1, f2) = (edges[b], edges[b + 1], edges[b + 2]);
        let row = &mut a[(CHROMA + b) * k..(CHROMA + b + 1) * k];
        let mut sum = 0.0;
        for (p, w) in row.iter_mut().enumerate() {
            let f = p as f32 * bin_hz;
            *w = if f > f0 && f < f1 {
                (f - f0) / (f1 - f0)
            } else if f >= f1 && f < f2 {
                (f2 - f) / (f2 - f1)
            } else {
                0.0
            };
            sum += *w;
        }
        if sum == 0.0 {
            // Narrower than a bin (low bands with a short FFT): the bin under its centre.
            row[((f1 / bin_hz).round() as usize).min(k - 1)] = 1.0;
        } else {
            row.iter_mut().for_each(|w| *w /= sum);
        }
    }
    a
}

const MAJOR: [f32; 12] = [6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88];
const MINOR: [f32; 12] = [6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17];

/// The best-matching key for a chroma vector: 0–11 major on C…B, 12–23 minor, and the correlation.
pub fn key_of(chroma: &[f32; 12]) -> Option<(usize, f32)> {
    let mean = chroma.iter().sum::<f32>() / 12.0;
    if mean <= 0.0 {
        return None;
    }
    let pearson = |profile: &[f32; 12], tonic: usize| {
        let pm = profile.iter().sum::<f32>() / 12.0;
        let (mut num, mut da, mut db) = (0.0, 0.0, 0.0);
        for (pc, &c) in chroma.iter().enumerate() {
            let x = c - mean;
            let y = profile[(pc + 12 - tonic) % 12] - pm;
            num += x * y;
            da += x * x;
            db += y * y;
        }
        if da == 0.0 {
            0.0
        } else {
            num / (da * db).sqrt()
        }
    };
    (0..24)
        .map(|key| {
            (
                key,
                if key < 12 {
                    pearson(&MAJOR, key)
                } else {
                    pearson(&MINOR, key - 12)
                },
            )
        })
        .max_by(|a, b| a.1.total_cmp(&b.1))
}

/// What one pushed frame produced.
#[derive(Debug, Clone, PartialEq)]
pub struct Update {
    /// Mel dB columns completed by this frame: none, or [`FRAMES`] when a batch was multiplied.
    pub mel_db: Vec<[f32; MEL]>,
    /// Chroma averaged over the last several seconds, the loudest class = 1.
    pub chroma: [f32; 12],
    pub key: Option<(usize, f32)>,
}

/// Frames in, a matrix product every [`FRAMES`] of them.
pub struct Tonal {
    n: usize,
    rate: u32,
    a: Packed,
    /// k × FRAMES: frame j's magnitudes in column j.
    b: Vec<f32>,
    filled: usize,
    chroma: [f32; 12],
}

impl Tonal {
    /// For `n`-sample frames (a power of two ≥ 64) at `sample_rate`.
    pub fn new(n: usize, sample_rate: u32) -> Tonal {
        let k = n / 2;
        Tonal {
            n,
            rate: sample_rate,
            a: Packed::new(&filterbank(k, sample_rate), ROWS, k),
            b: vec![0.0; k * FRAMES],
            filled: 0,
            chroma: [0.0; 12],
        }
    }

    pub fn fits(&self, n: usize, sample_rate: u32) -> bool {
        self.n == n && self.rate == sample_rate
    }

    pub fn push(&mut self, samples: &[f32], fft: Kernel, mat: MatKernel) -> Update {
        let fft = if Kernel::available().contains(&fft) {
            fft
        } else {
            Kernel::Baseline
        };
        let power = dsp::power_spectrum(fft, samples);
        let j = self.filled;
        for (p, &pw) in power.iter().enumerate() {
            self.b[p * FRAMES + j] = pw.sqrt();
        }
        self.filled += 1;
        let mut mel_db = Vec::new();
        if self.filled == FRAMES {
            self.filled = 0;
            let c = matmul(mat, &self.a, &self.b, FRAMES);
            for j in 0..FRAMES {
                // Each frame's chroma counts once, loudest class = 1; silence counts not at all.
                let frame: Vec<f32> = (0..CHROMA).map(|r| c[r * FRAMES + j]).collect();
                let top = frame.iter().cloned().fold(0.0, f32::max);
                // About an 8-second memory at 30 frames a second.
                self.chroma.iter_mut().for_each(|x| *x *= 0.996);
                if top > 1e-4 {
                    for (acc, v) in self.chroma.iter_mut().zip(&frame) {
                        *acc += v / top;
                    }
                }
                let mut col = [0f32; MEL];
                for b in 0..MEL {
                    let v = c[(CHROMA + b) * FRAMES + j];
                    col[b] = v * v;
                }
                dsp::to_db(&mut col);
                mel_db.push(col);
            }
        }
        let top = self.chroma.iter().cloned().fold(0.0, f32::max);
        let chroma = if top > 0.0 { self.chroma.map(|x| x / top) } else { [0.0; 12] };
        Update {
            mel_db,
            chroma,
            key: key_of(&self.chroma),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn lcg(seed: &mut u64) -> f32 {
        *seed = seed.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
        ((*seed >> 40) as f32) / (1u64 << 24) as f32
    }

    #[test]
    fn every_kernel_this_cpu_has_matches_the_exact_product() {
        // On the phone this runs NEON, BF16 and both SME kernels too (see perf.rs).
        let mut s = 7;
        for (m, k, n) in [(64, 2048, 16), (16, 64, 32), (8, 4, 16)] {
            let a: Vec<f32> = (0..m * k).map(|_| lcg(&mut s)).collect();
            let b: Vec<f32> = (0..k * n).map(|_| lcg(&mut s)).collect();
            let packed = Packed::new(&a, m, k);
            for kern in MatKernel::available() {
                let c = matmul(kern, &packed, &b, n);
                // bf16 keeps 8 bits of mantissa: 1% of the sum of magnitudes; fp32, far less.
                let tol = if matches!(kern, MatKernel::Bf16 | MatKernel::SmeBf16) {
                    1e-2
                } else {
                    1e-4
                };
                for i in 0..m {
                    for j in 0..n {
                        let (mut exact, mut mag) = (0f64, 0f64);
                        for p in 0..k {
                            let t = a[i * k + p] as f64 * b[p * n + j] as f64;
                            exact += t;
                            mag += t.abs();
                        }
                        let got = c[i * n + j] as f64;
                        assert!(
                            (got - exact).abs() <= tol * mag + 1e-6,
                            "{kern:?} {m}×{k}×{n} [{i},{j}]: {got} vs {exact}"
                        );
                    }
                }
            }
        }
    }

    fn chord(freqs: &[f32], n: usize, rate: f32, offset: usize) -> Vec<f32> {
        (0..n)
            .map(|i| {
                let t = (i + offset) as f32 / rate;
                freqs
                    .iter()
                    .map(|f| {
                        (1..=3)
                            .map(|h| 0.2 / h as f32 * (std::f32::consts::TAU * f * h as f32 * t).sin())
                            .sum::<f32>()
                    })
                    .sum()
            })
            .collect()
    }

    fn key_after(progression: &[&[f32]]) -> (usize, f32) {
        let (n, rate) = (8192, 48_000);
        let mut tonal = Tonal::new(n, rate);
        let mut last = None;
        for (c, notes) in progression.iter().enumerate() {
            for f in 0..FRAMES {
                last = tonal
                    .push(
                        &chord(notes, n, rate as f32, (c * FRAMES + f) * n),
                        Kernel::best(),
                        MatKernel::best(),
                    )
                    .key;
            }
        }
        last.expect("a key")
    }

    #[test]
    fn i_iv_v_i_in_c_is_c_major() {
        let (c, d, e, f, g, a, b) = (261.63, 293.66, 329.63, 349.23, 392.0, 440.0, 493.88);
        let (key, r) = key_after(&[&[c, e, g], &[f, a, c * 2.0], &[g, b, d * 2.0], &[c, e, g]]);
        assert_eq!(key, 0, "r = {r}");
    }

    #[test]
    fn i_iv_v_i_in_a_minor_is_a_minor() {
        let (a, b, c, d, e, f, gs) = (220.0, 246.94, 261.63, 293.66, 329.63, 349.23, 415.30);
        let (key, r) = key_after(&[&[a, c, e], &[d, f, a * 2.0], &[e, gs, b], &[a, c, e]]);
        assert_eq!(key, 12 + 9, "r = {r}");
    }

    #[test]
    fn a_batch_gives_one_mel_column_per_frame_and_a_sine_lands_in_its_band() {
        let (n, rate) = (4096, 48_000);
        let mut tonal = Tonal::new(n, rate);
        let mut cols = Vec::new();
        for f in 0..FRAMES {
            let u = tonal.push(&chord(&[1_000.0], n, rate as f32, f * n), Kernel::Baseline, MatKernel::Baseline);
            assert_eq!(u.mel_db.len(), if f == FRAMES - 1 { FRAMES } else { 0 });
            cols = u.mel_db;
        }
        let loudest = cols[0].iter().enumerate().max_by(|a, b| a.1.total_cmp(b.1)).unwrap().0;
        // 1 kHz on a 40 Hz–16 kHz mel axis of 52 bands falls around band 15.
        assert!((10..22).contains(&loudest), "loudest mel band {loudest}");
    }

    #[test]
    fn silence_has_no_key() {
        let mut tonal = Tonal::new(1024, 44_100);
        let mut u = None;
        for _ in 0..FRAMES {
            u = Some(tonal.push(&[0.0; 1024], Kernel::Baseline, MatKernel::Baseline));
        }
        assert_eq!(u.unwrap().key, None);
    }
}
