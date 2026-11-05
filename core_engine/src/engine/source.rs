// core_engine/src/engine/source.rs
//
// Where the bytes come from: a file, or an HTTP URL read as it downloads.
//
// The HTTP source goes through `Env::fetch_stream`, like every other request the core makes, and keeps
// what has arrived in memory so the decoder can seek backwards for free and forwards as far as the
// download has got.
//
// It downloads in **ranges** of [CHUNK] when the server takes them. Measured on the desktop
// (`examples/engine_probe.rs`, 2026-11-03): googlevideo sends a whole-file GET at about twice the
// bitrate, ~32 KB/s for a 128 kbps AAC, and probing an MP4 reads most of the file, so a 6-minute
// song took **180 s** to start. In ranges, as ExoPlayer asks for them on Android, each comes at full
// speed. A server that ignores `Range` answers 200 with the whole body, which is read as one stream
// as before (the core's local server, files on disk, plain CDNs).
//
// Dropping the source cancels the download — skipping a track must not leave it downloading.

use std::io::{self, Read, Seek, SeekFrom};
use std::sync::{Arc, Condvar, Mutex};

use symphonia::core::io::MediaSource;

use crate::env::{Env, HttpRequest};

use super::EngineError;

/// Opens `uri`: `http(s)://` through `E`, anything else as a file — a `file://` URI or a plain path.
///
/// Answers the source and a format hint (extension or MIME type) for the decoder.
pub fn open<E: Env + 'static>(
    uri: &str,
) -> Result<(Box<dyn MediaSource>, Option<String>), EngineError> {
    if uri.starts_with("http://") || uri.starts_with("https://") {
        let source = HttpSource::open::<E>(uri)?;
        let hint = source.mime.clone().or_else(|| extension_of(uri));
        return Ok((Box::new(source), hint));
    }
    let path = path_of(uri);
    let file = std::fs::File::open(&path)
        .map_err(|e| EngineError::Open(format!("{}: {e}", path.display())))?;
    Ok((Box::new(file), path.to_str().and_then(extension_of)))
}

