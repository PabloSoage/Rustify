// core_engine/src/addon/transport.rs
//
// Talking to an addon. Two calls, fixed paths, and one struct that is the complete list of what
// leaves the device.

use super::manifest::{Manifest, MAX_MANIFEST_BYTES};
use super::registry::InstalledAddon;
use super::{security, AddonError};
use crate::env::{Env, HttpRequest};
use serde::{Deserialize, Serialize};

/// How long an addon has to answer before we move on to the next one in the chain.
///
/// Short on purpose: an addon is one link in a fallback chain, and a slow third party must not be
/// able to hold up playback. Ten seconds is already a long time to stare at a paused player.
pub const ADDON_TIMEOUT_MS: u64 = 10_000;

/// Largest stream answer we will read. It is a small JSON object.
pub const MAX_ANSWER_BYTES: usize = 16 * 1024;

/// **Everything an addon is told about a track. This struct is the privacy contract.**
///
/// It is a struct rather than a bag of query parameters precisely so it can be read at a glance,
/// shown to the user in the install dialog, and reviewed when it changes.
///
/// What is deliberately *not* here, and must never be added: the Spotify access token, the `sp_dc`
/// cookie, the account id, the user's library, what else is in the queue, or anything identifying
/// the device. An addon resolves audio for one song; it has no business knowing who is asking.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct TrackQuery {
    /// `spotify`, `ytm`, `isrc`, `local` -- from `TrackId::kind`.
    pub kind: String,
    /// The id within that kind. For `local` this is **not** sent; see [`TrackQuery::is_sendable`].
    pub id: String,
    pub title: String,
    pub artists: Vec<String>,
    pub duration_ms: u32,
    /// Recording identifier, when Spotify gave us one. The most precise thing an addon can match on.
    pub isrc: String,
}

impl TrackQuery {
    /// Whether this query may be sent to a third party at all.
    ///
    /// A `local` track is a file on the user's device: its "id" is a `content://` URI naming their
    /// storage layout, and there is nothing an addon could do with it anyway. Sending it would be
    /// leaking the shape of someone's phone to a third party for no benefit.
    pub fn is_sendable(&self) -> bool {
        self.kind != "local" && !(self.id.is_empty() && self.isrc.is_empty())
    }

    fn to_query_string(&self) -> String {
        let mut out = String::new();
        let mut push = |key: &str, value: &str| {
            if value.is_empty() {
                return;
            }
            if !out.is_empty() {
                out.push('&');
            }
            out.push_str(key);
            out.push('=');
            out.push_str(&urlencoding::encode(value));
        };
        push("kind", &self.kind);
        push("id", &self.id);
        push("title", &self.title);
        push("artist", &self.artists.join(", "));
        push("isrc", &self.isrc);
        if self.duration_ms > 0 {
            if !out.is_empty() {
                out.push('&');
            }
            out.push_str(&format!("duration_ms={}", self.duration_ms));
        }
        out
    }
}

/// What an addon answers with when it can resolve a track.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct StreamAnswer {
    pub url: String,
    #[serde(default)]
    pub mime: Option<String>,
    /// Epoch millis. Resolved URLs expire -- a googlevideo one lasts about six hours.
    #[serde(default, rename = "expiresAtMs")]
    pub expires_at_ms: Option<i64>,
}

/// Fetches and validates an addon's manifest. This is what "install by URL" runs.
pub async fn fetch_manifest<E: Env>(base_url: &str) -> Result<(Manifest, String), AddonError> {
    let base = security::validate_addon_url(base_url)?;
    let normalised = normalise_base(base.as_str());
    let manifest_url = format!("{normalised}/manifest.json");

    let response = E::fetch(
        HttpRequest::get(manifest_url.as_str())
            .header("Accept", "application/json")
            .timeout_ms(ADDON_TIMEOUT_MS),
    )
    .await?;

    if !response.is_success() {
        return Err(AddonError::Unreachable(format!(
            "manifest request answered {}",
            response.status
        )));
    }
    // Checked against the body we actually received rather than trusting Content-Length, which the
    // sender controls.
    if response.body.len() > MAX_MANIFEST_BYTES {
        return Err(AddonError::InvalidManifest("manifest too large".into()));
    }

    let manifest = Manifest::parse(&response.body)?;
    Ok((manifest, normalised))
}

/// Asks an addon to resolve a track.
///
/// `Ok(None)` means the addon answered and does not have it -- a normal outcome that should move the
/// chain on to the next provider without logging an error.
pub async fn resolve_stream<E: Env>(
    addon: &InstalledAddon,
    query: &TrackQuery,
) -> Result<Option<StreamAnswer>, AddonError> {
    if !query.is_sendable() {
        return Ok(None);
    }
    if !addon.manifest.handles_kind(&query.kind) {
        return Ok(None);
    }

    let url = format!("{}/stream?{}", addon.base_url, query.to_query_string());
    let response = E::fetch(
        HttpRequest::get(url.as_str())
            .header("Accept", "application/json")
            .timeout_ms(ADDON_TIMEOUT_MS),
    )
    .await?;

    if response.status == 404 || response.status == 204 {
        return Ok(None);
    }
    if !response.is_success() {
        return Err(AddonError::Unreachable(format!(
            "stream request answered {}",
            response.status
        )));
    }
    if response.body.len() > MAX_ANSWER_BYTES {
        return Err(AddonError::Protocol("answer too large".into()));
    }

    let answer: StreamAnswer = serde_json::from_slice(&response.body)
        .map_err(|e| AddonError::Protocol(format!("could not read the answer: {e}")))?;

    // The addon's own URL was checked at install time; this one arrives at play time and is checked
    // every time, because it is the one that gets handed to the player.
    security::validate_stream_url(&answer.url)?;
    Ok(Some(answer))
}

