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