/// The file a `file://` URI names, percent-decoded (`file:///home/a/My%20Song.mp3`,
/// `file:///C:/Music/a.mp3` on Windows): what the desktop's local tracks are. Anything that is not
/// a well-formed file URI is taken as a path as it stands, less a `file://` prefix.
fn path_of(uri: &str) -> std::path::PathBuf {
    if uri.starts_with("file:") {
        if let Some(path) = url::Url::parse(uri).ok().and_then(|u| u.to_file_path().ok()) {
            return path;
        }
    }
    std::path::PathBuf::from(uri.strip_prefix("file://").unwrap_or(uri))
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

/// One range request's worth. Big enough that a song is a handful of requests, small enough that
/// the first one (which the decoder waits for) is quick.
const CHUNK: u64 = 1 << 20;

/// Tries per range before the download is called failed.
const RANGE_TRIES: usize = 3;

fn range(from: u64, to_inclusive: u64) -> String {
    format!("bytes={from}-{to_inclusive}")
}

/// The total from a `Content-Range: bytes 0-1048575/5818745` header.
fn total_from_content_range(value: &str) -> Option<u64> {
    value.rsplit('/').next()?.trim().parse().ok()
}

/// Appends a body to the download. `Err` with the message on a network error; `Ok(false)` when the
/// source was dropped meanwhile.
async fn drain(mut body: crate::env::ByteStream, shared: &Shared) -> Result<bool, String> {
    loop {
        let next = std::future::poll_fn(|cx| body.poll_chunk(cx)).await;
        let Ok(mut state) = shared.state.lock() else {
            return Ok(false);
        };
        if state.cancelled {
            return Ok(false);
        }
        match next {
            Some(Ok(bytes)) => state.data.extend_from_slice(&bytes),
            Some(Err(e)) => return Err(e.message()),
            None => return Ok(true),
        }
        drop(state);
        shared.arrived.notify_all();
    }
}

impl HttpSource {
    /// Starts the request and returns once the response head is in. Blocking: called from the
    /// engine's opener thread, never from the player thread or a device callback.
    pub fn open<E: Env + 'static>(url: &str) -> Result<Self, EngineError> {
        let request = HttpRequest::get(url).header("Range", range(0, CHUNK - 1));
        let (head, body) = crate::get_runtime()
            .block_on(E::fetch_stream(request))
            .map_err(|e| EngineError::Open(e.message()))?;
        if !head.is_success() {
            return Err(EngineError::Http(head.status));
        }
        // 206: the rest comes in ranges. With a total (`bytes 0-1048575/5818745`) they stop there;
        // with `/*`, which the core's proxy answers when the CDN gave no size, they go on until one
        // comes back short, empty or refused. Anything else is the whole body.
        let ranged = head.status == 206;
        let ranged_total = ranged
            .then(|| {
                head.header("Content-Range")
                    .and_then(total_from_content_range)
            })
            .flatten();
        // A 206's Content-Length is the first range's, not the file's: without a total the length
        // stays unknown until the download ends.
        let total = if ranged {
            ranged_total
        } else {
            head.content_length()
        };
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
        let url = url.to_string();
        crate::get_runtime().spawn(async move {
            let finish = |error: Option<String>| {
                if let Ok(mut state) = pump.state.lock() {
                    match error {
                        Some(e) => state.error = Some(e),
                        None => state.done = true,
                    }
                }
                pump.arrived.notify_all();
            };
            match drain(body, &pump).await {
                Ok(true) => {}
                Ok(false) => return,
                Err(e) => return finish(Some(e)),
            }
            // Without a total, a range that comes back shorter than asked was the last one.
            let ended_short = |asked_to: u64| {
                ranged_total.is_none()
                    && pump
                        .state
                        .lock()
                        .map(|s| (s.data.len() as u64) < asked_to)
                        .unwrap_or(false)
            };
            if ranged && ended_short(CHUNK) {
                return finish(None);
            }
            if ranged {
                loop {
                    let have = match pump.state.lock() {
                        Ok(state) if !state.cancelled => state.data.len() as u64,
                        _ => return,
                    };
                    let to = match ranged_total {
                        Some(total) if have >= total => break,
                        Some(total) => (have + CHUNK).min(total) - 1,
                        None => have + CHUNK - 1,
                    };
                    let mut last_error = String::new();
                    let mut got = false;
                    for _ in 0..RANGE_TRIES {
                        let request =
                            HttpRequest::get(url.clone()).header("Range", range(have, to));
                        match E::fetch_stream(request).await {
                            Ok((head, body)) if head.status == 206 => {
                                match drain(body, &pump).await {
                                    Ok(true) if ended_short(to + 1) => return finish(None),
                                    Ok(true) => {
                                        got = true;
                                        break;
                                    }
                                    Ok(false) => return,
                                    // A cut mid-range: the next try asks from where it stopped.
                                    Err(e) => last_error = e,
                                }
                            }
                            // Without a total, asking past the end is how the end is found: 416,
                            // or a server that stopped taking ranges. A refusal is retried below.
                            Ok((head, _))
                                if ranged_total.is_none()
                                    && (head.status == 416 || head.is_success()) =>
                            {
                                return finish(None)
                            }
                            Ok((head, _)) => {
                                last_error = format!("range answered HTTP {}", head.status)
                            }
                            Err(e) => last_error = e.message(),
                        }
                        if pump
                            .state
                            .lock()
                            .map(|s| s.data.len() as u64 > have)
                            .unwrap_or(false)
                        {
                            break; // part of it arrived; the outer loop asks for the rest
                        }
                    }
                    if !got
                        && pump
                            .state
                            .lock()
                            .map(|s| s.data.len() as u64 <= have)
                            .unwrap_or(true)
                    {
                        return finish(Some(last_error));
                    }
                }
            }
            finish(None);
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
        response.headers.push((
            "Content-Type".into(),
            "audio/mp4; codecs=\"mp4a.40.2\"".into(),
        ));
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
    fn a_server_that_takes_ranges_is_read_in_ranges_to_the_end() {
        let _guard = mock::lock_and_reset();
        mock::honour_ranges();
        let expected = body((CHUNK as usize) * 2 + 12_345);
        let mut response = HttpResponse::ok(expected.clone());
        response
            .headers
            .push(("Content-Type".into(), "audio/mp4".into()));
        mock::on_url("https://cdn.test/v", response);

        let mut source = HttpSource::open::<MockEnv>("https://cdn.test/v").expect("opens");
        assert_eq!(
            source.byte_len(),
            Some(expected.len() as u64),
            "the total comes from Content-Range"
        );
        let mut all = Vec::new();
        source.read_to_end(&mut all).expect("reads");
        assert_eq!(all.len(), expected.len());
        assert!(
            all == expected,
            "the ranges join without a gap or an overlap"
        );

        assert_eq!(
            ranges_asked(),
            vec![
                format!("bytes=0-{}", CHUNK - 1),
                format!("bytes={}-{}", CHUNK, 2 * CHUNK - 1),
                format!("bytes={}-{}", 2 * CHUNK, expected.len() - 1),
            ]
        );
    }

    fn ranges_asked() -> Vec<String> {
        mock::state()
            .requests
            .iter()
            .filter_map(|r| {
                r.headers
                    .iter()
                    .find(|(k, _)| k == "Range")
                    .map(|(_, v)| v.clone())
            })
            .collect()
    }

    #[test]
    fn without_a_total_ranges_go_on_until_one_comes_back_short() {
        let _guard = mock::lock_and_reset();
        mock::honour_ranges_without_total();
        let expected = body((CHUNK as usize) * 2 + 12_345);
        mock::on_url("https://cdn.test/p", HttpResponse::ok(expected.clone()));

        let mut source = HttpSource::open::<MockEnv>("https://cdn.test/p").expect("opens");
        assert_eq!(
            source.byte_len(),
            None,
            "the first range's length is not the file's"
        );
        let mut all = Vec::new();
        source.read_to_end(&mut all).expect("reads");
        assert_eq!(all.len(), expected.len());
        assert!(all == expected, "every range arrived, in order");
        assert_eq!(
            source.seek(SeekFrom::End(0)).expect("the end is known now"),
            expected.len() as u64
        );
        assert_eq!(
            ranges_asked(),
            vec![
                format!("bytes=0-{}", CHUNK - 1),
                format!("bytes={}-{}", CHUNK, 2 * CHUNK - 1),
                format!("bytes={}-{}", 2 * CHUNK, 3 * CHUNK - 1),
            ]
        );
    }

    #[test]
    fn without_a_total_a_416_past_the_end_ends_the_download() {
        let _guard = mock::lock_and_reset();
        mock::honour_ranges_without_total();
        let expected = body((CHUNK as usize) * 2);
        mock::on_url("https://cdn.test/q", HttpResponse::ok(expected.clone()));

        let mut source = HttpSource::open::<MockEnv>("https://cdn.test/q").expect("opens");
        let mut all = Vec::new();
        source.read_to_end(&mut all).expect("a 416 is the end, not an error");
        assert!(all == expected);
        assert_eq!(
            ranges_asked(),
            vec![
                format!("bytes=0-{}", CHUNK - 1),
                format!("bytes={}-{}", CHUNK, 2 * CHUNK - 1),
                format!("bytes={}-{}", 2 * CHUNK, 3 * CHUNK - 1),
            ]
        );
    }

    #[test]
    fn without_a_total_a_short_first_range_is_the_whole_file() {
        let _guard = mock::lock_and_reset();
        mock::honour_ranges_without_total();
        let expected = body(5_000);
        mock::on_url("https://cdn.test/r", HttpResponse::ok(expected.clone()));

        let mut source = HttpSource::open::<MockEnv>("https://cdn.test/r").expect("opens");
        let mut all = Vec::new();
        source.read_to_end(&mut all).expect("reads");
        assert!(all == expected);
        assert_eq!(ranges_asked(), vec![format!("bytes=0-{}", CHUNK - 1)]);
    }

    #[test]
    fn the_total_is_read_from_content_range() {
        assert_eq!(
            total_from_content_range("bytes 0-1048575/5818745"),
            Some(5_818_745)
        );
        assert_eq!(total_from_content_range("bytes 0-1/*"), None);
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
    fn a_file_uri_is_opened_at_the_path_it_names() {
        let dir = std::env::temp_dir().join(format!("rustify source {}", crate::server::random_hex(6)));
        std::fs::create_dir_all(&dir).expect("a folder");
        let path = dir.join("Jóga 01.MP3");
        std::fs::write(&path, b"abc").expect("a file");
        // As the desktop writes a local track's id: percent-encoded, three slashes.
        let uri = url::Url::from_file_path(&path).expect("absolute").to_string();
        assert!(uri.starts_with("file:///") && uri.contains("%20"), "{uri}");

        let (mut source, hint) = open::<MockEnv>(&uri).expect("opens");
        assert_eq!(hint.as_deref(), Some("mp3"));
        let mut all = Vec::new();
        source.read_to_end(&mut all).expect("reads");
        assert_eq!(all, b"abc");

        assert_eq!(path_of(path.to_str().expect("utf-8")), path, "a plain path stays as it is");
        assert!(open::<MockEnv>(&format!("{uri}.missing")).is_err());
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn hints_come_from_the_extension_without_the_query() {
        assert_eq!(
            extension_of("https://x/y/song.FLAC?sig=1"),
            Some("flac".into())
        );
        assert_eq!(extension_of("C:\\Music\\a.mp3"), Some("mp3".into()));
        assert_eq!(extension_of("https://x/videoplayback?id=1"), None);
    }
}
