// core_engine/src/spotify/search.rs
//
// Search endpoints — Hybrid architecture.
// GQL for searching all types; REST batch for tracks (via batch_get_tracks,
// which now correctly uses RestBatchTracksResponse).
// Albums, artists, and playlists are parsed inline from GQL.

use serde_json::json;
use crate::spotify::client::*;
use crate::spotify::models::*;

const HASH_SEARCH_DESKTOP: &str = "4801118d4a100f756e833d33984436a3899cff359c532f8fd3aaf174b60b3b49";
const HASH_SEARCH_TRACKS: &str = "bc1ca2fcd0ba1013a0fc88e6cc4f190af501851e3dafd3e1ef85840297694428";
const HASH_SEARCH_ALBUMS: &str = "a71d2c993fc98e1c880093738a55a38b57e69cc4ce5a8c113e6c5920f9513ee2";
const HASH_SEARCH_ARTISTS: &str = "0e6f9020a66fe15b93b3bb5c7e6484d1d8cb3775963996eaede72bac4d97e909";
const HASH_SEARCH_PLAYLISTS: &str = "fc3a690182167dbad20ac7a03f842b97be4e9737710600874cb903f30112ad58";

impl SpotifyClient {
    /// Extract track IDs from GQL tracksV2 search results and batch-fetch
    /// full details from REST.  batch_get_tracks now uses RestBatchTracksResponse
    /// (proper snake_case) so deserialization is correct.
    async fn convert_gql_tracks(&self, tracks_data: &serde_json::Value) -> SpotifyResult<Vec<FullTrack>> {
        let empty = vec![];
        let tracks: Vec<FullTrack> = tracks_data["items"]
            .as_array()
            .unwrap_or(&empty)
            .iter()
            .filter(|item| {
                item["item"]["__typename"].as_str() == Some("TrackResponseWrapper") &&
                item["item"]["data"]["__typename"].as_str() == Some("Track")
            })
            .filter_map(|item| {
                parse_gql_track(&item["item"]["data"])
            })
            .collect();

        Ok(tracks)
    }

    /// Convert GQL albumsV2 search results inline.
    fn convert_gql_albums(&self, albums_data: &serde_json::Value) -> Vec<SimpleAlbum> {
        let empty = vec![];
        albums_data["items"]
            .as_array()
            .unwrap_or(&empty)
            .iter()
            .filter(|item| {
                item["__typename"].as_str() == Some("AlbumResponseWrapper") &&
                item["data"]["__typename"].as_str() == Some("Album")
            })
            .filter_map(|item| {
                let album = &item["data"];
                let uri = album["uri"].as_str()?;
                let id = id_from_uri(uri)?.to_string();

                let images = parse_images_from_sources(&album["coverArt"]["sources"]);
                let artists = parse_gql_artists(&album["artists"]);

                Some(SimpleAlbum {
                    id: id.clone(),
                    name: album["name"].as_str().unwrap_or("").to_string(),
                    external_uri: format!("https://open.spotify.com/album/{}", id),
                    release_date: album["date"]["year"].as_u64().map(|y| y.to_string()),
                    release_date_precision: Some("year".to_string()),
                    images,
                    artists,
                    album_type: album["type"].as_str().map(|s| s.to_lowercase()),
                })
            })
            .collect()
    }

    /// Convert GQL artists search results inline.
    fn convert_gql_artists_list(&self, artists_data: &serde_json::Value) -> Vec<FullArtist> {
        let empty = vec![];
        artists_data["items"]
            .as_array()
            .unwrap_or(&empty)
            .iter()
            .filter(|item| {
                item["__typename"].as_str() == Some("ArtistResponseWrapper") &&
                item["data"]["__typename"].as_str() == Some("Artist")
            })
            .filter_map(|item| {
                let artist = &item["data"];
                let uri = artist["uri"].as_str()?;
                let id = id_from_uri(uri)?.to_string();

                let images = parse_images_from_sources(&artist["visuals"]["avatarImage"]["sources"]);

                Some(FullArtist {
                    id: id.clone(),
                    name: artist["profile"]["name"].as_str().unwrap_or("").to_string(),
                    external_uri: format!("https://open.spotify.com/artist/{}", id),
                    images,
                    genres: vec![],
                    followers: None,
                })
            })
            .collect()
    }

