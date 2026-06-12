// core_engine/src/spotify/artist.rs
//
// Artist endpoints.
// GET requests now use 100% GraphQL via api-partner.spotify.com,
// completely bypassing REST blocks for Free accounts.

use crate::spotify::client::*;
use crate::spotify::models::*;
use serde_json::json;

impl SpotifyClient {
    /// Fetch artist details via GraphQL API.
    pub async fn get_artist(&self, id: &str) -> SpotifyResult<FullArtist> {
        let uri = format!("spotify:artist:{}", id);
        let variables = json!({
            "uri": uri,
            "locale": "en",
            "includePrerelease": false
        });

        let gql = self.gql_post(variables, "queryArtistOverview", "").await?;
        let artist_data = &gql["data"]["artistUnion"];

        if artist_data.is_null() || artist_data["__typename"].as_str() != Some("Artist") {
            return Err(SpotifyError::ApiError(404, "Artist not found".to_string()));
        }

        let name = artist_data["profile"]["name"].as_str().unwrap_or("").to_string();
        let images = parse_images_from_sources(&artist_data["visuals"]["avatarImage"]["sources"]);
        let followers = artist_data["stats"]["followers"].as_u64().map(|f| f as u32);

        Ok(FullArtist {
            id: id.to_string(),
            name,
            external_uri: format!("https://open.spotify.com/artist/{}", id),
            images,
            genres: vec![],
            followers,
        })
    }

    /// Fetch artist's top tracks via GraphQL API.
    pub async fn get_artist_top_tracks(&self, id: &str, limit: u32, _offset: u32) -> SpotifyResult<PaginatedResponse<FullTrack>> {
        let uri = format!("spotify:artist:{}", id);
        let variables = json!({
            "uri": uri,
            "locale": "en",
            "includePrerelease": false
        });

        let gql = self.gql_post(variables, "queryArtistOverview", "").await?;
        let artist_data = &gql["data"]["artistUnion"];

        if artist_data.is_null() || artist_data["__typename"].as_str() != Some("Artist") {
            return Err(SpotifyError::ApiError(404, "Artist not found".to_string()));
        }

        let empty = vec![];
        let items = artist_data["discography"]["topTracks"]["items"].as_array().unwrap_or(&empty);
        
        let tracks: Vec<FullTrack> = items.iter()
            .filter_map(|item| {
                let mut track = parse_gql_track(&item["track"])?;
                // GQL top tracks usually lack albumOfTrack structure inside parent artist response sometimes.
                // If it is None, we set a fallback SimpleAlbum so Kotlin won't crash on missing covers
                if track.album.is_none() {
                    track.album = Some(SimpleAlbum {
                        id: String::new(),
                        name: String::new(),
                        external_uri: String::new(),
                        release_date: None,
                        release_date_precision: None,
                        images: vec![],
                        artists: vec![],
                        album_type: None,
                    });
                }
                Some(track)
            })
            .collect();

        let total = tracks.len() as u32;

        Ok(PaginatedResponse {
            items: tracks,
            total,
            limit,
            next_offset: None,
            has_more: false,
        })
    }

