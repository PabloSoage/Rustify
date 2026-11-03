// core_engine/src/engine/source.rs
//
// Where the bytes come from: a file, or an HTTP URL read as it downloads.
//
// The HTTP source goes through `Env::fetch_stream`, like every other request the core makes, and keeps
// what has arrived in memory so the decoder can seek backwards for free and forwards as far as the
// download has got. A forward seek past that waits for the bytes rather than issuing a range request:
// a song is a few megabytes and arrives in seconds, and one connection per track is what the CDN
// (and the core's own local server, for cached and Deezer tracks) already expects.
//
// Dropping the source cancels the download — skipping a track must not leave it downloading.

use std::io::{self, Read, Seek, SeekFrom};
use std::sync::{Arc, Condvar, Mutex};

use symphonia::core::io::MediaSource;

use crate::env::{Env, HttpRequest};

use super::EngineError;

/// Opens `uri`: `http(s)://` through `E`, anything else as a path (`file://` prefix optional).
///
/// Answers the source and a format hint (extension or MIME type) for the decoder.
pub fn open<E: Env>(uri: &str) -> Result<(Box<dyn MediaSource>, Option<String>), EngineError> {
    if uri.starts_with("http://") || uri.starts_with("https://") {
        let source = HttpSource::open::<E>(uri)?;
        let hint = source.mime.clone().or_else(|| extension_of(uri));
        return Ok((Box::new(source), hint));
    }
    let path = uri.strip_prefix("file://").unwrap_or(uri);
    let file = std::fs::File::open(path)
        .map_err(|e| EngineError::Open(format!("{path}: {e}")))?;
    Ok((Box::new(file), extension_of(path)))
}

/// The extension of the last path segment, ignoring a query string.
fn extension_of(uri: &str) -> Option<String> {
    let path = uri.split(['?', '#']).next().unwrap_or(uri);
    let name = path.rsplit(['/', '\\']).next()?;
    let (_, ext) = name.rsplit_once('.')?;
    (!ext.is_empty() && ext.len() <= 5).then(|| ext.to_ascii_lowercase())
}

#[derive(Default)]
struct Download {
    data: Vec<u8>,
    done: bool,
    error: Option<String>,
    cancelled: bool,
}

struct Shared {
    state: Mutex<Download>,
    arrived: Condvar,
}

/// An HTTP body, readable and seekable while it is still arriving.
pub struct HttpSource {
    shared: Arc<Shared>,
    pos: u64,
    total: Option<u64>,
    mime: Option<String>,
}

impl HttpSource {
    /// Starts the request and returns once the response head is in. Blocking: called from the
    /// engine's opener thread, never from the player thread or a device callback.
    pub fn open<E: Env>(url: &str) -> Result<Self, EngineError> {
        let request = HttpRequest::get(url);
        let (head, mut body) = crate::get_runtime()
            .block_on(E::fetch_stream(request))
            .map_err(|e| EngineError::Open(e.message()))?;
        if !head.is_success() {
            return Err(EngineError::Http(head.status));
        }
        let total = head.content_length();
        let mime = head
            .header("Content-Type")
            .map(|v| v.split(';').next().unwrap_or(v).trim().to_ascii_lowercase())
            .filter(|m| m.starts_with("audio/") || m.starts_with("video/"));

        let shared = Arc::new(Shared {
            state: Mutex::new(Download {
                data: Vec::with_capacity(total.unwrap_or(0).min(256 << 20) as usize),
                ..Default::default()
            }),
            arrived: Condvar::new(),
        });
        let pump = shared.clone();
        crate::get_runtime().spawn(async move {
            loop {
                let next = std::future::poll_fn(|cx| body.poll_chunk(cx)).await;
                let Ok(mut state) = pump.state.lock() else {
                    return;
                };
                if state.cancelled {
                    return;
                }
                match next {
                    Some(Ok(bytes)) => state.data.extend_from_slice(&bytes),
                    Some(Err(e)) => {
                        state.error = Some(e.message());
                        pump.arrived.notify_all();
                        return;
                    }
                    None => {
                        state.done = true;
                        pump.arrived.notify_all();
                        return;
                    }
                }
                pump.arrived.notify_all();
            }
        });

        Ok(HttpSource {
            shared,
            pos: 0,
            total,
            mime,
        })
    }
}

