// core_engine/src/server/fill.rs
//
// Getting a track onto disk, so the *next* play of it is a file read.
//
// The one rule this file exists to enforce: **a fill never happens while anyone is waiting for it.**
// The player is handed the loopback URL only when the bytes are already there, and otherwise plays
// the upstream URL exactly as it did before this server existed. A fill is therefore always
// background work, and its failure is a log line, not a playback error.
//
// Nothing here holds a track in memory: `Env::fetch_to_file` streams to disk, and the Deezer
// decryption reads and writes a window at a time.

use crate::audio::deezer;
use crate::env::{Env, EnvError, HttpRequest, LogLevel};
use std::collections::HashSet;
use std::sync::{Mutex, OnceLock};

/// What has to happen to the bytes between the CDN and the cache.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Kind {
    /// Store what arrives.
    Plain,
    /// Blowfish-striped Deezer audio: decrypt with the key derived from this song id.
    ///
    /// This is the reason the decryption moved into the core. Behind the server it is an ordinary
    /// file and Media3 needs no custom `DataSource` to play it, which is the whole point of C.
    Deezer { sng_id: String },
}

/// Cache paths currently being filled, so a player retrying, or a queue that pre-buffers the same
/// track twice, does not start a download per attempt.
static FILLING: OnceLock<Mutex<HashSet<String>>> = OnceLock::new();

fn claim(cache_path: &str) -> bool {
    let filling = FILLING.get_or_init(|| Mutex::new(HashSet::new()));
    match filling.lock() {
        Ok(mut set) => set.insert(cache_path.to_owned()),
        Err(_) => false,
    }
}

fn release(cache_path: &str) {
    if let Some(filling) = FILLING.get() {
        if let Ok(mut set) = filling.lock() {
            set.remove(cache_path);
        }
    }
}

/// Is this path being downloaded right now? Diagnostics and tests.
pub fn is_filling(cache_path: &str) -> bool {
    FILLING
        .get()
        .and_then(|f| f.lock().ok().map(|set| set.contains(cache_path)))
        .unwrap_or(false)
}

/// Starts a background fill, unless one is already running for this path.
///
/// Returns whether it started one. Fire and forget by design: the caller has already told the
/// player to use the upstream URL.
pub fn start<E: Env>(url: String, cache_path: String, kind: Kind, sweep_root: Option<String>) -> bool {
    if !claim(&cache_path) {
        return false;
    }

    E::exec_concurrent(async move {
        match run::<E>(&url, &cache_path, &kind).await {
            Ok(bytes) => {
                E::log(
                    LogLevel::Info,
                    "StreamCache",
                    &format!("cached {bytes} bytes for {cache_path}"),
                );
                if let Some(root) = sweep_root {
                    let freed = super::cache::sweep(&root, super::cache::DEFAULT_BUDGET_BYTES).await;
                    if freed > 0 {
                        E::log(
                            LogLevel::Info,
                            "StreamCache",
                            &format!("swept {freed} bytes to stay under budget"),
                        );
                    }
                }
            }
            Err(e) => E::log(
                LogLevel::Warn,
                "StreamCache",
                &format!("fill failed for {cache_path}: {e}"),
            ),
        }
        release(&cache_path);
    });
    true
}

