// core_engine/src/spotify/client.rs
use reqwest::Client;
use serde_json::Value;
use std::time::{SystemTime, UNIX_EPOCH};
use totp_rs::{Algorithm, Secret, TOTP};
use crate::spotify::models::SpotifyCredentials;

pub struct SpotifyClient {
    http: Client,
    credentials: Option<SpotifyCredentials>,
}

impl SpotifyClient {
    pub fn new() -> Self {
        Self {
            http: Client::new(),
            credentials: None,
        }
    }

    /// Obtiene el último 'nuance' (semilla) desde el Gist público de GitHub
    async fn get_latest_nuance(&self) -> Result<(u32, String), Box<dyn std::error::Error>> {
        let response = self.http.get("https://api.github.com/gists/22ed9c6ba463899e933427f7de1f0eef")
            .header("User-Agent", "Rustify-Core") // GitHub requires a User-Agent
            .send()
            .await?;

        let gist_json: Value = response.json().await?;
        let content_str = gist_json["files"]["nuances.json"]["content"].as_str().unwrap_or("{}");

        // Parse the inner JSON string
        let nuances: Vec<Value> = serde_json::from_str(content_str)?;

        // The Dart code sorted by "v" descending and took the first.
        // Assuming the first element is the latest for simplicity here.
        if let Some(latest) = nuances.first() {
            let v = latest["v"].as_u64().unwrap_or(0) as u32;
            let s = latest["s"].as_str().unwrap_or("").to_string();
            Ok((v, s))
        } else {
            Err("No nuances found in Gist".into())
        }
    }

    /// Genera la contraseña TOTP basada en el tiempo del servidor de Spotify
    async fn generate_totp(&self, secret_str: &str) -> Result<String, Box<dyn std::error::Error>> {
        // Fetch server time
        let time_res = self.http.get("https://open.spotify.com/api/server-time")
            .send()
            .await?;

        let time_json: Value = time_res.json().await?;
        let timestamp_seconds = time_json["serverTime"].as_u64().unwrap_or(0);

        // Setup TOTP
        let secret = Secret::Raw(secret_str.as_bytes().to_vec());
        let totp = TOTP::new(
            Algorithm::SHA1,
            6,
            1,
            30,
            secret.to_bytes().unwrap(),
        )?;

        // Generate the code based on Spotify's server time
        let code = totp.generate(timestamp_seconds);
        Ok(code)
    }

    /// Comprueba si el token actual está caducado
    pub fn is_expired(&self) -> bool {
        if let Some(creds) = &self.credentials {
            let now = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_millis() as u64;
            return now > creds.expiration;
        }
        true
    }
}