// core_engine/src/spotify/playlist.rs
//
// Playlist endpoints.
// GET requests now use 100% GraphQL via api-partner.spotify.com.
// Write/mutation operations remain REST since they don't encounter Envoy blocks.

use serde_json::{json, Value};
use crate::spotify::client::*;
use crate::spotify::models::*;

impl SpotifyClient {
    /// Fetch full playlist details via GraphQL API.
    pub async fn get_playlist(&self, id: &str) -> SpotifyResult<FullPlaylist> {
        let uri = format!("spotify:playlist:{}", id);
        let variables = json!({
            "uri": uri,
            "offset": 0,
            "limit": 1,
            "enableWatchFeedEntrypoint": false
        });

        let gql = self.gql_post(variables, "fetchPlaylist", "").await?;
        let playlist_data = &gql["data"]["playlistV2"];

        if playlist_data.is_null() || playlist_data["__typename"].as_str() != Some("Playlist") {
            return Err(SpotifyError::ApiError(404, "Playlist not found".to_string()));
        }

        let name = playlist_data["name"].as_str().unwrap_or("").to_string();
        let description = playlist_data["description"].as_str().map(|s| s.to_string());
        let images = parse_images_nested(&playlist_data["images"]);

        let owner = playlist_data.get("ownerV2").and_then(|o| o.get("data")).map(|owner_data| {
            let owner_uri = owner_data["uri"].as_str().unwrap_or("");
            let owner_id = id_from_uri(owner_uri).unwrap_or("").to_string();
            SpotifyUser {
                id: owner_id.clone(),
                name: owner_data["name"].as_str().map(|s| s.to_string()),
                external_uri: format!("https://open.spotify.com/user/{}", owner_id),
                images: parse_images_from_sources(&owner_data["avatar"]["sources"]),
                followers: None,
                country: None,
                product: None,
            }
        });

        let total_tracks = playlist_data["content"]["totalCount"].as_u64().unwrap_or(0) as u32;

        Ok(FullPlaylist {
            id: id.to_string(),
            name,
            description,
            images,
            external_uri: format!("https://open.spotify.com/playlist/{}", id),
            owner,
            tracks: Some(PlaylistTracks { total: total_tracks }),
            collaborative: false,
            public: playlist_data["public"].as_bool(),
        })
    }

    /// Fetch tracks within a playlist via GraphQL API.
    pub async fn get_playlist_tracks(&self, id: &str, limit: u32, offset: u32) -> SpotifyResult<PaginatedResponse<FullTrack>> {
        let uri = format!("spotify:playlist:{}", id);
        let variables = json!({
            "uri": uri,
            "offset": offset,
            "limit": limit.min(100),
            "enableWatchFeedEntrypoint": false
        });

        let gql = self.gql_post(variables, "fetchPlaylist", "").await?;
        let playlist_data = &gql["data"]["playlistV2"];

        if playlist_data.is_null() || playlist_data["__typename"].as_str() != Some("Playlist") {
            return Err(SpotifyError::ApiError(404, "Playlist not found".to_string()));
        }

        let total_count = playlist_data["content"]["totalCount"].as_u64().unwrap_or(0) as u32;

        let empty = vec![];
        let items_arr = playlist_data["content"]["items"].as_array().unwrap_or(&empty);

        let mut tracks = Vec::new();
        for item in items_arr {
            let track_val = item.get("itemV2")
                .or_else(|| item.get("item"))
                .and_then(|v| v.get("data"));
            if let Some(track_val) = track_val {
                if let Some(track) = parse_gql_track(track_val) {
                    tracks.push(track);
                }
            }
        }

        let has_more = offset + (tracks.len() as u32) < total_count;
        let next_offset = if has_more {
            Some(offset + limit.min(100))
        } else {
            None
        };

        Ok(PaginatedResponse {
            items: tracks,
            total: total_count,
            limit: limit.min(100),
            next_offset,
            has_more,
        })
    }

    /// Create a new playlist via REST API.
    pub async fn create_playlist(
        &self,
        user_id: &str,
        name: &str,
        description: &str,
        public: bool,
    ) -> SpotifyResult<FullPlaylist> {
        let path = format!("/users/{}/playlists", user_id);
        let body = json!({
            "name": name,
            "description": description,
            "public": public,
            "collaborative": false
        });
        let rest: RestFullPlaylist = self.api_post(&path, &body).await?;
        Ok(FullPlaylist::from(rest))
    }

    /// Update playlist details via REST API.
    pub async fn update_playlist(
        &self,
        id: &str,
        name: Option<&str>,
        description: Option<&str>,
        public: Option<bool>,
    ) -> SpotifyResult<()> {
        let path = format!("/playlists/{}", id);
        let mut body = serde_json::Map::new();
        if let Some(n) = name {
            body.insert("name".to_string(), json!(n));
        }
        if let Some(d) = description {
            body.insert("description".to_string(), json!(d));
        }
        if let Some(p) = public {
            body.insert("public".to_string(), json!(p));
        }
        self.api_put(&path, &Value::Object(body)).await
    }

    /// Add tracks to a playlist via REST API.
    pub async fn add_tracks_to_playlist(
        &self,
        id: &str,
        track_ids: &[String],
        position: Option<u32>,
    ) -> SpotifyResult<()> {
        let uris: Vec<String> = track_ids.iter()
            .map(|tid| format!("spotify:track:{}", tid))
            .collect();
        let path = format!("/playlists/{}/tracks", id);
        let mut body = serde_json::Map::new();
        body.insert("uris".to_string(), json!(uris));
        if let Some(pos) = position {
            body.insert("position".to_string(), json!(pos));
        }
        self.api_post(&path, &Value::Object(body)).await
    }

    /// Remove tracks from a playlist via REST API.
    pub async fn remove_tracks_from_playlist(
        &self,
        id: &str,
        track_ids: &[String],
    ) -> SpotifyResult<()> {
        let uris: Vec<Value> = track_ids.iter()
            .map(|tid| json!({"uri": format!("spotify:track:{}", tid)}))
            .collect();
        let path = format!("/playlists/{}/tracks", id);
        let body = json!({ "tracks": uris });
        self.api_delete(&path, &body).await
    }

    /// Follow/save a playlist via REST API.
    pub async fn follow_playlist(&self, id: &str) -> SpotifyResult<()> {
        let path = format!("/playlists/{}/followers", id);
        let body = json!({ "public": false });
        self.api_put(&path, &body).await
    }

    /// Unfollow/unsave a playlist via REST API.
    pub async fn unfollow_playlist(&self, id: &str) -> SpotifyResult<()> {
        let path = format!("/playlists/{}/followers", id);
        self.api_delete(&path, &json!({})).await
    }
}
