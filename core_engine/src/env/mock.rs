// core_engine/src/env/mock.rs
//
// An [`Env`] that touches nothing: canned responses, in-memory storage, a clock that only moves
// when a test moves it. This is the payoff of the whole abstraction — before it there was no way to
// exercise the engine without a network and a device.

use super::{
    ByteStream, Env, EnvError, HttpRequest, HttpResponse, HttpResponseHead, LogLevel, TryEnvFuture,
};
use bytes::Bytes;
use std::collections::HashMap;
use std::sync::{Mutex, MutexGuard, OnceLock};

/// How a canned body is cut up by [`Env::fetch_stream`].
///
/// **Deliberately hostile sizes.** A real network hands over whatever it feels like, and the one
/// assumption that would quietly break the Deezer proxy is "a chunk ends where a stripe ends". So
/// the default pattern is primes and near-misses around the 2048-byte stripe: 1 byte, 7, one short
/// of a stripe, exactly a stripe, and two and a half. A test that passes against this cannot be
/// passing by alignment luck.
const DEFAULT_CHUNK_SIZES: [usize; 6] = [1, 7, 2047, 2048, 5000, 3];

/// Splits `body` following `sizes`, cycling through them. An empty `sizes` means one chunk.
fn split_into_chunks(body: &[u8], sizes: &[usize]) -> Vec<Bytes> {
    if body.is_empty() {
        return Vec::new();
    }
    if sizes.is_empty() {
        return vec![Bytes::copy_from_slice(body)];
    }
    let mut chunks = Vec::new();
    let mut offset = 0;
    let mut i = 0;
    while offset < body.len() {
        let want = sizes[i % sizes.len()].max(1);
        let end = offset.saturating_add(want).min(body.len());
        chunks.push(Bytes::copy_from_slice(&body[offset..end]));
        offset = end;
        i += 1;
    }
    chunks
}

#[derive(Debug, Default)]
pub struct MockState {
    /// Key → value, exactly as `get_storage`/`set_storage` see it.
    pub storage: HashMap<String, String>,
    /// Exact-URL canned responses, consulted before `response_prefixes`.
    pub responses: HashMap<String, HttpResponse>,
    /// Prefix → response, for URLs with query strings you do not want to spell out.
    pub response_prefixes: Vec<(String, HttpResponse)>,
    /// Every request that was made, in order — so a test can assert on what was *asked*.
    pub requests: Vec<HttpRequest>,
    pub now_ms: i64,
    pub logs: Vec<(LogLevel, String, String)>,
    /// Chunk sizes `fetch_stream` cuts a canned body into, cycled. See [`DEFAULT_CHUNK_SIZES`].
    pub stream_chunk_sizes: Vec<usize>,
}

impl MockState {
    fn fresh() -> Self {
        MockState {
            stream_chunk_sizes: DEFAULT_CHUNK_SIZES.to_vec(),
            ..MockState::default()
        }
    }
}

static STATE: OnceLock<Mutex<MockState>> = OnceLock::new();

/// Serialises tests. `Env`'s methods are associated functions, so the mock's state is necessarily
/// process-wide, and Rust runs tests in parallel threads by default. Every test that touches
/// `MockEnv` must hold this guard.
static TEST_LOCK: Mutex<()> = Mutex::new(());

pub fn state() -> MutexGuard<'static, MockState> {
    STATE
        .get_or_init(|| Mutex::new(MockState::fresh()))
        .lock()
        // A panic inside one test must not cascade into "poisoned" failures in every later one.
        .unwrap_or_else(|e| e.into_inner())
}

/// Takes the test lock and wipes the state. Hold the returned guard for the whole test.
#[must_use = "the guard must outlive the test, or another test will race with it"]
pub fn lock_and_reset() -> MutexGuard<'static, ()> {
    let guard = TEST_LOCK.lock().unwrap_or_else(|e| e.into_inner());
    *state() = MockState::fresh();
    guard
}

/// Registers a canned response for an exact URL.
pub fn on_url(url: impl Into<String>, response: HttpResponse) {
    state().responses.insert(url.into(), response);
}

/// Registers a canned response for any URL starting with `prefix`.
pub fn on_prefix(prefix: impl Into<String>, response: HttpResponse) {
    state().response_prefixes.push((prefix.into(), response));
}

pub struct MockEnv;

impl Env for MockEnv {
    fn fetch(req: HttpRequest) -> TryEnvFuture<HttpResponse> {
        let url = req.url.clone();
        let found = {
            let mut state = state();
            state.requests.push(req);
            state.responses.get(&url).cloned().or_else(|| {
                state
                    .response_prefixes
                    .iter()
                    .find(|(prefix, _)| url.starts_with(prefix.as_str()))
                    .map(|(_, response)| response.clone())
            })
        };
        Box::pin(async move {
            // Loud on purpose: an unregistered URL means the test does not describe what the code
            // actually does, and a silent empty 200 would hide that.
            found.ok_or_else(|| EnvError::Fetch(format!("no canned response for {url}")))
        })
    }

    fn fetch_to_file(req: HttpRequest, path: &str) -> TryEnvFuture<u64> {
        let path = path.to_owned();
        let url = req.url.clone();
        let found = {
            let mut state = state();
            state.requests.push(req);
            state.responses.get(&url).cloned().or_else(|| {
                state
                    .response_prefixes
                    .iter()
                    .find(|(prefix, _)| url.starts_with(prefix.as_str()))
                    .map(|(_, response)| response.clone())
            })
        };
        Box::pin(async move {
            let response =
                found.ok_or_else(|| EnvError::Fetch(format!("no canned response for {url}")))?;
            if !response.is_success() {
                return Err(EnvError::Fetch(format!(
                    "upstream answered {}",
                    response.status
                )));
            }
            // A real file, because the callers being tested go on to read it back: the stream cache
            // decrypts what this wrote, and asserting on that is the point of the test.
            if let Some(parent) = std::path::Path::new(&path).parent() {
                tokio::fs::create_dir_all(parent)
                    .await
                    .map_err(|e| EnvError::Storage(e.to_string()))?;
            }
            tokio::fs::write(&path, &response.body)
                .await
                .map_err(|e| EnvError::Storage(e.to_string()))?;
            Ok(response.body.len() as u64)
        })
    }


