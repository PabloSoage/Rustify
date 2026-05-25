// core_engine/src/youtube/scraper.rs
use reqwest::Client;
use serde_json::{json, Value};
use crate::youtube::models::{YouTubeTrack, AudioStream};

pub struct YouTubeScraper {
    http: Client,
    api_key: String,
}

impl YouTubeScraper {
    pub fn new() -> Self {
        // La API Key de InnerTube para la versión Web de YouTube es universal y estática
        let static_key = "AIzaSyAOphv2f7dfX4vGvT4X7_v4v7v4v7v4v7v".to_string();
        Self {
            http: Client::new(),
            api_key: static_key,
        }
    }

    /// Busca en YouTube Music emulando el cliente oficial 'WEB_REMIX'
    pub async fn search(&self, query: &str) -> Result<Vec<YouTubeTrack>, Box<dyn std::error::Error>> {
        let url = format!("https://music.youtube.com/youtubei/v1/search?key={}", self.api_key);

        // Payload oficial de InnerTube para simular una búsqueda en YT Music
        let body = json!({
            "context": {
                "client": {
                    "clientName": "WEB_REMIX",
                    "clientVersion": "1.20240520.01.00",
                    "hl": "es",
                    "gl": "ES"
                }
            },
            "query": query
        });

        let response = self.http.post(&url)
            .json(&body)
            .send()
            .await?;

        let json_res: Value = response.json().await?;
        let mut tracks = Vec::new();

        // Mapeo heurístico del árbol JSON masivo de InnerTube (sin crashing)
        if let Some(contents) = json_res["contents"]["tabbedSearchResultsRenderer"]["tabs"][0]["tabRenderer"]["content"]["sectionListRenderer"]["contents"].as_array() {
            for section in contents {
                if let Some(results) = section["musicShelfRenderer"]["contents"].as_array() {
                    for item in results {
                        if let Some(column) = item["musicResponsiveListItemRenderer"]["flexColumns"].as_array() {
                            // Extracción segura de ID, Título y Autor usando punteros seguros de Rust
                            let id = item["musicResponsiveListItemRenderer"]["playlistItemData"]["videoId"].as_str().unwrap_or("").to_string();
                            if id.is_empty() { continue; }

                            let title = column[0]["musicResponsiveListItemFlexColumnRenderer"]["text"]["runs"][0]["text"].as_str().unwrap_or("Unknown").to_string();
                            let author = column[1]["musicResponsiveListItemFlexColumnRenderer"]["text"]["runs"][0]["text"].as_str().unwrap_or("Unknown").to_string();

                            tracks.push(YouTubeTrack {
                                id,
                                title,
                                author,
                                duration_sec: 0, // Opcional: Parsear los 'runs' del tiempo si es necesario
                                thumbnail_url: String::new(),
                            });
                        }
                    }
                }
            }
        }

        Ok(tracks)
    }

    /// Extrae los streams directos de audio de un VideoID usando el endpoint /player
    pub async fn get_audio_streams(&self, video_id: &str) -> Result<Vec<AudioStream>, Box<dyn std::error::Error>> {
        let url = format!("https://music.youtube.com/youtubei/v1/player?key={}", self.api_key);

        let body = json!({
            "context": {
                "client": {
                    "clientName": "WEB_REMIX",
                    "clientVersion": "1.20240520.01.00",
                    "hl": "es",
                    "gl": "ES"
                }
            },
            "videoId": video_id,
            "playbackContext": {
                "contentPlaybackContext": {
                    "signatureTimestamp": 19500 // Firmware bypass para firmas rotatorias básicas
                }
            }
        });

        let response = self.http.post(&url)
            .json(&body)
            .send()
            .await?;

        let json_res: Value = response.json().await?;
        let mut streams = Vec::new();

        // Extraer formatos adaptativos (aquí Google separa video de audio por rendimiento)
        if let Some(formats) = json_res["streamingData"]["adaptiveFormats"].as_array() {
            for format in formats {
                let mime_type = format["mimeType"].as_str().unwrap_or("");

                // Nos quedamos exclusivamente con los flujos de AUDIO (ahorro drástico de datos y batería)
                if mime_type.contains("audio/") {
                    let stream_url = format["url"].as_str().unwrap_or("").to_string();
                    if stream_url.is_empty() { continue; }

                    let container = if mime_type.contains("webm") { "webm".to_string() } else { "mp4".to_string() };
                    let bitrate = format["bitrate"].as_u64().unwrap_or(0) as u32;

                    streams.push(AudioStream {
                        url: stream_url,
                        container,
                        bitrate,
                    });
                }
            }
        }

        // Ordenar por bitrate de mayor a menor calidad de audio de forma eficiente
        streams.sort_by(|a, b| b.bitrate.cmp(&a.bitrate));

        Ok(streams)
    }
}