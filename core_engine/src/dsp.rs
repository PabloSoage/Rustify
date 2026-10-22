//! Spectrum analysis for the audio lab (4.7.1): Hann window, radix-2 FFT, power per log-spaced band,
//! in dB — over the PCM the player is actually sending to the speaker.
//!
//! One arm64 build, several kernels, chosen at runtime by what the CPU reports:
//!
//! | kernel        | needs            | what it is                                                  |
//! |---------------|------------------|-------------------------------------------------------------|
//! | `baseline`    | —                | Rust, armv8.0; NEON where the compiler finds it             |
//! | `v82`         | fp16+dotprod+rdm | the same Rust compiled for armv8.2 (`#[target_feature]`)    |
//! | `sve2`        | sve2             | the same Rust compiled for armv9                            |
//! | `neon_hand`   | (aarch64)        | NEON intrinsics in C, four butterflies per instruction       |
//! | `sve2_hand`   | sve2             | SVE2 intrinsics in C, vector-length agnostic, predicated     |
//! | `ssve`        | sme              | the SVE2 kernel in SME streaming mode (armv9.2): vectors as  |
//! |               |                  | wide as the SME unit's, which can be wider than SVE's        |
//!
//! The C kernels are in `native/fft_kernels.c`, each function with its own `target` attribute, so
//! nothing outside them uses the newer instructions, and none is called unless the CPU said it has
//! the extension. Every kernel computes the same thing; the tests compare them.
//!
//! [`Kernel::best`] does not trust the architecture: on first use it times every kernel this CPU
//! can run and keeps the fastest. A wider instruction set is only "better" when it is faster here.

use std::cell::RefCell;
use std::sync::OnceLock;

/// Which compiled variant of the FFT runs.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kernel {
    Baseline,
    V82,
    Sve2,
    NeonHand,
    Sve2Hand,
    Ssve,
}

impl Kernel {
    pub fn name(self) -> &'static str {
        match self {
            Kernel::Baseline => "armv8.0 NEON (compiler)",
            Kernel::V82 => "armv8.2 (compiler)",
            Kernel::Sve2 => "armv9 SVE2 (compiler)",
            Kernel::NeonHand => "armv8.0 NEON (hand)",
            Kernel::Sve2Hand => "armv9 SVE2 (hand)",
            Kernel::Ssve => "armv9.2 SME streaming",
        }
    }

    pub fn id(self) -> &'static str {
        match self {
            Kernel::Baseline => "baseline",
            Kernel::V82 => "v82",
            Kernel::Sve2 => "sve2",
            Kernel::NeonHand => "neon_hand",
            Kernel::Sve2Hand => "sve2_hand",
            Kernel::Ssve => "ssve",
        }
    }

    /// The kernels this CPU can run.
    pub fn available() -> Vec<Kernel> {
        #[allow(unused_mut)] // pushed to on aarch64 only
        let mut v = vec![Kernel::Baseline];
        #[cfg(target_arch = "aarch64")]
        {
            v.push(Kernel::NeonHand);
            if std::arch::is_aarch64_feature_detected!("fp16")
                && std::arch::is_aarch64_feature_detected!("dotprod")
                && std::arch::is_aarch64_feature_detected!("rdm")
            {
                v.push(Kernel::V82);
            }
            if std::arch::is_aarch64_feature_detected!("sve2") {
                v.push(Kernel::Sve2);
                v.push(Kernel::Sve2Hand);
            }
            if has_sme() {
                v.push(Kernel::Ssve);
            }
        }
        v
    }

    /// The fastest kernel on this CPU, measured once per process (a few milliseconds).
    pub fn best() -> Kernel {
        static BEST: OnceLock<Kernel> = OnceLock::new();
        *BEST.get_or_init(|| bench(2048, 60).into_iter().min_by_key(|(_, ns)| *ns).map(|(k, _)| k).unwrap_or(Kernel::Baseline))
    }
}