    fn fetch_stream(req: HttpRequest) -> TryEnvFuture<(HttpResponseHead, ByteStream)> {
        let url = req.url.clone();
        let (found, sizes) = {
            let mut state = state();
            state.requests.push(req);
            let found = state.responses.get(&url).cloned().or_else(|| {
                state
                    .response_prefixes
                    .iter()
                    .find(|(prefix, _)| url.starts_with(prefix.as_str()))
                    .map(|(_, response)| response.clone())
            });
            (found, state.stream_chunk_sizes.clone())
        };
        Box::pin(async move {
            let response =
                found.ok_or_else(|| EnvError::Fetch(format!("no canned response for {url}")))?;
            let head = HttpResponseHead {
                status: response.status,
                headers: response.headers.clone(),
            };
            Ok((head, ByteStream::from_chunks(split_into_chunks(&response.body, &sizes))))
        })
    }
    fn get_storage(key: &str) -> TryEnvFuture<Option<String>> {
        let value = state().storage.get(key).cloned();
        Box::pin(async move { Ok(value) })
    }

    fn set_storage(key: &str, value: Option<&str>) -> TryEnvFuture<()> {
        let key = key.to_owned();
        let value = value.map(str::to_owned);
        {
            let mut state = state();
            match value {
                Some(value) => state.storage.insert(key, value),
                None => state.storage.remove(&key),
            };
        }
        Box::pin(async move { Ok(()) })
    }

    fn now_ms() -> i64 {
        state().now_ms
    }

    fn exec_concurrent<F>(future: F)
    where
        F: std::future::Future<Output = ()> + Send + 'static,
    {
        match tokio::runtime::Handle::try_current() {
            Ok(handle) => {
                handle.spawn(future);
            }
            // Recorded rather than dropped in silence: a test that expected background work to run
            // should be able to find out why it did not.
            Err(_) => state().logs.push((
                LogLevel::Warn,
                "MockEnv".to_owned(),
                "exec_concurrent called with no Tokio runtime; future dropped".to_owned(),
            )),
        }
    }

    fn log(level: LogLevel, tag: &str, message: &str) {
        state()
            .logs
            .push((level, tag.to_owned(), message.to_owned()));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::env::storage;
    use serde::{Deserialize, Serialize};

    #[derive(Debug, Serialize, Deserialize, PartialEq, Eq, Default)]
    struct Mapping {
        spotify_id: String,
        youtube_id: String,
    }

    #[tokio::test]
    async fn storage_round_trips() {
        let _guard = lock_and_reset();

        assert_eq!(MockEnv::get_storage("absent").await.unwrap(), None);

        let value = Mapping {
            spotify_id: "abc".into(),
            youtube_id: "xyz".into(),
        };
        storage::set_json::<MockEnv, _>("mapping", Some(&value))
            .await
            .unwrap();

        let read: Option<Mapping> = storage::get_json::<MockEnv, _>("mapping").await.unwrap();
        assert_eq!(read, Some(value));
    }

    #[tokio::test]
    async fn deleting_an_absent_key_succeeds() {
        let _guard = lock_and_reset();
        storage::set_json::<MockEnv, Mapping>("nothing-here", None)
            .await
            .unwrap();
        assert_eq!(MockEnv::get_storage("nothing-here").await.unwrap(), None);
    }

    #[tokio::test]
    async fn corrupt_json_is_an_error_not_an_empty_value() {
        let _guard = lock_and_reset();
        MockEnv::set_storage("mapping", Some("{ this is not json"))
            .await
            .unwrap();

        let read = storage::get_json::<MockEnv, Mapping>("mapping").await;
        assert!(matches!(read, Err(EnvError::Serde(_))));

        // And the cache-only helper is the one allowed to shrug it off.
        let lenient = storage::get_json_or_default::<MockEnv, Mapping>("mapping").await;
        assert_eq!(lenient, Mapping::default());
    }

    #[tokio::test]
    async fn fetch_serves_canned_responses_and_records_requests() {
        let _guard = lock_and_reset();
        on_url(
            "https://example.test/track/1",
            HttpResponse::ok(r#"{"spotify_id":"1","youtube_id":"v"}"#),
        );

        let response = MockEnv::fetch(HttpRequest::get("https://example.test/track/1"))
            .await
            .unwrap();
        assert!(response.is_success());
        assert_eq!(
            response.json::<Mapping>().unwrap(),
            Mapping {
                spotify_id: "1".into(),
                youtube_id: "v".into()
            }
        );

        assert_eq!(state().requests.len(), 1);
        assert_eq!(state().requests[0].url, "https://example.test/track/1");
    }

    #[tokio::test]
    async fn an_unregistered_url_fails_loudly() {
        let _guard = lock_and_reset();
        let result = MockEnv::fetch(HttpRequest::get("https://example.test/unknown")).await;
        assert!(matches!(result, Err(EnvError::Fetch(_))));
    }

    #[tokio::test]
    async fn the_clock_only_moves_when_the_test_moves_it() {
        let _guard = lock_and_reset();
        assert_eq!(MockEnv::now_ms(), 0);
        state().now_ms = 1_700_000_000_000;
        assert_eq!(MockEnv::now_ms(), 1_700_000_000_000);
    }
}
