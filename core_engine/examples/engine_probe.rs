//! Times what the desktop engine does to start a YouTube track: resolve, open the HTTP source,
//! probe the container, first decoded block, and how fast blocks then arrive.
//!
//! ```text
//! cargo run --example engine_probe --features engine -- fJ9rUzIMcZQ
//! ```

use core_engine::engine::{decode::Decoder, source};
use core_engine::env::android::AndroidEnv;
use core_engine::youtube::innertube::{self, AudioPrefs};
use std::time::Instant;

fn main() {
    let video = std::env::args()
        .nth(1)
        .unwrap_or_else(|| "fJ9rUzIMcZQ".into());
    let prefer = std::env::args().nth(2).unwrap_or_else(|| "aac".into());
    let t = Instant::now();
    let prefs = AudioPrefs {
        prefer,
        max_kbps: 0,
    };
    let audio = tokio::runtime::Runtime::new()
        .expect("runtime")
        .block_on(innertube::resolve_audio::<AndroidEnv>(&video, &prefs))
        .expect("resolve");
    println!(
        "resolved in {} ms: itag {} {} length {:?}",
        t.elapsed().as_millis(),
        audio.itag,
        audio.mime_type,
        audio.content_length
    );

    let t = Instant::now();
    let (src, hint) = source::open::<AndroidEnv>(&audio.url).expect("open");
    println!(
        "source open in {} ms (hint {:?})",
        t.elapsed().as_millis(),
        hint
    );

    let t = Instant::now();
    let mut decoder = Decoder::open(src, hint.as_deref()).expect("probe");
    println!(
        "probed in {} ms: {} Hz, {} ch, {:?} ms",
        t.elapsed().as_millis(),
        decoder.sample_rate(),
        decoder.channels(),
        decoder.duration_ms()
    );

    let t = Instant::now();
    let mut frames = 0u64;
    let mut first = None;
    while t.elapsed().as_secs() < 10 {
        match decoder.next_block() {
            Ok(Some(b)) => {
                first.get_or_insert(t.elapsed().as_millis());
                frames += (b.samples.len() / b.channels.max(1)) as u64;
            }
            Ok(None) => break,
            Err(e) => {
                println!("decode error: {e}");
                break;
            }
        }
    }
    let audio_s = frames as f64 / decoder.sample_rate().max(1) as f64;
    println!(
        "first block after {:?} ms; {:.1} s of audio decoded in {:.1} s ({:.1}x real time)",
        first,
        audio_s,
        t.elapsed().as_secs_f64(),
        audio_s / t.elapsed().as_secs_f64()
    );
}
