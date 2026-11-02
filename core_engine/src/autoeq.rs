//! AutoEq per output device (5.x §5, `21`): the headphone-correction curves of Jaakko Pasanen's
//! AutoEq (MIT), about 8 800 measured profiles, applied on Android through `DynamicsProcessing`.
//!
//! AutoEq publishes each profile as a parametric EQ (`… ParametricEQ.txt`): a pre-amp and up to ten
//! peaking and shelving biquads. `DynamicsProcessing` takes something else — a bank of bands, each a
//! cut-off and a gain — so the curve is evaluated here, with the RBJ Audio-EQ-Cookbook formulas, at
//! the centre of each band. Pure arithmetic, so it is tested exactly; the profile files and the index
//! of names come from the AutoEq repository through `Env`.

use crate::env::{Env, EnvError, HttpRequest};
use crate::search::fold;
use serde::Serialize;
use std::f64::consts::PI;

pub const RAW: &str = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results/";
const TIMEOUT_MS: u64 = 20_000;
/// The rate the response is evaluated at; Android's mixer runs at 48 kHz.
const FS: f64 = 48_000.0;

#[derive(Debug, Clone, Copy, PartialEq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum Kind {
    Peak,
    LowShelf,
    HighShelf,
}

#[derive(Debug, Clone, Copy, PartialEq, Serialize)]
pub struct Filter {
    pub kind: Kind,
    pub fc: f64,
    pub gain_db: f64,
    pub q: f64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Default)]
pub struct Peq {
    pub preamp_db: f64,
    pub filters: Vec<Filter>,
}

/// `Preamp: -6.1 dB` and `Filter 1: ON PK Fc 105 Hz Gain -4.2 dB Q 0.70` lines; OFF filters skipped.
pub fn parse(text: &str) -> Result<Peq, String> {
    let mut peq = Peq::default();
    for line in text.lines().map(str::trim).filter(|l| !l.is_empty()) {
        let words: Vec<&str> = line.split_whitespace().collect();
        if words.first() == Some(&"Preamp:") {
            peq.preamp_db = words.get(1).and_then(|v| v.parse().ok()).ok_or(format!("bad pre-amp: {line}"))?;
            continue;
        }
        if !words.first().is_some_and(|w| w.starts_with("Filter")) || !words.contains(&"ON") {
            continue;
        }
        let after = |key: &str| words.iter().position(|w| *w == key).and_then(|i| words.get(i + 1)).and_then(|v| v.parse::<f64>().ok());
        let kind = if words.contains(&"PK") || words.contains(&"PEQ") {
            Kind::Peak
        } else if words.contains(&"LSC") || words.contains(&"LS") {
            Kind::LowShelf
        } else if words.contains(&"HSC") || words.contains(&"HS") {
            Kind::HighShelf
        } else {
            return Err(format!("unknown filter: {line}"));
        };
        let (Some(fc), Some(gain_db)) = (after("Fc"), after("Gain")) else {
            return Err(format!("bad filter: {line}"));
        };
        peq.filters.push(Filter { kind, fc, gain_db, q: after("Q").unwrap_or(0.707) });
    }
    if peq.filters.is_empty() {
        return Err("no filters".into());
    }
    Ok(peq)
}

