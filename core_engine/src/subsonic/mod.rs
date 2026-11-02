// core_engine/src/subsonic/mod.rs
//
// Subsonic / OpenSubsonic as an audio source (5.x §4, `20`): the user's own server — Navidrome on
// the NAS, Gonic, Airsonic, Ampache — plays a Spotify or YouTube Music track from the user's own
// files when it has them, at their quality, without YouTube.
//
// ## How a track is found
//
// The catalogue track is not a Subsonic one, so it is looked up: `search3` for the title, then the
// same rule the local matcher uses (`matcher::local`): folded title equal (or equal once a
// "(Remastered)"-style suffix is dropped), an artist in common, duration within ±5 s when both are
// known. No match is not an error: the chain asks the next source.
//
// ## Authentication
//
// The token form of the API (1.13+): `t = md5(password + salt)`, `s = salt`, a fresh salt per
// request, so the password itself never travels. The stream URL carries the same parameters,
// because ExoPlayer requests it on its own.

use crate::env::{Env, EnvError, HttpRequest};
use crate::search::fold;
use md5::{Digest, Md5};
use serde::{Deserialize, Serialize};
use std::sync::atomic::{AtomicU64, Ordering};

const API_VERSION: &str = "1.16.1";
const CLIENT: &str = "Rustify";
const TIMEOUT_MS: u64 = 10_000;
const DURATION_TOLERANCE_MS: i64 = 5_000;

#[derive(Debug, Clone, Deserialize, Serialize, Default)]
#[serde(rename_all = "camelCase", default)]
pub struct Config {
    /// Base URL, e.g. `https://music.example.org` or `http://192.168.1.10:4533`.
    pub server: String,
    pub user: String,
    pub password: String,
}

/// What a match answers: where to stream it from, and enough to show the user what it is.
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Found {
    pub id: String,
    pub stream_url: String,
    /// `flac`, `mp3`, `opus`… as the server stores it.
    pub suffix: String,
    pub bit_rate: u32,
}

#[derive(Debug, Clone, Deserialize, Default)]
#[serde(rename_all = "camelCase", default)]
struct Song {
    id: String,
    title: String,
    artist: String,
    /// Seconds.
    duration: i64,
    suffix: String,
    bit_rate: u32,
}

static SALT_COUNTER: AtomicU64 = AtomicU64::new(0);

