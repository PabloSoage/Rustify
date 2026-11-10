// core_engine/src/matcher/lyrics.rs
//
// Is the song a lyrics site answered with the song that is playing?
//
// ## Why this exists
//
// Three of the lyrics providers are searches, not lookups: Musixmatch's `macro.subtitles.get`
// always answers its *best* match for an artist and a title, LRCLIB's `/api/search` a list, and
// Genius a list of hits. The app took the first answer every time, so a song with no lyrics on the
// site showed somebody else's: an instrumental by a Japanese artist came back with the lyrics of an
// unrelated song in another language (owner, 2026-11-10). Nothing errored — which is the problem with
// a search read as a lookup.
//
// The rule is [`super::local`]'s, loosened only where lyrics sites legitimately differ from the
// track, and it lives here for the same reason that one does: `search::fold` is here, and a sixth
// hand-written name comparison in Kotlin is how the accents were missed last time.

use super::local::{normalise, Candidate};
use crate::search;

/// How far the found song's duration may be from the track's and still be it, in milliseconds.
///
/// Wider than the local matcher's five seconds: a lyrics site often lists one release of a song for
/// all of them (album, single, remaster), and plain lyrics are the same words for every one. A radio
/// edit against an extended mix is still turned away.
const DURATION_TOLERANCE_MS: u32 = 15_000;

/// The words a version tag is made of, after a ` - ` or inside brackets: `Song - Remastered 2011`,
/// `Song (Live)`, `Song [Radio Edit]`. Cut from both sides before comparing, because the site and
/// Spotify rarely agree on them and they do not change the words sung.
const VERSION_WORDS: &[&str] = &[
    "remaster",
    "remastered",
    "version",
    "edit",
    "mix",
    "remix",
    "radio",
    "extended",
    "album",
    "track",
    "live",
    "mono",
    "stereo",
    "acoustic",
    "demo",
    "deluxe",
    "bonus",
    "explicit",
    "clean",
    "single",
    "instrumental",
    "original",
    "from",
];

/// The ways a title may be written that still mean the same song: as it is, without a version tag,
/// and the romaji of each (a Japanese title is often listed in kana on one side and romaji on the
/// other).
fn title_keys(title: &str) -> Vec<String> {
    let mut raw = vec![title.to_string()];
    if let Some(base) = strip_version(title) {
        raw.push(base);
    }
    keys(&raw)
}

fn artist_keys(artists: &[String]) -> Vec<String> {
    keys(artists)
}

fn keys(raw: &[String]) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for text in raw {
        for key in [normalise(text), search::fold(&search::romaji_short(text))] {
            if !key.is_empty() && !out.contains(&key) {
                out.push(key);
            }
        }
    }
    out
}

/// The title without a trailing version tag, if it has one: everything from a ` - ` or a bracket
/// onwards, when what follows is made of [`VERSION_WORDS`] (and years). `None` when nothing is cut,
/// or when cutting would leave nothing.
fn strip_version(title: &str) -> Option<String> {
    let at = [" - ", " – ", "(", "["]
        .iter()
        .filter_map(|sep| title.find(sep))
        .min()?;
    let (head, tail) = title.split_at(at);
    let head = head.trim();
    if head.is_empty() {
        return None;
    }
    let tail = search::fold(tail);
    let is_tag = tail.split(' ').filter(|w| !w.is_empty()).all(|w| {
        VERSION_WORDS.contains(&w)
            || w.chars().all(|c| c.is_ascii_digit())
            || w == "feat"
            || w == "ft"
    }) || tail.contains("feat ")
        || tail.starts_with("ft ");
    is_tag.then(|| head.to_string())
}

