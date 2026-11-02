// core_engine/src/scrobble/lastfm.rs
//
// Scrobbling to Last.fm (`23` §3): Spotube 6 has it as a plugin and Pano Scrobbler is built around
// it, so Rustify does it too, beside ListenBrainz and with the same rules (`super::qualifies`, an
// offline queue that keeps each listen's real time).
//
// ## Keys
//
// Last.fm signs every write with an application key and secret. Rustify ships none: the user makes
// their own API account (last.fm/api/account/create, a minute) and enters both, then logs in once
// with `auth.getMobileSession`. Only the session key is kept, never the password.
//
// ## Signing
//
// `api_sig` is md5 of every parameter except `format` and `callback`, sorted by name and written as
// `namevalue` one after another, followed by the secret.
//
// ## What a failure means
//
// Last.fm answers errors as `{"error": n, "message": …}`. The session or the key being wrong (4, 9,
// 10, 13, 26) stops and keeps the queue: nothing will work until the user fixes it. Service trouble
// and rate limits (11, 16, 29, or no answer) keep the queue for later. Anything else is about the
// batch itself and drops it, as with ListenBrainz's 400.

use super::{Listen, Report};
use crate::env::{storage, Env, EnvError, HttpRequest, LogLevel};
use md5::{Digest, Md5};
use serde::{Deserialize, Serialize};

pub const API: &str = "https://ws.audioscrobbler.com/2.0/";
pub const QUEUE_KEY: &str = "lastfm_queue";
/// `track.scrobble` takes at most 50 at once.
const BATCH: usize = 50;
const TIMEOUT_MS: u64 = 15_000;