fn salt<E: Env>() -> String {
    let n = SALT_COUNTER.fetch_add(1, Ordering::Relaxed);
    let digest = Md5::digest(format!("{}:{n}", E::now_ms()).as_bytes());
    hex(&digest)[..12].to_string()
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// `u`, `t`, `s`, `v`, `c` and `f`, URL-encoded, for one request.
fn auth_query(cfg: &Config, salt: &str) -> String {
    let token = hex(&Md5::digest(format!("{}{salt}", cfg.password).as_bytes()));
    format!(
        "u={}&t={token}&s={salt}&v={API_VERSION}&c={CLIENT}&f=json",
        urlencoding::encode(&cfg.user)
    )
}

fn endpoint(cfg: &Config, method: &str, salt: &str, extra: &str) -> String {
    let base = cfg.server.trim_end_matches('/');
    format!("{base}/rest/{method}.view?{}{extra}", auth_query(cfg, salt))
}

/// The `subsonic-response` body, or the server's own error message.
async fn call<E: Env>(cfg: &Config, method: &str, extra: &str) -> Result<serde_json::Value, EnvError> {
    let url = endpoint(cfg, method, &salt::<E>(), extra);
    let response = E::fetch(HttpRequest::get(url).timeout_ms(TIMEOUT_MS)).await?;
    if !response.is_success() {
        return Err(EnvError::Fetch(format!("Subsonic {method}: HTTP {}", response.status)));
    }
    let body: serde_json::Value = response.json()?;
    let inner = body.get("subsonic-response").cloned().unwrap_or_default();
    if inner.get("status").and_then(|s| s.as_str()) != Some("ok") {
        let message = inner
            .pointer("/error/message")
            .and_then(|m| m.as_str())
            .unwrap_or("unknown error");
        return Err(EnvError::Fetch(format!("Subsonic {method}: {message}")));
    }
    Ok(inner)
}

/// Checks the server and the credentials; answers the server's name and version.
pub async fn ping<E: Env>(cfg: &Config) -> Result<String, EnvError> {
    let r = call::<E>(cfg, "ping", "").await?;
    let kind = r.get("type").and_then(|v| v.as_str()).unwrap_or("Subsonic");
    let version = r
        .get("serverVersion")
        .or_else(|| r.get("version"))
        .and_then(|v| v.as_str())
        .unwrap_or("");
    Ok(format!("{kind} {version}").trim().to_string())
}

/// Drops a trailing `(…)` or ` - …` qualifier: "Song (2011 Remaster)", "Song - Live".
fn base_title(title: &str) -> String {
    let t = title.split(" - ").next().unwrap_or(title);
    let t = t.split(" (").next().unwrap_or(t);
    fold(t)
}

fn matches(song: &Song, title: &str, artists: &[String], duration_ms: i64) -> bool {
    let title_ok = fold(&song.title) == fold(title) || base_title(&song.title) == base_title(title);
    let song_artist = fold(&song.artist);
    let artist_ok = artists.iter().any(|a| {
        let a = fold(a);
        !a.is_empty() && (song_artist.contains(&a) || a.contains(&song_artist))
    });
    let duration_ok =
        duration_ms <= 0 || song.duration <= 0 || (song.duration * 1000 - duration_ms).abs() <= DURATION_TOLERANCE_MS;
    title_ok && artist_ok && duration_ok
}

/// The server's copy of a track, or `None` when it has none.
pub async fn find<E: Env>(cfg: &Config, title: &str, artists: &[String], duration_ms: i64) -> Result<Option<Found>, EnvError> {
    let query = urlencoding::encode(title).into_owned();
    let r = call::<E>(cfg, "search3", &format!("&query={query}&songCount=20&albumCount=0&artistCount=0")).await?;
    let songs: Vec<Song> = r
        .pointer("/searchResult3/song")
        .cloned()
        .and_then(|v| serde_json::from_value(v).ok())
        .unwrap_or_default();
    Ok(songs.into_iter().find(|s| matches(s, title, artists, duration_ms)).map(|s| Found {
        // `format=raw`: the stored file, whatever transcoding the server sets for this client
        // (Navidrome keeps one per player). Symfonium asks the same way.
        stream_url: endpoint(cfg, "stream", &salt::<E>(), &format!("&id={}&format=raw", urlencoding::encode(&s.id))),
        id: s.id,
        suffix: s.suffix,
        bit_rate: s.bit_rate,
    }))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::env::mock::{self, MockEnv};
    use crate::env::HttpResponse;

    fn cfg() -> Config {
        Config { server: "http://nas:4533/".into(), user: "pablo s".into(), password: "sesame".into() }
    }

    #[test]
    fn the_token_is_md5_of_password_and_salt() {
        // md5("sesamec19b2d") from the Subsonic API documentation's own example.
        let q = auth_query(&cfg(), "c19b2d");
        assert!(q.contains("t=26719a1196d2a940705a59634eb18eab"), "{q}");
        assert!(q.starts_with("u=pablo%20s&"), "{q}");
        assert!(!q.contains("sesame"));
    }

    #[test]
    fn a_remaster_or_a_live_tag_still_matches_but_another_song_does_not() {
        let song = |t: &str, a: &str, d: i64| Song { title: t.into(), artist: a.into(), duration: d, ..Song::default() };
        let artists = vec!["Björk".to_string()];
        assert!(matches(&song("Jóga (2011 Remaster)", "Bjork", 305), "Joga", &artists, 305_000));
        assert!(matches(&song("Jóga", "Björk; Someone", 0), "Jóga", &artists, 305_000));
        assert!(!matches(&song("Jóga", "Björk", 280), "Jóga", &artists, 305_000), "25 s apart");
        assert!(!matches(&song("Hunter", "Björk", 305), "Jóga", &artists, 305_000));
        assert!(!matches(&song("Jóga", "Sigur Rós", 305), "Jóga", &artists, 305_000));
    }

    #[tokio::test]
    async fn a_track_is_found_and_streamed_with_its_own_salt() {
        let _guard = mock::lock_and_reset();
        mock::on_prefix(
            "http://nas:4533/rest/search3.view?",
            HttpResponse::ok(
                r#"{"subsonic-response":{"status":"ok","version":"1.16.1","searchResult3":{"song":[
                {"id":"x1","title":"Hunter","artist":"Björk","duration":250,"suffix":"mp3","bitRate":320},
                {"id":"x2","title":"Jóga","artist":"Björk","duration":305,"suffix":"flac","bitRate":900}]}}}"#,
            ),
        );
        let found = find::<MockEnv>(&cfg(), "Jóga", &["Björk".into()], 305_000).await.unwrap().unwrap();
        assert_eq!((found.id.as_str(), found.suffix.as_str(), found.bit_rate), ("x2", "flac", 900));
        assert!(found.stream_url.starts_with("http://nas:4533/rest/stream.view?u=pablo%20s&t="));
        assert!(found.stream_url.ends_with("&id=x2&format=raw"), "the stored file, never a transcode");
        let search = mock::state().requests[0].url.clone();
        assert!(search.contains("&query=J%C3%B3ga&songCount=20"), "{search}");
    }

    #[tokio::test]
    async fn the_servers_own_error_is_reported() {
        let _guard = mock::lock_and_reset();
        mock::on_prefix(
            "http://nas:4533/rest/ping.view?",
            HttpResponse::ok(r#"{"subsonic-response":{"status":"failed","error":{"code":40,"message":"Wrong username or password"}}}"#),
        );
        let e = ping::<MockEnv>(&cfg()).await.unwrap_err();
        assert!(e.to_string().contains("Wrong username or password"), "{e}");
    }
}
