// core_engine/src/youtube/innertube.rs
//
// YouTube audio with one HTTP request, no Python.
//
// yt-dlp resolves a track by starting a Python process that downloads the watch page and runs the
// player's JavaScript. Measured on the emulator in 4.0: 35.8 s of a 40 s budget for one song. The
// official clients do not do that: they POST `/youtubei/v1/player` and get URLs back. Some clients
// get them **directly playable** — no signature to decipher, no `n` parameter to transform, no
// proof-of-origin token — and those are the ones a client with no JavaScript engine can use.
//
// Which clients those are is not something to guess. The table below starts from the one the
// YouTube Music mods (Morphe, RVX) ship for their "spoof streaming data" patch, and was **measured**
// on 2026-10-20 (docs `serie-4.x/02` §2.3): visionOS answers in ~0.5 s and its URLs serve the whole
// file; Android VR/XR answer just as fast but their URLs return 403 past the first MiB without a PO
// token, which this project does not generate. So only visionOS is in the default table.
//
// The table is **data**: versions get retired, and when that happens the fix is a new table in
// storage (`innertube.clients`), not a new APK.
//
// Everything goes through `Env`, like the rest of the core: testable with `MockEnv`, no `reqwest`
// here. Deliberately not through `rustypipe`, whose HTTP client bypasses `Env` (T3) and which picks
// clients on its own.

use crate::env::storage;
use crate::env::{Env, EnvError, HttpRequest, LogLevel, Method};
use crate::errors::{Classify, ErrorKind};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::fmt;
use std::sync::Mutex;

const API: &str = "https://youtubei.googleapis.com/youtubei/v1";
const CLIENTS_KEY: &str = "innertube.clients";
const VISITOR_KEY: &str = "innertube.visitor";
/// A visitor id is good for a long time; refetched daily so a stale one never piles up refusals.
const VISITOR_TTL_MS: i64 = 24 * 3_600_000;
const REQUEST_TIMEOUT_MS: u64 = 8_000;

/// One client to impersonate. Field names follow the InnerTube `context.client` object.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ClientProfile {
    /// Our own name for it, for logs and for remembering which one worked.
    pub key: String,
    pub client_name: String,
    /// `X-YouTube-Client-Name`.
    pub client_id: u32,
    pub client_version: String,
    pub user_agent: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub device_make: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub device_model: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub os_name: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub os_version: Option<String>,
}

fn vision_os(version: &str, model: &str) -> ClientProfile {
    ClientProfile {
        key: format!("visionos-{version}"),
        client_name: "VISIONOS".into(),
        client_id: 101,
        client_version: version.into(),
        user_agent: format!(
            "com.google.visionosyoutube/{version} ({model}; U; CPU visionOS 26_6_1 like Mac OS X; en_US) gzip"
        ),
        device_make: Some("Apple".into()),
        device_model: Some(model.into()),
        os_name: Some("visionOS".into()),
        os_version: Some("26.6.1".into()),
    }
}

/// The measured default. See the module comment for why it is this short.
pub fn default_clients() -> Vec<ClientProfile> {
    vec![vision_os("1.03", "RealityDevice17,1"), vision_os("1.02", "RealityDevice14,1")]
}

/// What the caller wants from the stream.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct AudioPrefs {
    /// `"opus"` (WebM, itag 251 and friends) or `"aac"` (MP4, itag 140). Anything else: highest bitrate.
    pub prefer: String,
    /// Upper bound in kbps; 0 means none.
    pub max_kbps: u32,
}

/// A playable audio stream.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct InnertubeAudio {
    pub url: String,
    pub mime_type: String,
    pub itag: u32,
    pub bitrate: u64,
    pub content_length: Option<u64>,
    /// How much louder than YouTube's reference this recording is, in dB. For normalisation.
    pub loudness_db: Option<f64>,
    pub approx_duration_ms: Option<u64>,
    /// From the URL's own `expire` parameter, when it has one.
    pub expires_at_ms: Option<i64>,
    /// Which [`ClientProfile::key`] produced it.
    pub client: String,
}

