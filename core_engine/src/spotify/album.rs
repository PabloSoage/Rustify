// core_engine/src/spotify/album.rs
//
// Album endpoints rewritten to use 100% GraphQL via api-partner.spotify.com,
// completely bypassing Envoy REST 429 blocks for Free accounts.

use crate::spotify::client::*;
use crate::spotify::models::*;
use serde_json::json;

const HASH_WHATS_NEW_FEED: &str = "3b53dede3c6054e8b7c962dd280eb6761c5d1c82b06b039f4110d76a62b4966b";
const HASH_ADD_TO_LIBRARY: &str = "a3c1ff58e6a36fec5fe1e3a193dc95d9071d96b9ba53c5ba9c1494fb1ee73915";
const HASH_REMOVE_FROM_LIBRARY: &str = "a3c1ff58e6a36fec5fe1e3a193dc95d9071d96b9ba53c5ba9c1494fb1ee73915";

impl SpotifyClient {
    /// Fetch full album details via GraphQL API.
    pub async fn get_album(&self, id: &str) -> SpotifyResult<FullAlbum> {
        let uri = format!("spotify:album:{}", id);
        let variables = json!({
            "uri": uri,
            "locale": "en",
            "offset": 0,
            "limit": 1
        });

        // Passes empty static hash to trigger dynamic lookups automatically
        let gql = self.gql_post(variables, "getAlbum", "").await?;
        let album_data = &gql["data"]["albumUnion"];

        if album_data.is_null() || album_data["__typename"].as_str() != Some("Album") {
            return Err(SpotifyError::ApiError(404, "Album not found".to_string()));
        }

        let name = album_data["name"].as_str().unwrap_or("").to_string();
        let images = parse_images_from_sources(&album_data["coverArt"]["sources"]);
        let artists = parse_gql_artists(&album_data["artists"]);
        let release_date = album_data["date"]["isoString"].as_str()
            .or_else(|| album_data["date"]["year"].as_u64().map(|_| "2026")) // fallback
            .map(|s| s.to_string());
        let release_date_precision = album_data["date"]["precision"].as_str()
            .or(Some("day"))
            .map(|s| s.to_string());
        let total_tracks = album_data["tracksV2"]["totalCount"].as_u64().map(|c| c as u32);
        let label = album_data["label"].as_str().map(|s| s.to_string());

        Ok(FullAlbum {
            id: id.to_string(),
            name,
            external_uri: format!("https://open.spotify.com/album/{}", id),
            release_date,
            release_date_precision,
            images,
            artists,
            album_type: album_data["type"].as_str().map(|s| s.to_lowercase()),
            total_tracks,
            record_label: label,
            genres: vec![],
        })
    }

    /// Fetch tracks for an album using GQL.
    pub async fn get_album_tracks(&self, id: &str, limit: u32, offset: u32) -> SpotifyResult<PaginatedResponse<FullTrack>> {
        let uri = format!("spotify:album:{}", id);
        let variables = json!({
            "uri": uri,
            "locale": "en",
            "offset": offset,
            "limit": limit.min(50)
        });

        let gql = self.gql_post(variables, "getAlbum", "").await?;
        let album_data = &gql["data"]["albumUnion"];

        if album_data.is_null() || album_data["__typename"].as_str() != Some("Album") {
            return Err(SpotifyError::ApiError(404, "Album not found".to_string()));
        }

        let total_count = album_data["tracksV2"]["totalCount"].as_u64().unwrap_or(0) as u32;

        let empty = vec![];
        let items_arr = album_data["tracksV2"]["items"].as_array().unwrap_or(&empty);

        // Map GQL items to FullTrack
        let mut tracks = Vec::new();
        for item in items_arr {
            let track_val = &item["track"];
            if let Some(mut track) = parse_gql_track(track_val) {
                // Set the album context since GQL topTracks or tracks of getAlbum don't have albumOfTrack
                let album_images = parse_images_from_sources(&album_data["coverArt"]["sources"]);
                let album_artists = parse_gql_artists(&album_data["artists"]);
                track.album = Some(SimpleAlbum {
                    id: id.to_string(),
                    name: album_data["name"].as_str().unwrap_or("").to_string(),
                    external_uri: format!("https://open.spotify.com/album/{}", id),
                    release_date: album_data["date"]["isoString"].as_str().map(|s| s.to_string()),
                    release_date_precision: album_data["date"]["precision"].as_str().map(|s| s.to_string()),
                    images: album_images,
                    artists: album_artists,
                    album_type: album_data["type"].as_str().map(|s| s.to_lowercase()),
                });
                tracks.push(track);
            }
        }

        let has_more = offset + (tracks.len() as u32) < total_count;
        let next_offset = if has_more {
            Some(offset + limit.min(50))
        } else {
            None
        };

        Ok(PaginatedResponse {
            items: tracks,
            total: total_count,
            limit: limit.min(50),
            next_offset,
            has_more,
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
