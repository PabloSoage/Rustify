//! Checks `subsonic.rs` against a real server (`20` §4): ping, a wrong password, a track the server
//! has, one it does not, and the first bytes of the stream URL that ExoPlayer would open.
//!
//! ```text
//! cargo run --example subsonic_probe -- http://127.0.0.1:4533 user password "Title" "Artist" seconds
//! ```

use core_engine::env::android::AndroidEnv;
use core_engine::env::{Env, HttpRequest};
use core_engine::subsonic::{self, Config};

#[tokio::main]
async fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let [server, user, password, title, artist, seconds] = args.as_slice() else {
        eprintln!("usage: subsonic_probe server user password title artist seconds");
        std::process::exit(2);
    };
    let cfg = Config { server: server.clone(), user: user.clone(), password: password.clone() };
    let duration_ms = seconds.parse::<i64>().unwrap_or(0) * 1000;

    println!("ping: {:?}", subsonic::ping::<AndroidEnv>(&cfg).await);
    let wrong = Config { password: format!("{password}-wrong"), ..cfg.clone() };
    println!("ping, wrong password: {:?}", subsonic::ping::<AndroidEnv>(&wrong).await);

    let started = std::time::Instant::now();
    let found = subsonic::find::<AndroidEnv>(&cfg, title, std::slice::from_ref(artist), duration_ms).await;
    println!("find {title:?}: {:?} in {} ms", found.as_ref().map(|f| f.as_ref().map(|f| (&f.id, &f.suffix, f.bit_rate))), started.elapsed().as_millis());
    let absent = subsonic::find::<AndroidEnv>(&cfg, "No Such Song Anywhere", std::slice::from_ref(artist), 0).await;
    println!("find absent: {:?}", absent.map(|f| f.is_some()));
    let off_by_a_minute = subsonic::find::<AndroidEnv>(&cfg, title, std::slice::from_ref(artist), duration_ms + 60_000).await;
    println!("find, duration 60 s off: {:?}", off_by_a_minute.map(|f| f.is_some()));

    if let Ok(Some(f)) = found {
        let response = AndroidEnv::fetch(HttpRequest::get(f.stream_url.clone()).timeout_ms(10_000)).await.unwrap();
        let magic: String = response.body.iter().take(4).map(|b| if b.is_ascii_graphic() { *b as char } else { '.' }).collect();
        println!(
            "stream: HTTP {}, {} bytes, starts with {magic:?}, content-type {:?}",
            response.status,
            response.body.len(),
            response.headers.iter().find(|(k, _)| k.eq_ignore_ascii_case("content-type")).map(|(_, v)| v)
        );
    }
}