#[derive(Debug, Clone, PartialEq)]
pub enum InnertubeError {
    /// The request did not complete.
    Network(String),
    /// YouTube answered with an HTTP error status.
    Status(u16),
    /// Every client was asked and none returned a usable stream; the reasons, per client.
    Unplayable(Vec<String>),
}

impl fmt::Display for InnertubeError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            InnertubeError::Network(e) => write!(f, "innertube: network: {e}"),
            InnertubeError::Status(s) => write!(f, "innertube: HTTP {s}"),
            InnertubeError::Unplayable(reasons) => write!(f, "innertube: no client could play it ({})", reasons.join("; ")),
        }
    }
}

impl std::error::Error for InnertubeError {}

impl Classify for InnertubeError {
    fn kind(&self) -> ErrorKind {
        match self {
            InnertubeError::Network(_) => ErrorKind::Transient,
            InnertubeError::Status(429) => ErrorKind::RateLimited,
            InnertubeError::Status(s) if (500..600).contains(s) => ErrorKind::Transient,
            InnertubeError::Status(_) | InnertubeError::Unplayable(_) => ErrorKind::Permanent,
        }
    }
}

impl From<EnvError> for InnertubeError {
    fn from(e: EnvError) -> Self {
        InnertubeError::Network(e.message())
    }
}

/// The client that worked last, tried first next time.
static LAST_GOOD: Mutex<Option<String>> = Mutex::new(None);

#[derive(Debug, Clone, Serialize, Deserialize)]
struct Visitor {
    value: String,
    fetched_at: i64,
}

async fn clients<E: Env>() -> Vec<ClientProfile> {
    match storage::get_json::<E, Vec<ClientProfile>>(CLIENTS_KEY).await {
        Ok(Some(list)) if !list.is_empty() => list,
        _ => default_clients(),
    }
}

/// Replaces the client table, e.g. from a file published with a release. Empty restores the default.
pub async fn set_clients<E: Env>(list: &[ClientProfile]) -> Result<(), EnvError> {
    if list.is_empty() {
        storage::set_json::<E, Vec<ClientProfile>>(CLIENTS_KEY, None).await
    } else {
        storage::set_json::<E, Vec<ClientProfile>>(CLIENTS_KEY, Some(&list.to_vec())).await
    }
}

fn context_client(c: &ClientProfile, visitor: Option<&str>) -> Value {
    let mut client = json!({
        "clientName": c.client_name,
        "clientVersion": c.client_version,
        "hl": "en",
        "gl": "US",
    });
    let obj = client.as_object_mut().expect("built as an object");
    for (name, value) in [
        ("deviceMake", &c.device_make),
        ("deviceModel", &c.device_model),
        ("osName", &c.os_name),
        ("osVersion", &c.os_version),
    ] {
        if let Some(v) = value {
            obj.insert(name.into(), json!(v));
        }
    }
    if let Some(v) = visitor {
        obj.insert("visitorData".into(), json!(v));
    }
    client
}

fn request<E: Env>(path: &str, c: &ClientProfile, body: &Value, visitor: Option<&str>) -> Result<HttpRequest, EnvError> {
    let mut req = HttpRequest::new(Method::Post, format!("{API}/{path}?prettyPrint=false"))
        .header("User-Agent", c.user_agent.clone())
        .header("X-YouTube-Client-Name", c.client_id.to_string())
        .header("X-YouTube-Client-Version", c.client_version.clone())
        .timeout_ms(REQUEST_TIMEOUT_MS)
        .body_json(body)?;
    if let Some(v) = visitor {
        req = req.header("X-Goog-Visitor-Id", v.to_string());
    }
    Ok(req)
}

