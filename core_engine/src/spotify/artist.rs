// core_engine/src/spotify/artist.rs
//
// Artist endpoints — REST with proper RestXxx types.

use serde_json::json;
use crate::spotify::client::*;
use crate::spotify::models::*;

impl SpotifyClient {
    /// Fetch artist details via REST API.
    pub async fn get_artist(&self, id: &str) -> SpotifyResult<FullArtist> {
        let path = format!("/artists/{}", id);
        let rest: RestFullArtist = self.api_get(&path).await?;
        Ok(FullArtist::from(rest))
    }

    /// Fetch artist's top tracks via REST API.
    pub async fn get_artist_top_tracks(&self, id: &str, limit: u32, offset: u32) -> SpotifyResult<PaginatedResponse<FullTrack>> {
        // market=from_token works with web-player tokens and avoids hardcoding a country
        let path = format!("/artists/{}/top-tracks?market=from_token", id);
        let rest: RestTopTracksResponse = self.api_get(&path).await?;
        let tracks: Vec<FullTrack> = rest.tracks.into_iter().map(FullTrack::from).collect();
        let total = tracks.len() as u32;

        Ok(PaginatedResponse {
            items: tracks,
            total,
            limit,
            next_offset: Some(offset + limit),
            has_more: false,
        })
    }

    /// Fetch artist's albums via REST API.
    pub async fn get_artist_albums(&self, id: &str, limit: u32, offset: u32) -> SpotifyResult<PaginatedResponse<SimpleAlbum>> {
        let path = format!("/artists/{}/albums?limit={}&offset={}", id, limit.min(50), offset);
        let paging: RestPaging<RestSimpleAlbum> = self.api_get(&path).await?;

        let next_offset = if paging.next.is_some() {
            Some(offset + limit.min(50))
        } else {
            None
        };

        Ok(PaginatedResponse {
            items: paging.items.into_iter().map(SimpleAlbum::from).collect(),
            total: paging.total,
            limit: paging.limit,
            next_offset,
            has_more: paging.next.is_some(),
        })
    }

    /// Fetch related artists via REST API.
    pub async fn get_related_artists(&self, id: &str, limit: u32, offset: u32) -> SpotifyResult<PaginatedResponse<FullArtist>> {
        let path = format!("/artists/{}/related-artists", id);
        let rest: RestRelatedArtistsResponse = self.api_get(&path).await?;
        let artists: Vec<FullArtist> = rest.artists.into_iter().map(FullArtist::from).collect();
        let total = artists.len() as u32;

        Ok(PaginatedResponse {
            items: artists,
            total,
            limit,
            next_offset: Some(offset + limit),
            has_more: false,
        })
    }

    /// Follow artists via GQL addToLibrary (matches Spotube plugin behaviour).
    pub async fn follow_artists(&self, ids: &[String]) -> SpotifyResult<()> {
        if ids.is_empty() { return Ok(()); }
        let uris: Vec<String> = ids.iter().map(|id| format!("spotify:artist:{}", id)).collect();
        let variables = json!({ "uris": uris });
        self.gql_post(variables, "addToLibrary", "a3c1ff58e6a36fec5fe1e3a193dc95d9071d96b9ba53c5ba9c1494fb1ee73915").await?;
        Ok(())
    }

    /// Unfollow artists via GQL removeFromLibrary (matches Spotube plugin behaviour).
    pub async fn unfollow_artists(&self, ids: &[String]) -> SpotifyResult<()> {
        if ids.is_empty() { return Ok(()); }
        let uris: Vec<String> = ids.iter().map(|id| format!("spotify:artist:{}", id)).collect();
        let variables = json!({ "uris": uris });
        self.gql_post(variables, "removeFromLibrary", "a3c1ff58e6a36fec5fe1e3a193dc95d9071d96b9ba53c5ba9c1494fb1ee73915").await?;
        Ok(())
    }
}
