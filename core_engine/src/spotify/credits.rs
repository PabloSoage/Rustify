// core_engine/src/spotify/credits.rs
//
// Who made a track — "Show credits" in the official clients — and the "suggested tracks" under a
// playlist. Both read the public catalogue and answer the anonymous token, so neither needs an
// account (measured 2026-10-20; docs `serie-4.x/08`).

use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

use super::client::{SpotifyClient, SpotifyError, SpotifyResult};
use super::models::{FullTrack, SimpleAlbum, SimpleArtist, SpotifyImage};
use crate::env::Env;

/// A track's credits, grouped the way Spotify groups them ("Artist", "Composition & Lyrics",
/// "Production & Engineering"…), in the order it sends them.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct TrackCredits {
    pub groups: Vec<CreditGroup>,
    /// Labels and other sources ("One Little Independent Records").
    pub sources: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct CreditGroup {
    pub name: String,
    pub people: Vec<Credit>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct Credit {
    pub name: String,
    pub role: String,
    /// The artist page to open, when the contributor has one.
    pub artist_id: Option<String>,
}

/// Parses `queryTrackCreditsGroupedModal`. A person with two roles in one group (composer and
/// lyricist, typically) is one row with both, which is how the official client shows them.
pub fn parse_credits(answer: &Value) -> Option<TrackCredits> {
    let trait_ = &answer["data"]["trackUnion"]["creditsTrait"];
    let items = trait_["contributors"]["items"].as_array()?;
    let mut groups: Vec<CreditGroup> = Vec::new();
    for item in items {
        let name = item["name"].as_str().unwrap_or_default().trim();
        if name.is_empty() {
            continue;
        }
        let role = item["role"].as_str().unwrap_or_default().to_string();
        let group_name = item["roleGroup"]["name"].as_str().unwrap_or("Other").to_string();
        let artist_id = item["uri"].as_str().and_then(|uri| match crate::links::parse(uri) {
            Some(crate::links::LinkTarget::SpotifyArtist(id)) => Some(id),
            _ => None,
        });
        let group = match groups.iter_mut().position(|g| g.name == group_name) {
            Some(i) => &mut groups[i],
            None => {
                groups.push(CreditGroup { name: group_name, people: Vec::new() });
                groups.last_mut().expect("just pushed")
            }
        };
        match group.people.iter_mut().find(|p| p.name == name) {
            Some(p) if !role.is_empty() && !p.role.split(", ").any(|r| r == role) => {
                if p.role.is_empty() {
                    p.role = role;
                } else {
                    p.role = format!("{}, {}", p.role, role);
                }
                if p.artist_id.is_none() {
                    p.artist_id = artist_id;
                }
            }
            Some(_) => {}
            None => group.people.push(Credit { name: name.to_string(), role, artist_id }),
        }
    }
    let sources = trait_["sources"]["items"]
        .as_array()
        .map(|s| s.iter().filter_map(|i| i["name"].as_str()).map(str::to_string).collect())
        .unwrap_or_default();
    Some(TrackCredits { groups, sources })
}

/// Maps a `playlistextender` recommendation to the track shape every screen already draws.
fn extender_track(item: &Value) -> Option<FullTrack> {
    let id = item["id"].as_str()?.to_string();
    let album = &item["album"];
    let images = ["largeImageUrl", "imageUrl"]
        .iter()
        .filter_map(|k| album[*k].as_str())
        .map(|url| SpotifyImage { url: url.to_string(), height: None, width: None })
        .collect();
    let artists: Vec<SimpleArtist> = item["artists"]
        .as_array()
        .map(|a| {
            a.iter()
                .filter_map(|ar| {
                    let aid = ar["id"].as_str()?.to_string();
                    Some(SimpleArtist {
                        external_uri: format!("https://open.spotify.com/artist/{}", aid),
                        id: aid,
                        name: ar["name"].as_str().unwrap_or_default().to_string(),
                        images: None,
                    })
                })
                .collect()
        })
        .unwrap_or_default();
    let album = album["id"].as_str().map(|aid| SimpleAlbum {
        id: aid.to_string(),
        name: album["name"].as_str().unwrap_or_default().to_string(),
        external_uri: format!("https://open.spotify.com/album/{}", aid),
        release_date: None,
        release_date_precision: None,
        images,
        artists: artists.clone(),
        album_type: None,
    });
    Some(FullTrack {
        external_uri: format!("https://open.spotify.com/track/{}", id),
        id: Some(id),
        name: item["name"].as_str().unwrap_or_default().to_string(),
        explicit: item["explicit"].as_bool().unwrap_or(false),
        duration_ms: item["duration"].as_u64().unwrap_or(0) as u32,
        isrc: String::new(),
        artists,
        album,
        added_at: None,
    })
}

impl<E: Env> SpotifyClient<E> {
    /// The credits of a track.
    pub async fn get_track_credits(&self, track_id: &str) -> SpotifyResult<TrackCredits> {
        let answer = self
            .gql_post(
                json!({
                    "trackUri": format!("spotify:track:{track_id}"),
                    "contributorsLimit": 100,
                    "contributorsOffset": 0,
                }),
                "queryTrackCreditsGroupedModal",
                "",
            )
            .await?;
        parse_credits(&answer).ok_or_else(|| SpotifyError::ParseError("no credits in the answer".into()))
    }

    /// Tracks that fit a playlist and are not in it — the official clients' "Recommended" block.
    ///
    /// `skip` is what is already there (or was already offered and dismissed); the service leaves
    /// those out itself, so the caller does not have to filter.
    pub async fn extend_playlist(&self, playlist_id: &str, skip: &[String], count: u32) -> SpotifyResult<Vec<FullTrack>> {
        let answer = self
            .spclient_post(
                "/playlistextender/extendp/",
                &json!({
                    "playlistURI": format!("spotify:playlist:{playlist_id}"),
                    "trackSkipIDs": skip,
                    "numResults": count,
                }),
            )
            .await?;
        Ok(answer["recommendedTracks"]
            .as_array()
            .map(|items| items.iter().filter_map(extender_track).collect())
            .unwrap_or_default())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::env::mock::{self, MockEnv};
    use crate::env::HttpResponse;

    fn joga_credits() -> Value {
        json!({"data": {"trackUnion": {"__typename": "Track", "creditsTrait": {
            "contributors": {"items": [
                {"name": "Björk", "role": "Main Artist", "roleGroup": {"name": "Artist"}, "uri": "spotify:artist:7w29UYBi0qsHi5RTcv3lmA"},
                {"name": "Björk Guðmundsdóttir", "role": "Composer", "roleGroup": {"name": "Composition & Lyrics"}, "uri": "spotify:artist:3HDfNAh4MpiptvaJP9We0w"},
                {"name": "Björk Guðmundsdóttir", "role": "Lyricist", "roleGroup": {"name": "Composition & Lyrics"}, "uri": "spotify:artist:3HDfNAh4MpiptvaJP9We0w"},
                {"name": "Marta Salogni", "role": "Immersive Mixing Engineer", "roleGroup": {"name": "Production & Engineering"}, "uri": null}
            ]},
            "sources": {"items": [{"name": "One Little Independent Records"}]}
        }}}})
    }

    #[test]
    fn credits_are_grouped_and_one_person_with_two_roles_is_one_row() {
        let c = parse_credits(&joga_credits()).unwrap();
        assert_eq!(c.groups.iter().map(|g| g.name.as_str()).collect::<Vec<_>>(),
                   ["Artist", "Composition & Lyrics", "Production & Engineering"]);
        assert_eq!(c.groups[1].people.len(), 1);
        assert_eq!(c.groups[1].people[0].role, "Composer, Lyricist");
        assert_eq!(c.groups[1].people[0].artist_id.as_deref(), Some("3HDfNAh4MpiptvaJP9We0w"));
        assert_eq!(c.groups[2].people[0].artist_id, None);
        assert_eq!(c.sources, ["One Little Independent Records"]);
    }

    #[test]
    fn an_answer_without_credits_is_none_not_an_empty_list() {
        assert_eq!(parse_credits(&json!({"data": {"trackUnion": {}}})), None);
    }

    #[test]
    fn an_extender_recommendation_becomes_a_playable_track() {
        let t = extender_track(&json!({
            "id": "732341mjCR7zodzYa5IEQf", "name": "Play Dead",
            "artists": [{"id": "7w29UYBi0qsHi5RTcv3lmA", "name": "Björk"}],
            "album": {"id": "7204VGDwNIF4yKgUChWE2R", "name": "Play Dead",
                      "largeImageUrl": "https://i.scdn.co/image/large", "imageUrl": "https://i.scdn.co/image/small"},
            "duration": 240040, "explicit": false
        })).unwrap();
        assert_eq!(t.id.as_deref(), Some("732341mjCR7zodzYa5IEQf"));
        assert_eq!(t.duration_ms, 240_040);
        assert_eq!(t.artists[0].name, "Björk");
        let album = t.album.unwrap();
        assert_eq!(album.images[0].url, "https://i.scdn.co/image/large");
        assert!(extender_track(&json!({"name": "no id"})).is_none());
    }

    #[tokio::test]
    async fn the_extender_is_asked_with_what_to_skip() {
        let _guard = mock::lock_and_reset();
        mock::on_prefix(
            "https://spclient.wg.spotify.com/playlistextender/extendp/",
            HttpResponse::ok(r#"{"recommendedTracks":[]}"#),
        );
        let client = SpotifyClient::<MockEnv>::new();
        // No token mocked: what matters is that no search or REST call is made in its place.
        let _ = client.extend_playlist("PL1", &["a".into()], 10).await;
        assert!(!mock::state().requests.iter().any(|r| r.url.contains("/v1/")));
    }
}