/// SME, read from the kernel's HWCAP2 bits: `is_aarch64_feature_detected!("sme")` is not stable yet.
#[cfg(target_arch = "aarch64")]
pub(crate) fn has_sme() -> bool {
    extern "C" {
        fn getauxval(kind: std::os::raw::c_ulong) -> std::os::raw::c_ulong;
    }
    const AT_HWCAP2: std::os::raw::c_ulong = 26;
    const HWCAP2_SME: std::os::raw::c_ulong = 1 << 23;
    // SAFETY: getauxval only reads the process's auxiliary vector.
    unsafe { getauxval(AT_HWCAP2) & HWCAP2_SME != 0 }
}

#[cfg(target_arch = "aarch64")]
extern "C" {
    fn rf_fft_neon(re: *mut f32, im: *mut f32, n: usize, twr: *const f32, twi: *const f32);
    fn rf_fft_sve2(re: *mut f32, im: *mut f32, n: usize, twr: *const f32, twi: *const f32);
    fn rf_fft_ssve(re: *mut f32, im: *mut f32, n: usize, twr: *const f32, twi: *const f32);
    fn rf_sve_vl_bytes() -> usize;
    fn rf_sme_svl_bytes() -> usize;
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
        probe!("neon", "lse", "rdm", "fp16", "dotprod", "rcpc", "fcma", "sha3", "fhm", "i8mm", "bf16", "sve", "sve2");
        if has_sme() {
            out.push("sme");
        }
    }
    out
}

/// Vector lengths in bits: SVE's and the SME unit's (streaming), when the CPU has them.
pub fn vector_lengths() -> (Option<usize>, Option<usize>) {
    #[cfg(target_arch = "aarch64")]
    {
        let sve = std::arch::is_aarch64_feature_detected!("sve")
            // SAFETY: only called when the CPU has SVE.
            .then(|| unsafe { rf_sve_vl_bytes() } * 8);
        // SAFETY: only called when the CPU has SME.
        let sme = has_sme().then(|| unsafe { rf_sme_svl_bytes() } * 8);
        (sve, sme)
    }
    #[cfg(not(target_arch = "aarch64"))]
    (None, None)
}

/// What the lab draws for one frame.
#[derive(Debug, Clone, PartialEq)]
pub struct Analysis {
    /// Power per band in dB (0 dB = full scale sine), `bands` values, low to high frequency.
    pub bands_db: Vec<f32>,
    pub rms: f32,
    pub peak: f32,
}

/// Analyses `samples` (mono, -1..1; length a power of two, at least 64) with `kernel`. A kernel the
/// CPU cannot run falls back to the baseline instead of faulting.
pub fn analyse(kernel: Kernel, samples: &[f32], sample_rate: u32, bands: usize) -> Analysis {
    let kernel = if Kernel::available().contains(&kernel) { kernel } else { Kernel::Baseline };
    analyse_impl(samples, sample_rate, bands, kernel)
}

/// Runs the FFT stages with `kernel` over data already windowed and in bit-reversed order.
fn run_fft(kernel: Kernel, re: &mut [f32], im: &mut [f32], t: &Tables) {
    match kernel {
        #[cfg(target_arch = "aarch64")]
        // SAFETY: `analyse` only lets through kernels `available()` saw the features for.
        Kernel::V82 => unsafe { fft_v82(re, im, &t.twr, &t.twi) },
        #[cfg(target_arch = "aarch64")]
        // SAFETY: as above.
        Kernel::Sve2 => unsafe { fft_sve2(re, im, &t.twr, &t.twi) },
        #[cfg(target_arch = "aarch64")]
        // SAFETY: the C kernels read and write exactly `n` floats of each array and `n - 1` twiddles.
        Kernel::NeonHand => unsafe { rf_fft_neon(re.as_mut_ptr(), im.as_mut_ptr(), re.len(), t.twr.as_ptr(), t.twi.as_ptr()) },
        #[cfg(target_arch = "aarch64")]
        // SAFETY: as above, and the CPU has SVE2.
        Kernel::Sve2Hand => unsafe { rf_fft_sve2(re.as_mut_ptr(), im.as_mut_ptr(), re.len(), t.twr.as_ptr(), t.twi.as_ptr()) },
        #[cfg(target_arch = "aarch64")]
        // SAFETY: as above, and the CPU has SME.
        Kernel::Ssve => unsafe { rf_fft_ssve(re.as_mut_ptr(), im.as_mut_ptr(), re.len(), t.twr.as_ptr(), t.twi.as_ptr()) },
        _ => fft_in_place(re, im, &t.twr, &t.twi),
    }
}

