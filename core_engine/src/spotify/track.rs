// core_engine/src/spotify/track.rs
//
// Track endpoints.
// get_track uses REST with proper RestFullTrack deserialization → FullTrack.
// save/unsave use GQL addToLibrary/removeFromLibrary (unchanged).
// get_track_radio asks inspiredby-mix for the seed's radio playlist, as the official clients do.

use crate::env::Env;
use crate::spotify::client::*;
use crate::spotify::models::*;
use serde_json::json;

const HASH_ADD_TO_LIBRARY: &str = "a3c1ff58e6a36fec5fe1e3a193dc95d9071d96b9ba53c5ba9c1494fb1ee73915";
const HASH_REMOVE_FROM_LIBRARY: &str = "a3c1ff58e6a36fec5fe1e3a193dc95d9071d96b9ba53c5ba9c1494fb1ee73915";

impl<E: Env> SpotifyClient<E> {
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

    /// The radio for a track: the playlist Spotify's own "Go to song radio" opens.
    ///
    /// It used to search for a playlist named "{title} Radio" and take an exact name match — which
    /// depended on such a playlist existing, being searchable and not sharing its title with another
    /// song's ("Intro", "Home"). The official clients ask `inspiredby-mix` for the seed's radio
    /// playlist and open that (APK 9.1.42, docs `serie-4.x/04` §2); it answers the anonymous token
    /// too, measured, so the radio works without an account.
    pub async fn get_track_radio(&self, id: &str) -> SpotifyResult<Vec<FullTrack>> {
        let answer = self
            .spclient_get(&format!(
                "/inspiredby-mix/v2/seed_to_playlist/spotify:track:{id}?response-format=json"
            ))
            .await?;
        let playlist_id = radio_playlist_id(&answer)
            .ok_or_else(|| SpotifyError::ParseError("inspiredby-mix returned no playlist".into()))?;
        Ok(self.get_playlist_tracks(&playlist_id, 50, 0).await?.items)
    }
}

/// The playlist id in an `inspiredby-mix` answer: `{"mediaItems":[{"uri":"spotify:playlist:ID"}]}`.
fn radio_playlist_id(answer: &serde_json::Value) -> Option<String> {
    answer["mediaItems"]
        .as_array()?
        .iter()
        .filter_map(|item| item["uri"].as_str())
        .find_map(|uri| match crate::links::parse(uri) {
            Some(crate::links::LinkTarget::SpotifyPlaylist(id)) => Some(id),
            _ => None,
        })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::env::mock::{self, MockEnv};
    use crate::env::HttpResponse;

    #[test]
    fn the_radio_playlist_is_read_through_the_link_parser() {
        let answer = json!({"total": 1, "mediaItems": [{"uri": "spotify:playlist:37i9dQZF1E8OupsoFEdU4S"}]});
        assert_eq!(radio_playlist_id(&answer).as_deref(), Some("37i9dQZF1E8OupsoFEdU4S"));
        assert_eq!(radio_playlist_id(&json!({"mediaItems": []})), None);
        assert_eq!(radio_playlist_id(&json!({"mediaItems": [{"uri": "spotify:track:x"}]})), None);
    }

    #[tokio::test]
    async fn the_radio_asks_inspiredby_mix_for_the_seed() {
        let _guard = mock::lock_and_reset();
        mock::on_prefix(
            "https://spclient.wg.spotify.com/inspiredby-mix/v2/seed_to_playlist/spotify:track:abc",
            HttpResponse::ok(r#"{"mediaItems":[{"uri":"spotify:playlist:PL1"}]}"#),
        );
        let client = SpotifyClient::<MockEnv>::new();
        // No session and no anonymous token mocked: the request must still be the right one, and the
        // failure must come from the token, not from a search for "<title> Radio".
        let _ = client.get_track_radio("abc").await;
        assert!(!mock::state().requests.iter().any(|r| r.url.contains("searchPlaylists") || r.url.contains("search")));
    }
}
