//! Spectrum analysis for the audio lab (4.7.1): Hann window, radix-2 FFT, power per log-spaced band,
//! in dB — over the PCM the player is actually sending to the speaker.
//!
//! One arm64 build, several kernels. The same code is compiled three times for aarch64 — baseline
//! (armv8.0, NEON), armv8.2 (`fp16`, `dotprod`, `rdm`) and armv9 (`sve2`) — through
//! `#[target_feature]`, and [`Kernel::best`] picks the most capable one the CPU reports at runtime
//! (`is_aarch64_feature_detected!`). A phone without SVE2 never runs an SVE2 instruction; a phone
//! with it uses it. Every kernel computes the same thing, and the tests say so.
//!
//! Every loop is written to vectorise: no branches in the hot loops, and dB through a polynomial
//! `log2` on the float's bits instead of a libm call, which the vectoriser cannot widen.

use std::cell::RefCell;

/// Which compiled variant of the analysis runs.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kernel {
    /// Portable code; on aarch64 this is armv8.0 with NEON.
    Baseline,
    /// armv8.2-a: half-precision arithmetic, dot product, rounding multiply.
    V82,
    /// armv9-a: SVE2 (scalable vectors).
    Sve2,
}

impl Kernel {
    pub fn name(self) -> &'static str {
        match self {
            Kernel::Baseline => "armv8.0 (NEON)",
            Kernel::V82 => "armv8.2 (fp16, dotprod)",
            Kernel::Sve2 => "armv9 (SVE2)",
        }
    }

    pub fn id(self) -> &'static str {
        match self {
            Kernel::Baseline => "baseline",
            Kernel::V82 => "v82",
            Kernel::Sve2 => "sve2",
        }
    }

    /// The kernels this CPU can run, least capable first.
    pub fn available() -> Vec<Kernel> {
        #[allow(unused_mut)] // pushed to on aarch64 only
        let mut v = vec![Kernel::Baseline];
        #[cfg(target_arch = "aarch64")]
        {
            if std::arch::is_aarch64_feature_detected!("fp16")
                && std::arch::is_aarch64_feature_detected!("dotprod")
                && std::arch::is_aarch64_feature_detected!("rdm")
            {
                v.push(Kernel::V82);
            }
            if std::arch::is_aarch64_feature_detected!("sve2") {
                v.push(Kernel::Sve2);
            }
        }
        v
    }

    pub fn best() -> Kernel {
        *Kernel::available().last().unwrap_or(&Kernel::Baseline)
    }
}

/// The ARM extensions the CPU reports, by name, for the lab screen. Empty off aarch64.
pub fn cpu_features() -> Vec<&'static str> {
    #[allow(unused_mut)]
    let mut out = Vec::new();
    #[cfg(target_arch = "aarch64")]
    {
        macro_rules! probe {
            ($($f:tt),*) => { $( if std::arch::is_aarch64_feature_detected!($f) { out.push($f); } )* };
        }
        probe!("neon", "lse", "rdm", "fp16", "dotprod", "rcpc", "sha3", "fhm", "i8mm", "bf16", "sve", "sve2");
    }
    out
}

/// What the lab draws for one frame.
#[derive(Debug, Clone, PartialEq)]
pub struct Analysis {
    /// Power per band in dB (0 dB = full scale sine), `bands` values, low to high frequency.
    pub bands_db: Vec<f32>,
    pub rms: f32,
    pub peak: f32,
}

/// Analyses `samples` (mono, -1..1; length a power of two, at least 64) with `kernel`.
pub fn analyse(kernel: Kernel, samples: &[f32], sample_rate: u32, bands: usize) -> Analysis {
    match kernel {
        Kernel::Baseline => analyse_impl(samples, sample_rate, bands),
        #[cfg(target_arch = "aarch64")]
        // SAFETY: only reachable when `available()` saw the features (callers pick from it).
        Kernel::V82 if Kernel::available().contains(&Kernel::V82) => unsafe { analyse_v82(samples, sample_rate, bands) },
        #[cfg(target_arch = "aarch64")]
        Kernel::Sve2 if Kernel::available().contains(&Kernel::Sve2) => unsafe { analyse_sve2(samples, sample_rate, bands) },
        _ => analyse_impl(samples, sample_rate, bands),
    }
}

#[cfg(target_arch = "aarch64")]
#[target_feature(enable = "fp16,dotprod,rdm")]
unsafe fn analyse_v82(samples: &[f32], sample_rate: u32, bands: usize) -> Analysis {
    analyse_impl(samples, sample_rate, bands)
}

#[cfg(target_arch = "aarch64")]
#[target_feature(enable = "sve2")]
unsafe fn analyse_sve2(samples: &[f32], sample_rate: u32, bands: usize) -> Analysis {
    analyse_impl(samples, sample_rate, bands)
}

