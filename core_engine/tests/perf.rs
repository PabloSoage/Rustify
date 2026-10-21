// core_engine/tests/perf.rs
//
// Timings for the two paths that spend real CPU, to decide the release profile's `opt-level` with a
// number rather than a guess (debt C8). Ignored by default — they measure, they do not assert — and
// run with:
//
//   CARGO_PROFILE_BENCH_OPT_LEVEL=z cargo test --release --test perf -- --ignored --nocapture
//   CARGO_PROFILE_BENCH_OPT_LEVEL=3 cargo test --release --test perf -- --ignored --nocapture
//
// (`cargo test --release` builds with the `bench` profile, which inherits `release`.)

use std::time::Instant;

#[test]
#[ignore]
fn deezer_decrypt_throughput() {
    let key = [7u8; 16];
    let mut buf = vec![0x5au8; 64 * 1024 * 1024];
    let t = Instant::now();
    core_engine::audio::deezer::decrypt_aligned(&key, &mut buf, 0);
    let s = t.elapsed().as_secs_f64();
    println!("deezer decrypt: 64 MiB in {:.3} s = {:.1} MiB/s", s, 64.0 / s);
}

#[test]
#[ignore]
fn search_index_and_query() {
    let items: Vec<(String, Vec<String>)> = (0..20_000)
        .map(|i| {
            (
                format!("id{i}"),
                vec![format!("Canción número {i} — Björk"), format!("Álbum {} de prueba", i % 300)],
            )
        })
        .collect();
    let t = Instant::now();
    let index = core_engine::search::Index::build(items);
    let built = t.elapsed().as_secs_f64();
    let t = Instant::now();
    let mut hits = 0;
    for q in ["bjork", "cancion 1999", "album 42", "numero", "zzz"].iter().cycle().take(200) {
        hits += index.query(q, 50).len();
    }
    let queried = t.elapsed().as_secs_f64();
    println!("search: build 20k in {:.3} s; 200 queries in {:.3} s ({:.2} ms each); {hits} hits", built, queried, queried * 1000.0 / 200.0);
}

#[test]
#[ignore]
fn json_parse_throughput() {
    // What the core does all day: parse API responses. 5 000 track objects, the shape of a long
    // playlist page, parsed 20 times.
    let tracks: Vec<serde_json::Value> = (0..5_000)
        .map(|i| {
            serde_json::json!({
                "id": format!("{i:022}"), "name": format!("Canción {i} (feat. Björk)"),
                "duration_ms": 180_000 + i, "explicit": i % 7 == 0, "popularity": i % 100,
                "artists": [{"id": "a1", "name": "Artista"}, {"id": "a2", "name": "Otra"}],
                "album": {"id": format!("al{i}"), "name": "Álbum", "images": [
                    {"url": "https://i.scdn.co/image/ab67616d0000b273abcdef", "width": 640, "height": 640}]}
            })
        })
        .collect();
    let text = serde_json::to_string(&serde_json::json!({ "items": tracks })).unwrap();
    let t = Instant::now();
    let mut n = 0;
    for _ in 0..20 {
        let v: serde_json::Value = serde_json::from_str(&text).unwrap();
        n += v["items"].as_array().unwrap().len();
    }
    let s = t.elapsed().as_secs_f64();
    let mib = text.len() as f64 * 20.0 / (1024.0 * 1024.0);
    println!("json parse: {:.1} MiB in {:.3} s = {:.1} MiB/s ({n} items)", mib, s, mib / s);
}
