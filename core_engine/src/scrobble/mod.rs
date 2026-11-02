// core_engine/src/scrobble/mod.rs
//
// Scrobbling to ListenBrainz (`18`, after Pano Scrobbler and Metrolist): every listen Rustify counts
// is also sent to the user's ListenBrainz profile, which is where stats.fm-style history lives for
// people who do not want it inside one app.
//
// ListenBrainz rather than Last.fm first: it needs only the user's token (Settings → API on their
// profile page), no application key and secret shipped in the APK, and its API is open.
//
// ## The rules
//
// - **What counts** is ListenBrainz's own guideline, stricter than the app's metrics: a track
//   longer than 30 s, played for 4 minutes or half its length. The metrics keep Spotify's 30 s rule;
//   the two answer different questions.
// - **Offline first.** A listen is queued in storage before anything is sent, and the queue is sent
//   in batches whenever a new listen arrives. The car is mostly without signal; a listen in the
//   garage must not be lost, and must keep its real time (`listened_at` is when it started).
// - **A batch refused as malformed (400) is dropped**, logged, and the rest goes on: otherwise one
//   bad listen would block every later one forever. A 401 stops and says so: the token is wrong.
//   Anything else (no network, 429, 5xx) keeps the queue for next time.
// - **Bounded.** At most [MAX_QUEUE] listens wait; past that the oldest go first.

use crate::env::{storage, Env, EnvError, HttpRequest, LogLevel};
use serde::{Deserialize, Serialize};
use serde_json::json;

pub const API: &str = "https://api.listenbrainz.org/1/";
pub const QUEUE_KEY: &str = "listenbrainz_queue";
pub const MAX_QUEUE: usize = 5_000;
/// ListenBrainz accepts up to 1 000 listens per request; smaller batches fail smaller.
const BATCH: usize = 100;
const TIMEOUT_MS: u64 = 15_000;