thread_local! {
    /// Twiddles and Hann window per FFT size, built once per thread.
    static TABLES: RefCell<Option<Tables>> = const { RefCell::new(None) };
}

struct Tables {
    n: usize,
    cos: Vec<f32>,
    sin: Vec<f32>,
    window: Vec<f32>,
    bitrev: Vec<u32>,
}

impl Tables {
    fn new(n: usize) -> Tables {
        let half = n / 2;
        let tau = std::f64::consts::TAU;
        let cos = (0..half).map(|k| (tau * k as f64 / n as f64).cos() as f32).collect();
        let sin = (0..half).map(|k| -(tau * k as f64 / n as f64).sin() as f32).collect();
        let window = (0..n).map(|i| (0.5 - 0.5 * (tau * i as f64 / (n - 1) as f64).cos()) as f32).collect();
        let bits = n.trailing_zeros();
        let bitrev = (0..n as u32).map(|i| i.reverse_bits() >> (32 - bits)).collect();
        Tables { n, cos, sin, window, bitrev }
    }
}

#[inline(always)]
fn analyse_impl(samples: &[f32], sample_rate: u32, bands: usize) -> Analysis {
    let n = samples.len();
    assert!(n >= 64 && n.is_power_of_two(), "FFT size must be a power of two ≥ 64");
    let bands = bands.max(1);

    // Level, before the window.
    let mut sum_sq = 0f32;
    let mut peak = 0f32;
    for &s in samples {
        sum_sq += s * s;
        peak = peak.max(s.abs());
    }
    let rms = (sum_sq / n as f32).sqrt();

    TABLES.with(|t| {
        let mut slot = t.borrow_mut();
        if slot.as_ref().map(|t| t.n) != Some(n) {
            *slot = Some(Tables::new(n));
        }
        let tables = slot.as_ref().expect("built above");

        // Windowed, bit-reversed into place.
        let mut re = vec![0f32; n];
        let mut im = vec![0f32; n];
        for i in 0..n {
            re[tables.bitrev[i] as usize] = samples[i] * tables.window[i];
        }
        fft_in_place(&mut re, &mut im, &tables.cos, &tables.sin);

        // Power per bin, normalised so a full-scale sine reads 0 dB (Hann's coherent gain is 0.5).
        let half = n / 2;
        let scale = 4.0 / (n as f32 * n as f32 * 0.25);
        let mut power = vec![0f32; half];
        for k in 0..half {
            power[k] = (re[k] * re[k] + im[k] * im[k]) * scale;
        }

        // Log-spaced bands from 20 Hz to Nyquist; each band is the mean power of its bins.
        let nyquist = sample_rate as f32 / 2.0;
        let bin_hz = nyquist / half as f32;
        let lo = 20f32.min(nyquist / 2.0);
        let ratio = (nyquist / lo).ln() / bands as f32;
        let mut bands_db = vec![0f32; bands];
        for (b, out) in bands_db.iter_mut().enumerate() {
            let f0 = lo * (ratio * b as f32).exp();
            let f1 = lo * (ratio * (b + 1) as f32).exp();
            let k0 = ((f0 / bin_hz) as usize).clamp(1, half - 1);
            let k1 = ((f1 / bin_hz).ceil() as usize).clamp(k0 + 1, half);
            let mut acc = 0f32;
            for p in &power[k0..k1] {
                acc += *p;
            }
            *out = acc / (k1 - k0) as f32;
        }
        to_db(&mut bands_db);
        Analysis { bands_db, rms, peak }
    })
}

/// Iterative radix-2 Cooley–Tukey over data already in bit-reversed order.
#[inline(always)]
fn fft_in_place(re: &mut [f32], im: &mut [f32], cos: &[f32], sin: &[f32]) {
    let n = re.len();
    let mut size = 2;
    while size <= n {
        let half = size / 2;
        let step = n / size;
        let mut start = 0;
        while start < n {
            // The inner loop is a straight run over `half` butterflies: what the vectoriser widens.
            for j in 0..half {
                let wr = cos[j * step];
                let wi = sin[j * step];
                let a = start + j;
                let b = a + half;
                let tr = re[b] * wr - im[b] * wi;
                let ti = re[b] * wi + im[b] * wr;
                re[b] = re[a] - tr;
                im[b] = im[a] - ti;
                re[a] += tr;
                im[a] += ti;
            }
            start += size;
        }
        size *= 2;
    }
}

