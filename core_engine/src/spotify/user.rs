// core_engine/src/spotify/user.rs
//
// User profile and library endpoints.
// Matching the Spotube spotify-gql-client behavior:
//   - savedTracks:   GQL fetchLibraryTracks → parse track data directly from GQL
//                    (avoids REST batch which had serde deserialization issues)
//   - savedAlbums:   GQL libraryV3 IDs → REST batch (now via proper REST types)
//   - savedArtists:  GQL libraryV3 IDs → REST batch (now via proper REST types)
//   - savedPlaylists: pure GQL libraryV3 (already worked fine)

use serde_json::{json, Value};
use crate::spotify::client::*;
use crate::spotify::models::*;

const HASH_LIBRARY_TRACKS: &str = "087278b20b743578a6262c2b0b4bcd20d879c503cc359a2285baf083ef944240";
const HASH_LIBRARY_V3: &str = "2de10199b2441d6e4ae875f27d2db361020c399fb10b03951120223fbed10b08";

impl SpotifyClient {
    /// Get the current user's profile via REST API.
    pub async fn get_me(&self) -> SpotifyResult<SpotifyUser> {
        let rest: RestUser = self.api_get("/me").await?;
        Ok(SpotifyUser::from(rest))
    }

    /// Get user's saved tracks.
    ///
    /// Uses GQL `fetchLibraryTracks` and parses track data **directly** from
    /// the GQL response using `parse_gql_track()`.  This avoids the REST batch
    /// fetch entirely — the GQL response already contains full track metadata
    /// (name, duration, artists, album art, ISRC, etc.) so no second round-trip
    /// is needed and there is no serde snake_case / camelCase mismatch.
    pub async fn get_saved_tracks(&self, limit: u32, offset: u32) -> SpotifyResult<PaginatedResponse<FullTrack>> {
        let variables = json!({
            "offset": offset,
            "limit": limit.min(50)
        });

        let gql = self.gql_post(variables, "fetchLibraryTracks", HASH_LIBRARY_TRACKS).await?;

        let tracks_data = &gql["data"]["me"]["library"]["tracks"];
        let paging_info = &tracks_data["pagingInfo"];
        let total_count = tracks_data["totalCount"].as_u64().unwrap_or(0) as u32;

        let empty = vec![];
        let tracks: Vec<FullTrack> = tracks_data["items"]
            .as_array()
            .unwrap_or(&empty)
            .iter()
            .filter(|item| item["__typename"].as_str() == Some("UserLibraryTrackResponse"))
            .filter_map(|item| parse_gql_track(&item["track"]))
            .collect();

        let current_offset = paging_info["offset"].as_u64().unwrap_or(offset as u64) as u32;
        let current_limit = paging_info["limit"].as_u64().unwrap_or(limit as u64) as u32;
        let next_offset = current_offset + current_limit;
        let has_more = next_offset < total_count;

        Ok(PaginatedResponse {
            items: tracks,
            total: total_count,
            limit: current_limit,
            next_offset: if has_more { Some(next_offset) } else { None },
            has_more,
        })
    }