/// A visitor id, cached. Without one some answers are refused; a failure to get one is not fatal.
async fn visitor<E: Env>(c: &ClientProfile) -> Option<String> {
    if let Ok(Some(v)) = storage::get_json::<E, Visitor>(VISITOR_KEY).await {
        if E::now_ms() - v.fetched_at < VISITOR_TTL_MS {
            return Some(v.value);
        }
    }
    let body = json!({ "context": { "client": context_client(c, None) } });
    let req = request::<E>("visitor_id", c, &body, None).ok()?;
    let res = E::fetch(req).await.ok()?;
    if !res.is_success() {
        return None;
    }
    let value = res.json::<Value>().ok()?["responseContext"]["visitorData"].as_str()?.to_string();
    let _ = storage::set_json::<E, Visitor>(VISITOR_KEY, Some(&Visitor { value: value.clone(), fetched_at: E::now_ms() })).await;
    Some(value)
}

/// Picks the stream to play from a `/player` answer, or says why there is none.
pub fn pick(player: &Value, prefs: &AudioPrefs, client: &str) -> Result<InnertubeAudio, String> {
    let status = player["playabilityStatus"]["status"].as_str().unwrap_or("");
    if status != "OK" {
        let reason = player["playabilityStatus"]["reason"].as_str().unwrap_or("");
        return Err(format!("{status} {reason}").trim().to_string());
    }
    let formats = player["streamingData"]["adaptiveFormats"].as_array().cloned().unwrap_or_default();
    let mut candidates: Vec<&Value> = formats
        .iter()
        .filter(|f| f["mimeType"].as_str().is_some_and(|m| m.starts_with("audio/")))
        // A format with only `signatureCipher` needs the player's JavaScript: not for us.
        .filter(|f| f["url"].as_str().is_some())
        // Dynamic-range-compressed variants (Morphe's DrcAudioPatch): never the default.
        .filter(|f| !f["isDrc"].as_bool().unwrap_or(false))
        // Some videos carry AI-dubbed tracks; the original is the one marked default.
        .filter(|f| f["audioTrack"]["audioIsDefault"].as_bool().unwrap_or(true))
        .filter(|f| prefs.max_kbps == 0 || f["bitrate"].as_u64().unwrap_or(0) <= u64::from(prefs.max_kbps) * 1000)
        .collect();
    if candidates.is_empty() {
        return Err("no directly playable audio format".into());
    }
    let wanted = match prefs.prefer.as_str() {
        "opus" => Some("audio/webm"),
        "aac" => Some("audio/mp4"),
        _ => None,
    };
    candidates.sort_by_key(|f| {
        let mime = f["mimeType"].as_str().unwrap_or("");
        let preferred = wanted.is_some_and(|w| mime.starts_with(w));
        (std::cmp::Reverse(preferred), std::cmp::Reverse(f["bitrate"].as_u64().unwrap_or(0)))
    });
    let f = candidates[0];
    let url = f["url"].as_str().unwrap_or_default().to_string();
    let number = |v: &Value| v.as_str().and_then(|s| s.parse::<u64>().ok()).or_else(|| v.as_u64());
    Ok(InnertubeAudio {
        expires_at_ms: expire_param(&url),
        mime_type: f["mimeType"].as_str().unwrap_or_default().split(';').next().unwrap_or_default().to_string(),
        itag: f["itag"].as_u64().unwrap_or(0) as u32,
        bitrate: f["bitrate"].as_u64().unwrap_or(0),
        content_length: number(&f["contentLength"]),
        loudness_db: f["loudnessDb"].as_f64().or_else(|| player["playerConfig"]["audioConfig"]["loudnessDb"].as_f64()),
        approx_duration_ms: number(&f["approxDurationMs"]),
        client: client.to_string(),
        url,
    })
}

fn expire_param(url: &str) -> Option<i64> {
    let query = url.split_once('?')?.1;
    query
        .split('&')
        .find_map(|pair| pair.strip_prefix("expire="))
        .and_then(|v| v.parse::<i64>().ok())
        .map(|secs| secs * 1000)
}

