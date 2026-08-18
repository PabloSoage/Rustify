// core_engine/src/server/range.rs
//
// `Range: bytes=…` parsing, kept apart from the server so it can be tested without a socket.
//
// Media3 seeks by asking for a byte range. A server that ignores `Range` and always answers 200
// with the whole body looks like it works — until you drag the scrubber, and then the player
// re-downloads from zero every time.

/// A resolved byte range: inclusive `start`, inclusive `end`, both already clamped to the file.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ByteRange {
    pub start: u64,
    pub end: u64,
}

impl ByteRange {
    pub fn len(&self) -> u64 {
        self.end - self.start + 1
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RangeOutcome {
    /// No `Range` header, or one we do not understand — answer 200 with the whole body.
    Whole,
    /// A satisfiable range — answer 206.
    Partial(ByteRange),
    /// A syntactically valid range that lies outside the file — answer 416.
    Unsatisfiable,
}

/// Parses a `Range` header value against a known file size.
///
/// Only `bytes=` with a single range is supported. Multi-range requests (`bytes=0-9,20-29`) are
/// answered as [`RangeOutcome::Whole`]: a correct answer to those is a multipart body, and no media
/// player asks for one. Pretending to support it by serving the first range would be worse than not
/// supporting it.
pub fn parse(header: Option<&str>, file_len: u64) -> RangeOutcome {
    let raw = match header {
        Some(raw) => raw.trim(),
        None => return RangeOutcome::Whole,
    };
    let spec = match raw.strip_prefix("bytes=") {
        Some(spec) => spec.trim(),
        None => return RangeOutcome::Whole,
    };
    if spec.contains(',') {
        return RangeOutcome::Whole;
    }
    let (first, last) = match spec.split_once('-') {
        Some(parts) => parts,
        None => return RangeOutcome::Whole,
    };
    let first = first.trim();
    let last = last.trim();

    // An empty file cannot satisfy any range at all.
    if file_len == 0 {
        return RangeOutcome::Unsatisfiable;
    }

    let range = if first.is_empty() {
        // `bytes=-N` — the last N bytes.
        let suffix: u64 = match last.parse() {
            Ok(n) => n,
            Err(_) => return RangeOutcome::Whole,
        };
        if suffix == 0 {
            return RangeOutcome::Unsatisfiable;
        }
        let start = file_len.saturating_sub(suffix);
        ByteRange {
            start,
            end: file_len - 1,
        }
    } else {
        let start: u64 = match first.parse() {
            Ok(n) => n,
            Err(_) => return RangeOutcome::Whole,
        };
        if start >= file_len {
            return RangeOutcome::Unsatisfiable;
        }
        let end = if last.is_empty() {
            file_len - 1
        } else {
            match last.parse::<u64>() {
                // Clamped, not rejected: asking past the end of the file is legal and means
                // "to the end". ExoPlayer does exactly this.
                Ok(n) => n.min(file_len - 1),
                Err(_) => return RangeOutcome::Whole,
            }
        };
        if end < start {
            return RangeOutcome::Unsatisfiable;
        }
        ByteRange { start, end }
    };

    RangeOutcome::Partial(range)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn no_header_means_the_whole_body() {
        assert_eq!(parse(None, 100), RangeOutcome::Whole);
    }

    #[test]
    fn an_open_ended_range_runs_to_the_end() {
        // This is the one ExoPlayer opens a stream with.
        assert_eq!(
            parse(Some("bytes=0-"), 100),
            RangeOutcome::Partial(ByteRange { start: 0, end: 99 })
        );
        assert_eq!(
            parse(Some("bytes=40-"), 100),
            RangeOutcome::Partial(ByteRange { start: 40, end: 99 })
        );
    }

    #[test]
    fn a_closed_range_is_inclusive_at_both_ends() {
        let parsed = parse(Some("bytes=0-9"), 100);
        assert_eq!(
            parsed,
            RangeOutcome::Partial(ByteRange { start: 0, end: 9 })
        );
        match parsed {
            RangeOutcome::Partial(r) => assert_eq!(r.len(), 10),
            _ => panic!("expected a partial range"),
        }
    }

    #[test]
    fn an_end_past_the_file_is_clamped_rather_than_refused() {
        assert_eq!(
            parse(Some("bytes=90-100000"), 100),
            RangeOutcome::Partial(ByteRange { start: 90, end: 99 })
        );
    }

    #[test]
    fn a_suffix_range_counts_back_from_the_end() {
        assert_eq!(
            parse(Some("bytes=-10"), 100),
            RangeOutcome::Partial(ByteRange { start: 90, end: 99 })
        );
        // A suffix longer than the file is the whole file, not an error.
        assert_eq!(
            parse(Some("bytes=-500"), 100),
            RangeOutcome::Partial(ByteRange { start: 0, end: 99 })
        );
    }

    #[test]
    fn a_start_past_the_end_is_unsatisfiable() {
        assert_eq!(parse(Some("bytes=100-"), 100), RangeOutcome::Unsatisfiable);
        assert_eq!(parse(Some("bytes=200-300"), 100), RangeOutcome::Unsatisfiable);
        assert_eq!(parse(Some("bytes=9-4"), 100), RangeOutcome::Unsatisfiable);
        assert_eq!(parse(Some("bytes=-0"), 100), RangeOutcome::Unsatisfiable);
    }

    #[test]
    fn an_empty_file_satisfies_nothing() {
        assert_eq!(parse(Some("bytes=0-"), 0), RangeOutcome::Unsatisfiable);
    }

    #[test]
    fn multi_range_and_nonsense_fall_back_to_the_whole_body() {
        // Answering the first of several ranges would be a wrong answer dressed as a right one.
        assert_eq!(parse(Some("bytes=0-9,20-29"), 100), RangeOutcome::Whole);
        assert_eq!(parse(Some("items=0-9"), 100), RangeOutcome::Whole);
        assert_eq!(parse(Some("bytes=abc-def"), 100), RangeOutcome::Whole);
        assert_eq!(parse(Some("bytes=0"), 100), RangeOutcome::Whole);
    }
}
