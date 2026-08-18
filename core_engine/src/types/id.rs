// core_engine/src/types/id.rs
//
// Content identity as sum types instead of strings with prefixes.
//
// Point D of docs/stremio-core/PLAN-3.x.md. The idea is `stremio-core`'s; the types are ours,
// because their `MetaItem`/`Video`/`Stream` model video catalogues, not audio.
//
// Why this exists at all — two shipped bugs, both the same design fault:
//
//   * **v2.11.3** — a `spotify:local:{artist}:{album}:{title}:{seconds}` URI was split on `:` and the
//     LAST segment (the duration) was taken as the id. Many local tracks ended up sharing the id
//     "180", and duplicate `LazyColumn` keys crashed the playlist screen.
//   * **v2.11.8** — a `ytm:` id reached the Spotify resolver, and the fix was a hand-written
//     `youtubeIdOf()` bolted on at two call sites. There are still a dozen loose
//     `startsWith("ytm:")` around the app.
//
// In both cases the identity was a `String` and the compiler had nothing to check. Here it does:
// matching a `TrackId` exhaustively means a new kind of track cannot be silently mishandled.
//
// **Wire compatibility is deliberate.** `Display` emits exactly the strings in use today and
// `FromStr` parses them back, so nothing persisted, serialized over JNI, or backed up to Drive has
// to change. This type is a lens over the existing format, not a new format.

use std::fmt;
use std::str::FromStr;

/// Everything that can appear in `FullTrack.id`.
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub enum TrackId {
    /// A real Spotify track: a bare base62 id, no prefix.
    Spotify(String),
    /// YouTube Music, stored as `ytm:{videoId}`.
    Ytm(String),
    /// A file on the device, stored as `local:{uri}` where the URI is a `content://` or `file://`
    /// URI. **The URI itself contains colons**, which is why this is never parsed by splitting.
    Local(String),
    /// A local file added to a Spotify playlist from the desktop app, kept as its whole
    /// `spotify:local:{artist}:{album}:{title}:{seconds}` URI.
    ///
    /// It carries **no Spotify id**, and that is the point: `spotify()` returns `None` here, so the
    /// v2.11.3 bug is not expressible. The engine already builds these with `id: None`
    /// (`spotify/client.rs::parse_local_track`); this variant lets the rest of the code say so.
    SpotifyLocal(String),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum IdError {
    /// The whole string was empty or blank.
    Empty,
    /// A prefix was present but nothing followed it — `"ytm:"` on its own.
    EmptyPayload(&'static str),
}

impl fmt::Display for IdError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            IdError::Empty => write!(f, "empty id"),
            IdError::EmptyPayload(prefix) => write!(f, "id has prefix '{prefix}' but no value"),
        }
    }
}

impl std::error::Error for IdError {}

impl TrackId {
    /// The YouTube video id, if this track is one. Replaces the hand-written `youtubeIdOf()` that
    /// v2.11.8 had to add in two places.
    pub fn youtube_video(&self) -> Option<&str> {
        match self {
            TrackId::Ytm(video_id) => Some(video_id),
            _ => None,
        }
    }

    /// The Spotify track id, if there is one. `SpotifyLocal` has none — that is not an oversight.
    pub fn spotify(&self) -> Option<&str> {
        match self {
            TrackId::Spotify(id) => Some(id),
            _ => None,
        }
    }

    /// The device URI, if this track is a local file.
    pub fn local_uri(&self) -> Option<&str> {
        match self {
            TrackId::Local(uri) => Some(uri),
            _ => None,
        }
    }

    /// Whether the audio lives on the device rather than behind a backend.
    pub fn is_on_device(&self) -> bool {
        matches!(self, TrackId::Local(_))
    }

    /// A short, stable name for the kind. For logs and diagnostics only — never persisted.
    pub fn kind(&self) -> &'static str {
        match self {
            TrackId::Spotify(_) => "spotify",
            TrackId::Ytm(_) => "ytm",
            TrackId::Local(_) => "local",
            TrackId::SpotifyLocal(_) => "spotify-local",
        }
    }
}

impl fmt::Display for TrackId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            TrackId::Spotify(id) => write!(f, "{id}"),
            TrackId::Ytm(video_id) => write!(f, "ytm:{video_id}"),
            TrackId::Local(uri) => write!(f, "local:{uri}"),
            // Already a full URI; re-prefixing it would corrupt it.
            TrackId::SpotifyLocal(uri) => write!(f, "{uri}"),
        }
    }
}