    /// Convert GQL playlists search results inline.
    fn convert_gql_playlists(&self, playlists_data: &serde_json::Value) -> Vec<SimplePlaylist> {
        let empty = vec![];
        playlists_data["items"]
            .as_array()
            .unwrap_or(&empty)
            .iter()
            .filter(|item| {
                item["__typename"].as_str() == Some("PlaylistResponseWrapper") &&
                item["data"]["__typename"].as_str() == Some("Playlist")
            })
            .filter_map(|item| {
                let playlist = &item["data"];
                let uri = playlist["uri"].as_str()?;
                let id = id_from_uri(uri)?.to_string();

                let owner = &playlist["ownerV2"]["data"];
                let owner_id = id_from_uri(owner["uri"].as_str().unwrap_or(""))
                    .unwrap_or("")
                    .to_string();

                let images = parse_images_nested(&playlist["images"]);

                Some(SimplePlaylist {
                    id: id.clone(),
                    name: playlist["name"].as_str().unwrap_or("").to_string(),
                    description: playlist["description"].as_str().map(|s| s.to_string()),
                    images,
                    external_uri: format!("https://open.spotify.com/playlist/{}", id),
                    owner: Some(SpotifyUser {
                        id: owner_id.clone(),
                        name: owner["name"].as_str().map(|s| s.to_string()),
                        images: parse_images_from_sources(&owner["avatar"]["sources"]),
                        external_uri: format!("https://open.spotify.com/user/{}", owner_id),
                        followers: None,
                        country: None,
                        product: None,
                    }),
                    tracks: None,
                })
            })
            .collect()
    }

    pub async fn search_all(&self, query: &str, limit: u32) -> SpotifyResult<NormalizedSearchResults> {
        let variables = json!({
            "searchTerm": query,
            "offset": 0,
            "limit": limit.min(10),
            "numberOfTopResults": 5,
            "includeAudiobooks": false,
            "includeArtistHasConcertsField": false,
            "includePreReleases": false,
            "includeLocalConcertsField": false,
            "includeAuthors": false
        });

        let gql = self.gql_post(variables, "searchDesktop", HASH_SEARCH_DESKTOP).await?;

        let search_data = &gql["data"]["searchV2"];

        let albums = self.convert_gql_albums(&search_data["albumsV2"]);
        let artists = self.convert_gql_artists_list(&search_data["artists"]);
        let playlists = self.convert_gql_playlists(&search_data["playlists"]);
        let tracks = self.convert_gql_tracks(&search_data["tracksV2"]).await?;

        Ok(NormalizedSearchResults { tracks, albums, artists, playlists })
    }

    pub async fn search_tracks(&self, query: &str, limit: u32, offset: u32) -> SpotifyResult<PaginatedResponse<FullTrack>> {
        let variables = json!({
            "searchTerm": query,
            "offset": offset,
            "limit": limit.min(50),
            "numberOfTopResults": 20,
            "includePreReleases": false,
            "includeAudiobooks": true,
            "includeAuthors": false
        });

        let gql = self.gql_post(variables, "searchTracks", HASH_SEARCH_TRACKS).await?;

        let search_data = &gql["data"]["searchV2"]["tracksV2"];
        let paging_info = &search_data["pagingInfo"];
        let total_count = search_data["totalCount"].as_u64().unwrap_or(0) as u32;

        let tracks = self.convert_gql_tracks(search_data).await?;

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

    pub async fn search_albums(&self, query: &str, limit: u32, offset: u32) -> SpotifyResult<PaginatedResponse<SimpleAlbum>> {
        let variables = json!({
            "searchTerm": query,
            "offset": offset,
            "limit": limit.min(50),
            "numberOfTopResults": 20,
            "includePreReleases": false,
            "includeAudiobooks": false,
            "includeAuthors": false
        });

        let gql = self.gql_post(variables, "searchAlbums", HASH_SEARCH_ALBUMS).await?;

        let search_data = &gql["data"]["searchV2"]["albumsV2"];
        let paging_info = &search_data["pagingInfo"];
        let total_count = search_data["totalCount"].as_u64().unwrap_or(0) as u32;

        let albums = self.convert_gql_albums(search_data);

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

    pub async fn search_artists(&self, query: &str, limit: u32, offset: u32) -> SpotifyResult<PaginatedResponse<FullArtist>> {
        let variables = json!({
            "searchTerm": query,
            "offset": offset,
            "limit": limit.min(50),
            "numberOfTopResults": 20,
            "includePreReleases": false,
            "includeAudiobooks": true,
            "includeAuthors": false
        });

        let gql = self.gql_post(variables, "searchArtists", HASH_SEARCH_ARTISTS).await?;

        let search_data = &gql["data"]["searchV2"]["artists"];
        let paging_info = &search_data["pagingInfo"];
        let total_count = search_data["totalCount"].as_u64().unwrap_or(0) as u32;

        let artists = self.convert_gql_artists_list(search_data);

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

    pub async fn search_playlists(&self, query: &str, limit: u32, offset: u32) -> SpotifyResult<PaginatedResponse<SimplePlaylist>> {
        let variables = json!({
            "searchTerm": query,
            "offset": offset,
            "limit": limit.min(50),
            "numberOfTopResults": 20,
            "includePreReleases": false,
            "includeAudiobooks": true,
            "includeAuthors": false
        });

        let gql = self.gql_post(variables, "searchPlaylists", HASH_SEARCH_PLAYLISTS).await?;

        let search_data = &gql["data"]["searchV2"]["playlists"];
        let paging_info = &search_data["pagingInfo"];
        let total_count = search_data["totalCount"].as_u64().unwrap_or(0) as u32;

        let playlists = self.convert_gql_playlists(search_data);

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