#[cfg(target_arch = "aarch64")]
#[target_feature(enable = "fp16,dotprod,rdm")]
unsafe fn fft_v82(re: &mut [f32], im: &mut [f32], twr: &[f32], twi: &[f32]) {
    fft_in_place(re, im, twr, twi)
}

#[cfg(target_arch = "aarch64")]
#[target_feature(enable = "sve2")]
unsafe fn fft_sve2(re: &mut [f32], im: &mut [f32], twr: &[f32], twi: &[f32]) {
    fft_in_place(re, im, twr, twi)
}

thread_local! {
    /// Twiddles, Hann window and bit-reversal per FFT size, built once per thread.
    static TABLES: RefCell<Option<Tables>> = const { RefCell::new(None) };
}

struct Tables {
    n: usize,
    /// Twiddles stage by stage, contiguous: the stage of half-size `h` at offset `h - 1`, entry `j`
    /// = e^(-2πi·j / 2h). Contiguous so every kernel reads them with plain vector loads.
    twr: Vec<f32>,
    twi: Vec<f32>,
    window: Vec<f32>,
    bitrev: Vec<u32>,
}

impl Tables {
    fn new(n: usize) -> Tables {
        let tau = std::f64::consts::TAU;
        let mut twr = Vec::with_capacity(n - 1);
        let mut twi = Vec::with_capacity(n - 1);
        let mut half = 1;
        while half < n {
            for j in 0..half {
                let a = tau * j as f64 / (2 * half) as f64;
                twr.push(a.cos() as f32);
                twi.push(-a.sin() as f32);
            }
            half *= 2;
        }
        let window = (0..n).map(|i| (0.5 - 0.5 * (tau * i as f64 / (n - 1) as f64).cos()) as f32).collect();
        let bits = n.trailing_zeros();
        let bitrev = (0..n as u32).map(|i| i.reverse_bits() >> (32 - bits)).collect();
        Tables { n, twr, twi, window, bitrev }
    }
}

fn analyse_impl(samples: &[f32], sample_rate: u32, bands: usize, kernel: Kernel) -> Analysis {
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

    let power = power_spectrum(kernel, samples);
    let half = n / 2;

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
}

/// Power per FFT bin (`n / 2` values) of a Hann-windowed frame, normalised so a full-scale sine
/// reads 1.0 (0 dB) in its bin. `samples.len()` is a power of two ≥ 64 and `kernel` one the CPU has.
pub(crate) fn power_spectrum(kernel: Kernel, samples: &[f32]) -> Vec<f32> {
    let n = samples.len();
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
        run_fft(kernel, &mut re, &mut im, tables);

        // Normalised so a full-scale sine reads 0 dB (Hann's coherent gain is 0.5).
        let half = n / 2;
        let scale = 4.0 / (n as f32 * n as f32 * 0.25);
        let mut power = vec![0f32; half];
        for k in 0..half {
            power[k] = (re[k] * re[k] + im[k] * im[k]) * scale;
        }
        power
    })
}

