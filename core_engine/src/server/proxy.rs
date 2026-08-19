// core_engine/src/server/proxy.rs
//
// Serving a Deezer track straight from the CDN, decrypting it on the way through.
//
// This is what the 3.1 cache could not do. The cache serves a track that has already been
// downloaded and decrypted; the *first* play has nothing on disk yet, and until 3.2 it fell back to
// a Kotlin `DataSource` that did the same decryption a second time. With `Env::fetch_stream` the
// bytes can be transformed as they arrive, so the first play is an ordinary HTTP response too.
//
// Two things carry all the difficulty, and both are separated out here so they can be tested
// without a socket:
//
//   * **Re-forming stripes.** The network hands over chunks of whatever size it likes. Deezer's
//     scheme is defined on 2048-byte stripes. Anything that assumes a chunk ends where a stripe ends
//     produces audio that plays and is noise — see `MockEnv`'s deliberately hostile chunk sizes.
//   * **Range arithmetic.** A seek arrives as a byte offset in the *plaintext*; the CDN must be
//     asked for a stripe-aligned offset; and the answer to the client has to describe what the
//     client asked for, not what we asked upstream. Getting that wrong makes the scrubber lie.
//
// The format itself is specified in `docs/stremio-core/DEEZER-CRYPTO.md`.

use crate::audio::deezer::{self, STRIPE};
use crate::env::ByteStream;

use hyper::body::{Body, Bytes, Frame, SizeHint};
use std::io;
use std::pin::Pin;
use std::task::{Context, Poll};

// =============================================================================
// RANGE PLANNING — pure, so it can be tested without a network
// =============================================================================

/// What the client asked for.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Intent {
    /// No `Range` header, or one we do not understand.
    Whole,
    /// `bytes=N-`
    From(u64),
    /// `bytes=N-M`, inclusive.
    Between(u64, u64),
    /// `bytes=-N`: the last N bytes. Cannot be planned until the total size is known.
    Suffix(u64),
}

pub fn parse_intent(header: Option<&str>) -> Intent {
    let raw = match header {
        Some(raw) => raw.trim(),
        None => return Intent::Whole,
    };
    let spec = match raw.strip_prefix("bytes=") {
        Some(spec) => spec.trim(),
        None => return Intent::Whole,
    };
    // Multi-range is legal HTTP and nothing here needs it; answering the whole body is a valid
    // response to a range request and beats answering a multipart body nobody asked for well.
    if spec.contains(',') {
        return Intent::Whole;
    }
    let (start, end) = match spec.split_once('-') {
        Some(parts) => parts,
        None => return Intent::Whole,
    };
    let (start, end) = (start.trim(), end.trim());

    if start.is_empty() {
        return match end.parse::<u64>() {
            Ok(n) if n > 0 => Intent::Suffix(n),
            _ => Intent::Whole,
        };
    }
    let start: u64 = match start.parse() {
        Ok(start) => start,
        Err(_) => return Intent::Whole,
    };
    if end.is_empty() {
        return Intent::From(start);
    }
    match end.parse::<u64>() {
        Ok(end) if end >= start => Intent::Between(start, end),
        // An end before the start is nonsense rather than a range; treat the header as absent.
        _ => Intent::From(start),
    }
}

/// Turns a suffix range into an absolute one, now that the total is known.
pub fn resolve_suffix(n: u64, total: u64) -> Intent {
    if total == 0 {
        return Intent::Whole;
    }
    let start = total.saturating_sub(n);
    Intent::Between(start, total - 1)
}

/// What to ask the CDN for, and how to line its answer up with what the client wanted.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Plan {
    /// Offset requested from the CDN. Always a multiple of [`STRIPE`].
    pub aligned: u64,
    /// The `Range` header value to send upstream, if any.
    pub upstream_range: Option<String>,
    /// Bytes to throw away from the front of the first decrypted stripe.
    pub skip: usize,
    /// Index of the stripe at `aligned` — the number that decides which stripes are encrypted.
    pub stripe_index: u64,
    /// First byte the client wants.
    pub client_start: u64,
    /// Last byte the client wants, if it said.
    pub client_end: Option<u64>,
}

