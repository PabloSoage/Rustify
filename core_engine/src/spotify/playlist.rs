// core_engine/src/spotify/playlist.rs
//
// Playlist endpoints — REST with proper RestXxx deserialization.

use serde_json::{json, Value};
use crate::spotify::client::*;
use crate::spotify::models::*;

impl SpotifyClient {
    /// Fetch full playlist details via REST API.
    pub async fn get_playlist(&self, id: &str) -> SpotifyResult<FullPlaylist> {
        let path = format!("/playlists/{}", id);
        let rest: RestFullPlaylist = self.api_get(&path).await?;
        Ok(FullPlaylist::from(rest))
    }

    /// Fetch tracks within a playlist via REST API.
    /// Items deserialize into RestPlaylistTrackItem (which contains RestFullTrack),
    /// then convert to FullTrack via From.
    pub async fn get_playlist_tracks(&self, id: &str, limit: u32, offset: u32) -> SpotifyResult<PaginatedResponse<FullTrack>> {
        let path = format!("/playlists/{}/tracks?limit={}&offset={}", id, limit.min(100), offset);
        let paging: RestPaging<RestPlaylistTrackItem> = self.api_get(&path).await?;

        let tracks: Vec<FullTrack> = paging.items
            .into_iter()
            .filter_map(|item| item.track)
            .map(FullTrack::from)
            .collect();

        let next_offset = if paging.next.is_some() {
            Some(offset + limit.min(100))
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
