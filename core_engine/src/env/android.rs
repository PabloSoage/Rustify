// core_engine/src/env/android.rs
//
// The Android implementation of [`Env`]. This is the only file in the engine that is allowed to
// assume Android; everything above it is portable by construction.

use super::{Env, EnvError, HttpRequest, HttpResponse, LogLevel, Method, TryEnvFuture};
use std::path::PathBuf;
use std::sync::OnceLock;
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tokio::io::AsyncWriteExt;

/// Where persisted keys live. Set from JNI at startup; falls back to the resolver cache directory,
/// which the app already initialises, so there is one less thing to forget to wire up.
static STORAGE_DIR: OnceLock<PathBuf> = OnceLock::new();

/// One client for the whole process: connection pooling and TLS session reuse are most of the
/// latency budget on mobile.
static HTTP: OnceLock<reqwest::Client> = OnceLock::new();

/// Sets the directory persisted keys are written to. Idempotent; the first call wins.
pub fn init_storage_dir(dir: &str) {
    let _ = STORAGE_DIR.set(PathBuf::from(dir));
}

/// Ceiling for any request that does not set its own [`HttpRequest::timeout_ms`].
///
/// This is a safety net with teeth, not a default anyone should rely on. Before 3.0 the Spotify
/// client owned its own `reqwest::Client` with a 15 s whole-request timeout; when requests moved
/// behind `Env` that timeout moved to `SpotifyClient::base_request`, which left every *other*
/// caller — the GQL hash scraper pulling a 4.7 MB bundle, the streaming server fetching upstream —
/// with a connect timeout and **nothing bounding the read**. On a slow connection that is a request
/// that never returns, which is precisely the failure this app already has a name for (point N).
const DEFAULT_REQUEST_TIMEOUT: Duration = Duration::from_secs(30);

/// Ceiling for a whole download in [`Env::fetch_to_file`]. Ten minutes is not a latency budget, it
/// is a "this will never finish" detector: a FLAC on a bad connection can genuinely take minutes,
/// and the 30 s request ceiling would kill every one of them.
const DOWNLOAD_TIMEOUT_MS: u64 = 10 * 60 * 1000;

fn http_client() -> &'static reqwest::Client {
    HTTP.get_or_init(|| {
        reqwest::Client::builder()
            .connect_timeout(Duration::from_secs(10))
            .timeout(DEFAULT_REQUEST_TIMEOUT)
            .pool_idle_timeout(Duration::from_secs(30))
            .build()
            .unwrap_or_else(|_| reqwest::Client::new())
    })
}

/// Keys live in their own subdirectory so they cannot collide with the loose JSON files the engine
/// wrote before this abstraction existed (`youtube_mappings.json` and friends).
fn storage_path(key: &str) -> Option<PathBuf> {
    let base = match STORAGE_DIR.get() {
        Some(dir) => dir.clone(),
        None => PathBuf::from(crate::youtube::server::get_cache_dir()?),
    };
    Some(base.join("store").join(super::storage::sanitise_key(key)))
}

fn to_reqwest_method(method: Method) -> reqwest::Method {
    match method {
        Method::Get => reqwest::Method::GET,
        Method::Post => reqwest::Method::POST,
        Method::Put => reqwest::Method::PUT,
        Method::Delete => reqwest::Method::DELETE,
        Method::Head => reqwest::Method::HEAD,
    }
}

pub struct AndroidEnv;

impl Env for AndroidEnv {
    fn fetch(req: HttpRequest) -> TryEnvFuture<HttpResponse> {
        Box::pin(async move {
            let mut builder = http_client().request(to_reqwest_method(req.method), &req.url);
            for (name, value) in &req.headers {
                builder = builder.header(name.as_str(), value.as_str());
            }
            if let Some(ms) = req.timeout_ms {
                builder = builder.timeout(Duration::from_millis(ms));
            }
            if let Some(body) = req.body {
                builder = builder.body(body);
            }

            let response = builder
                .send()
                .await
                .map_err(|e| EnvError::Fetch(e.to_string()))?;

            let status = response.status().as_u16();
            // Collected before `bytes()`, which consumes the response.
            let headers = response
                .headers()
                .iter()
                .filter_map(|(name, value)| {
                    value
                        .to_str()
                        .ok()
                        .map(|value| (name.as_str().to_string(), value.to_string()))
                })
                .collect();
            let body = response
                .bytes()
                .await
                .map_err(|e| EnvError::Fetch(e.to_string()))?
                .to_vec();

            Ok(HttpResponse {
                status,
                headers,
                body,
            })
        })
    }