/// Iterative radix-2 Cooley–Tukey over data already in bit-reversed order, twiddles per stage.
#[inline(always)]
fn fft_in_place(re: &mut [f32], im: &mut [f32], twr: &[f32], twi: &[f32]) {
    let n = re.len();
    let mut half = 1;
    while half < n {
        let wr = &twr[half - 1..2 * half - 1];
        let wi = &twi[half - 1..2 * half - 1];
        let size = half * 2;
        let mut start = 0;
        while start < n {
            // A straight run over `half` butterflies with contiguous twiddles: what vectorises.
            // Zipped iterators, not indices: indexing six slices left a bounds check per element,
            // which kept the loop scalar (measured 19× slower than the hand-written NEON on a phone).
            let (lo_r, hi_r) = re[start..start + size].split_at_mut(half);
            let (lo_i, hi_i) = im[start..start + size].split_at_mut(half);
            let lanes = lo_r.iter_mut().zip(lo_i.iter_mut()).zip(hi_r.iter_mut().zip(hi_i.iter_mut()));
            for (((lr, li), (hr, hi)), (w_r, w_i)) in lanes.zip(wr.iter().zip(wi)) {
                let tr = *hr * *w_r - *hi * *w_i;
                let ti = *hr * *w_i + *hi * *w_r;
                *hr = *lr - tr;
                *hi = *li - ti;
                *lr += tr;
                *li += ti;
            }
            start += size;
        }
        half *= 2;
    }
}

/// `10·log10(x)` in place, floored at -120 dB, branch-free so it vectorises: log2 from the float's
/// exponent plus a series on the mantissa (error under 0.001 dB).
#[inline(always)]
pub(crate) fn to_db(values: &mut [f32]) {
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

/// Nanoseconds per FFT of an `n`-point frame, for each kernel this CPU has — the lab's "measure"
/// button, and how [`Kernel::best`] chooses. Times the FFT alone: the window, the bands and the dB
/// are the same code for every kernel and would only dilute the difference.
pub fn bench(n: usize, iterations: usize) -> Vec<(Kernel, u64)> {
    let n = n.max(64).next_power_of_two();
    let tables = Tables::new(n);
    let signal: Vec<f32> = (0..n)
        .map(|i| {
            let t = i as f32 / 48_000.0;
            0.5 * (std::f32::consts::TAU * 440.0 * t).sin() + 0.25 * (std::f32::consts::TAU * 3_000.0 * t).sin()
        })
        .collect();
    let mut start_re = vec![0f32; n];
    for i in 0..n {
        start_re[tables.bitrev[i] as usize] = signal[i] * tables.window[i];
    }
    Kernel::available()
        .into_iter()
        .map(|k| {
            let mut re = start_re.clone();
            let mut im = vec![0f32; n];
            run_fft(k, &mut re, &mut im, &tables); // warm caches and, for SME, the unit
            let t = std::time::Instant::now();
            for _ in 0..iterations.max(1) {
                re.copy_from_slice(&start_re);
                im.iter_mut().for_each(|x| *x = 0.0);
                run_fft(k, std::hint::black_box(&mut re), &mut im, &tables);
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
        let a = analyse(Kernel::Baseline, &[0f32; 1024], 44_100, 32);
        assert!(a.bands_db.iter().all(|&d| d <= -119.0));
        assert_eq!(a.peak, 0.0);
    }

    #[test]
    fn every_kernel_this_cpu_has_agrees_with_the_baseline() {
        // On the phone this runs the hand-written NEON, SVE2 and SME kernels too (see perf.rs).
        for n in [64, 256, 2048, 8192] {
            let x = sine(440.0, n, 44_100.0, 0.3);
            let base = analyse(Kernel::Baseline, &x, 44_100, 48);
            for k in Kernel::available() {
                let a = analyse(k, &x, 44_100, 48);
                for (p, q) in base.bands_db.iter().zip(&a.bands_db) {
                    assert!((p - q).abs() < 0.05, "{k:?} n={n}: {p} vs {q}");
                }
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
        // Off aarch64 (CI, the emulator) no ARM kernel exists; asking for one must not fault.
        let x = sine(440.0, 1024, 44_100.0, 0.5);
        let b = analyse(Kernel::Baseline, &x, 44_100, 16);
        for k in [Kernel::Ssve, Kernel::Sve2Hand, Kernel::NeonHand] {
            let a = analyse(k, &x, 44_100, 16);
            for (p, q) in a.bands_db.iter().zip(&b.bands_db) {
                assert!((p - q).abs() < 0.05);
            }
        }
    }

    #[test]
    fn the_best_kernel_is_one_this_cpu_has() {
        assert!(Kernel::available().contains(&Kernel::best()));
    }
}
