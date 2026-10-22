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
//
// Measure on the phone, not the PC: build for arm64 with `cargo ndk -t arm64-v8a test --release
// --test perf --no-run`, push the binary to /data/local/tmp and run it pinned to the big cores
// (`taskset c0 ./perf-… --ignored --nocapture`). Run variants interleaved: a locked phone changes
// clock speed between runs. That is how 4.7.1 moved the profile from "z" to "s".

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

#[test]
#[ignore]
fn fft_kernels_on_this_cpu() {
    // The audio lab's kernels, timed on the device (adb shell). Prints the CPU's vector lengths too.
    let (sve, sme) = core_engine::dsp::vector_lengths();
    println!("fft: features {:?}; SVE {:?} bits, SME {:?} bits", core_engine::dsp::cpu_features(), sve, sme);
    for n in [1024, 4096, 16384] {
        for (k, ns) in core_engine::dsp::bench(n, 3000) {
            println!("fft n={n:>5} {:<26} {:>9.2} us", k.name(), ns as f64 / 1000.0);
        }
    }
}

#[test]
#[ignore]
fn atomics_under_contention() {
    // What LSE atomics (Armv8.1) change: an atomic add, an Arc refcount and tokio's scheduler, each
    // hammered from 8 threads at once. Without LSE every one is a load-exclusive/store-exclusive
    // retry loop; with it, one CAS/LDADD the cache line goes to. Do not pin this one to a core.
    use std::sync::atomic::{AtomicU64, Ordering};
    use std::sync::Arc;
    const THREADS: usize = 8;
    const N: u64 = 2_000_000;

    let counter = Arc::new(AtomicU64::new(0));
    let t = Instant::now();
    std::thread::scope(|s| {
        for _ in 0..THREADS {
            let c = &counter;
            s.spawn(move || for _ in 0..N { c.fetch_add(1, Ordering::AcqRel); });
        }
    });
    let add = t.elapsed().as_secs_f64();

    let shared = Arc::new([0u8; 64]);
    let t = Instant::now();
    std::thread::scope(|s| {
        for _ in 0..THREADS {
            let a = &shared;
            s.spawn(move || for _ in 0..N { std::hint::black_box(Arc::clone(a)); });
        }
    });
    let arc = t.elapsed().as_secs_f64();

    let rt = tokio::runtime::Builder::new_multi_thread().worker_threads(THREADS).build().unwrap();
    let t = Instant::now();
    rt.block_on(async {
        let tasks: Vec<_> = (0..200_000u64).map(|i| tokio::spawn(async move { i * 2 })).collect();
        let mut sum = 0;
        for task in tasks { sum += task.await.unwrap(); }
        std::hint::black_box(sum);
    });
    let spawn = t.elapsed().as_secs_f64();

    let ops = (THREADS as u64 * N) as f64;
    println!(
        "atomics: fetch_add {:.1} ns/op, Arc clone+drop {:.1} ns/op, tokio 200k spawn+join {:.0} ms",
        add * 1e9 / ops, arc * 1e9 / ops, spawn * 1000.0
    );
}

#[test]
#[ignore]
fn matmul_kernels_on_this_cpu() {
    // The key/mel filterbank product (64×k by k×16) on every kernel this CPU has: NEON, BF16 BFMMLA,
    // SME FMOPA and BFMOPA.
    for n in [2048, 8192] {
        for (k, ns) in core_engine::tonal::bench(n, 300) {
            println!("matmul k={:>5} {:<28} {:>9.2} us", n / 2, k.name(), ns as f64 / 1000.0);
        }
    }
}
