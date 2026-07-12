// core_engine/src/youtube/models.rs
use serde::{Deserialize, Serialize};

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct YouTubeTrack {
    pub id: String,
    pub title: String,
    pub author: String,
    pub duration_sec: u32,
    pub thumbnail_url: String,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct AudioStream {
    pub url: String,
    pub container: String, // "mp4" (AAC) o "webm" (Opus)
    pub bitrate: u32,
}

// =============================================================================
// YouTube Music (E40) — YTM domain models for JNI JSON serialization
// =============================================================================

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct YtmArtistRef {
    pub id: String,
    pub name: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct YtmThumb {
    pub url: String,
    pub width: u32,
    pub height: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct YtmTrack {
    pub video_id: String,
    pub title: String,
    pub artists: Vec<YtmArtistRef>,
    pub album_id: Option<String>,
    pub duration_sec: u32,
    pub thumbnail_url: String,
    pub is_explicit: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct YtmAlbum {
    pub browse_id: String,
    pub title: String,
    pub artists: Vec<YtmArtistRef>,
    pub year: Option<u32>,
    pub thumbnail_url: String,
    pub tracks: Vec<YtmTrack>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct YtmArtist {
    pub channel_id: String,
    pub name: String,
    pub thumbnail_url: String,
    pub top_tracks: Vec<YtmTrack>,
    pub albums: Vec<YtmAlbumSlim>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct YtmAlbumSlim {
    pub browse_id: String,
    pub title: String,
    pub year: Option<u32>,
    pub thumbnail_url: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct YtmPlaylist {
    pub playlist_id: String,
    pub title: String,
    pub author: Option<String>,
    pub thumbnail_url: String,
    pub tracks: Vec<YtmTrack>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct YtmSearchResults {
    pub tracks: Vec<YtmTrack>,
    pub albums: Vec<YtmAlbumSlim>,
    pub artists: Vec<YtmArtistRef>,
    pub playlists: Vec<YtmPlaylist>,
}