    /// Fetch artist's albums via GraphQL API.
    pub async fn get_artist_albums(&self, id: &str, limit: u32, offset: u32) -> SpotifyResult<PaginatedResponse<SimpleAlbum>> {
        let uri = format!("spotify:artist:{}", id);
        let variables = json!({
            "uri": uri,
            "locale": "en",
            "includePrerelease": false
        });

        let gql = self.gql_post(variables, "queryArtistOverview", "").await?;
        let artist_data = &gql["data"]["artistUnion"];

        if artist_data.is_null() || artist_data["__typename"].as_str() != Some("Artist") {
            return Err(SpotifyError::ApiError(404, "Artist not found".to_string()));
        }

        let discography = &artist_data["discography"];
        let mut albums_list = Vec::new();

        // Helper to extract albums and singles from GQL groups
        let mut extract_group = |group_name: &str| {
            let empty_items = vec![];
            let items = discography[group_name]["items"].as_array().unwrap_or(&empty_items);
            for item in items {
                let release_val = if !item["releases"]["items"].is_null() {
                    item["releases"]["items"].get(0).unwrap_or(item)
                } else {
                    item
                };

                let uri = release_val["uri"].as_str().or_else(|| release_val["id"].as_str());
                if let Some(uri_str) = uri {
                    let album_id = id_from_uri(uri_str).unwrap_or("").to_string();
                    if !album_id.is_empty() {
                        let name = release_val["name"].as_str().unwrap_or("").to_string();
                        let images = parse_images_from_sources(&release_val["coverArt"]["sources"]);
                        
                        let artists = if !release_val["artists"]["items"].is_null() {
                            parse_gql_artists(&release_val["artists"])
                        } else {
                            vec![SimpleArtist {
                                id: id.to_string(),
                                name: artist_data["profile"]["name"].as_str().unwrap_or("").to_string(),
                                external_uri: format!("https://open.spotify.com/artist/{}", id),
                                images: None,
                            }]
                        };

                        let release_date = release_val["date"]["isoString"].as_str()
                            .or_else(|| release_val["date"]["year"].as_u64().map(|_| "2026"))
                            .map(|s| s.to_string());

                        albums_list.push(SimpleAlbum {
                            id: album_id.clone(),
                            name,
                            external_uri: format!("https://open.spotify.com/album/{}", album_id),
                            release_date,
                            release_date_precision: Some("day".to_string()),
                            images,
                            artists,
                            album_type: release_val["type"].as_str().map(|s| s.to_lowercase()).or(Some(group_name.trim_end_matches('s').to_lowercase())),
                        });
                    }
                }
            }
        };

        extract_group("albums");
        extract_group("singles");

        let total = albums_list.len() as u32;

        let start = offset.min(total) as usize;
        let end = (offset + limit).min(total) as usize;
        let paginated_items = albums_list[start..end].to_vec();

        let has_more = end < total as usize;
        let next_offset = if has_more { Some(offset + limit) } else { None };

        Ok(PaginatedResponse {
            items: paginated_items,
            total,
            limit,
            next_offset,
            has_more,
        })
    }

    /// Fetch related artists via GraphQL API.
    pub async fn get_related_artists(&self, id: &str, limit: u32, offset: u32) -> SpotifyResult<PaginatedResponse<FullArtist>> {
        let uri = format!("spotify:artist:{}", id);
        let variables = json!({
            "uri": uri,
            "locale": "en",
            "includePrerelease": false
        });

        let gql = self.gql_post(variables, "queryArtistOverview", "").await?;
        let artist_data = &gql["data"]["artistUnion"];

        if artist_data.is_null() || artist_data["__typename"].as_str() != Some("Artist") {
            return Err(SpotifyError::ApiError(404, "Artist not found".to_string()));
        }

        let empty = vec![];
        let related_items = artist_data["relatedArtists"]["items"].as_array().unwrap_or(&empty);
        let artists: Vec<FullArtist> = related_items.iter().filter_map(|item| {
            let uri = item["uri"].as_str()?;
            let artist_id = id_from_uri(uri)?.to_string();
            let name = item["profile"]["name"].as_str().unwrap_or("").to_string();
            let images = parse_images_from_sources(&item["visuals"]["avatarImage"]["sources"]);
            Some(FullArtist {
                id: artist_id.clone(),
                name,
                external_uri: format!("https://open.spotify.com/artist/{}", artist_id),
                images,
                genres: vec![],
                followers: None,
            })
        }).collect();

        let total = artists.len() as u32;

        Ok(PaginatedResponse {
            items: artists,
            total,
            limit,
            next_offset: Some(offset + limit),
            has_more: false,
        })
    }

    /// Follow artists via GQL addToLibrary.
    pub async fn follow_artists(&self, ids: &[String]) -> SpotifyResult<()> {
        if ids.is_empty() { return Ok(()); }
        let uris: Vec<String> = ids.iter().map(|id| format!("spotify:artist:{}", id)).collect();
        let variables = json!({ "uris": uris });
        self.gql_post(variables, "addToLibrary", "").await?;
        Ok(())
    }

    /// Unfollow artists via GQL removeFromLibrary.
    pub async fn unfollow_artists(&self, ids: &[String]) -> SpotifyResult<()> {
        if ids.is_empty() { return Ok(()); }
        let uris: Vec<String> = ids.iter().map(|id| format!("spotify:artist:{}", id)).collect();
        let variables = json!({ "uris": uris });
        self.gql_post(variables, "removeFromLibrary", "").await?;
        Ok(())
    }
}
