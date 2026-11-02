//! Measures `tempo.rs` against an annotated collection (`19` §3), the way the ISMIR 2004 tempo
//! contest scored entries:
//!
//! - **Acc1**: the estimate is within 4 % of the annotated tempo;
//! - **Acc2**: the same, also accepting the octave errors ×2, ×½, ×3 and ×⅓.
//!
//! Input on stdin, one excerpt per line: `path<TAB>bpm`, with `path` a PCM WAV (8, 16, 24 or 32-bit
//! integer, any channel count). The Ballroom set is what it was written for:
//!
//! ```text
//! cargo run --release --example tempo_eval < list.tsv
//! ```

use core_engine::tempo::Tempo;
use std::io::BufRead;

fn read_wav(path: &str) -> Option<(Vec<f32>, u32)> {
    let bytes = std::fs::read(path).ok()?;
    if bytes.len() < 12 || &bytes[0..4] != b"RIFF" || &bytes[8..12] != b"WAVE" {
        return None;
    }
    let (mut channels, mut rate, mut bits) = (0u16, 0u32, 0u16);
    let mut i = 12;
    while i + 8 <= bytes.len() {
        let id = &bytes[i..i + 4];
        let size = u32::from_le_bytes(bytes[i + 4..i + 8].try_into().ok()?) as usize;
        let body = &bytes[i + 8..(i + 8 + size).min(bytes.len())];
        if id == b"fmt " && body.len() >= 16 {
            channels = u16::from_le_bytes([body[2], body[3]]);
            rate = u32::from_le_bytes(body[4..8].try_into().ok()?);
            bits = u16::from_le_bytes([body[14], body[15]]);
        } else if id == b"data" && channels > 0 {
            let width = (bits / 8) as usize;
            let frame = width * channels as usize;
            let mono = body
                .chunks_exact(frame)
                .map(|f| {
                    let sum: f32 = f
                        .chunks_exact(width)
                        .map(|s| match width {
                            1 => (s[0] as f32 - 128.0) / 128.0,
                            2 => i16::from_le_bytes([s[0], s[1]]) as f32 / 32_768.0,
                            3 => (i32::from_le_bytes([0, s[0], s[1], s[2]]) >> 8) as f32 / 8_388_608.0,
                            _ => i32::from_le_bytes([s[0], s[1], s[2], s[3]]) as f32 / 2_147_483_648.0,
                        })
                        .sum();
                    sum / channels as f32
                })
                .collect();
            return Some((mono, rate));
        }
        i += 8 + size + (size & 1);
    }
    None
}

fn within(estimate: f32, truth: f32, factor: f32) -> bool {
    (estimate - truth * factor).abs() <= 0.04 * truth * factor
}

fn main() {
    let (mut n, mut acc1, mut acc2, mut skipped) = (0usize, 0usize, 0usize, 0usize);
    let mut seconds = 0f64;
    let mut per_class: std::collections::BTreeMap<String, (usize, usize, usize)> = Default::default();
    for line in std::io::stdin().lock().lines().map_while(Result::ok) {
        let mut parts = line.split('\t');
        let (Some(path), Some(bpm)) = (parts.next(), parts.next().and_then(|b| b.trim().parse::<f32>().ok())) else {
            continue;
        };
        let Some((pcm, rate)) = read_wav(path) else {
            skipped += 1;
            continue;
        };
        let started = std::time::Instant::now();
        let mut t = Tempo::new(rate);
        t.push(&pcm);
        let estimate = t.estimate();
        seconds += started.elapsed().as_secs_f64();
        n += 1;
        let class = std::path::Path::new(path)
            .parent()
            .and_then(|p| p.file_name())
            .map(|s| s.to_string_lossy().into_owned())
            .unwrap_or_default();
        let entry = per_class.entry(class).or_default();
        entry.0 += 1;
        if let Some(e) = estimate {
            let a1 = within(e.bpm, bpm, 1.0);
            let a2 = a1 || [2.0, 0.5, 3.0, 1.0 / 3.0].iter().any(|&f| within(e.bpm, bpm, f));
            acc1 += a1 as usize;
            acc2 += a2 as usize;
            entry.1 += a1 as usize;
            entry.2 += a2 as usize;
            if std::env::var_os("VERBOSE").is_some() {
                println!("{path}\t{bpm}\t{:.1}\t{:.1}\t{}", e.bpm, e.confidence, if a1 { "ok" } else if a2 { "octave" } else { "wrong" });
            }
        }
    }
    let pct = |k: usize, n: usize| 100.0 * k as f64 / n.max(1) as f64;
    for (class, (c, a1, a2)) in &per_class {
        println!("{class:<20} n={c:<4} acc1={:5.1}%  acc2={:5.1}%", pct(*a1, *c), pct(*a2, *c));
    }
    println!(
        "TOTAL n={n} skipped={skipped}  acc1={:.1}%  acc2={:.1}%  analysis {:.1} ms/excerpt",
        pct(acc1, n),
        pct(acc2, n),
        1000.0 * seconds / n.max(1) as f64
    );
}