    fn fetch_to_file(req: HttpRequest, path: &str) -> TryEnvFuture<u64> {
        let path = PathBuf::from(path);
        Box::pin(async move {
            let mut builder = http_client().request(to_reqwest_method(req.method), &req.url);
            for (name, value) in &req.headers {
                builder = builder.header(name.as_str(), value.as_str());
            }
            // A download is not a request: a 40 MB track over a slow connection legitimately takes
            // longer than `DEFAULT_REQUEST_TIMEOUT`, which is why this one is stated per call and
            // is generous. It is still bounded — an unbounded read is what point N is about.
            builder = builder.timeout(Duration::from_millis(
                req.timeout_ms.unwrap_or(DOWNLOAD_TIMEOUT_MS),
            ));
            if let Some(body) = req.body {
                builder = builder.body(body);
            }

            let mut response = builder
                .send()
                .await
                .map_err(|e| EnvError::Fetch(e.to_string()))?;
            let status = response.status().as_u16();
            if !(200..300).contains(&status) {
                return Err(EnvError::Fetch(format!("upstream answered {status}")));
            }

            if let Some(parent) = path.parent() {
                tokio::fs::create_dir_all(parent)
                    .await
                    .map_err(|e| EnvError::Storage(e.to_string()))?;
            }
            // Same reasoning as `set_storage`: write beside the destination and rename, so a
            // reader never sees a partial file under the name that means "complete".
            let mut tmp = path.clone().into_os_string();
            tmp.push(".part");
            let tmp = PathBuf::from(tmp);

            let mut written: u64 = 0;
            {
                let file = tokio::fs::File::create(&tmp)
                    .await
                    .map_err(|e| EnvError::Storage(e.to_string()))?;
                let mut file = tokio::io::BufWriter::new(file);
                // `chunk()` rather than `bytes()`: this is the whole point of the method. It needs
                // no `stream` feature and never materialises the body.
                loop {
                    let chunk = match response.chunk().await {
                        Ok(Some(chunk)) => chunk,
                        Ok(None) => break,
                        Err(e) => {
                            // The temporary is removed rather than left behind: it is indistinguishable
                            // from a complete download except by size, and nothing knows the size.
                            let _ = tokio::fs::remove_file(&tmp).await;
                            return Err(EnvError::Fetch(e.to_string()));
                        }
                    };
                    written += chunk.len() as u64;
                    if let Err(e) = file.write_all(&chunk).await {
                        let _ = tokio::fs::remove_file(&tmp).await;
                        return Err(EnvError::Storage(e.to_string()));
                    }
                }
                if let Err(e) = file.flush().await {
                    let _ = tokio::fs::remove_file(&tmp).await;
                    return Err(EnvError::Storage(e.to_string()));
                }
            }

            tokio::fs::rename(&tmp, &path)
                .await
                .map_err(|e| EnvError::Storage(e.to_string()))?;
            Ok(written)
        })
    }

    fn get_storage(key: &str) -> TryEnvFuture<Option<String>> {
        let path = storage_path(key);
        Box::pin(async move {
            let path = path.ok_or_else(|| EnvError::Storage("storage dir not initialised".into()))?;
            match tokio::fs::read_to_string(&path).await {
                Ok(contents) => Ok(Some(contents)),
                Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(None),
                Err(e) => Err(EnvError::Storage(e.to_string())),
            }
        })
    }

    fn set_storage(key: &str, value: Option<&str>) -> TryEnvFuture<()> {
        let path = storage_path(key);
        let value = value.map(str::to_owned);
        Box::pin(async move {
            let path = path.ok_or_else(|| EnvError::Storage("storage dir not initialised".into()))?;
            let value = match value {
                Some(value) => value,
                // Deleting what is not there is a success, not a failure.
                None => {
                    return match tokio::fs::remove_file(&path).await {
                        Ok(()) => Ok(()),
                        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
                        Err(e) => Err(EnvError::Storage(e.to_string())),
                    }
                }
            };

            if let Some(parent) = path.parent() {
                tokio::fs::create_dir_all(parent)
                    .await
                    .map_err(|e| EnvError::Storage(e.to_string()))?;
            }

            // Write to a temporary file and rename over the target. `rename` is atomic within a
            // filesystem, so a process death mid-write leaves the previous value intact instead of
            // a half-written file — which is how a truncated JSON becomes a wiped library.
            //
            // Appended, not `with_extension("tmp")`: that *replaces* the extension, so the keys
            // `schema.v1` and `schema.v2` would share one temporary and race each other.
            let tmp = {
                let mut tmp = path.clone().into_os_string();
                tmp.push(".tmp");
                PathBuf::from(tmp)
            };
            tokio::fs::write(&tmp, value.as_bytes())
                .await
                .map_err(|e| EnvError::Storage(e.to_string()))?;
            tokio::fs::rename(&tmp, &path)
                .await
                .map_err(|e| EnvError::Storage(e.to_string()))?;
            Ok(())
        })
    }

    fn now_ms() -> i64 {
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_millis() as i64)
            .unwrap_or(0)
    }

    fn exec_concurrent<F>(future: F)
    where
        F: std::future::Future<Output = ()> + Send + 'static,
    {
        // Explicitly the engine's runtime rather than `tokio::spawn`, which panics when called from
        // a thread with no runtime in scope — and JNI calls arrive on JVM threads.
        crate::get_runtime().spawn(future);
    }

    fn log(level: LogLevel, tag: &str, message: &str) {
        #[cfg(target_os = "android")]
        {
            use std::ffi::CString;
            extern "C" {
                fn __android_log_write(
                    prio: std::os::raw::c_int,
                    tag: *const std::os::raw::c_char,
                    text: *const std::os::raw::c_char,
                ) -> std::os::raw::c_int;
            }
            let prio = match level {
                LogLevel::Debug => 3,
                LogLevel::Info => 4,
                LogLevel::Warn => 5,
                LogLevel::Error => 6,
            };
            if let (Ok(tag), Ok(text)) = (CString::new(tag), CString::new(message)) {
                unsafe {
                    __android_log_write(prio, tag.as_ptr(), text.as_ptr());
                }
            }
        }
        #[cfg(not(target_os = "android"))]
        {
            let _ = level;
            eprintln!("[{tag}] {message}");
        }
    }
}