/// Plans the upstream request. [`Intent::Suffix`] must be resolved first — it is the one shape that
/// cannot be aligned without knowing how big the file is.
pub fn plan_upstream(intent: Intent) -> Plan {
    let (start, end) = match intent {
        Intent::Whole | Intent::Suffix(_) => (0, None),
        Intent::From(start) => (start, None),
        Intent::Between(start, end) => (start, Some(end)),
    };
    let aligned = start - (start % STRIPE as u64);
    Plan {
        aligned,
        // Always open-ended upstream, even when the client asked for a closed range: the extra
        // bytes are never read (the body stops at `remaining`) and it means a forward seek inside
        // what we already have costs nothing. Asking for the exact window instead would turn every
        // small seek into a new connection.
        upstream_range: if aligned > 0 || !matches!(intent, Intent::Whole) {
            Some(format!("bytes={aligned}-"))
        } else {
            None
        },
        skip: (start - aligned) as usize,
        stripe_index: aligned / STRIPE as u64,
        client_start: start,
        client_end: end,
    }
}

/// What to put in the response head.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Answer {
    pub status: u16,
    pub content_range: Option<String>,
    pub content_length: Option<u64>,
    /// How many bytes the body owes. `None` means "until upstream stops".
    pub remaining: Option<u64>,
}

/// Shapes the answer to the client from the plan and whatever the CDN revealed about the total.
///
/// `total` is `None` when the CDN said nothing useful. That is a degraded but honest outcome: a 200
/// with no `Content-Length`, which plays and seeks badly, rather than a `Content-Range` with a
/// number we invented.
pub fn answer(plan: &Plan, ranged: bool, total: Option<u64>) -> Answer {
    // Past the end of a file whose size we know: 416 is the only correct answer, and the one that
    // stops a player retrying the same impossible request.
    if let Some(total) = total {
        if plan.client_start >= total {
            return Answer {
                status: 416,
                content_range: Some(format!("bytes */{total}")),
                content_length: None,
                remaining: Some(0),
            };
        }
    }

    if !ranged {
        return Answer {
            status: 200,
            content_range: None,
            content_length: total,
            remaining: total,
        };
    }

    // Clamped, because a client may ask past the end and expects what exists rather than an error.
    let end = match (plan.client_end, total) {
        (Some(end), Some(total)) => Some(end.min(total - 1)),
        (Some(end), None) => Some(end),
        (None, Some(total)) => Some(total - 1),
        (None, None) => None,
    };

    match end {
        Some(end) => {
            let len = end - plan.client_start + 1;
            let total_text = total.map(|t| t.to_string()).unwrap_or_else(|| "*".to_string());
            Answer {
                status: 206,
                content_range: Some(format!(
                    "bytes {}-{}/{}",
                    plan.client_start, end, total_text
                )),
                content_length: Some(len),
                remaining: Some(len),
            }
        }
        // A range was asked for and nothing is known about the size. Answering 206 would mean
        // inventing a `Content-Range`; answering 200 from an offset would be a lie about what the
        // bytes are. The honest move is 200 from the start, which is what `Intent::Whole` planned.
        None => Answer {
            status: 200,
            content_range: None,
            content_length: None,
            remaining: None,
        },
    }
}

// =============================================================================
// THE BODY
// =============================================================================

/// A response body that decrypts Deezer stripes as they come off the network.
///
/// `Unpin` by construction: the upstream is already a `Pin<Box<…>>` and everything else is plain
/// data, so `poll_frame` needs no unsafe projection.
pub struct DecryptingBody {
    upstream: ByteStream,
    key: [u8; 16],
    /// Index of the next stripe to be handled — carried across chunks, which is the detail that
    /// breaks silently when it is got wrong.
    stripe_index: u64,
    /// Bytes seen but not yet forming a whole stripe.
    carry: Vec<u8>,
    /// Bytes to discard from the front, because the client asked for an offset inside a stripe.
    skip: usize,
    /// Bytes still owed. `None` means "until upstream ends".
    remaining: Option<u64>,
    finished: bool,
}

impl DecryptingBody {
    pub fn new(upstream: ByteStream, sng_id: &str, plan: &Plan, remaining: Option<u64>) -> Self {
        DecryptingBody {
            upstream,
            key: deezer::blowfish_key(sng_id),
            stripe_index: plan.stripe_index,
            carry: Vec::with_capacity(STRIPE * 2),
            skip: plan.skip,
            remaining,
            finished: false,
        }
    }