impl Read for HttpSource {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        if buf.is_empty() {
            return Ok(0);
        }
        let mut state = self
            .shared
            .state
            .lock()
            .map_err(|_| io::Error::other("download state poisoned"))?;
        loop {
            let have = state.data.len() as u64;
            if self.pos < have {
                let start = self.pos as usize;
                let n = buf.len().min(state.data.len() - start);
                buf[..n].copy_from_slice(&state.data[start..start + n]);
                self.pos += n as u64;
                return Ok(n);
            }
            if let Some(e) = &state.error {
                return Err(io::Error::other(e.clone()));
            }
            if state.done || state.cancelled {
                return Ok(0);
            }
            state = self
                .shared
                .arrived
                .wait(state)
                .map_err(|_| io::Error::other("download state poisoned"))?;
        }
    }
}

impl Seek for HttpSource {
    fn seek(&mut self, to: SeekFrom) -> io::Result<u64> {
        let end = match self.total {
            Some(total) => Some(total),
            None => {
                let state = self
                    .shared
                    .state
                    .lock()
                    .map_err(|_| io::Error::other("download state poisoned"))?;
                state.done.then_some(state.data.len() as u64)
            }
        };
        let target = match to {
            SeekFrom::Start(p) => p as i128,
            SeekFrom::Current(d) => self.pos as i128 + d as i128,
            SeekFrom::End(d) => match end {
                Some(end) => end as i128 + d as i128,
                None => {
                    return Err(io::Error::new(
                        io::ErrorKind::Unsupported,
                        "length unknown until the download ends",
                    ))
                }
            },
        };
        if target < 0 {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "seek before the start",
            ));
        }
        self.pos = target as u64;
        Ok(self.pos)
    }
}

impl MediaSource for HttpSource {
    fn is_seekable(&self) -> bool {
        // Without a length the decoder cannot seek by byte offset to a time; it can still play.
        self.total.is_some()
    }

    fn byte_len(&self) -> Option<u64> {
        self.total
    }
}

impl Drop for HttpSource {
    fn drop(&mut self) {
        if let Ok(mut state) = self.shared.state.lock() {
            state.cancelled = true;
            // Free the buffer now rather than when the pump next wakes.
            state.data = Vec::new();
        }
        self.shared.arrived.notify_all();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::env::mock::{self, MockEnv};
    use crate::env::HttpResponse;

    fn body(n: usize) -> Vec<u8> {
        (0..n).map(|i| (i % 251) as u8).collect()
    }

    #[test]
    fn reads_the_whole_body_across_hostile_chunk_boundaries() {
        let _guard = mock::lock_and_reset();
        let expected = body(10_000);
        let mut response = HttpResponse::ok(expected.clone());
        response
            .headers
            .push(("Content-Length".into(), expected.len().to_string()));
        response
            .headers
            .push(("Content-Type".into(), "audio/mp4; codecs=\"mp4a.40.2\"".into()));
        mock::on_url("https://cdn.test/a.m4a", response);

        let mut source = HttpSource::open::<MockEnv>("https://cdn.test/a.m4a").expect("opens");
        assert_eq!(source.byte_len(), Some(10_000));
        assert_eq!(source.mime.as_deref(), Some("audio/mp4"));
        let mut all = Vec::new();
        source.read_to_end(&mut all).expect("reads");
        assert_eq!(all, expected);
    }

    #[test]
    fn seeks_back_and_forth_within_what_arrived() {
        let _guard = mock::lock_and_reset();
        let expected = body(4096);
        let mut response = HttpResponse::ok(expected.clone());
        response
            .headers
            .push(("Content-Length".into(), "4096".into()));
        mock::on_url("https://cdn.test/b", response);
        let mut source = HttpSource::open::<MockEnv>("https://cdn.test/b").expect("opens");
        source.seek(SeekFrom::End(-10)).expect("seeks");
        let mut tail = [0u8; 10];
        source.read_exact(&mut tail).expect("reads");
        assert_eq!(&tail[..], &expected[4086..]);
        source.seek(SeekFrom::Start(5)).expect("seeks");
        let mut head = [0u8; 3];
        source.read_exact(&mut head).expect("reads");
        assert_eq!(&head[..], &expected[5..8]);
    }

    #[test]
    fn an_error_status_is_an_http_error() {
        let _guard = mock::lock_and_reset();
        mock::on_url("https://cdn.test/gone", HttpResponse::with_status(403, ""));
        let err = HttpSource::open::<MockEnv>("https://cdn.test/gone")
            .err()
            .expect("refused");
        assert_eq!(err, EngineError::Http(403));
    }

    #[test]
    fn hints_come_from_the_extension_without_the_query() {
        assert_eq!(extension_of("https://x/y/song.FLAC?sig=1"), Some("flac".into()));
        assert_eq!(extension_of("C:\\Music\\a.mp3"), Some("mp3".into()));
        assert_eq!(extension_of("https://x/videoplayback?id=1"), None);
    }
}
