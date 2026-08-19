// core_engine/src/links/mod.rs
//
// Reading and writing the links Rustify understands — point F of the stremio-core evaluation.
//
// This used to be four Kotlin files with their own regexes (`SpotifyLinkParser`,
// `YtMusicLinkParser`, `RustifyWrapperLink`, and an inline copy inside `MainActivity`). They agreed
// today; nothing made them agree tomorrow, and the same evaluation notes that a divergent regex is
// what this replaced once already.
//
// It belongs in the core for the same reason `TrackId` does: it is pure, it is exact, and every
// platform needs the identical answer. Windows and iOS inherit it instead of writing a third and
// fourth version.
//
// Deliberately **not** a URL validator. Anything unrecognised is `None`, and `None` means "this is
// not one of our links" — never "this link is dangerous". Refusing hostile URLs is `addon::security`
// and it is a different question with different rules.

use crate::types::id::TrackId;
use url::Url;

/// A link Rustify can act on.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum LinkTarget {
    SpotifyTrack(String),
    SpotifyAlbum(String),
    SpotifyPlaylist(String),
    SpotifyArtist(String),
    YtmTrack(String),
    YtmAlbum(String),
    YtmArtist(String),
    YtmPlaylist(String),
}

impl LinkTarget {
    /// The word used in the `rustify://` scheme and in the deep-link token.
    pub fn kind(&self) -> &'static str {
        match self {
            LinkTarget::SpotifyTrack(_) => "track",
            LinkTarget::SpotifyAlbum(_) => "album",
            LinkTarget::SpotifyPlaylist(_) => "playlist",
            LinkTarget::SpotifyArtist(_) => "artist",
            LinkTarget::YtmTrack(_) => "ytmtrack",
            LinkTarget::YtmAlbum(_) => "ytmalbum",
            LinkTarget::YtmArtist(_) => "ytmartist",
            LinkTarget::YtmPlaylist(_) => "ytmplaylist",
        }
    }

    pub fn id(&self) -> &str {
        match self {
            LinkTarget::SpotifyTrack(id)
            | LinkTarget::SpotifyAlbum(id)
            | LinkTarget::SpotifyPlaylist(id)
            | LinkTarget::SpotifyArtist(id)
            | LinkTarget::YtmTrack(id)
            | LinkTarget::YtmAlbum(id)
            | LinkTarget::YtmArtist(id)
            | LinkTarget::YtmPlaylist(id) => id,
        }
    }

    /// `"track:ID"` — what the app routes on internally.
    pub fn deep_link_token(&self) -> String {
        format!("{}:{}", self.kind(), self.id())
    }

    /// `"rustify://track/ID"` — the custom scheme, used when no verified host is configured.
    pub fn rustify_scheme(&self) -> String {
        format!("rustify://{}/{}", self.kind(), self.id())
    }

    /// The public URL this target came from, or would come from.
    pub fn canonical_url(&self) -> String {
        match self {
            LinkTarget::SpotifyTrack(id) => format!("https://open.spotify.com/track/{id}"),
            LinkTarget::SpotifyAlbum(id) => format!("https://open.spotify.com/album/{id}"),
            LinkTarget::SpotifyPlaylist(id) => format!("https://open.spotify.com/playlist/{id}"),
            LinkTarget::SpotifyArtist(id) => format!("https://open.spotify.com/artist/{id}"),
            LinkTarget::YtmTrack(id) => format!("https://music.youtube.com/watch?v={id}"),
            LinkTarget::YtmAlbum(id) => format!("https://music.youtube.com/browse/{id}"),
            LinkTarget::YtmArtist(id) => format!("https://music.youtube.com/channel/{id}"),
            LinkTarget::YtmPlaylist(id) => format!("https://music.youtube.com/playlist?list={id}"),
        }
    }

    /// The track this link names, if it names one.
    ///
    /// Through [`TrackId`] rather than by building a string, so a link and a queue entry cannot
    /// disagree about what a YouTube Music track is called — the whole point of point D.
    pub fn track_id(&self) -> Option<TrackId> {
        match self {
            LinkTarget::SpotifyTrack(id) => Some(TrackId::Spotify(id.clone())),
            LinkTarget::YtmTrack(id) => Some(TrackId::Ytm(id.clone())),
            _ => None,
        }
    }
}

/// Pulls the first URL out of arbitrary text — a share sheet hands over "Look at this: https://…".
fn first_url(text: &str) -> Option<&str> {
    let start = text.find("http://").into_iter().chain(text.find("https://")).min()?;
    let rest = &text[start..];
    let end = rest
        .find(|c: char| c.is_whitespace())
        .unwrap_or(rest.len());
    Some(&rest[..end])
}