    /// Trims a decrypted run down to what the client actually asked for.
    fn shape(&mut self, mut bytes: Vec<u8>) -> Option<Bytes> {
        if self.skip > 0 {
            let drop = self.skip.min(bytes.len());
            bytes.drain(..drop);
            self.skip -= drop;
        }
        if let Some(remaining) = self.remaining.as_mut() {
            if bytes.len() as u64 > *remaining {
                bytes.truncate(*remaining as usize);
            }
            *remaining -= bytes.len() as u64;
        }
        if bytes.is_empty() {
            None
        } else {
            Some(Bytes::from(bytes))
        }
    }
}

impl Body for DecryptingBody {
    type Data = Bytes;
    type Error = io::Error;

    fn poll_frame(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
    ) -> Poll<Option<Result<Frame<Self::Data>, Self::Error>>> {
        let this = self.get_mut();
        loop {
            if this.finished || this.remaining == Some(0) {
                this.finished = true;
                return Poll::Ready(None);
            }

            match this.upstream.poll_chunk(cx) {
                Poll::Pending => return Poll::Pending,
                Poll::Ready(Some(Err(e))) => {
                    this.finished = true;
                    return Poll::Ready(Some(Err(io::Error::new(
                        io::ErrorKind::Other,
                        e.to_string(),
                    ))));
                }
                Poll::Ready(Some(Ok(chunk))) => {
                    this.carry.extend_from_slice(&chunk);
                    let whole = (this.carry.len() / STRIPE) * STRIPE;
                    if whole == 0 {
                        // Not a stripe yet. Ask for more rather than emit half of one — half a
                        // stripe cannot be decrypted, and emitting it raw would be noise.
                        continue;
                    }
                    let mut out: Vec<u8> = this.carry.drain(..whole).collect();
                    this.stripe_index +=
                        deezer::decrypt_aligned(&this.key, &mut out, this.stripe_index);
                    match this.shape(out) {
                        Some(data) => return Poll::Ready(Some(Ok(Frame::data(data)))),
                        None => continue,
                    }
                }
                Poll::Ready(None) => {
                    // Whatever is left is a trailing partial stripe, which Deezer never encrypts.
                    let tail = std::mem::take(&mut this.carry);
                    this.finished = true;
                    if tail.is_empty() {
                        return Poll::Ready(None);
                    }
                    return match this.shape(tail) {
                        Some(data) => Poll::Ready(Some(Ok(Frame::data(data)))),
                        None => Poll::Ready(None),
                    };
                }
            }
        }
    }