impl FromStr for TrackId {
    type Err = IdError;

    fn from_str(raw: &str) -> Result<Self, Self::Err> {
        if raw.trim().is_empty() {
            return Err(IdError::Empty);
        }
        // Longest prefix first. `spotify:local:` must be tested before anything treats the string as
        // a bare Spotify id, and `localpl:` never matches `local:` because the sixth byte is 'p'.
        if raw.starts_with(SPOTIFY_LOCAL_PREFIX) {
            return Ok(TrackId::SpotifyLocal(raw.to_owned()));
        }
        if let Some(video_id) = raw.strip_prefix(YTM_PREFIX) {
            return non_empty(video_id, YTM_PREFIX).map(|v| TrackId::Ytm(v.to_owned()));
        }
        // `strip_prefix` and nothing else: a `content://` URI is full of colons, and splitting is
        // precisely what broke v2.11.3.
        if let Some(uri) = raw.strip_prefix(LOCAL_PREFIX) {
            return non_empty(uri, LOCAL_PREFIX).map(|u| TrackId::Local(u.to_owned()));
        }
        Ok(TrackId::Spotify(raw.to_owned()))
    }
}

/// Playlist identity. Same idea, different alphabet of prefixes.
///
/// One honest gap: a **remote YouTube Music playlist id has no prefix**, so it is indistinguishable
/// from a Spotify playlist id by looking at it. Parsing a bare id therefore yields
/// [`PlaylistId::Spotify`]; a YTM one can only be built where the calling context already knows.
/// This type does not pretend to solve that, it just refuses to guess.
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub enum PlaylistId {
    Spotify(String),
    /// A playlist that exists only on the device, stored as `localpl:{uuid}`.
    Local(String),
    /// A YouTube Music playlist that exists only on the device, stored as `ytmpl:{uuid}`.
    YtmLocal(String),
    /// A remote YouTube Music playlist. Never produced by [`FromStr`]; see the type docs.
    Ytm(String),
}

impl PlaylistId {
    pub fn kind(&self) -> &'static str {
        match self {
            PlaylistId::Spotify(_) => "spotify",
            PlaylistId::Local(_) => "localpl",
            PlaylistId::YtmLocal(_) => "ytmpl",
            PlaylistId::Ytm(_) => "ytm",
        }
    }

    /// Whether the playlist lives only on this device, in either flavour.
    pub fn is_device_only(&self) -> bool {
        matches!(self, PlaylistId::Local(_) | PlaylistId::YtmLocal(_))
    }
}

impl fmt::Display for PlaylistId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            PlaylistId::Spotify(id) | PlaylistId::Ytm(id) => write!(f, "{id}"),
            PlaylistId::Local(id) => write!(f, "localpl:{id}"),
            PlaylistId::YtmLocal(id) => write!(f, "ytmpl:{id}"),
        }
    }
}

impl FromStr for PlaylistId {
    type Err = IdError;

    fn from_str(raw: &str) -> Result<Self, Self::Err> {
        if raw.trim().is_empty() {
            return Err(IdError::Empty);
        }
        if let Some(id) = raw.strip_prefix(LOCAL_PLAYLIST_PREFIX) {
            return non_empty(id, LOCAL_PLAYLIST_PREFIX).map(|i| PlaylistId::Local(i.to_owned()));
        }
        if let Some(id) = raw.strip_prefix(YTM_PLAYLIST_PREFIX) {
            return non_empty(id, YTM_PLAYLIST_PREFIX).map(|i| PlaylistId::YtmLocal(i.to_owned()));
        }
        Ok(PlaylistId::Spotify(raw.to_owned()))
    }
}

const YTM_PREFIX: &str = "ytm:";
const LOCAL_PREFIX: &str = "local:";
const SPOTIFY_LOCAL_PREFIX: &str = "spotify:local:";
const LOCAL_PLAYLIST_PREFIX: &str = "localpl:";
const YTM_PLAYLIST_PREFIX: &str = "ytmpl:";

