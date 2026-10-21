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
