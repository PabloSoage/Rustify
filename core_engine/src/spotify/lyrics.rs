// core_engine/src/spotify/lyrics.rs
//
// Spotify's own lyrics (4.3): what the official app shows, asked for by track id.
//
// Every other lyrics source is matched by "artist + title", which is exactly the kind of match that
// fails quietly (a remaster, a featuring, an accent). This one is asked for the track itself.
//
// Measured on 2026-10-20: `color-lyrics` answers 400 to the anonymous token, with or without a
// client-token — it needs the user's session, so without one this returns `NotAuthenticated` and the
// app falls through to its other providers. The request is the one Morphe's lyrics patch makes
// (`lyrics/requests/SpotifyProvider.java`): the user's bearer token, `app-platform: WebPlayer`, and
// the web player's `client-token`.
//
// About the client-token: Spotify's protocol allows it to answer with a **challenge** (hashcash, a
// script to evaluate, an HMAC) instead of a token (`Spotify.dll`: `spotify.clienttoken.http.v0.*`).
// The web client was granted one directly when measured. A challenge is not solved here — that is an
// anti-abuse defence, not an interface — and the request then goes without one.

use crate::env::{Env, HttpRequest, Method};
use crate::spotify::client::*;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

const CLIENT_TOKEN_URL: &str = "https://clienttoken.spotify.com/v1/clienttoken";
/// The web player version the requests present, as Morphe does.
const WEB_PLAYER_VERSION: &str = "1.2.57.183.g8c1b7eb0";

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct SpotifyLyricLine {
    pub start_ms: i64,
    pub words: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct SpotifyLyrics {
    /// `LINE_SYNCED`, `SYLLABLE_SYNCED` or `UNSYNCED`.
    pub sync_type: String,
    pub lines: Vec<SpotifyLyricLine>,
    /// Who licensed them (Musixmatch, LyricFind…), for the credit line.
    pub provider: String,
}

/// Parses a `color-lyrics/v2` answer. Times arrive as strings.
pub fn parse_lyrics(answer: &Value) -> Option<SpotifyLyrics> {
    let lyrics = &answer["lyrics"];
    let lines: Vec<SpotifyLyricLine> = lyrics["lines"]
        .as_array()?
        .iter()
        .map(|line| SpotifyLyricLine {
            start_ms: line["startTimeMs"]
                .as_str()
                .and_then(|s| s.parse().ok())
                .or_else(|| line["startTimeMs"].as_i64())
                .unwrap_or(0),
            words: line["words"].as_str().unwrap_or("").to_string(),
        })
        .collect();
    if lines.iter().all(|l| l.words.trim().is_empty()) {
        return None;
    }
    Some(SpotifyLyrics {
        sync_type: lyrics["syncType"].as_str().unwrap_or("UNSYNCED").to_string(),
        provider: lyrics["provider"].as_str().unwrap_or("").to_string(),
        lines,
    })
}

impl<E: Env> SpotifyClient<E> {
    /// The web player's client-token for this session, cached until it expires. `None` when Spotify
    /// answered with a challenge, or when there is no session to ask for.
    async fn client_token(&self) -> Option<String> {
        {
            let cached = self.client_token.read().unwrap_or_else(std::sync::PoisonError::into_inner);
            if let Some((token, expires)) = cached.as_ref() {
                if self.now_ms() + 60_000 < *expires {
                    return Some(token.clone());
                }
            }
        }
        let client_id = self.session_client_id()?;
        let body = json!({
            "client_data": {
                "client_version": WEB_PLAYER_VERSION,
                "client_id": client_id,
                "js_sdk_data": {
                    "device_brand": "unknown",
                    "device_model": "unknown",
                    "os": "windows",
                    "os_version": "NT 10.0",
                    "device_id": format!("{:032x}", self.now_ms()),
                    "device_type": "computer"
                }
            }
        });
        let req: HttpRequest = self
            .base_request(Method::Post, CLIENT_TOKEN_URL)
            .header("Accept", "application/json")
            .body_json(&body)
            .ok()?;
        let res = E::fetch(req).await.ok()?;
        if !res.is_success() {
            return None;
        }
        let answer: Value = res.json().ok()?;
        if answer["response_type"].as_str() != Some("RESPONSE_GRANTED_TOKEN_RESPONSE") {
            return None;
        }
        let token = answer["granted_token"]["token"].as_str()?.to_string();
        let lifetime_s = answer["granted_token"]["expires_after_seconds"].as_u64().unwrap_or(3600);
        *self.client_token.write().unwrap_or_else(std::sync::PoisonError::into_inner) =
            Some((token.clone(), self.now_ms() + lifetime_s * 1000));
        Some(token)
    }

    /// Spotify's lyrics for a track, for the signed-in user.
    pub async fn get_lyrics(&self, track_id: &str) -> SpotifyResult<SpotifyLyrics> {
        // The user's own token, never the anonymous one: measured, it is refused.
        let token = self.access_token()?;
        let mut req = self
            .base_request(
                Method::Get,
                format!("https://spclient.wg.spotify.com/color-lyrics/v2/track/{track_id}?format=json&market=from_token"),
            )
            .bearer(&token)
            .header("App-Platform", "WebPlayer")
            .header("Accept", "application/json")
            .header("spotify-app-version", WEB_PLAYER_VERSION);
        if let Some(ct) = self.client_token().await {
            req = req.header("client-token", ct);
        }
        let res = self.send_checked(req).await?;
        let answer: Value = res
            .json()
            .map_err(|e| SpotifyError::ParseError(format!("lyrics parse error: {e}")))?;
        parse_lyrics(&answer).ok_or_else(|| SpotifyError::ParseError("no lyrics in answer".into()))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::env::mock::{self, MockEnv};

    #[test]
    fn a_line_synced_answer_becomes_lines_with_times() {
        let answer = json!({"lyrics": {"syncType": "LINE_SYNCED", "provider": "MusixMatch", "lines": [
            {"startTimeMs": "1230", "words": "All these accidents", "syllables": [], "endTimeMs": "0"},
            {"startTimeMs": "5400", "words": "♪", "syllables": [], "endTimeMs": "0"}
        ]}});
        let lyrics = parse_lyrics(&answer).unwrap();
        assert_eq!(lyrics.sync_type, "LINE_SYNCED");
        assert_eq!(lyrics.lines[0], SpotifyLyricLine { start_ms: 1230, words: "All these accidents".into() });
        assert_eq!(lyrics.provider, "MusixMatch");
    }

    #[test]
    fn an_answer_with_no_words_is_no_lyrics() {
        assert!(parse_lyrics(&json!({"lyrics": {"lines": [{"startTimeMs": "0", "words": ""}]}})).is_none());
        assert!(parse_lyrics(&json!({})).is_none());
    }

    #[tokio::test]
    async fn without_a_session_nothing_is_asked() {
        let _guard = mock::lock_and_reset();
        let result = SpotifyClient::<MockEnv>::new().get_lyrics("abc").await;
        assert!(matches!(result, Err(SpotifyError::NotAuthenticated)));
        assert!(mock::state().requests.is_empty());
    }
}