/// Resolves a YouTube video to a playable audio stream, trying each client in order — the one that
/// worked last first.
pub async fn resolve_audio<E: Env>(video_id: &str, prefs: &AudioPrefs) -> Result<InnertubeAudio, InnertubeError> {
    let mut list = clients::<E>().await;
    if let Some(last) = LAST_GOOD.lock().unwrap_or_else(std::sync::PoisonError::into_inner).clone() {
        if let Some(i) = list.iter().position(|c| c.key == last) {
            let c = list.remove(i);
            list.insert(0, c);
        }
    }
    let visitor = visitor::<E>(&list[0]).await;
    let mut reasons = Vec::new();
    let mut last_status = None;
    for c in &list {
        let body = json!({
            "context": { "client": context_client(c, visitor.as_deref()) },
            "videoId": video_id,
            "contentCheckOk": true,
            "racyCheckOk": true,
        });
        let req = request::<E>("player", c, &body, visitor.as_deref()).map_err(InnertubeError::from)?;
        let res = match E::fetch(req).await {
            Ok(res) => res,
            Err(e) => {
                // A transport failure is the network, not the client: asking the next one will not help.
                return Err(InnertubeError::from(e));
            }
        };
        if !res.is_success() {
            last_status = Some(res.status);
            reasons.push(format!("{}: HTTP {}", c.key, res.status));
            if res.status == 429 {
                return Err(InnertubeError::Status(429));
            }
            continue;
        }
        let player: Value = match res.json() {
            Ok(v) => v,
            Err(e) => {
                reasons.push(format!("{}: {}", c.key, e.message()));
                continue;
            }
        };
        match pick(&player, prefs, &c.key) {
            Ok(audio) => {
                *LAST_GOOD.lock().unwrap_or_else(std::sync::PoisonError::into_inner) = Some(c.key.clone());
                return Ok(audio);
            }
            Err(reason) => reasons.push(format!("{}: {reason}", c.key)),
        }
    }
    E::log(LogLevel::Warn, "Innertube", &format!("{video_id}: {}", reasons.join("; ")));
    match last_status {
        Some(s) if reasons.len() == list.len() && (500..600).contains(&s) => Err(InnertubeError::Status(s)),
        _ => Err(InnertubeError::Unplayable(reasons)),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::env::mock::{self, MockEnv};
    use crate::env::HttpResponse;

    fn reset_last_good() {
        *LAST_GOOD.lock().unwrap() = None;
    }

    fn player_ok(formats: Value) -> String {
        json!({
            "playabilityStatus": {"status": "OK"},
            "streamingData": {"adaptiveFormats": formats},
        })
        .to_string()
    }

    fn formats() -> Value {
        json!([
            {"itag": 137, "mimeType": "video/mp4; codecs=\"avc1\"", "bitrate": 4_000_000, "url": "https://v/137"},
            {"itag": 140, "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"", "bitrate": 130_000, "url": "https://a/140?expire=1792536061&x=1", "contentLength": "5000000", "loudnessDb": 5.67, "approxDurationMs": "305000"},
            {"itag": 251, "mimeType": "audio/webm; codecs=\"opus\"", "bitrate": 140_000, "url": "https://a/251", "contentLength": "5025760"},
            {"itag": 252, "mimeType": "audio/webm; codecs=\"opus\"", "bitrate": 900_000, "signatureCipher": "s=xx&url=https://a/252"},
            {"itag": 253, "mimeType": "audio/webm; codecs=\"opus\"", "bitrate": 500_000, "url": "https://a/253", "isDrc": true},
            {"itag": 254, "mimeType": "audio/webm; codecs=\"opus\"", "bitrate": 600_000, "url": "https://a/254", "audioTrack": {"audioIsDefault": false}}
        ])
    }

    fn mock_visitor() {
        mock::on_prefix(format!("{API}/visitor_id"), HttpResponse::ok(r#"{"responseContext":{"visitorData":"VD"}}"#));
    }

    #[test]
    fn the_best_direct_original_non_drc_audio_wins() {
        let player: Value = serde_json::from_str(&player_ok(formats())).unwrap();
        let audio = pick(&player, &AudioPrefs::default(), "visionos-1.03").unwrap();
        // 252 needs a cipher, 253 is DRC, 254 is a dub, 137 is video: 251 is the best left.
        assert_eq!(audio.itag, 251);
        assert_eq!(audio.mime_type, "audio/webm");
    }

    #[test]
    fn a_preference_beats_bitrate_and_the_fields_come_through() {
        let player: Value = serde_json::from_str(&player_ok(formats())).unwrap();
        let audio = pick(&player, &AudioPrefs { prefer: "aac".into(), max_kbps: 0 }, "c").unwrap();
        assert_eq!(audio.itag, 140);
        assert_eq!(audio.loudness_db, Some(5.67));
        assert_eq!(audio.content_length, Some(5_000_000));
        assert_eq!(audio.approx_duration_ms, Some(305_000));
        assert_eq!(audio.expires_at_ms, Some(1_792_536_061_000));
    }

    #[test]
    fn a_refusal_says_why() {
        let player = json!({"playabilityStatus": {"status": "LOGIN_REQUIRED", "reason": "Please sign in"}});
        assert_eq!(pick(&player, &AudioPrefs::default(), "c").unwrap_err(), "LOGIN_REQUIRED Please sign in");
    }

    #[tokio::test]
    async fn the_table_is_asked_in_order_with_the_last_good_client_first() {
        let _guard = mock::lock_and_reset();
        reset_last_good();
        mock_visitor();
        mock::on_prefix(format!("{API}/player"), HttpResponse::ok(player_ok(formats())));
        set_clients::<MockEnv>(&[vision_os("1.03", "RealityDevice17,1"), vision_os("1.02", "RealityDevice14,1")])
            .await
            .unwrap();
        let first = resolve_audio::<MockEnv>("vid", &AudioPrefs::default()).await.unwrap();
        assert_eq!(first.client, "visionos-1.03");

        *LAST_GOOD.lock().unwrap() = Some("visionos-1.02".into());
        let second = resolve_audio::<MockEnv>("vid", &AudioPrefs::default()).await.unwrap();
        assert_eq!(second.client, "visionos-1.02");

        // The visitor id and the client identity travelled with the request.
        let sent = mock::state().requests.iter().find(|r| r.url.contains("/player")).cloned().unwrap();
        assert!(sent.headers.iter().any(|(k, v)| k == "X-Goog-Visitor-Id" && v == "VD"));
        assert!(sent.headers.iter().any(|(k, v)| k == "X-YouTube-Client-Name" && v == "101"));
    }

    #[tokio::test]
    async fn nothing_playable_is_permanent_and_rate_limits_are_rate_limits() {
        let _guard = mock::lock_and_reset();
        reset_last_good();
        mock_visitor();
        mock::on_prefix(
            format!("{API}/player"),
            HttpResponse::ok(r#"{"playabilityStatus":{"status":"UNPLAYABLE","reason":"nope"}}"#),
        );
        let err = resolve_audio::<MockEnv>("vid", &AudioPrefs::default()).await.unwrap_err();
        assert_eq!(err.kind(), ErrorKind::Permanent);
        assert!(err.to_string().contains("visionos-1.03: UNPLAYABLE nope"));

        assert_eq!(InnertubeError::Status(429).kind(), ErrorKind::RateLimited);
        assert_eq!(InnertubeError::Network("x".into()).kind(), ErrorKind::Transient);
    }

    #[tokio::test]
    async fn the_visitor_id_is_cached() {
        let _guard = mock::lock_and_reset();
        reset_last_good();
        mock_visitor();
        mock::on_prefix(format!("{API}/player"), HttpResponse::ok(player_ok(formats())));
        resolve_audio::<MockEnv>("a", &AudioPrefs::default()).await.unwrap();
        resolve_audio::<MockEnv>("b", &AudioPrefs::default()).await.unwrap();
        let asked = mock::state().requests.iter().filter(|r| r.url.contains("/visitor_id")).count();
        assert_eq!(asked, 1);
    }
}