/// `10·log10(x)` in place, floored at -120 dB, branch-free so it vectorises: log2 from the float's
/// exponent plus a series on the mantissa (error under 0.001 dB).
#[inline(always)]
fn to_db(values: &mut [f32]) {
    const DB_PER_LOG2: f32 = 10.0 * std::f32::consts::LOG10_2;
    for v in values.iter_mut() {
        let x = v.max(1e-12);
        let bits = x.to_bits();
        let exp = ((bits >> 23) & 0xff) as i32 - 127;
        let m = f32::from_bits((bits & 0x007f_ffff) | 0x3f80_0000); // mantissa in [1, 2)
        // log2(m) = 2·atanh(s)/ln 2 with s = (m-1)/(m+1) ∈ [0, 1/3): four odd terms leave an
        // error under 1e-5, and a division vectorises where a libm call does not.
        let s = (m - 1.0) / (m + 1.0);
        let s2 = s * s;
        let log2m = 2.885_39 * s * (1.0 + s2 * (1.0 / 3.0 + s2 * (0.2 + s2 * (1.0 / 7.0))));
        *v = ((exp as f32 + log2m) * DB_PER_LOG2).max(-120.0);
    }
}

/// Nanoseconds per analysis of an `n`-sample frame, for each kernel this CPU has — the lab's
/// "measure" button. A fixed test signal, so the kernels do the same work.
pub fn bench(n: usize, iterations: usize) -> Vec<(Kernel, u64)> {
    let signal: Vec<f32> = (0..n)
        .map(|i| {
            let t = i as f32 / 48_000.0;
            0.5 * (std::f32::consts::TAU * 440.0 * t).sin() + 0.25 * (std::f32::consts::TAU * 3_000.0 * t).sin()
        })
        .collect();
    Kernel::available()
        .into_iter()
        .map(|k| {
            // Warm the tables and caches first.
            let _ = analyse(k, &signal, 48_000, 64);
            let t = std::time::Instant::now();
            for _ in 0..iterations.max(1) {
                std::hint::black_box(analyse(k, std::hint::black_box(&signal), 48_000, 64));
            }
            (k, t.elapsed().as_nanos() as u64 / iterations.max(1) as u64)
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sine(freq: f32, n: usize, rate: f32, amp: f32) -> Vec<f32> {
        (0..n).map(|i| amp * (std::f32::consts::TAU * freq * i as f32 / rate).sin()).collect()
    }

    #[test]
    fn a_full_scale_sine_reads_about_zero_db_in_its_band() {
        let a = analyse(Kernel::Baseline, &sine(1_000.0, 4096, 48_000.0, 1.0), 48_000, 64);
        let (loudest, db) = a.bands_db.iter().enumerate().fold((0, f32::MIN), |m, (i, &d)| if d > m.1 { (i, d) } else { m });
        assert!(db > -9.0 && db < 1.0, "peak band {db} dB");
        // 1 kHz on a 20 Hz–24 kHz log axis sits a little past the middle.
        assert!((30..45).contains(&loudest), "loudest band {loudest}");
        assert!((a.peak - 1.0).abs() < 0.01);
        assert!((a.rms - 0.707).abs() < 0.01);
    }

    #[test]
    fn silence_is_the_floor() {
        let a = analyse(Kernel::Baseline, &vec![0f32; 1024], 44_100, 32);
        assert!(a.bands_db.iter().all(|&d| d <= -119.0));
        assert_eq!(a.peak, 0.0);
    }

    #[test]
    fn every_kernel_this_cpu_has_agrees_with_the_baseline() {
        let x = sine(440.0, 2048, 44_100.0, 0.3);
        let base = analyse(Kernel::Baseline, &x, 44_100, 48);
        for k in Kernel::available() {
            let a = analyse(k, &x, 44_100, 48);
            for (p, q) in base.bands_db.iter().zip(&a.bands_db) {
                assert!((p - q).abs() < 0.05, "{:?}: {p} vs {q}", k);
            }
        }
    }

    #[test]
    fn the_fast_db_matches_libm() {
        for &x in &[1e-9f32, 1e-6, 0.001, 0.02, 0.5, 1.0, 3.7, 1000.0] {
            let mut v = [x];
            to_db(&mut v);
            let exact = 10.0 * x.log10();
            assert!((v[0] - exact).abs() < 0.02, "{x}: {} vs {exact}", v[0]);
        }
    }

    #[test]
    fn a_kernel_this_cpu_lacks_falls_back_instead_of_faulting() {
        // On a CPU without SVE2 (CI's x86_64 among them) this must not execute an SVE2 instruction.
        let x = sine(440.0, 1024, 44_100.0, 0.5);
        let a = analyse(Kernel::Sve2, &x, 44_100, 16);
        let b = analyse(Kernel::Baseline, &x, 44_100, 16);
        for (p, q) in a.bands_db.iter().zip(&b.bands_db) {
            assert!((p - q).abs() < 0.05);
        }
    }
}