#[derive(Debug, Clone, Deserialize, Default)]
#[serde(rename_all = "camelCase", default)]
pub struct Credentials {
    pub api_key: String,
    pub secret: String,
    pub session_key: String,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Session {
    pub name: String,
    pub key: String,
}

pub fn sign(params: &[(String, String)], secret: &str) -> String {
    let mut sorted: Vec<&(String, String)> = params
        .iter()
        .filter(|(k, _)| k != "format" && k != "callback")
        .collect();
    sorted.sort_by(|a, b| a.0.cmp(&b.0));
    let mut text = String::new();
    for (k, v) in sorted {
        text.push_str(k);
        text.push_str(v);
    }
    text.push_str(secret);
    Md5::digest(text.as_bytes())
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect()
}

fn request(mut params: Vec<(String, String)>, api_key: &str, secret: &str) -> HttpRequest {
    params.push(("api_key".into(), api_key.into()));
    let sig = sign(&params, secret);
    params.push(("api_sig".into(), sig));
    params.push(("format".into(), "json".into()));
    let pairs: Vec<(&str, &str)> = params
        .iter()
        .map(|(k, v)| (k.as_str(), v.as_str()))
        .collect();
    HttpRequest::post(API)
        .timeout_ms(TIMEOUT_MS)
        .body_form(&pairs)
}

#[derive(Debug, PartialEq)]
enum Outcome {
    Ok(serde_json::Value),
    /// The session, the key or the signature: fix before anything else will work.
    Unauthorized(String),
    /// Try again later; nothing is wrong with the request.
    Later,
    /// The request itself was refused; sending it again will not help.
    Refused(String),
}

fn outcome(status: u16, body: &[u8]) -> Outcome {
    let json: Option<serde_json::Value> = serde_json::from_slice(body).ok();
    match json
        .as_ref()
        .and_then(|j| j.get("error"))
        .and_then(|e| e.as_i64())
    {
        None if (200..300).contains(&status) => Outcome::Ok(json.unwrap_or_default()),
        None => Outcome::Later,
        Some(code) => {
            let message = json
                .as_ref()
                .and_then(|j| j.get("message"))
                .and_then(|m| m.as_str())
                .unwrap_or("")
                .to_string();
            match code {
                4 | 9 | 10 | 13 | 26 => Outcome::Unauthorized(format!("Last.fm {code}: {message}")),
                11 | 16 | 29 => Outcome::Later,
                _ => Outcome::Refused(format!("Last.fm {code}: {message}")),
            }
        }
    }
}

/// Logs in once; the session key it answers is what every later call uses.
pub async fn login<E: Env>(
    api_key: &str,
    secret: &str,
    user: &str,
    password: &str,
) -> Result<Session, EnvError> {
    let params = vec![
        ("method".into(), "auth.getMobileSession".into()),
        ("username".into(), user.into()),
        ("password".into(), password.into()),
    ];
    let r = E::fetch(request(params, api_key, secret)).await?;
    match outcome(r.status, &r.body) {
        Outcome::Ok(json) => {
            let s = json.get("session").cloned().unwrap_or_default();
            let key = s
                .get("key")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string();
            if key.is_empty() {
                return Err(EnvError::Fetch("Last.fm answered no session".into()));
            }
            Ok(Session {
                name: s
                    .get("name")
                    .and_then(|v| v.as_str())
                    .unwrap_or(user)
                    .to_string(),
                key,
            })
        }
        Outcome::Unauthorized(m) | Outcome::Refused(m) => Err(EnvError::Fetch(m)),
        Outcome::Later => Err(EnvError::Fetch(format!(
            "Last.fm unavailable (HTTP {})",
            r.status
        ))),
    }
}

fn scrobble_params(listens: &[Listen], session_key: &str) -> Vec<(String, String)> {
    let mut p = vec![
        ("method".to_string(), "track.scrobble".to_string()),
        ("sk".to_string(), session_key.to_string()),
    ];
    for (i, l) in listens.iter().enumerate() {
        p.push((format!("artist[{i}]"), l.artist.clone()));
        p.push((format!("track[{i}]"), l.track.clone()));
        p.push((format!("timestamp[{i}]"), l.listened_at.to_string()));
        if !l.release.is_empty() {
            p.push((format!("album[{i}]"), l.release.clone()));
        }
        if l.duration_ms > 0 {
            p.push((format!("duration[{i}]"), (l.duration_ms / 1000).to_string()));
        }
    }
    p
}

/// `accepted` and `ignored` as Last.fm counts them; numbers or strings depending on the day.
fn counts(json: &serde_json::Value) -> (usize, usize) {
    let attr = json
        .pointer("/scrobbles/@attr")
        .cloned()
        .unwrap_or_default();
    let n = |k: &str| match attr.get(k) {
        Some(serde_json::Value::Number(n)) => n.as_u64().unwrap_or(0) as usize,
        Some(serde_json::Value::String(s)) => s.parse().unwrap_or(0),
        _ => 0,
    };
    (n("accepted"), n("ignored"))
}

/// Queues `listen` and sends whatever is queued.
pub async fn submit<E: Env>(creds: &Credentials, listen: Listen) -> Result<Report, EnvError> {
    let mut queue: Vec<Listen> = storage::get_json_or_default::<E, _>(QUEUE_KEY).await;
    queue.push(listen);
    if queue.len() > super::MAX_QUEUE {
        let excess = queue.len() - super::MAX_QUEUE;
        queue.drain(..excess);
    }
    storage::set_json::<E, _>(QUEUE_KEY, Some(&queue)).await?;
    flush::<E>(creds).await
}

/// Sends the queue in batches of 50, oldest first, keeping what could not be sent.
pub async fn flush<E: Env>(creds: &Credentials) -> Result<Report, EnvError> {
    let mut queue: Vec<Listen> = storage::get_json_or_default::<E, _>(QUEUE_KEY).await;
    let mut report = Report::default();
    while !queue.is_empty() {
        let n = queue.len().min(BATCH);
        let req = request(
            scrobble_params(&queue[..n], &creds.session_key),
            &creds.api_key,
            &creds.secret,
        );
        let r = match E::fetch(req).await {
            Ok(r) => r,
            Err(_) => break,
        };
        match outcome(r.status, &r.body) {
            Outcome::Ok(json) => {
                let (accepted, ignored) = counts(&json);
                report.sent += accepted;
                report.rejected += ignored;
            }
            Outcome::Refused(m) => {
                E::log(
                    LogLevel::Warn,
                    "Last.fm",
                    &format!("{n} scrobbles refused: {m}"),
                );
                report.rejected += n;
            }
            Outcome::Unauthorized(m) => {
                storage::set_json::<E, _>(QUEUE_KEY, Some(&queue)).await?;
                return Err(EnvError::Fetch(m));
            }
            Outcome::Later => break,
        }
        queue.drain(..n);
        storage::set_json::<E, _>(QUEUE_KEY, Some(&queue)).await?;
    }
    report.pending = queue.len();
    Ok(report)
}

/// Tells Last.fm what is playing now. Best effort: never queued.
pub async fn now_playing<E: Env>(creds: &Credentials, listen: &Listen) -> Result<(), EnvError> {
    let mut p = vec![
        ("method".to_string(), "track.updateNowPlaying".to_string()),
        ("sk".to_string(), creds.session_key.clone()),
        ("artist".to_string(), listen.artist.clone()),
        ("track".to_string(), listen.track.clone()),
    ];
    if !listen.release.is_empty() {
        p.push(("album".into(), listen.release.clone()));
    }
    if listen.duration_ms > 0 {
        p.push(("duration".into(), (listen.duration_ms / 1000).to_string()));
    }
    E::fetch(request(p, &creds.api_key, &creds.secret))
        .await
        .map(|_| ())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::env::mock::{self, MockEnv};
    use crate::env::HttpResponse;

    fn creds() -> Credentials {
        Credentials {
            api_key: "key".into(),
            secret: "secret".into(),
            session_key: "sk".into(),
        }
    }

    fn listen(at: i64) -> Listen {
        Listen {
            listened_at: at,
            artist: "Ado".into(),
            track: "Usseewa".into(),
            duration_ms: 200_000,
            ..Listen::default()
        }
    }

    fn form(req: &crate::env::HttpRequest) -> Vec<(String, String)> {
        url::form_urlencoded::parse(req.body.as_deref().unwrap_or_default())
            .into_owned()
            .collect()
    }

    #[test]
    fn the_signature_sorts_by_name_and_leaves_format_out() {
        let p = vec![
            ("method".to_string(), "auth.getMobileSession".to_string()),
            ("username".to_string(), "u".to_string()),
            ("api_key".to_string(), "k".to_string()),
            ("format".to_string(), "json".to_string()),
        ];
        // md5("api_keykmethodauth.getMobileSessionusernameus")
        let expected: String = Md5::digest(b"api_keykmethodauth.getMobileSessionusernameus")
            .iter()
            .map(|b| format!("{b:02x}"))
            .collect();
        assert_eq!(sign(&p, "s"), expected);
    }

    #[test]
    fn errors_are_sorted_into_what_to_do() {
        assert_eq!(
            outcome(403, br#"{"error":9,"message":"Invalid session key"}"#),
            Outcome::Unauthorized("Last.fm 9: Invalid session key".into())
        );
        assert_eq!(
            outcome(200, br#"{"error":29,"message":"Rate limit"}"#),
            Outcome::Later
        );
        assert_eq!(outcome(503, b"<html>"), Outcome::Later);
        assert!(matches!(
            outcome(400, br#"{"error":6,"message":"Invalid parameters"}"#),
            Outcome::Refused(_)
        ));
    }

    #[tokio::test]
    async fn a_backlog_goes_in_one_signed_batch_with_its_real_times() {
        let _guard = mock::lock_and_reset();
        let r = submit::<MockEnv>(&creds(), listen(100)).await.unwrap();
        assert_eq!((r.sent, r.pending), (0, 1), "no network: it waits");
        mock::on_url(
            API,
            HttpResponse::ok(r#"{"scrobbles":{"@attr":{"accepted":2,"ignored":0}}}"#),
        );
        let r = submit::<MockEnv>(&creds(), listen(200)).await.unwrap();
        assert_eq!((r.sent, r.pending), (2, 0));
        let sent = form(&mock::state().requests.last().cloned().unwrap());
        let get = |k: &str| sent.iter().find(|(n, _)| n == k).map(|(_, v)| v.clone());
        assert_eq!(get("method").as_deref(), Some("track.scrobble"));
        assert_eq!(get("timestamp[0]").as_deref(), Some("100"));
        assert_eq!(get("timestamp[1]").as_deref(), Some("200"));
        assert_eq!(get("duration[0]").as_deref(), Some("200"));
        assert_eq!(get("sk").as_deref(), Some("sk"));
        let unsigned: Vec<(String, String)> = sent
            .iter()
            .filter(|(k, _)| k != "api_sig")
            .cloned()
            .collect();
        assert_eq!(get("api_sig"), Some(sign(&unsigned, "secret")));
    }

    #[tokio::test]
    async fn a_dead_session_keeps_the_queue_and_says_why() {
        let _guard = mock::lock_and_reset();
        mock::on_url(
            API,
            HttpResponse::with_status(403, r#"{"error":9,"message":"Invalid session key"}"#),
        );
        let err = submit::<MockEnv>(&creds(), listen(1)).await.unwrap_err();
        assert!(format!("{err:?}").contains("Invalid session key"));
        let queued: Vec<Listen> = storage::get_json_or_default::<MockEnv, _>(QUEUE_KEY).await;
        assert_eq!(queued, vec![listen(1)]);
    }

    #[tokio::test]
    async fn login_keeps_only_the_session() {
        let _guard = mock::lock_and_reset();
        mock::on_url(
            API,
            HttpResponse::ok(r#"{"session":{"name":"pablo","key":"abc","subscriber":0}}"#),
        );
        let s = login::<MockEnv>("key", "secret", "pablo", "pw")
            .await
            .unwrap();
        assert_eq!(
            s,
            Session {
                name: "pablo".into(),
                key: "abc".into()
            }
        );
        mock::on_url(
            API,
            HttpResponse::with_status(403, r#"{"error":4,"message":"Authentication Failed"}"#),
        );
        assert!(login::<MockEnv>("key", "secret", "pablo", "bad")
            .await
            .is_err());
    }
}
