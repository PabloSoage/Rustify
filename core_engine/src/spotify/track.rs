// core_engine/src/spotify/track.rs
//
// Track endpoints.
// get_track uses REST with proper RestFullTrack deserialization → FullTrack.
// save/unsave use GQL addToLibrary/removeFromLibrary (unchanged).
// get_track_radio uses the REST recommendations endpoint (requires no extra scope).

use serde_json::json;
use crate::spotify::client::*;
use crate::spotify::models::*;

const HASH_ADD_TO_LIBRARY: &str = "a3c1ff58e6a36fec5fe1e3a193dc95d9071d96b9ba53c5ba9c1494fb1ee73915";
const HASH_REMOVE_FROM_LIBRARY: &str = "a3c1ff58e6a36fec5fe1e3a193dc95d9071d96b9ba53c5ba9c1494fb1ee73915";

impl SpotifyClient {
    /// Fetch full track details via GraphQL API.
    pub async fn get_track(&self, id: &str) -> SpotifyResult<FullTrack> {
        let uri = format!("spotify:track:{}", id);
        let variables = json!({
            "uri": uri
        });

        // Passes empty static hash to trigger dynamic lookups automatically
        let gql = self.gql_post(variables, "getTrack", "").await?;
        let track_val = &gql["data"]["trackUnion"];

        if track_val.is_null() || track_val["__typename"].as_str() != Some("Track") {
            return Err(SpotifyError::ApiError(404, "Track not found".to_string()));
        }

        parse_gql_track(track_val)
            .ok_or_else(|| SpotifyError::ParseError("Failed to parse track from GQL".to_string()))
    }

    /// Save tracks to library via GQL addToLibrary.
    pub async fn save_tracks(&self, ids: &[String]) -> SpotifyResult<()> {
        let uris: Vec<String> = ids.iter().map(|id| format!("spotify:track:{}", id)).collect();
        let variables = json!({ "uris": uris });
        self.gql_post(variables, "addToLibrary", HASH_ADD_TO_LIBRARY).await?;
        Ok(())
    }

    /// Remove tracks from library via GQL removeFromLibrary.
    pub async fn unsave_tracks(&self, ids: &[String]) -> SpotifyResult<()> {
        let uris: Vec<String> = ids.iter().map(|id| format!("spotify:track:{}", id)).collect();
        let variables = json!({ "uris": uris });
        self.gql_post(variables, "removeFromLibrary", HASH_REMOVE_FROM_LIBRARY).await?;
        Ok(())
    }

    /// Generate track radio
    /// Spotube/Hetu behavior: Gets the track, then searches for "{track_name} Radio"
    /// and fetches the first 50 tracks of that playlist.
    pub async fn get_track_radio(&self, id: &str) -> SpotifyResult<Vec<FullTrack>> {
        let track = self.get_track(id).await?;
        let query = format!("{} Radio", track.name);
        
        let playlists_resp = self.search_playlists(&query, 20, 0).await?;
        
        let radio_playlist = playlists_resp.items.into_iter()
            .find(|p| p.name == query)
            .ok_or_else(|| SpotifyError::InternalError("Radio playlist not found".into()))?;

        let tracks_resp = self.get_playlist_tracks(&radio_playlist.id, 50, 0).await?;
        // Convert the paginated response to a vector just like the hetu code
        Ok(tracks_resp.items)
    }
}