/// One listen, as the app reports it.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase", default)]
pub struct Listen {
    /// Seconds since the epoch, when the track started.
    pub listened_at: i64,
    pub artist: String,
    pub track: String,
    pub release: String,
    pub duration_ms: i64,
    pub isrc: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct Report {
    pub sent: usize,
    pub pending: usize,
    /// Dropped because ListenBrainz refused them as malformed.
    pub rejected: usize,
}

/// ListenBrainz's guideline for a listen.
pub fn qualifies(listened_ms: i64, duration_ms: i64) -> bool {
    if duration_ms > 0 && duration_ms <= 30_000 {
        return false;
    }
    listened_ms >= 240_000 || (duration_ms > 0 && listened_ms * 2 >= duration_ms)
}

fn track_metadata(l: &Listen) -> serde_json::Value {
    let mut info = json!({ "submission_client": "Rustify", "media_player": "Rustify" });
    if l.duration_ms > 0 {
        info["duration_ms"] = json!(l.duration_ms);
    }
    if !l.isrc.is_empty() {
        info["isrc"] = json!(l.isrc);
    }
    let mut meta = json!({ "artist_name": l.artist, "track_name": l.track, "additional_info": info });
    if !l.release.is_empty() {
        meta["release_name"] = json!(l.release);
    }
    meta
}

/// The body of `submit-listens`: `single` for one listen, `import` for a backlog.
fn submit_body(listens: &[Listen]) -> serde_json::Value {
    let payload: Vec<_> = listens
        .iter()
        .map(|l| json!({ "listened_at": l.listened_at, "track_metadata": track_metadata(l) }))
        .collect();
    let listen_type = if listens.len() == 1 { "single" } else { "import" };
    json!({ "listen_type": listen_type, "payload": payload })
}

fn post<T: Serialize>(token: &str, path: &str, body: &T) -> Result<HttpRequest, EnvError> {
    HttpRequest::post(format!("{API}{path}"))
        .header("Authorization", format!("Token {token}"))
        .timeout_ms(TIMEOUT_MS)
        .body_json(body)
}

/// Queues `listen` and sends whatever is queued.
pub async fn submit<E: Env>(token: &str, listen: Listen) -> Result<Report, EnvError> {
    let mut queue: Vec<Listen> = storage::get_json_or_default::<E, _>(QUEUE_KEY).await;
    queue.push(listen);
    if queue.len() > MAX_QUEUE {
        let excess = queue.len() - MAX_QUEUE;
        queue.drain(..excess);
    }
    storage::set_json::<E, _>(QUEUE_KEY, Some(&queue)).await?;
    flush::<E>(token).await
}

/// Sends the queue in batches, oldest first, keeping what could not be sent.
pub async fn flush<E: Env>(token: &str) -> Result<Report, EnvError> {
    let mut queue: Vec<Listen> = storage::get_json_or_default::<E, _>(QUEUE_KEY).await;
    let mut report = Report::default();
    while !queue.is_empty() {
        let n = queue.len().min(BATCH);
        let response = match E::fetch(post(token, "submit-listens", &submit_body(&queue[..n]))?).await {
            Ok(r) => r,
            // No network: the listens wait for the next one.
            Err(_) => break,
        };
        match response.status {
            200..=299 => report.sent += n,
            400 => {
                E::log(LogLevel::Warn, "ListenBrainz", &format!("{n} listens refused: {}", response.text()));
                report.rejected += n;
            }
            401 => {
                storage::set_json::<E, _>(QUEUE_KEY, Some(&queue)).await?;
                return Err(EnvError::Fetch("ListenBrainz refused the token (401)".into()));
            }
            _ => break,
        }
        queue.drain(..n);
        storage::set_json::<E, _>(QUEUE_KEY, Some(&queue)).await?;
    }
    report.pending = queue.len();
    Ok(report)
}

/// Tells ListenBrainz what is playing now. Best effort: never queued.
pub async fn playing_now<E: Env>(token: &str, listen: &Listen) -> Result<(), EnvError> {
    let body = json!({ "listen_type": "playing_now", "payload": [{ "track_metadata": track_metadata(listen) }] });
    E::fetch(post(token, "submit-listens", &body)?).await.map(|_| ())
}

#[derive(Deserialize)]
struct Validation {
    #[serde(default)]
    valid: bool,
    #[serde(default)]
    user_name: Option<String>,
}

/// The user the token belongs to, or `None` when ListenBrainz says it is not valid.
pub async fn validate<E: Env>(token: &str) -> Result<Option<String>, EnvError> {
    let req = HttpRequest::get(format!("{API}validate-token"))
        .header("Authorization", format!("Token {token}"))
        .timeout_ms(TIMEOUT_MS);
    let v: Validation = E::fetch(req).await?.json()?;
    Ok(if v.valid { v.user_name } else { None })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::env::mock::{self, MockEnv};
    use crate::env::HttpResponse;

    fn listen(at: i64) -> Listen {
        Listen { listened_at: at, artist: "Ado".into(), track: "Usseewa".into(), duration_ms: 200_000, ..Listen::default() }
    }

    #[test]
    fn a_listen_is_four_minutes_or_half_and_never_a_short_track() {
        assert!(qualifies(100_000, 200_000));
        assert!(!qualifies(99_000, 200_000));
        assert!(qualifies(240_000, 3_600_000), "a long mix counts at 4 minutes");
        assert!(!qualifies(30_000, 30_000), "30 s or less never counts");
        assert!(qualifies(240_000, 0), "unknown length: 4 minutes");
    }

    #[test]
    fn one_listen_is_single_and_a_backlog_is_import() {
        assert_eq!(submit_body(&[listen(1)])["listen_type"], "single");
        let body = submit_body(&[listen(1), listen(2)]);
        assert_eq!(body["listen_type"], "import");
        assert_eq!(body["payload"][1]["listened_at"], 2);
        assert_eq!(body["payload"][0]["track_metadata"]["additional_info"]["duration_ms"], 200_000);
        assert!(body["payload"][0]["track_metadata"].get("release_name").is_none());
    }

    #[tokio::test]
    async fn without_network_the_listen_waits_and_goes_with_the_next() {
        let _guard = mock::lock_and_reset();
        // No canned response: every fetch is a transport failure.
        let r = submit::<MockEnv>("t", listen(1)).await.unwrap();
        assert_eq!((r.sent, r.pending), (0, 1));
        mock::on_url(format!("{API}submit-listens"), HttpResponse::ok("{\"status\":\"ok\"}"));
        let r = submit::<MockEnv>("t", listen(2)).await.unwrap();
        assert_eq!((r.sent, r.pending), (2, 0));
        let sent = mock::state().requests.last().cloned().unwrap();
        let body: serde_json::Value = serde_json::from_slice(&sent.body.unwrap()).unwrap();
        assert_eq!(body["listen_type"], "import");
        assert!(sent.headers.contains(&("Authorization".into(), "Token t".into())));
    }

    #[tokio::test]
    async fn a_malformed_batch_is_dropped_and_a_bad_token_keeps_the_queue() {
        let _guard = mock::lock_and_reset();
        mock::on_url(format!("{API}submit-listens"), HttpResponse::with_status(400, "bad"));
        let r = submit::<MockEnv>("t", listen(1)).await.unwrap();
        assert_eq!((r.rejected, r.pending), (1, 0));

        mock::on_url(format!("{API}submit-listens"), HttpResponse::with_status(401, "no"));
        assert!(submit::<MockEnv>("t", listen(2)).await.is_err());
        let queued: Vec<Listen> = storage::get_json_or_default::<MockEnv, _>(QUEUE_KEY).await;
        assert_eq!(queued, vec![listen(2)]);
    }

    #[tokio::test]
    async fn validation_names_the_user() {
        let _guard = mock::lock_and_reset();
        mock::on_url(format!("{API}validate-token"), HttpResponse::ok(r#"{"code":200,"valid":true,"user_name":"pablo"}"#));
        assert_eq!(validate::<MockEnv>("t").await.unwrap().as_deref(), Some("pablo"));
        mock::on_url(format!("{API}validate-token"), HttpResponse::ok(r#"{"code":200,"valid":false}"#));
        assert_eq!(validate::<MockEnv>("t").await.unwrap(), None);
    }
}
