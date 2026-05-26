// core_engine/src/spotify/album.rs
//
// Album endpoints.
// All REST calls now go through proper RestXxx types and convert via From.

use serde_json::json;
use crate::spotify::client::*;
use crate::spotify::models::*;

const HASH_WHATS_NEW_FEED: &str = "3b53dede3c6054e8b7c962dd280eb6761c5d1c82b06b039f4110d76a62b4966b";
const HASH_ADD_TO_LIBRARY: &str = "a3c1ff58e6a36fec5fe1e3a193dc95d9071d96b9ba53c5ba9c1494fb1ee73915";
const HASH_REMOVE_FROM_LIBRARY: &str = "a3c1ff58e6a36fec5fe1e3a193dc95d9071d96b9ba53c5ba9c1494fb1ee73915";

// Minimal REST struct for /albums/{id}/tracks (simplified track items).
#[derive(Debug, serde::Deserialize)]
struct RestSimpleTrackItem {
    id: Option<String>,
}

impl SpotifyClient {
    /// Fetch full album details via REST API.
    pub async fn get_album(&self, id: &str) -> SpotifyResult<FullAlbum> {
        let path = format!("/albums/{}", id);
        let rest: RestFullAlbum = self.api_get(&path).await?;
        Ok(FullAlbum::from(rest))
    }

    /// Fetch tracks for an album.
    /// GET /albums/{id}/tracks for IDs → batch GET /tracks for full FullTrack data.
    pub async fn get_album_tracks(&self, id: &str, limit: u32, offset: u32) -> SpotifyResult<PaginatedResponse<FullTrack>> {
        let path = format!("/albums/{}/tracks?limit={}&offset={}", id, limit.min(50), offset);
        let paging: RestPaging<RestSimpleTrackItem> = self.api_get(&path).await?;

        let track_ids: Vec<String> = paging.items.into_iter()
            .filter_map(|t| t.id)
            .collect();

        let tracks = self.batch_get_tracks(&track_ids).await?;

        let next_offset = if paging.next.is_some() {
            Some(offset + limit.min(50))
        } else {
            None
        };

        Ok(PaginatedResponse {
            items: tracks,
            total: paging.total,
            limit: paging.limit,
            next_offset,
            has_more: paging.next.is_some(),
        })
    }

    /// Fetch new releases via GQL queryWhatsNewFeed.
    pub async fn get_new_releases(&self, limit: u32, offset: u32) -> SpotifyResult<PaginatedResponse<SimpleAlbum>> {
        let variables = json!({
            "offset": offset,
            "limit": limit.min(50),
            "onlyUnPlayedItems": false,
            "includedContentTypes": ["ALBUM"]
        });

        let gql = self.gql_post(variables, "queryWhatsNewFeed", HASH_WHATS_NEW_FEED).await?;

        let releases_data = &gql["data"]["whatsNewFeedItems"];
        let paging_info = &releases_data["pagingInfo"];
        let total_count = releases_data["totalCount"].as_u64().unwrap_or(0) as u32;

        let empty = vec![];
        let albums: Vec<SimpleAlbum> = releases_data["items"]
            .as_array()
            .unwrap_or(&empty)
            .iter()
            .filter(|item| {
                item["content"]["__typename"].as_str() == Some("AlbumResponseWrapper") &&
                item["content"]["data"]["__typename"].as_str() == Some("Album")
            })
            .filter_map(|item| {
                let album = &item["content"]["data"];
                let uri = album["uri"].as_str()?;
                let id = id_from_uri(uri)?.to_string();

                let images = parse_images_from_sources(&album["coverArt"]["sources"]);
                let artists = parse_gql_artists(&album["artists"]);

                Some(SimpleAlbum {
                    id: id.clone(),
                    name: album["name"].as_str().unwrap_or("").to_string(),
                    external_uri: format!("https://open.spotify.com/album/{}", id),
                    release_date: album["date"]["isoString"].as_str().map(|s| s.to_string()),
                    release_date_precision: album["date"]["precision"].as_str().map(|s| s.to_string()),
                    images,
                    artists,
                    album_type: album["albumType"].as_str().map(|s| s.to_lowercase()),
                })
            })
            .collect();

        let current_offset = paging_info["offset"].as_u64().unwrap_or(offset as u64) as u32;
        let current_limit = paging_info["limit"].as_u64().unwrap_or(limit as u64) as u32;
        let next_offset = current_offset + current_limit;
        let has_more = next_offset < total_count;

        Ok(PaginatedResponse {
            items: albums,
            total: total_count,
            limit: current_limit,
            next_offset: if has_more { Some(next_offset) } else { None },
            has_more,
        })
    }

    /// Save albums to library via GQL addToLibrary.
    pub async fn save_albums(&self, ids: &[String]) -> SpotifyResult<()> {
        let uris: Vec<String> = ids.iter().map(|id| format!("spotify:album:{}", id)).collect();
        let variables = json!({ "uris": uris });
        self.gql_post(variables, "addToLibrary", HASH_ADD_TO_LIBRARY).await?;
        Ok(())
    }

    /// Remove albums from library via GQL removeFromLibrary.
    pub async fn unsave_albums(&self, ids: &[String]) -> SpotifyResult<()> {
        let uris: Vec<String> = ids.iter().map(|id| format!("spotify:album:{}", id)).collect();
        let variables = json!({ "uris": uris });
        self.gql_post(variables, "removeFromLibrary", HASH_REMOVE_FROM_LIBRARY).await?;
        Ok(())
    }
}