async fn run<E: Env>(url: &str, cache_path: &str, kind: &Kind) -> Result<u64, EnvError> {
    match kind {
        // `fetch_to_file` already writes beside the destination and renames, so a reader never sees
        // a partial file under the name that means "complete".
        Kind::Plain => E::fetch_to_file(HttpRequest::get(url), cache_path).await,

        Kind::Deezer { sng_id } => {
            let encrypted = format!("{cache_path}.enc");
            let decrypted = format!("{cache_path}.dec");

            E::fetch_to_file(HttpRequest::get(url), &encrypted).await?;

            let outcome = deezer::decrypt_file(sng_id, &encrypted, &decrypted).await;
            // The ciphertext is dead the moment the plaintext exists, and leaving it behind would
            // double what this cache costs on disk for no benefit at all.
            let _ = tokio::fs::remove_file(&encrypted).await;
            let bytes = outcome.map_err(|e| EnvError::Storage(e.to_string()))?;

            // Renamed last: `cache_path` appearing means the file is complete *and* decrypted. Any
            // other order leaves a window where a registration could hand out a URL to ciphertext.
            tokio::fs::rename(&decrypted, cache_path)
                .await
                .map_err(|e| EnvError::Storage(e.to_string()))?;
            Ok(bytes)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::env::mock::{self, MockEnv};
    use crate::env::HttpResponse;

    fn ok_body(body: Vec<u8>) -> HttpResponse {
        HttpResponse {
            status: 200,
            headers: Vec::new(),
            body,
        }
    }

    async fn scratch() -> std::path::PathBuf {
        let dir =
            std::env::temp_dir().join(format!("rustify-fill-{}", super::super::random_hex(8)));
        tokio::fs::create_dir_all(&dir).await.unwrap();
        dir
    }

    #[tokio::test]
    async fn a_plain_fill_lands_the_bytes_at_the_cache_path() {
        let _guard = mock::lock_and_reset();
        let dir = scratch().await;
        let cache = dir.join("track.bin").to_str().unwrap().to_owned();
        mock::on_url("https://cdn.test/a", ok_body(b"hello".to_vec()));

        let bytes = run::<MockEnv>("https://cdn.test/a", &cache, &Kind::Plain)
            .await
            .unwrap();
        assert_eq!(bytes, 5);
        assert_eq!(tokio::fs::read(&cache).await.unwrap(), b"hello");

        let _ = tokio::fs::remove_dir_all(&dir).await;
    }

    #[tokio::test]
    async fn a_deezer_fill_decrypts_and_leaves_no_ciphertext_behind() {
        use blowfish::cipher::Block;
        use blowfish::cipher::{BlockEncrypt, KeyInit};
        use blowfish::Blowfish;

        let _guard = mock::lock_and_reset();
        let dir = scratch().await;
        let cache = dir.join("deezer-1.flac").to_str().unwrap().to_owned();

        let sng_id = "3135556";
        let key = deezer::blowfish_key(sng_id);
        let plain: Vec<u8> = (0..(deezer::STRIPE * 2)).map(|i| (i % 251) as u8).collect();

        // Encrypt stripe 0 only, which is what the CDN does.
        let mut wire = plain.clone();
        let cipher = <Blowfish as KeyInit>::new_from_slice(&key).unwrap();
        let mut prev = [0u8, 1, 2, 3, 4, 5, 6, 7];
        for offset in (0..deezer::STRIPE).step_by(8) {
            let mut block = [0u8; 8];
            for i in 0..8 {
                block[i] = wire[offset + i] ^ prev[i];
            }
            let mut ga = Block::<Blowfish>::clone_from_slice(&block);
            cipher.encrypt_block(&mut ga);
            for i in 0..8 {
                wire[offset + i] = ga[i];
            }
            prev.copy_from_slice(&ga);
        }
        mock::on_url("https://cdn.test/enc", ok_body(wire));

        run::<MockEnv>(
            "https://cdn.test/enc",
            &cache,
            &Kind::Deezer {
                sng_id: sng_id.to_owned(),
            },
        )
        .await
        .unwrap();

        assert_eq!(tokio::fs::read(&cache).await.unwrap(), plain);
        assert!(
            !std::path::Path::new(&format!("{cache}.enc")).exists(),
            "the ciphertext must not survive the fill"
        );
        assert!(!std::path::Path::new(&format!("{cache}.dec")).exists());

        let _ = tokio::fs::remove_dir_all(&dir).await;
    }

    #[tokio::test]
    async fn a_failed_fill_leaves_nothing_at_the_cache_path() {
        // The consequence of getting this wrong is a zero-byte file that registers as "cached", so
        // every later play of that track serves silence and never retries.
        let _guard = mock::lock_and_reset();
        let dir = scratch().await;
        let cache = dir.join("gone.bin").to_str().unwrap().to_owned();
        mock::on_url(
            "https://cdn.test/410",
            HttpResponse {
                status: 410,
                headers: Vec::new(),
                body: Vec::new(),
            },
        );

        assert!(run::<MockEnv>("https://cdn.test/410", &cache, &Kind::Plain)
            .await
            .is_err());
        assert!(!std::path::Path::new(&cache).exists());

        let _ = tokio::fs::remove_dir_all(&dir).await;
    }

    #[test]
    fn the_same_path_is_only_claimed_once() {
        assert!(claim("/tmp/only-once"));
        assert!(!claim("/tmp/only-once"));
        assert!(is_filling("/tmp/only-once"));
        release("/tmp/only-once");
        assert!(!is_filling("/tmp/only-once"));
        assert!(claim("/tmp/only-once"));
        release("/tmp/only-once");
    }
}