/// Does `found` (what the lyrics site matched) describe the same song as `wanted` (what is playing)?
///
/// Each step can only reject:
///
/// 1. **Title**: one of the ways each side may be written ([`title_keys`]) is the same. No
///    "contains" here: *Hello* is not *Hello, Goodbye*.
/// 2. **Artist**: one in common, or one contained in the other (`Bruce Springsteen` against
///    `Bruce Springsteen & The E Street Band`). A site that does not say who sang it is not trusted.
/// 3. **Duration**, when both are known, within [`DURATION_TOLERANCE_MS`].
pub fn accepts(wanted: &Candidate, found: &Candidate) -> bool {
    let wanted_titles = title_keys(&wanted.name);
    let found_titles = title_keys(&found.name);
    if wanted_titles.is_empty() || !wanted_titles.iter().any(|t| found_titles.contains(t)) {
        return false;
    }

    let wanted_artists = artist_keys(&wanted.artists);
    let found_artists = artist_keys(&found.artists);
    if wanted_artists.is_empty() || found_artists.is_empty() {
        return false;
    }
    let same_artist = wanted_artists.iter().any(|a| {
        found_artists
            .iter()
            .any(|b| a == b || a.contains(b.as_str()) || b.contains(a.as_str()))
    });
    if !same_artist {
        return false;
    }

    if wanted.duration_ms > 0 && found.duration_ms > 0 {
        return wanted.duration_ms.abs_diff(found.duration_ms) <= DURATION_TOLERANCE_MS;
    }
    true
}

#[cfg(test)]
mod tests {
    use super::*;

    fn song(name: &str, artists: &[&str], seconds: u32) -> Candidate {
        Candidate {
            id: String::new(),
            name: name.to_string(),
            artists: artists.iter().map(|a| a.to_string()).collect(),
            isrc: String::new(),
            duration_ms: seconds * 1000,
        }
    }

    #[test]
    fn an_unrelated_best_match_is_turned_away() {
        // The owner's case: an instrumental the site does not have, answered with another song.
        let playing = song("Irasshaimase", &["Kerusu"], 150);
        let answered = song("Koi no Yokan", &["Some Other Band"], 240);
        assert!(!accepts(&playing, &answered));
    }

    #[test]
    fn the_same_title_by_another_artist_is_not_the_song() {
        let playing = song("Hello", &["Adele"], 295);
        assert!(!accepts(&playing, &song("Hello", &["Lionel Richie"], 0)));
    }

    #[test]
    fn a_longer_title_that_contains_it_is_not_the_song() {
        let playing = song("Hello", &["The Beatles"], 0);
        assert!(!accepts(
            &playing,
            &song("Hello, Goodbye", &["The Beatles"], 0)
        ));
    }

    #[test]
    fn the_same_song_written_differently_is_accepted() {
        let playing = song("Hells Bells", &["AC/DC"], 312);
        assert!(accepts(&playing, &song("Hells Bells", &["AC/DC"], 312)));
        assert!(accepts(
            &playing,
            &song("Hells Bells - Remastered 2003", &["AC/DC"], 310)
        ));
        assert!(accepts(&playing, &song("hells bells", &["ac/dc"], 0)));
        assert!(accepts(
            &song("Jóga", &["Björk"], 305),
            &song("Joga", &["Bjork"], 305)
        ));
        assert!(accepts(
            &song("Dancing in the Dark", &["Bruce Springsteen"], 0),
            &song(
                "Dancing in the Dark",
                &["Bruce Springsteen & The E Street Band"],
                0
            )
        ));
    }

    #[test]
    fn a_version_tag_on_either_side_is_ignored_but_a_real_subtitle_is_not() {
        let playing = song("One More Time (Radio Edit)", &["Daft Punk"], 0);
        assert!(accepts(&playing, &song("One More Time", &["Daft Punk"], 0)));
        // "(Part II)" is not a version tag: another song.
        let part_two = song("Another Brick in the Wall (Part II)", &["Pink Floyd"], 0);
        assert!(!accepts(
            &song("Another Brick in the Wall", &["Pink Floyd"], 0),
            &part_two
        ));
    }

    #[test]
    fn a_kana_title_matches_its_romaji() {
        assert!(accepts(
            &song("Irasshaimase", &["Kerusu"], 0),
            &song("いらっしゃいませ", &["Kerusu"], 0)
        ));
    }

    #[test]
    fn far_apart_durations_are_different_recordings() {
        let playing = song("One More Time", &["Daft Punk"], 320);
        assert!(!accepts(
            &playing,
            &song("One More Time", &["Daft Punk"], 230)
        ));
    }

    #[test]
    fn an_answer_that_does_not_say_who_sang_it_is_not_trusted() {
        assert!(!accepts(
            &song("Hells Bells", &["AC/DC"], 0),
            &song("Hells Bells", &[], 0)
        ));
    }
}