/// RBJ cookbook coefficients `[b0, b1, b2, a0, a1, a2]`.
fn coefficients(f: &Filter) -> [f64; 6] {
    let a = 10f64.powf(f.gain_db / 40.0);
    let w0 = 2.0 * PI * f.fc / FS;
    let (sin, cos) = w0.sin_cos();
    let alpha = sin / (2.0 * f.q);
    let sa = 2.0 * a.sqrt() * alpha;
    match f.kind {
        Kind::Peak => [1.0 + alpha * a, -2.0 * cos, 1.0 - alpha * a, 1.0 + alpha / a, -2.0 * cos, 1.0 - alpha / a],
        Kind::LowShelf => [
            a * ((a + 1.0) - (a - 1.0) * cos + sa),
            2.0 * a * ((a - 1.0) - (a + 1.0) * cos),
            a * ((a + 1.0) - (a - 1.0) * cos - sa),
            (a + 1.0) + (a - 1.0) * cos + sa,
            -2.0 * ((a - 1.0) + (a + 1.0) * cos),
            (a + 1.0) + (a - 1.0) * cos - sa,
        ],
        Kind::HighShelf => [
            a * ((a + 1.0) + (a - 1.0) * cos + sa),
            -2.0 * a * ((a - 1.0) + (a + 1.0) * cos),
            a * ((a + 1.0) + (a - 1.0) * cos - sa),
            (a + 1.0) - (a - 1.0) * cos + sa,
            2.0 * ((a - 1.0) - (a + 1.0) * cos),
            (a + 1.0) - (a - 1.0) * cos - sa,
        ],
    }
}

/// The gain of one biquad at `hz`, in dB.
fn filter_db(f: &Filter, hz: f64) -> f64 {
    let [b0, b1, b2, a0, a1, a2] = coefficients(f);
    let w = 2.0 * PI * hz / FS;
    // |b0 + b1 z^-1 + b2 z^-2| over the same for a, at z = e^{jw}.
    let mag = |c0: f64, c1: f64, c2: f64| {
        let re = c0 + c1 * w.cos() + c2 * (2.0 * w).cos();
        let im = -(c1 * w.sin() + c2 * (2.0 * w).sin());
        (re * re + im * im).sqrt()
    };
    20.0 * (mag(b0, b1, b2) / mag(a0, a1, a2)).log10()
}

/// The whole curve (pre-amp included) at `hz`, in dB.
pub fn response_db(peq: &Peq, hz: f64) -> f64 {
    peq.preamp_db + peq.filters.iter().map(|f| filter_db(f, hz)).sum::<f64>()
}

#[derive(Debug, Clone, Copy, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Band {
    /// Upper edge of the band, which is what `DynamicsProcessing.EqBand` takes.
    pub cutoff_hz: f32,
    pub gain_db: f32,
}

/// `n` log-spaced bands from 20 Hz to 20 kHz, each with the curve's gain at its geometric centre.
pub fn bands(peq: &Peq, n: usize) -> Vec<Band> {
    let n = n.max(2);
    let (lo, hi) = (20f64, 20_000f64);
    let step = (hi / lo).ln() / n as f64;
    (0..n)
        .map(|i| {
            let low = lo * (step * i as f64).exp();
            let high = lo * (step * (i + 1) as f64).exp();
            Band { cutoff_hz: high as f32, gain_db: response_db(peq, (low * high).sqrt()) as f32 }
        })
        .collect()
}

/// One line of AutoEq's `INDEX.md`.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct Entry {
    pub name: String,
    /// Relative to [`RAW`], URL-encoded as the index writes it: `oratory1990/over-ear/Sony%20WH-1000XM4`.
    pub path: String,
    /// Who measured it, and on what rig when given: "oratory1990", "crinacle on GRAS 43AG-7".
    pub source: String,
}

/// Index entries whose name holds every word of `query` (folded), oratory1990 first: his
/// measurements are the reference AutoEq itself recommends.
pub fn search(index: &str, query: &str, limit: usize) -> Vec<Entry> {
    let words: Vec<String> = query.split_whitespace().map(fold).collect();
    if words.is_empty() {
        return Vec::new();
    }
    let mut out: Vec<Entry> = index
        .lines()
        .filter_map(|line| {
            let rest = line.strip_prefix("- [")?;
            let (name, rest) = rest.split_once("](./")?;
            let (path, rest) = rest.split_once(')')?;
            let source = rest.trim().strip_prefix("by ").unwrap_or(rest.trim()).to_string();
            Some(Entry { name: name.to_string(), path: path.to_string(), source })
        })
        .filter(|e| {
            let name = fold(&e.name);
            words.iter().all(|w| name.contains(w.as_str()))
        })
        .collect();
    out.sort_by_key(|e| (!e.source.starts_with("oratory1990"), e.name.len()));
    out.truncate(limit);
    out
}

