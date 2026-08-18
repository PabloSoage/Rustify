// core_engine/src/spotify/playlist.rs
//
// Playlist endpoints.
// GET requests now use 100% GraphQL via api-partner.spotify.com.
// Most writes stay on REST, which they can: the exception is adding tracks, where REST answers a
// web-player token with a 429 that no amount of waiting clears — see `add_tracks_to_playlist`.

use crate::env::Env;
use crate::spotify::client::*;
use crate::spotify::models::*;
use serde_json::{json, Value};

impl<E: Env> SpotifyClient<E> {
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

    /// Add tracks to a playlist via the GQL `addToPlaylist` mutation.
    ///
    /// This is the one mutation that had to leave REST. `POST /v1/playlists/{id}/tracks` answers a
    /// web-player token with 429 no matter how patiently it is asked — the throttle is on the app
    /// token, not on the account, so waiting out `Retry-After` only postponed the same refusal and
    /// spent a minute of frozen UI doing it. The web player never touches that endpoint; it posts
    /// this mutation to pathfinder with the same token that already serves every read here.
    ///
    /// [position] is a placement, not an index: the mutation takes `newPosition` rather than an
    /// offset, and the only two placements the web player uses are the ends of the list.
    pub async fn add_tracks_to_playlist(
        &self,
        id: &str,
        track_ids: &[String],
        position: Option<u32>,
    ) -> SpotifyResult<()> {
        if track_ids.is_empty() {
            return Ok(());
        }
        let uris: Vec<String> = track_ids.iter()
            .map(|tid| format!("spotify:track:{}", tid))
            .collect();
        let move_type = if position == Some(0) {
            "TOP_OF_PLAYLIST"
        } else {
            "BOTTOM_OF_PLAYLIST"
        };
        // `playlistItemUris`, not `uris`. Measured: the operation answered
        // `missing variable '$playlistItemUris'`, which is also the proof that the name and the hash
        // were right — pathfinder had resolved the query before it looked at the arguments.
        let variables = json!({
            "playlistUri": format!("spotify:playlist:{}", id),
            "playlistItemUris": uris,
            "newPosition": { "moveType": move_type, "fromUid": Value::Null }
        });

        // The hash is scraped from the live web player, like every other operation here; there is no
        // static fallback on purpose, since a stale hash answers PersistedQueryNotFound and that is
        // harder to read than "no hash for addToPlaylist".
        let gql = self.gql_post(variables, "addToPlaylist", "").await?;

        // A mutation that refuses still answers 200, naming the refusal as the union member in the
        // payload, so the outcome has to be read rather than assumed. But only a *named* failure
        // counts as one, and the field it arrives under is not assumed either: an earlier version
        // demanded `data.addToPlaylist`, did not find it, and reported "no result" for a write that
        // had already gone through. Anything reaching this point was accepted — `gql_post` raises on
        // an `errors` array and on any HTTP status — so silence means success.
        if let Some(data) = gql["data"].as_object() {
            for value in data.values() {
                let typename = value["__typename"].as_str().unwrap_or("");
                if typename.contains("Error")
                    || typename.contains("Failure")
                    || typename.contains("Denied")
                {
                    return Err(SpotifyError::ApiError(403, format!("addToPlaylist: {}", typename)));
                }
            }
        }
        Ok(())
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