fn non_empty<'a>(value: &'a str, prefix: &'static str) -> Result<&'a str, IdError> {
    if value.is_empty() {
        Err(IdError::EmptyPayload(prefix))
    } else {
        Ok(value)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn parse(raw: &str) -> TrackId {
        raw.parse::<TrackId>().expect("should parse")
    }

    #[test]
    fn every_shape_round_trips() {
        for raw in [
            "4uLU6hMCjMI75M1A2tKUQC",
            "ytm:dQw4w9WgXcQ",
            "local:content://media/external/audio/media/1234",
            "local:file:///storage/emulated/0/Music/a.mp3",
            "spotify:local:Nick+Cave:The+Boatman%27s+Call:Into+My+Arms:249",
        ] {
            assert_eq!(parse(raw).to_string(), raw, "round trip failed for {raw}");
        }
    }

    #[test]
    fn a_local_uri_is_never_split_on_colons() {
        // The whole point: `content://` is full of colons and slashes.
        let id = parse("local:content://media/external/audio/media/1234");
        assert_eq!(
            id.local_uri(),
            Some("content://media/external/audio/media/1234")
        );
        assert_eq!(id.spotify(), None);
        assert_eq!(id.youtube_video(), None);
        assert!(id.is_on_device());
    }

    #[test]
    fn v2_11_3_is_no_longer_expressible() {
        // The bug: this URI was split on ':' and "249" was taken as the track id, so every local
        // track of the same length collided. There is now no way to ask this value for a Spotify id
        // and get one.
        let id = parse("spotify:local:Nick+Cave:The+Boatman%27s+Call:Into+My+Arms:249");
        assert!(matches!(id, TrackId::SpotifyLocal(_)));
        assert_eq!(id.spotify(), None);
        assert_eq!(id.kind(), "spotify-local");

        // And two different local tracks of the same duration stay different.
        let other = parse("spotify:local:Other:Album:Song:249");
        assert_ne!(id, other);
    }

    #[test]
    fn v2_11_8_is_no_longer_expressible() {
        // The bug: a `ytm:` id reached the Spotify resolver. `spotify()` is the only way to get one
        // and it says no.
        let id = parse("ytm:dQw4w9WgXcQ");
        assert_eq!(id.spotify(), None);
        assert_eq!(id.youtube_video(), Some("dQw4w9WgXcQ"));
    }

    #[test]
    fn a_bare_id_is_a_spotify_track() {
        let id = parse("4uLU6hMCjMI75M1A2tKUQC");
        assert_eq!(id.spotify(), Some("4uLU6hMCjMI75M1A2tKUQC"));
        assert_eq!(id.youtube_video(), None);
        assert!(!id.is_on_device());
    }

    #[test]
    fn empty_and_truncated_ids_are_rejected() {
        assert_eq!("".parse::<TrackId>(), Err(IdError::Empty));
        assert_eq!("   ".parse::<TrackId>(), Err(IdError::Empty));
        assert_eq!(
            "ytm:".parse::<TrackId>(),
            Err(IdError::EmptyPayload("ytm:"))
        );
        assert_eq!(
            "local:".parse::<TrackId>(),
            Err(IdError::EmptyPayload("local:"))
        );
    }

    #[test]
    fn localpl_is_not_a_local_track() {
        // "localpl:" shares five bytes with "local:" and must not be mistaken for it. It is a
        // playlist id, so as a track id it is just an opaque Spotify-shaped string.
        let id = parse("localpl:0f1e2d3c");
        assert!(!id.is_on_device());
        assert_eq!(id.local_uri(), None);
    }

    #[test]
    fn playlist_shapes_round_trip() {
        for raw in ["37i9dQZF1DXcBWIGoYBM5M", "localpl:0f1e-2d3c", "ytmpl:9a8b"] {
            let parsed = raw.parse::<PlaylistId>().expect("should parse");
            assert_eq!(parsed.to_string(), raw);
        }
        assert!(matches!(
            "localpl:0f1e".parse::<PlaylistId>(),
            Ok(PlaylistId::Local(_))
        ));
        assert!(matches!(
            "ytmpl:9a8b".parse::<PlaylistId>(),
            Ok(PlaylistId::YtmLocal(_))
        ));
        assert!(!"37i9dQZF1DXcBWIGoYBM5M"
            .parse::<PlaylistId>()
            .unwrap()
            .is_device_only());
    }

    #[test]
    fn a_remote_ytm_playlist_still_prints_bare() {
        // It cannot be *parsed* into this variant, but it must survive being printed.
        assert_eq!(PlaylistId::Ytm("VLPL1234".into()).to_string(), "VLPL1234");
    }
}