async fn get<E: Env>(url: String) -> Result<String, EnvError> {
    let r = E::fetch(HttpRequest::get(url.clone()).timeout_ms(TIMEOUT_MS)).await?;
    if !r.is_success() {
        return Err(EnvError::Fetch(format!("{url}: HTTP {}", r.status)));
    }
    Ok(r.text())
}

/// The index of every profile (~850 kB of Markdown).
pub async fn fetch_index<E: Env>() -> Result<String, EnvError> {
    get::<E>(format!("{RAW}INDEX.md")).await
}

/// The parametric EQ of the profile at `path` (an [`Entry::path`]), checked by parsing it.
pub async fn fetch_profile<E: Env>(path: &str) -> Result<String, EnvError> {
    let name = path.rsplit('/').next().unwrap_or(path);
    let text = get::<E>(format!("{RAW}{path}/{name}%20ParametricEQ.txt")).await?;
    parse(&text).map_err(EnvError::Fetch)?;
    Ok(text)
}

pub const INDEX_KEY: &str = "autoeq_index";
const INDEX_MAX_AGE_MS: i64 = 30 * 86_400_000;

#[derive(serde::Serialize, serde::Deserialize)]
struct CachedIndex {
    fetched_at: i64,
    text: String,
}

/// [`search`] over the index kept in storage, fetched again when missing or a month old. A failed
/// refresh falls back to the copy already kept.
pub async fn search_online<E: Env>(query: &str, limit: usize) -> Result<Vec<Entry>, EnvError> {
    let kept: Option<CachedIndex> = crate::env::storage::get_json::<E, _>(INDEX_KEY).await.unwrap_or(None);
    let fresh = kept.as_ref().is_some_and(|k| E::now_ms() - k.fetched_at < INDEX_MAX_AGE_MS);
    let text = if fresh {
        kept.map(|k| k.text).unwrap_or_default()
    } else {
        match fetch_index::<E>().await {
            Ok(text) => {
                let entry = CachedIndex { fetched_at: E::now_ms(), text };
                crate::env::storage::set_json::<E, _>(INDEX_KEY, Some(&entry)).await?;
                entry.text
            }
            Err(e) => kept.map(|k| k.text).ok_or(e)?,
        }
    };
    Ok(search(&text, query, limit))
}

#[cfg(test)]
mod tests {
    use super::*;

    const XM4: &str = "Preamp: -6.1 dB\n\
        Filter 1: ON LSC Fc 105 Hz Gain -4.2 dB Q 0.70\n\
        Filter 2: ON PK Fc 143 Hz Gain -5.2 dB Q 1.10\n\
        Filter 3: ON PK Fc 2289 Hz Gain 6.1 dB Q 1.57\n\
        Filter 4: OFF PK Fc 56 Hz Gain 1.2 dB Q 1.19\n\
        Filter 6: ON HSC Fc 10000 Hz Gain -1.0 dB Q 0.70\n";

    #[test]
    fn a_profile_is_read_with_its_off_filters_skipped() {
        let peq = parse(XM4).unwrap();
        assert_eq!(peq.preamp_db, -6.1);
        assert_eq!(peq.filters.len(), 4);
        assert_eq!(peq.filters[0], Filter { kind: Kind::LowShelf, fc: 105.0, gain_db: -4.2, q: 0.70 });
        assert_eq!(peq.filters[3].kind, Kind::HighShelf);
        assert!(parse("nonsense").is_err());
    }