    fn size_hint(&self) -> SizeHint {
        match self.remaining {
            Some(remaining) => SizeHint::with_exact(remaining),
            None => SizeHint::default(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use blowfish::cipher::Block;
    use blowfish::cipher::{BlockEncrypt, KeyInit};
    use blowfish::Blowfish;
    use http_body_util::BodyExt;

    const SNG_ID: &str = "3135556";

    // --- range planning ---------------------------------------------------

    #[test]
    fn range_headers_are_read_the_way_a_player_writes_them() {
        assert_eq!(parse_intent(None), Intent::Whole);
        assert_eq!(parse_intent(Some("bytes=0-")), Intent::From(0));
        assert_eq!(parse_intent(Some("bytes=5000-")), Intent::From(5000));
        assert_eq!(parse_intent(Some("bytes=100-199")), Intent::Between(100, 199));
        assert_eq!(parse_intent(Some("bytes=-500")), Intent::Suffix(500));
        // Nonsense degrades to "the whole thing" rather than to an error.
        assert_eq!(parse_intent(Some("items=1-2")), Intent::Whole);
        assert_eq!(parse_intent(Some("bytes=abc-")), Intent::Whole);
        assert_eq!(parse_intent(Some("bytes=0-1,5-6")), Intent::Whole);
        assert_eq!(parse_intent(Some("bytes=-0")), Intent::Whole);
        // An end before the start is not a window; take it as open-ended.
        assert_eq!(parse_intent(Some("bytes=900-100")), Intent::From(900));
    }

    #[test]
    fn the_upstream_offset_is_always_stripe_aligned() {
        // The whole scheme rests on this: ask the CDN for an offset inside a stripe and every
        // stripe index after it is wrong.
        for start in [0u64, 1, 2047, 2048, 2049, 5000, 1_000_003] {
            let plan = plan_upstream(Intent::From(start));
            assert_eq!(plan.aligned % STRIPE as u64, 0, "start {start}");
            assert!(plan.aligned <= start);
            assert!(start - plan.aligned < STRIPE as u64);
            assert_eq!(plan.skip, (start - plan.aligned) as usize);
            assert_eq!(plan.stripe_index, plan.aligned / STRIPE as u64);
        }
    }

    #[test]
    fn a_seek_into_the_middle_of_a_stripe_asks_for_the_stripe_and_skips_the_rest() {
        let plan = plan_upstream(Intent::From(5000));
        assert_eq!(plan.aligned, 4096);
        assert_eq!(plan.stripe_index, 2);
        assert_eq!(plan.skip, 904);
        assert_eq!(plan.upstream_range.as_deref(), Some("bytes=4096-"));
        assert_eq!(plan.client_start, 5000);
        assert_eq!(plan.client_end, None);
    }

    #[test]
    fn a_request_with_no_range_asks_the_cdn_for_no_range() {
        let plan = plan_upstream(Intent::Whole);
        assert_eq!(plan.upstream_range, None);
        assert_eq!(plan.aligned, 0);
        assert_eq!(plan.skip, 0);
        assert_eq!(plan.stripe_index, 0);
    }

    #[test]
    fn a_suffix_range_becomes_absolute_once_the_size_is_known() {
        assert_eq!(resolve_suffix(500, 10_000), Intent::Between(9_500, 9_999));
        // Asking for more than exists is the whole file, not an error.
        assert_eq!(resolve_suffix(50_000, 10_000), Intent::Between(0, 9_999));
        assert_eq!(resolve_suffix(500, 0), Intent::Whole);
    }

    #[test]
    fn the_answer_describes_what_the_client_asked_for_not_what_we_asked_upstream() {
        // The failure this prevents: reporting the aligned offset, so the scrubber jumps to a
        // slightly different place than the one the user dragged it to.
        let plan = plan_upstream(Intent::From(5000));
        let answer = answer(&plan, true, Some(10_000));
        assert_eq!(answer.status, 206);
        assert_eq!(answer.content_range.as_deref(), Some("bytes 5000-9999/10000"));
        assert_eq!(answer.content_length, Some(5000));
        assert_eq!(answer.remaining, Some(5000));
    }

    #[test]
    fn a_closed_range_past_the_end_is_clamped_rather_than_refused() {
        let plan = plan_upstream(Intent::Between(9_000, 99_999));
        let answer = answer(&plan, true, Some(10_000));
        assert_eq!(answer.status, 206);
        assert_eq!(answer.content_range.as_deref(), Some("bytes 9000-9999/10000"));
        assert_eq!(answer.content_length, Some(1000));
    }

    #[test]
    fn a_range_starting_past_the_end_is_416() {
        let plan = plan_upstream(Intent::From(10_000));
        let answer = answer(&plan, true, Some(10_000));
        assert_eq!(answer.status, 416);
        assert_eq!(answer.content_range.as_deref(), Some("bytes */10000"));
    }

    #[test]
    fn an_unknown_total_degrades_to_a_plain_200_instead_of_inventing_one() {
        let plan = plan_upstream(Intent::From(5000));
        let answer = answer(&plan, true, None);
        assert_eq!(answer.status, 200);
        assert_eq!(answer.content_range, None);
        assert_eq!(answer.content_length, None);
        assert_eq!(answer.remaining, None);
    }

    #[test]
    fn no_range_and_a_known_total_states_the_length() {
        let plan = plan_upstream(Intent::Whole);
        let answer = answer(&plan, false, Some(10_000));
        assert_eq!(answer.status, 200);
        assert_eq!(answer.content_length, Some(10_000));
        assert_eq!(answer.remaining, Some(10_000));
    }

    // --- the body ---------------------------------------------------------

    /// Encrypts `plain` the way the CDN would: stripe 0, 3, 6… only, full stripes only.
    fn to_wire(sng_id: &str, plain: &[u8]) -> Vec<u8> {
        let key = deezer::blowfish_key(sng_id);
        let cipher = <Blowfish as KeyInit>::new_from_slice(&key).unwrap();
        let mut wire = plain.to_vec();
        for (index, stripe) in wire.chunks_mut(STRIPE).enumerate() {
            if !deezer::stripe_is_encrypted(index as u64) || stripe.len() != STRIPE {
                continue;
            }
            let mut prev = [0u8, 1, 2, 3, 4, 5, 6, 7];
            for offset in (0..STRIPE).step_by(8) {
                let mut block = [0u8; 8];
                for i in 0..8 {
                    block[i] = stripe[offset + i] ^ prev[i];
                }
                let mut ga = Block::<Blowfish>::clone_from_slice(&block);
                cipher.encrypt_block(&mut ga);
                for i in 0..8 {
                    stripe[offset + i] = ga[i];
                }
                prev.copy_from_slice(&ga);
            }
        }
        wire
    }

    /// A stream that hands `data` over in exactly `sizes`, cycled — the point being that a chunk
    /// boundary almost never lines up with a stripe boundary.
    fn chunked(data: Vec<u8>, sizes: &[usize]) -> ByteStream {
        let mut chunks = Vec::new();
        let mut offset = 0;
        let mut i = 0;
        while offset < data.len() {
            let want = sizes[i % sizes.len()].max(1);
            let end = offset.saturating_add(want).min(data.len());
            chunks.push(Bytes::copy_from_slice(&data[offset..end]));
            offset = end;
            i += 1;
        }
        ByteStream::from_chunks(chunks)
    }

    async fn collect(body: DecryptingBody) -> Vec<u8> {
        body.collect().await.unwrap().to_bytes().to_vec()
    }

    fn sample(len: usize) -> Vec<u8> {
        (0..len).map(|i| (i % 251) as u8).collect()
    }

    #[tokio::test]
    async fn the_plaintext_survives_any_chunking_the_network_chooses() {
        // The one assumption that would break this quietly is "a chunk ends where a stripe ends".
        // These sizes are chosen so that it never does.
        let plain = sample(STRIPE * 4 + 777);
        let wire = to_wire(SNG_ID, &plain);

        for sizes in [
            vec![1],
            vec![7],
            vec![2047],
            vec![2048],
            vec![5000],
            vec![1, 7, 2047, 2048, 5000, 3],
            vec![100_000], // one chunk for the whole body
        ] {
            let plan = plan_upstream(Intent::Whole);
            let body = DecryptingBody::new(chunked(wire.clone(), &sizes), SNG_ID, &plan, None);
            assert_eq!(collect(body).await, plain, "chunk sizes {sizes:?}");
        }
    }

    #[tokio::test]
    async fn a_seek_lands_on_the_byte_the_client_asked_for() {
        let plain = sample(STRIPE * 4 + 777);
        let wire = to_wire(SNG_ID, &plain);

        for start in [0usize, 1, 2047, 2048, 2049, 5000, STRIPE * 4] {
            // Only the aligned tail of the wire is sent, exactly as the CDN would answer a Range.
            let plan = plan_upstream(Intent::From(start as u64));
            let from = plan.aligned as usize;
            let body = DecryptingBody::new(
                chunked(wire[from..].to_vec(), &[1, 7, 2047, 2048, 5000, 3]),
                SNG_ID,
                &plan,
                None,
            );
            assert_eq!(collect(body).await, plain[start..], "start {start}");
        }
    }

    #[tokio::test]
    async fn a_closed_range_stops_exactly_where_it_should() {
        let plain = sample(STRIPE * 4 + 777);
        let wire = to_wire(SNG_ID, &plain);
        let plan = plan_upstream(Intent::Between(5000, 5999));
        let from = plan.aligned as usize;
        let body = DecryptingBody::new(
            chunked(wire[from..].to_vec(), &[1, 7, 2047, 2048, 5000, 3]),
            SNG_ID,
            &plan,
            Some(1000),
        );
        assert_eq!(collect(body).await, plain[5000..6000]);
    }

    #[tokio::test]
    async fn an_upstream_that_stops_early_ends_the_body_instead_of_inventing_bytes() {
        // Half a track, asked for as a whole one. Serving silence to fill the gap would be worse
        // than a short response: the player can tell the difference between short and wrong.
        let plain = sample(STRIPE * 4);
        let wire = to_wire(SNG_ID, &plain);
        let plan = plan_upstream(Intent::Whole);
        let body = DecryptingBody::new(
            chunked(wire[..STRIPE * 2].to_vec(), &[2048]),
            SNG_ID,
            &plan,
            Some(STRIPE as u64 * 4),
        );
        assert_eq!(collect(body).await, plain[..STRIPE * 2]);
    }

    #[tokio::test]
    async fn a_body_shorter_than_one_stripe_still_comes_out_whole() {
        // Deezer never encrypts a partial stripe, so a tiny file is cleartext from end to end.
        let plain = sample(100);
        let plan = plan_upstream(Intent::Whole);
        let body = DecryptingBody::new(chunked(plain.clone(), &[7]), SNG_ID, &plan, None);
        assert_eq!(collect(body).await, plain);
    }
}
