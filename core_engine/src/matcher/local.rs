// core_engine/src/matcher/local.rs
//
// Deciding whether a file on the phone *is* a Spotify track — E-P, moving a decision that lived in
// Kotlin and had drifted.
//
// ## Why this moved
//
// `SpotifyRepository.isLocalMatch` compared names through a `normalizeName` that trimmed,
// lowercased and stripped `feat.` with four regexes — and **did not fold accents**. That is the same
// defect E-J was written about: six `contains(query, ignoreCase = true)` in `LibraryScreen`, none of
// which folded, so `bjork` did not find `Björk`. E-J fixed the library and left this one standing,
// where the symptom is quieter: a local copy of *Jóga* tagged `Joga` simply never matches, the track
// streams instead of playing off the disk it is already on, and nothing anywhere says so.
//
// `search::fold` is the fold this needed, already here and already tested. So the comparison moves
// to where it is, rather than a fifth regex being added next to the other four.
//
// ## Why a registry and not a call per comparison
//
// The same reason `search` has one: a lookup runs over the whole local library, and a JNI call per
// candidate would be far slower than the Kotlin it replaces. The library is handed over once when it
// is loaded, folded once, and kept; a lookup then sends one track and gets back one id.

use std::sync::{Mutex, OnceLock};

use serde::Deserialize;

use crate::search;

/// A track as this comparison needs it, in the shape the bridge sends.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Candidate {
    pub id: String,
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub artists: Vec<String>,
    #[serde(default)]
    pub isrc: String,
    #[serde(default)]
    pub duration_ms: u32,
}

/// A candidate with its comparison keys already folded, which is the whole point of the registry.
#[derive(Debug, Clone)]
struct Folded {
    id: String,
    isrc: String,
    duration_ms: u32,
    name_key: String,
    artist_keys: Vec<String>,
}

impl Folded {
    fn of(track: &Candidate) -> Self {
        Self {
            id: track.id.clone(),
            isrc: track.isrc.trim().to_string(),
            duration_ms: track.duration_ms,
            name_key: normalise(&track.name),
            artist_keys: track.artists.iter().map(|a| normalise(a)).collect(),
        }
    }
}

/// How far two durations may differ and still be the same recording, in milliseconds.
///
/// Five seconds, which is what the Kotlin used. Wide enough for a different master or a trailing
/// silence, narrow enough that a radio edit does not pass for the album version.
const DURATION_TOLERANCE_MS: u32 = 5_000;

/// Folds a title or an artist name to what a comparison should use.
///
/// Two steps, and the order matters. [`search::fold`] first — lowercase, unaccented, punctuation and
/// whitespace collapsed to single spaces — which turns `Jóga (feat. Björk)` into
/// `joga feat bjork`. **Then** the featuring clause is cut, on the folded text, where it is a plain
/// word boundary rather than four bracket-and-dot regexes that each have to guess at the spelling.
///
/// A name that is *only* a featuring clause keeps it: cutting at position zero would leave nothing to
/// compare, and an empty key matches everything.
pub fn normalise(name: &str) -> String {
    let folded = search::fold(name);
    // Longest first: " featuring " contains " feat ", and cutting at the shorter one would leave a
    // stray "uring" behind.
    //
    // Deliberately not `" with "`, tempting as it is: *Dancing With Myself* would become *Dancing*.
    // These three are what the Kotlin cut, and this move is not the place to widen the rule.
    for marker in [" featuring ", " feat ", " ft "] {
        if let Some(at) = folded.find(marker) {
            if at > 0 {
                return folded[..at].trim().to_string();
            }
        }
    }
    folded
}

/// Is `local` the same recording as `spotify`?
///
/// The order is the order of certainty, and each step can only reject:
///
/// 1. **ISRC.** When both sides have one it settles the question outright.
/// 2. **Title**, folded. Different titles are different tracks; no fuzziness here on purpose,
///    because a near-miss on a title is how the wrong song ends up playing.
/// 3. **Duration**, when both are known, within [`DURATION_TOLERANCE_MS`].
/// 4. **Artist**, at least one in common — or one contained in the other, which is what catches
///    `Bruce Springsteen` against `Bruce Springsteen & The E Street Band`.
fn is_match(spotify: &Folded, local: &Folded) -> bool {
    if !spotify.isrc.is_empty() && !local.isrc.is_empty() {
        return spotify.isrc == local.isrc;
    }

    if spotify.name_key.is_empty() || local.name_key.is_empty() {
        return false;
    }
    if spotify.name_key != local.name_key {
        return false;
    }

    if spotify.duration_ms > 0 && local.duration_ms > 0 {
        let diff = spotify.duration_ms.abs_diff(local.duration_ms);
        if diff > DURATION_TOLERANCE_MS {
            return false;
        }
    }

    if spotify.artist_keys.is_empty() || local.artist_keys.is_empty() {
        return false;
    }
    if spotify
        .artist_keys
        .iter()
        .any(|a| local.artist_keys.contains(a))
    {
        return true;
    }
    spotify.artist_keys.iter().any(|a| {
        local
            .artist_keys
            .iter()
            .any(|b| a.contains(b.as_str()) || b.contains(a.as_str()))
    })
}

static LIBRARY: OnceLock<Mutex<Vec<Folded>>> = OnceLock::new();

fn slot() -> &'static Mutex<Vec<Folded>> {
    LIBRARY.get_or_init(|| Mutex::new(Vec::new()))
}

/// Hands the local library over, folded once and kept until it is replaced.
///
/// Returns how many were kept. Replaces whatever was there: the caller owns when the library has
/// changed, and a registry that merged would keep serving files the user deleted.
pub fn put(tracks: &[Candidate]) -> usize {
    let folded: Vec<Folded> = tracks.iter().map(Folded::of).collect();
    let count = folded.len();
    if let Ok(mut guard) = slot().lock() {
        *guard = folded;
    }
    count
}