/// Reads any link Rustify understands out of `text`.
pub fn parse(text: &str) -> Option<LinkTarget> {
    let raw = first_url(text).unwrap_or_else(|| text.trim());
    let url = Url::parse(raw).ok()?;
    let host = url.host_str()?.to_ascii_lowercase();

    match host.as_str() {
        "open.spotify.com" => parse_spotify(&url),
        "youtu.be" => url
            .path_segments()?
            .next_back()
            .filter(|s| !s.is_empty())
            .map(|v| LinkTarget::YtmTrack(v.to_string())),
        "music.youtube.com" | "www.youtube.com" | "youtube.com" | "m.youtube.com" => {
            parse_youtube(&url)
        }
        _ => None,
    }
}

fn parse_spotify(url: &Url) -> Option<LinkTarget> {
    let mut segments = url.path_segments()?;
    let mut first = segments.next()?;
    // `open.spotify.com/intl-es/track/ID` — the localised form, which the first version of this
    // parser did not know about and which is what most shared links look like now.
    if first.starts_with("intl-") {
        first = segments.next()?;
    }
    let id = segments.next().filter(|s| !s.is_empty())?;
    // Ids are base62; anything else is a path that happens to start with the right word.
    if !id.chars().all(|c| c.is_ascii_alphanumeric()) {
        return None;
    }
    match first {
        "track" => Some(LinkTarget::SpotifyTrack(id.to_string())),
        "album" => Some(LinkTarget::SpotifyAlbum(id.to_string())),
        "playlist" => Some(LinkTarget::SpotifyPlaylist(id.to_string())),
        "artist" => Some(LinkTarget::SpotifyArtist(id.to_string())),
        _ => None,
    }
}

fn parse_youtube(url: &Url) -> Option<LinkTarget> {
    let mut video = None;
    let mut list = None;
    for (key, value) in url.query_pairs() {
        match key.as_ref() {
            "v" if !value.is_empty() => video = Some(value.into_owned()),
            "list" if !value.is_empty() => list = Some(value.into_owned()),
            _ => {}
        }
    }
    // A watch URL inside a playlist is a track first: that is the thing the user tapped.
    if let Some(v) = video {
        return Some(LinkTarget::YtmTrack(v));
    }
    if let Some(l) = list {
        return Some(LinkTarget::YtmPlaylist(l));
    }

    let segments: Vec<&str> = url.path_segments()?.filter(|s| !s.is_empty()).collect();
    match (segments.first(), segments.get(1)) {
        (Some(&"playlist"), Some(id)) => Some(LinkTarget::YtmPlaylist(id.to_string())),
        (Some(&"browse"), Some(id)) => Some(LinkTarget::YtmAlbum(id.to_string())),
        (Some(&"channel"), Some(id)) => Some(LinkTarget::YtmArtist(id.to_string())),
        _ => None,
    }
}

/// Wraps a link so that tapping it opens Rustify.
///
/// With a `host` the user controls and an `assetlinks.json` on it, the wrapper is a **verified** App
/// Link and Android opens it without asking. Without one it falls back to the custom scheme, which
/// works and is not verified — any app may claim it.
///
/// An unrecognised URL with a host is still wrapped: the host form wraps text, not a parsed link, so
/// a Rustify build that learns a new link type does not need the wrapper to learn it too.
pub fn wrap(url: &str, host: Option<&str>) -> String {
    if let Some(host) = host.filter(|h| !h.trim().is_empty()) {
        return format!(
            "https://{}/r/?s={}",
            host.trim(),
            percent_encode(url)
        );
    }
    match parse(url) {
        Some(target) => target.rustify_scheme(),
        None => url.to_string(),
    }
}

/// Recovers the wrapped link from `https://<known host>/r/?s=…`, or `None` if it is not one.
///
/// The host list is checked rather than trusted: an arbitrary site is not allowed to hand us a link
/// and have it treated as if it came from the user's own domain.
pub fn unwrap_wrapper(url: &str, known_hosts: &[&str]) -> Option<String> {
    let parsed = Url::parse(url).ok()?;
    let host = parsed.host_str()?.to_ascii_lowercase();
    if !known_hosts.iter().any(|h| h.eq_ignore_ascii_case(&host)) {
        return None;
    }
    let mut segments = parsed.path_segments()?;
    if segments.next() != Some("r") {
        return None;
    }
    // Form A: `/r/?s=<the whole url>`, which is what `wrap` produces.
    if let Some(payload) = parsed
        .query_pairs()
        .find(|(k, _)| k == "s")
        .map(|(_, v)| v.into_owned())
        .filter(|v| !v.is_empty())
    {
        return Some(payload);
    }
    // Form B: `/r/track/ID`, which older links and hand-written ones use. Kept because links live
    // in other people's messages long after the app that made them has changed.
    let rest: Vec<&str> = segments.filter(|s| !s.is_empty()).collect();
    if rest.is_empty() {
        return None;
    }
    Some(rest.join("/"))
}

