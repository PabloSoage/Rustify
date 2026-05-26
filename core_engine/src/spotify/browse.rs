// core_engine/src/spotify/browse.rs
//
// Browse/Home endpoints using the GraphQL API, matching the Spotube plugin.

use serde_json::json;
use crate::spotify::client::{SpotifyClient, SpotifyResult};
use crate::spotify::models::*;

const HASH_HOME: &str = "d62af2714f2623c923cc9eeca4b9545b4363abaa9188a9e94e2b63b823419a2c";

impl SpotifyClient {
    /// Fetch the home/browse sections via GraphQL home query.
    pub async fn get_browse_sections(&self, limit: u32) -> SpotifyResult<Vec<BrowseSection>> {
        let variables = json!({
            "timeZone": "Europe/Madrid",
            "sp_t": "",
            "facet": "",
            "sectionItemsLimit": limit.min(50)
        });

        let gql = self.gql_post(variables, "home", HASH_HOME).await?;

        let home_data = &gql["data"]["home"];
        let empty_vec = vec![];
        let sections = home_data["sectionContainer"]["sections"]["items"]
            .as_array()
            .unwrap_or(&empty_vec);

        let browse_sections: Vec<BrowseSection> = sections.iter()
            .filter(|section| {
                section["data"]["__typename"].as_str() == Some("HomeGenericSectionData") &&
                section["sectionItems"]["items"].as_array().map(|a| !a.is_empty()).unwrap_or(false)
            })
            .filter_map(|section| {
                let uri = section["uri"].as_str()?;
                let id = uri.split(':').last()?.to_string();
                let title = section["data"]["title"]["transformedLabel"].as_str()?.to_string();

                let empty_items = vec![];
                let items: Vec<BrowseSectionItem> = section["sectionItems"]["items"]
                    .as_array()
                    .unwrap_or(&empty_items)
                    .iter()
                    .filter_map(|item| {
                        let wrapper_type = item["content"]["__typename"].as_str()?;
                        let content_type = item["content"]["data"]["__typename"].as_str()?;

                        match (wrapper_type, content_type) {
                            ("PlaylistResponseWrapper", "Playlist") => {
                                let playlist_data = &item["content"]["data"];
                                let pid = item["uri"].as_str()?.split(':').last()?.to_string();
                                let owner_data = &playlist_data["ownerV2"]["data"];
                                let owner_id = owner_data["uri"].as_str()
                                    .and_then(|u| u.split(':').last())
                                    .unwrap_or("").to_string();

                                let images = playlist_data["images"]["items"]
                                    .as_array()
                                    .map(|imgs| {
                                        imgs.iter()
                                            .flat_map(|img| {
                                                img["sources"].as_array().unwrap_or(&vec![]).iter().filter_map(|s| {
                                                    Some(SpotifyImage {
                                                        url: s["url"].as_str()?.to_string(),
                                                        height: s["height"].as_u64().map(|h| h as u32),
                                                        width: s["width"].as_u64().map(|w| w as u32),
                                                    })
                                                }).collect::<Vec<_>>()
                                            })
                                            .collect::<Vec<_>>()
                                    })
                                    .unwrap_or_default();

                                Some(BrowseSectionItem::Playlist(SimplePlaylist {
                                    id: pid.clone(),
                                    name: playlist_data["name"].as_str()?.to_string(),
                                    description: playlist_data["description"].as_str().map(|s| s.to_string()),
                                    images,
                                    external_uri: format!("https://open.spotify.com/playlist/{}", pid),
                                    owner: Some(SpotifyUser {
                                        id: owner_id.clone(),
                                        name: owner_data["name"].as_str().map(|s| s.to_string()),
                                        images: vec![],
                                        external_uri: format!("https://open.spotify.com/user/{}", owner_id),
                                        followers: None,
                                        country: None,
                                        product: None,
                                    }),
                                    tracks: None,
                                }))
                            }
                            ("AlbumResponseWrapper", "Album") => {
                                let album_data = &item["content"]["data"];
                                let aid = item["uri"].as_str()?.split(':').last()?.to_string();

                                let artists = album_data["artists"]["items"]
                                    .as_array()
                                    .map(|arr| {
                                        arr.iter().filter_map(|a| {
                                            let artist_id = a["uri"].as_str()?.split(':').last()?.to_string();
                                            Some(SimpleArtist {
                                                id: artist_id.clone(),
                                                name: a["profile"]["name"].as_str()?.to_string(),
                                                external_uri: format!("https://open.spotify.com/artist/{}", artist_id),
                                                images: None,
                                            })
                                        }).collect()
                                    })
                                    .unwrap_or_default();

                                let images = album_data["coverArt"]["sources"]
                                    .as_array()
                                    .map(|arr| {
                                        arr.iter().filter_map(|s| {
                                            Some(SpotifyImage {
                                                url: s["url"].as_str()?.to_string(),
                                                height: s["height"].as_u64().map(|h| h as u32),
                                                width: s["width"].as_u64().map(|w| w as u32),
                                            })
                                        }).collect()
                                    })
                                    .unwrap_or_default();

                                Some(BrowseSectionItem::Album(SimpleAlbum {
                                    id: aid.clone(),
                                    name: album_data["name"].as_str()?.to_string(),
                                    album_type: album_data["albumType"].as_str().map(|s| s.to_lowercase()),
                                    release_date: None,
                                    release_date_precision: None,
                                    images,
                                    artists,
                                    external_uri: format!("https://open.spotify.com/album/{}", aid),
                                }))
                            }
                            _ => None,
                        }
                    })
                    .collect();

                if items.is_empty() {
                    return None;
                }

                Some(BrowseSection { id, title, items })
            })
            .collect();

        Ok(browse_sections)
    }
}