    /// Get user's saved albums.
    /// GQL libraryV3 for IDs, then REST batch for full FullAlbum data.
    pub async fn get_saved_albums(&self, limit: u32, offset: u32) -> SpotifyResult<PaginatedResponse<FullAlbum>> {
        let variables = json!({
            "filters": ["Albums"],
            "order": Value::Null,
            "textFilter": "",
            "features": ["LIKED_SONGS", "YOUR_EPISODES_V2", "PRERELEASES", "EVENTS"],
            "limit": limit.min(50),
            "offset": offset,
            "flatten": false,
            "expandedFolders": [],
            "folderUri": Value::Null,
            "includeFoldersWhenFlattening": true
        });

        let gql = self.gql_post(variables, "libraryV3", HASH_LIBRARY_V3).await?;

        let library_data = &gql["data"]["me"]["libraryV3"];
        let paging_info = &library_data["pagingInfo"];
        let total_count = library_data["totalCount"].as_u64().unwrap_or(0) as u32;

        let empty = vec![];
        let album_ids: Vec<String> = library_data["items"]
            .as_array()
            .unwrap_or(&empty)
            .iter()
            .filter(|item| {
                item["item"]["__typename"].as_str() == Some("AlbumResponseWrapper") &&
                item["item"]["data"]["__typename"].as_str() == Some("Album")
            })
            .filter_map(|item| id_from_uri(item["item"]["_uri"].as_str()?).map(|s| s.to_string()))
            .collect();

        let albums = if album_ids.is_empty() {
            vec![]
        } else {
            self.batch_get_albums(&album_ids).await?
        };

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

    /// Get user's followed artists.
    /// GQL libraryV3 for IDs, then REST batch for full FullArtist data.
    pub async fn get_followed_artists(&self, limit: u32, offset: u32) -> SpotifyResult<PaginatedResponse<FullArtist>> {
        let variables = json!({
            "filters": ["Artists"],
            "order": Value::Null,
            "textFilter": "",
            "features": ["LIKED_SONGS", "YOUR_EPISODES_V2", "PRERELEASES", "EVENTS"],
            "limit": limit.min(50),
            "offset": offset,
            "flatten": false,
            "expandedFolders": [],
            "folderUri": Value::Null,
            "includeFoldersWhenFlattening": true
        });

        let gql = self.gql_post(variables, "libraryV3", HASH_LIBRARY_V3).await?;

        let library_data = &gql["data"]["me"]["libraryV3"];
        let paging_info = &library_data["pagingInfo"];
        let total_count = library_data["totalCount"].as_u64().unwrap_or(0) as u32;

        let empty = vec![];
        let artist_ids: Vec<String> = library_data["items"]
            .as_array()
            .unwrap_or(&empty)
            .iter()
            .filter(|item| {
                item["item"]["__typename"].as_str() == Some("ArtistResponseWrapper") &&
                item["item"]["data"]["__typename"].as_str() == Some("Artist")
            })
            .filter_map(|item| id_from_uri(item["item"]["_uri"].as_str()?).map(|s| s.to_string()))
            .collect();

        let artists = if artist_ids.is_empty() {
            vec![]
        } else {
            self.batch_get_artists(&artist_ids).await?
        };

        let current_offset = paging_info["offset"].as_u64().unwrap_or(offset as u64) as u32;
        let current_limit = paging_info["limit"].as_u64().unwrap_or(limit as u64) as u32;
        let next_offset = current_offset + current_limit;
        let has_more = next_offset < total_count;

        Ok(PaginatedResponse {
            items: artists,
            total: total_count,
            limit: current_limit,
            next_offset: if has_more { Some(next_offset) } else { None },
            has_more,
        })
    }

    /// Get user's saved/created playlists.
    /// Purely GQL — libraryV3 returns all necessary SimplePlaylist metadata.
    pub async fn get_saved_playlists(&self, limit: u32, offset: u32) -> SpotifyResult<PaginatedResponse<SimplePlaylist>> {
        let variables = json!({
            "filters": ["Playlists"],
            "order": Value::Null,
            "textFilter": "",
            "features": ["LIKED_SONGS", "YOUR_EPISODES_V2", "PRERELEASES", "EVENTS"],
            "limit": limit.min(50),
            "offset": offset,
            "flatten": false,
            "expandedFolders": [],
            "folderUri": Value::Null,
            "includeFoldersWhenFlattening": true
        });

        let gql = self.gql_post(variables, "libraryV3", HASH_LIBRARY_V3).await?;

        let library_data = &gql["data"]["me"]["libraryV3"];
        let paging_info = &library_data["pagingInfo"];
        let total_count = library_data["totalCount"].as_u64().unwrap_or(0) as u32;

        let empty = vec![];
        let playlists: Vec<SimplePlaylist> = library_data["items"]
            .as_array()
            .unwrap_or(&empty)
            .iter()
            .filter(|item| {
                item["item"]["__typename"].as_str() == Some("PlaylistResponseWrapper") &&
                item["item"]["data"]["__typename"].as_str() == Some("Playlist")
            })
            .filter_map(|item| {
                let playlist_data = &item["item"]["data"];
                let uri = item["item"]["_uri"].as_str()?;
                let id = id_from_uri(uri)?.to_string();

                let owner_data = &playlist_data["ownerV2"]["data"];
                let owner_id = id_from_uri(owner_data["uri"].as_str()?).unwrap_or("").to_string();

                let images = parse_images_nested(&playlist_data["images"]);

                Some(SimplePlaylist {
                    id: id.clone(),
                    name: playlist_data["name"].as_str().unwrap_or("").to_string(),
                    description: playlist_data["description"].as_str().map(|s| s.to_string()),
                    images,
                    external_uri: format!("https://open.spotify.com/playlist/{}", id),
                    owner: Some(SpotifyUser {
                        id: owner_id.clone(),
                        name: owner_data["name"].as_str().map(|s| s.to_string()),
                        images: parse_images_from_sources(&owner_data["avatar"]["sources"]),
                        external_uri: format!("https://open.spotify.com/user/{}", owner_id),
                        followers: None,
                        country: None,
                        product: None,
                    }),
                    tracks: None, // libraryV3 doesn't return track counts
                })
            })
            .collect();

        let current_offset = paging_info["offset"].as_u64().unwrap_or(offset as u64) as u32;
        let current_limit = paging_info["limit"].as_u64().unwrap_or(limit as u64) as u32;
        let next_offset = current_offset + current_limit;
        let has_more = next_offset < total_count;

        Ok(PaginatedResponse {
            items: playlists,
            total: total_count,
            limit: current_limit,
            next_offset: if has_more { Some(next_offset) } else { None },
            has_more,
        })
    }
}