/// Percent-encodes everything that is not unreserved, which is what a query value needs.
///
/// Hand-rolled rather than pulled from `urlencoding`, whose `encode` leaves `/` and `:` alone — fine
/// for a path segment, wrong for a value that *is* a URL.
fn percent_encode(raw: &str) -> String {
    let mut out = String::with_capacity(raw.len() * 2);
    for byte in raw.as_bytes() {
        match byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(*byte as char)
            }
            other => out.push_str(&format!("%{other:02X}")),
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_shapes_a_shared_spotify_link_actually_has() {
        assert_eq!(
            parse("https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT"),
            Some(LinkTarget::SpotifyTrack("4cOdK2wGLETKBW3PvgPWqT".into()))
        );
        // The localised form, which is what the share sheet produces most of the time.
        assert_eq!(
            parse("https://open.spotify.com/intl-es/album/1DFixLWuPkv3KT3TnV35m3"),
            Some(LinkTarget::SpotifyAlbum("1DFixLWuPkv3KT3TnV35m3".into()))
        );
        // With the tracking parameters Spotify appends.
        assert_eq!(
            parse("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abc123"),
            Some(LinkTarget::SpotifyPlaylist("37i9dQZF1DXcBWIGoYBM5M".into()))
        );
        // Buried in the text a person actually shares.
        assert_eq!(
            parse("escucha esto https://open.spotify.com/artist/0TnOYISbd1XYRBk9myaseg ahora"),
            Some(LinkTarget::SpotifyArtist("0TnOYISbd1XYRBk9myaseg".into()))
        );
    }

    #[test]
    fn the_shapes_a_shared_youtube_link_actually_has() {
        assert_eq!(
            parse("https://music.youtube.com/watch?v=dQw4w9WgXcQ"),
            Some(LinkTarget::YtmTrack("dQw4w9WgXcQ".into()))
        );
        assert_eq!(
            parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42"),
            Some(LinkTarget::YtmTrack("dQw4w9WgXcQ".into()))
        );
        assert_eq!(
            parse("https://youtu.be/dQw4w9WgXcQ"),
            Some(LinkTarget::YtmTrack("dQw4w9WgXcQ".into()))
        );
        assert_eq!(
            parse("https://music.youtube.com/playlist?list=PL123"),
            Some(LinkTarget::YtmPlaylist("PL123".into()))
        );
        assert_eq!(
            parse("https://music.youtube.com/browse/MPREb_abc"),
            Some(LinkTarget::YtmAlbum("MPREb_abc".into()))
        );
        assert_eq!(
            parse("https://music.youtube.com/channel/UCabc"),
            Some(LinkTarget::YtmArtist("UCabc".into()))
        );
    }

    #[test]
    fn a_track_inside_a_playlist_is_the_track() {
        // The user tapped a song. Opening the playlist instead is the wrong thing, and both ids are
        // present in the URL, so the order this checks them in *is* the behaviour.
        assert_eq!(
            parse("https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=PL123"),
            Some(LinkTarget::YtmTrack("dQw4w9WgXcQ".into()))
        );
    }

    #[test]
    fn what_is_not_one_of_our_links_is_simply_not_one() {
        // `None` says "not ours", never "unsafe" — refusing hostile URLs is `addon::security`.
        assert_eq!(parse("https://example.com/track/abc"), None);
        assert_eq!(parse("not a url at all"), None);
        assert_eq!(parse(""), None);
        assert_eq!(parse("https://open.spotify.com/"), None);
        assert_eq!(parse("https://open.spotify.com/track/"), None);
        assert_eq!(parse("https://open.spotify.com/user/someone"), None);
        // An id with a slash in it is a path, not an id.
        assert_eq!(parse("https://open.spotify.com/track/ab%2Fcd"), None);
    }

    #[test]
    fn a_token_and_a_scheme_are_built_from_the_same_kind() {
        // These two used to be separate `when` blocks in separate files, which is exactly how they
        // would come to disagree about what "ytmalbum" is called.
        let target = LinkTarget::YtmAlbum("MPREb_abc".into());
        assert_eq!(target.deep_link_token(), "ytmalbum:MPREb_abc");
        assert_eq!(target.rustify_scheme(), "rustify://ytmalbum/MPREb_abc");

        let track = LinkTarget::SpotifyTrack("4cOdK2wGLETKBW3PvgPWqT".into());
        assert_eq!(track.deep_link_token(), "track:4cOdK2wGLETKBW3PvgPWqT");
        assert_eq!(track.rustify_scheme(), "rustify://track/4cOdK2wGLETKBW3PvgPWqT");
    }

    #[test]
    fn a_canonical_url_parses_back_to_what_produced_it() {
        for target in [
            LinkTarget::SpotifyTrack("4cOdK2wGLETKBW3PvgPWqT".into()),
            LinkTarget::SpotifyAlbum("1DFixLWuPkv3KT3TnV35m3".into()),
            LinkTarget::SpotifyPlaylist("37i9dQZF1DXcBWIGoYBM5M".into()),
            LinkTarget::SpotifyArtist("0TnOYISbd1XYRBk9myaseg".into()),
            LinkTarget::YtmTrack("dQw4w9WgXcQ".into()),
            LinkTarget::YtmAlbum("MPREbabc".into()),
            LinkTarget::YtmArtist("UCabc".into()),
            LinkTarget::YtmPlaylist("PL123".into()),
        ] {
            assert_eq!(
                parse(&target.canonical_url()),
                Some(target.clone()),
                "{target:?} did not survive its own url"
            );
        }
    }

    #[test]
    fn a_link_names_the_same_track_the_queue_would() {
        // Point D applied to point F: a link and a queue entry cannot disagree about a YTM id.
        assert_eq!(
            parse("https://music.youtube.com/watch?v=dQw4w9WgXcQ")
                .and_then(|t| t.track_id())
                .map(|id| id.to_string()),
            Some("ytm:dQw4w9WgXcQ".to_string())
        );
        assert_eq!(
            parse("https://open.spotify.com/album/1DFixLWuPkv3KT3TnV35m3")
                .and_then(|t| t.track_id()),
            None
        );
    }

    #[test]
    fn wrapping_with_a_host_survives_the_round_trip() {
        let hosts = ["rustify-music.github.io", "pablosoage.github.io"];
        let original = "https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT?si=x&y=1";
        let wrapped = wrap(original, Some("rustify-music.github.io"));

        assert!(wrapped.starts_with("https://rustify-music.github.io/r/?s="));
        // The `?`, `&` and `/` of the inner URL must not leak into the outer query, or everything
        // after the first `&` is silently a different parameter.
        assert!(!wrapped["https://rustify-music.github.io/r/?s=".len()..].contains('&'));
        assert_eq!(unwrap_wrapper(&wrapped, &hosts).as_deref(), Some(original));
    }

    #[test]
    fn wrapping_without_a_host_falls_back_to_the_scheme() {
        assert_eq!(
            wrap("https://open.spotify.com/track/abc123", None),
            "rustify://track/abc123"
        );
        assert_eq!(
            wrap("https://music.youtube.com/watch?v=dQw4w9WgXcQ", Some("  ")),
            "rustify://ytmtrack/dQw4w9WgXcQ"
        );
        // Nothing recognisable and nowhere to wrap it: hand back what came in rather than a link
        // that goes nowhere.
        assert_eq!(wrap("https://example.com/x", None), "https://example.com/x");
    }

    #[test]
    fn a_wrapper_from_a_host_we_do_not_know_is_not_unwrapped() {
        // Otherwise any site could hand the app a link and have it treated as the user's own.
        let hosts = ["rustify-music.github.io"];
        assert_eq!(
            unwrap_wrapper("https://evil.example/r/?s=https%3A%2F%2Fx", &hosts),
            None
        );
        assert_eq!(
            unwrap_wrapper("https://rustify-music.github.io/other?s=x", &hosts),
            None
        );
        assert_eq!(
            unwrap_wrapper("https://rustify-music.github.io/r/", &hosts),
            None
        );
    }

    #[test]
    fn the_older_path_form_of_a_wrapper_still_opens() {
        // `/r/track/ID` links are out there in other people's chat histories, and they do not get
        // rewritten when the app changes.
        let hosts = ["rustify-music.github.io"];
        assert_eq!(
            unwrap_wrapper("https://rustify-music.github.io/r/track/abc123", &hosts).as_deref(),
            Some("track/abc123")
        );
        assert_eq!(
            unwrap_wrapper(
                "https://rustify-music.github.io/r/open.spotify.com/track/abc123",
                &hosts
            )
            .as_deref(),
            Some("open.spotify.com/track/abc123")
        );
    }
}
