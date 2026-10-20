// core_engine/src/spotify/about.rs
//
// The "About" of an artist page (4.7): biography, monthly listeners, where they are listened to,
// and upcoming concerts. All of it is in the same `queryArtistOverview` answer the page already
// asks for, and it answers the anonymous token (measured 2026-10-21).

use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

use super::client::{SpotifyClient, SpotifyError, SpotifyResult};
use crate::env::Env;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct ArtistAbout {
    /// Plain text: Spotify sends it with `<a href="spotify:artist:…">` links, which are dropped.
    pub biography: String,
    pub monthly_listeners: Option<u64>,
    pub world_rank: Option<u32>,
    pub top_cities: Vec<TopCity>,
    pub concerts: Vec<Concert>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct TopCity {
    pub city: String,
    pub country: String,
    pub listeners: u64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct Concert {
    pub title: String,
    pub venue: String,
    pub city: String,
    /// ISO 8601 with the venue's offset, as Spotify sends it ("2026-12-28T20:00-05:00").
    pub starts_at: String,
    pub festival: bool,
}

/// Removes tags and decodes the few entities Spotify's biographies use.
fn plain_text(html: &str) -> String {
    let mut out = String::with_capacity(html.len());
    let mut in_tag = false;
    for c in html.chars() {
        match c {
            '<' => in_tag = true,
            '>' if in_tag => in_tag = false,
            _ if !in_tag => out.push(c),
            _ => {}
        }
    }
    out.replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .trim()
        .to_string()
}

/// Reads the about part of an `artistUnion`.
pub fn parse_about(artist: &Value) -> ArtistAbout {
    let stats = &artist["stats"];
    let top_cities = stats["topCities"]["items"]
        .as_array()
        .map(|items| {
            items
                .iter()
                .filter_map(|c| {
                    Some(TopCity {
                        city: c["city"].as_str()?.to_string(),
                        country: c["country"].as_str().unwrap_or_default().to_string(),
                        listeners: c["numberOfListeners"].as_u64().unwrap_or(0),
                    })
                })
                .collect()
        })
        .unwrap_or_default();
    let concerts = artist["goods"]["concerts"]["items"]
        .as_array()
        .map(|items| {
            items
                .iter()
                .filter_map(|i| {
                    let d = &i["data"];
                    Some(Concert {
                        title: d["title"].as_str()?.to_string(),
                        venue: d["location"]["name"].as_str().unwrap_or_default().to_string(),
                        city: d["location"]["city"].as_str().unwrap_or_default().to_string(),
                        starts_at: d["startDateIsoString"].as_str().unwrap_or_default().to_string(),
                        festival: d["festival"].as_bool().unwrap_or(false),
                    })
                })
                .collect()
        })
        .unwrap_or_default();
    ArtistAbout {
        biography: plain_text(artist["profile"]["biography"]["text"].as_str().unwrap_or_default()),
        monthly_listeners: stats["monthlyListeners"].as_u64(),
        world_rank: stats["worldRank"].as_u64().filter(|r| *r > 0).map(|r| r as u32),
        top_cities,
        concerts,
    }
}

impl<E: Env> SpotifyClient<E> {
    pub async fn get_artist_about(&self, id: &str) -> SpotifyResult<ArtistAbout> {
        let gql = self
            .gql_post(
                json!({ "uri": format!("spotify:artist:{id}"), "locale": "en", "includePrerelease": false }),
                "queryArtistOverview",
                "",
            )
            .await?;
        let artist = &gql["data"]["artistUnion"];
        if artist["__typename"].as_str() != Some("Artist") {
            return Err(SpotifyError::ApiError(404, "Artist not found".to_string()));
        }
        Ok(parse_about(artist))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_about_part_is_read_and_the_biography_is_plain_text() {
        let artist = json!({
            "profile": {"biography": {"text": "Inheritors of <a href=\"spotify:artist:0oSG\">David Bowie</a> &amp; more."}},
            "stats": {"monthlyListeners": 46481978u64, "worldRank": 82,
                      "topCities": {"items": [{"city": "London", "country": "GB", "numberOfListeners": 722950}]}},
            "goods": {"concerts": {"items": [{"data": {"title": "Maroon 5", "festival": false,
                "location": {"city": "Hollywood", "name": "Hard Rock Live"},
                "startDateIsoString": "2026-12-28T20:00-05:00"}}]}}
        });
        let a = parse_about(&artist);
        assert_eq!(a.biography, "Inheritors of David Bowie & more.");
        assert_eq!(a.monthly_listeners, Some(46_481_978));
        assert_eq!(a.world_rank, Some(82));
        assert_eq!(a.top_cities[0].city, "London");
        assert_eq!(a.concerts[0].venue, "Hard Rock Live");
        assert_eq!(a.concerts[0].starts_at, "2026-12-28T20:00-05:00");
    }

    #[test]
    fn rank_zero_means_unranked() {
        assert_eq!(parse_about(&json!({"stats": {"worldRank": 0}})).world_rank, None);
    }
}