    #[test]
    fn a_peak_has_its_gain_at_its_centre_and_none_far_away() {
        let f = Filter { kind: Kind::Peak, fc: 1_000.0, gain_db: 6.0, q: 1.0 };
        assert!((filter_db(&f, 1_000.0) - 6.0).abs() < 1e-6);
        assert!(filter_db(&f, 30.0).abs() < 0.05);
        assert!(filter_db(&f, 18_000.0).abs() < 0.1);
    }

    #[test]
    fn shelves_reach_their_gain_on_their_side_and_half_at_the_corner() {
        let low = Filter { kind: Kind::LowShelf, fc: 100.0, gain_db: -6.0, q: 0.707 };
        assert!((filter_db(&low, 20.0) + 6.0).abs() < 0.2);
        assert!((filter_db(&low, 100.0) + 3.0).abs() < 0.05, "{}", filter_db(&low, 100.0));
        assert!(filter_db(&low, 5_000.0).abs() < 0.05);
        let high = Filter { kind: Kind::HighShelf, fc: 8_000.0, gain_db: 4.0, q: 0.707 };
        assert!((filter_db(&high, 20_000.0) - 4.0).abs() < 0.3);
        assert!(filter_db(&high, 200.0).abs() < 0.05);
    }

    #[test]
    fn bands_carry_the_whole_curve_with_the_preamp() {
        let peq = parse(XM4).unwrap();
        let b = bands(&peq, 32);
        assert_eq!(b.len(), 32);
        assert!((b[31].cutoff_hz - 20_000.0).abs() < 1.0);
        // Near 2.3 kHz the +6.1 dB peak, less the 6.1 dB pre-amp: about 0 dB.
        let near = b.iter().min_by(|x, y| (x.cutoff_hz - 2_600.0).abs().total_cmp(&(y.cutoff_hz - 2_600.0).abs())).unwrap();
        assert!(near.gain_db.abs() < 1.5, "{near:?}");
        assert!(b.iter().all(|x| x.gain_db < 1.0), "the pre-amp keeps every band from boosting into clipping");
    }

    #[test]
    fn the_index_is_searched_by_every_word_with_oratory1990_first() {
        let index = "# Index\n\
            - [Sony WH-1000XM4](./crinacle/GRAS%2043AG-7%20over-ear/Sony%20WH-1000XM4) by crinacle on GRAS 43AG-7\n\
            - [Sony WH-1000XM4](./oratory1990/over-ear/Sony%20WH-1000XM4) by oratory1990\n\
            - [Sony WH-1000XM3](./oratory1990/over-ear/Sony%20WH-1000XM3) by oratory1990\n";
        let found = search(index, "sony xm4", 10);
        assert_eq!(found.len(), 2);
        assert_eq!(found[0].path, "oratory1990/over-ear/Sony%20WH-1000XM4");
        assert_eq!(found[1].source, "crinacle on GRAS 43AG-7");
        assert!(search(index, "", 10).is_empty());
    }

    #[tokio::test]
    async fn the_index_is_kept_and_a_failed_refresh_uses_the_kept_copy() {
        use crate::env::mock::{self, MockEnv};
        let _guard = mock::lock_and_reset();
        mock::on_url(
            format!("{RAW}INDEX.md"),
            crate::env::HttpResponse::ok("- [Sennheiser HD 600](./oratory1990/over-ear/Sennheiser%20HD%20600) by oratory1990\n"),
        );
        assert_eq!(search_online::<MockEnv>("hd 600", 5).await.unwrap().len(), 1);
        let fetched = mock::state().requests.len();
        assert_eq!(search_online::<MockEnv>("hd 600", 5).await.unwrap().len(), 1);
        assert_eq!(mock::state().requests.len(), fetched, "fresh copy: no second download");

        // A month later, with the network down: the old copy still answers.
        mock::state().now_ms += 31 * 86_400_000;
        mock::state().responses.clear();
        assert_eq!(search_online::<MockEnv>("hd 600", 5).await.unwrap().len(), 1);
    }
}