/// Trailing slashes are stripped so `{base}/manifest.json` never becomes `{base}//manifest.json`.
fn normalise_base(url: &str) -> String {
    url.trim_end_matches('/').to_owned()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::env::mock::{self, MockEnv};
    use crate::env::HttpResponse;

    const MANIFEST: &str = r#"{"id":"org.example.b","version":"1","name":"Example","resources":["stream"]}"#;

    fn query() -> TrackQuery {
        TrackQuery {
            kind: "spotify".into(),
            id: "4uLU6hMCjMI75M1A2tKUQC".into(),
            title: "A song".into(),
            artists: vec!["An artist".into()],
            duration_ms: 210_000,
            isrc: "USUM71700966".into(),
        }
    }

    fn installed() -> InstalledAddon {
        InstalledAddon {
            manifest: Manifest::parse(MANIFEST.as_bytes()).unwrap(),
            base_url: "https://addons.example.org/b".into(),
            enabled: true,
        }
    }

    #[tokio::test]
    async fn installing_fetches_and_validates_the_manifest() {
        let _guard = mock::lock_and_reset();
        mock::on_url(
            "https://addons.example.org/b/manifest.json",
            HttpResponse::ok(MANIFEST),
        );
        // The trailing slash must not produce a double slash in the manifest URL.
        let (manifest, base) = fetch_manifest::<MockEnv>("https://addons.example.org/b/")
            .await
            .unwrap();
        assert_eq!(manifest.id, "org.example.b");
        assert_eq!(base, "https://addons.example.org/b");
    }

    #[tokio::test]
    async fn a_private_address_is_refused_before_any_request_leaves() {
        let _guard = mock::lock_and_reset();
        assert!(fetch_manifest::<MockEnv>("https://169.254.169.254/")
            .await
            .is_err());
        // And nothing was even attempted -- the check is before the fetch, not after it.
        assert!(mock::state().requests.is_empty());
    }

    #[tokio::test]
    async fn a_resolved_url_is_validated_every_time_not_just_at_install() {
        let _guard = mock::lock_and_reset();
        mock::on_prefix(
            "https://addons.example.org/b/stream",
            HttpResponse::ok(r#"{"url":"file:///data/data/com.varuna.rustify/x.db"}"#),
        );
        let result = resolve_stream::<MockEnv>(&installed(), &query()).await;
        assert!(matches!(result, Err(AddonError::Refused(_))));
    }

    #[tokio::test]
    async fn a_loopback_stream_url_is_refused() {
        let _guard = mock::lock_and_reset();
        // Would aim the player at this device's own services, the local streaming server included.
        mock::on_prefix(
            "https://addons.example.org/b/stream",
            HttpResponse::ok(r#"{"url":"http://127.0.0.1:9/steal"}"#),
        );
        assert!(resolve_stream::<MockEnv>(&installed(), &query())
            .await
            .is_err());
    }

    #[tokio::test]
    async fn a_good_answer_comes_back_whole() {
        let _guard = mock::lock_and_reset();
        mock::on_prefix(
            "https://addons.example.org/b/stream",
            HttpResponse::ok(
                r#"{"url":"https://cdn.example.org/a.m4a","mime":"audio/mp4","expiresAtMs":123}"#,
            ),
        );
        let answer = resolve_stream::<MockEnv>(&installed(), &query())
            .await
            .unwrap()
            .unwrap();
        assert_eq!(answer.url, "https://cdn.example.org/a.m4a");
        assert_eq!(answer.mime.as_deref(), Some("audio/mp4"));
        assert_eq!(answer.expires_at_ms, Some(123));
    }

    #[tokio::test]
    async fn not_having_the_track_is_not_an_error() {
        let _guard = mock::lock_and_reset();
        mock::on_prefix(
            "https://addons.example.org/b/stream",
            HttpResponse::with_status(404, ""),
        );
        assert_eq!(
            resolve_stream::<MockEnv>(&installed(), &query())
                .await
                .unwrap(),
            None
        );
    }

    #[tokio::test]
    async fn a_local_track_is_never_sent_to_a_third_party() {
        let _guard = mock::lock_and_reset();
        let local = TrackQuery {
            kind: "local".into(),
            id: "content://media/external/audio/media/1234".into(),
            ..query()
        };
        assert!(!local.is_sendable());
        assert_eq!(
            resolve_stream::<MockEnv>(&installed(), &local)
                .await
                .unwrap(),
            None
        );
        // The point: the request never happened. Someone's storage layout is not addon business.
        assert!(mock::state().requests.is_empty());
    }

    #[test]
    fn the_query_string_carries_metadata_and_nothing_else() {
        let encoded = query().to_query_string();
        assert!(encoded.contains("kind=spotify"));
        assert!(encoded.contains("isrc=USUM71700966"));
        assert!(encoded.contains("duration_ms=210000"));
        assert!(encoded.contains("artist=An%20artist"));
        // The whole privacy claim, as an assertion rather than a promise in a comment.
        for forbidden in ["token", "sp_dc", "cookie", "Bearer", "user"] {
            assert!(
                !encoded.contains(forbidden),
                "query string leaked {forbidden}: {encoded}"
            );
        }
    }
}
