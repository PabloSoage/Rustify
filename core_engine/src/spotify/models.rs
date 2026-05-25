// core_engine/src/spotify/models.rs
use serde::{Deserialize, Serialize};

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct SpotifyCredentials {
    pub access_token: String,
    pub expiration: u64, // Timestamp in milliseconds
}

#[derive(Debug, Serialize, Deserialize)]
pub struct SimpleArtist {
    pub id: String,
    pub name: String,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct SimpleAlbum {
    pub id: String,
    pub name: String,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct FullTrack {
    pub id: String,
    pub name: String,
    pub external_uri: String,
    pub duration_ms: u32,
    pub artists: Vec<SimpleArtist>,
    pub album: Option<SimpleAlbum>,
}