/// The id of the local file that is this track, or `None`.
///
/// `None` when nothing matches **and** when no library has been handed over, which are the same
/// answer from the caller's side: play it the way you would have anyway.
pub fn find(spotify: &Candidate) -> Option<String> {
    let needle = Folded::of(spotify);
    let guard = slot().lock().ok()?;
    guard
        .iter()
        .find(|candidate| is_match(&needle, candidate))
        .map(|candidate| candidate.id.clone())
}

// There is no `forget`: `put` with an empty list is the same thing, and T10 was a reminder that a
// second way to do something nobody calls is just code to keep.

#[cfg(test)]
mod tests {
    use super::*;

    fn track(id: &str, name: &str, artists: &[&str], duration_ms: u32) -> Candidate {
        Candidate {
            id: id.to_string(),
            name: name.to_string(),
            artists: artists.iter().map(|a| a.to_string()).collect(),
            isrc: String::new(),
            duration_ms,
        }
    }

    fn with_isrc(mut t: Candidate, isrc: &str) -> Candidate {
        t.isrc = isrc.to_string();
        t
    }

    // The registry is process-wide, so every test that touches it must set it up itself rather than
    // inherit whatever ran before. `put` replaces, which is what makes that cheap.

    #[test]
    fn the_accented_title_that_used_to_be_missed() {
        // The reason this moved. Kotlin lowercased and stripped `feat.` but never folded accents,
        // so a local file tagged with plain ASCII never matched the Spotify track it was a copy of —
        // and the only symptom was that it streamed instead of playing off the disk.
        put(&[track("local:1", "Joga", &["Bjork"], 305_000)]);
        assert_eq!(
            find(&track("spotify:1", "Jóga", &["Björk"], 305_000)),
            Some("local:1".to_string())
        );
    }

    #[test]
    fn a_featuring_clause_is_cut_wherever_it_is_written() {
        // Four spellings that four separate regexes used to have to cover one at a time.
        for written in [
            "Forever (feat. Drake)",
            "Forever [Feat. Drake]",
            "Forever featuring Drake",
            "Forever ft. Drake",
        ] {
            assert_eq!(normalise(written), "forever", "failed on {written:?}");
        }
    }

    #[test]
    fn a_title_that_is_only_a_featuring_clause_keeps_it() {
        // Cutting at position zero leaves an empty key, and an empty key matches everything.
        assert!(!normalise("feat. Drake").is_empty());
    }

    #[test]
    fn the_isrc_settles_it_when_both_sides_have_one() {
        put(&[with_isrc(
            track("local:1", "completely different title", &["nobody"], 1),
            "USUM71700966",
        )]);
        assert_eq!(
            find(&with_isrc(
                track("spotify:1", "Bad and Boujee", &["Migos"], 343_000),
                "USUM71700966"
            )),
            Some("local:1".to_string())
        );
    }

    #[test]
    fn a_different_isrc_is_a_rejection_and_not_a_fall_through() {
        // Both sides know the answer and they disagree. Falling through to compare titles would let
        // a cover version pass for the original.
        put(&[with_isrc(
            track("local:1", "Jolene", &["Dolly Parton"], 162_000),
            "USRC10000001",
        )]);
        assert_eq!(
            find(&with_isrc(
                track("spotify:1", "Jolene", &["Dolly Parton"], 162_000),
                "GBAAA0000002"
            )),
            None
        );
    }

    #[test]
    fn a_radio_edit_does_not_pass_for_the_album_version() {
        put(&[track("local:1", "Marquee Moon", &["Television"], 190_000)]);
        assert_eq!(
            find(&track("spotify:1", "Marquee Moon", &["Television"], 583_000)),
            None
        );
    }

    #[test]
    fn a_missing_duration_does_not_reject() {
        // Plenty of local files have no duration in their tags. Absent is not "wrong".
        put(&[track("local:1", "Marquee Moon", &["Television"], 0)]);
        assert_eq!(
            find(&track("spotify:1", "Marquee Moon", &["Television"], 583_000)),
            Some("local:1".to_string())
        );
    }

    #[test]
    fn one_artist_in_common_is_enough_and_containment_counts() {
        put(&[track(
            "local:1",
            "Atlantic City",
            &["Bruce Springsteen & The E Street Band"],
            240_000,
        )]);
        assert_eq!(
            find(&track("spotify:1", "Atlantic City", &["Bruce Springsteen"], 240_000)),
            Some("local:1".to_string())
        );
    }

    #[test]
    fn a_different_artist_with_the_same_title_is_not_a_match() {
        put(&[track("local:1", "Hurt", &["Nine Inch Nails"], 373_000)]);
        assert_eq!(
            find(&track("spotify:1", "Hurt", &["Johnny Cash"], 216_000)),
            None
        );
    }

    #[test]
    fn an_empty_library_answers_none_rather_than_panicking() {
        put(&[]);
        assert_eq!(find(&track("spotify:1", "anything", &["anyone"], 1000)), None);
    }

    #[test]
    fn put_replaces_rather_than_merges() {
        // A registry that merged would go on offering files the user deleted from the folder.
        put(&[track("local:1", "Gone", &["Someone"], 1000)]);
        put(&[track("local:2", "Still Here", &["Someone"], 1000)]);
        assert_eq!(find(&track("s", "Gone", &["Someone"], 1000)), None);
        assert_eq!(
            find(&track("s", "Still Here", &["Someone"], 1000)),
            Some("local:2".to_string())
        );
    }
}
