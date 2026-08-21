// core_engine/src/search/mod.rs
//
// Local search — point J.
//
// The evaluation rated this "low value: we already have local search in Kotlin, moving it to the
// core would only share it". That was half right. What it did not say is that there is not *a*
// local search in Kotlin: there are six, one per tab, each a `contains(query, ignoreCase = true)`
// written where it was needed. Playlists match on name and owner, albums on name and artists,
// artists on name, tracks on one set of fields in the liked-songs tab and a different set in the
// local-music tab. They agree today because they were written the same week.
//
// That is the shape F already cost this project once, so it goes the same way: one implementation,
// here, and the call sites ask rather than reimplement.
//
// Moving it also buys two things `contains` cannot do, and both are things a person notices:
//
//   * **Accents do not matter.** Typing `bjork` finds `Björk`, and `corazon` finds `Corazón`. With
//     `contains` they simply do not, which reads as "the app does not have that song".
//   * **Word order does not matter.** `dark side moon` finds `The Dark Side of the Moon`. Every
//     token has to appear somewhere, but not adjacently and not in order, which is how people
//     actually half-remember a title.
//
// It ranks as well as filters, because with tokens matching out of order a plain yes/no puts an
// album whose *artist* happens to contain the word above the album actually named it.

/// Folds a string to what a search should compare: lowercase, unaccented, and with runs of
/// punctuation and whitespace collapsed to single spaces.
///
/// Deliberately not a full Unicode normalisation. Music metadata in practice is Latin script plus
/// the odd CJK title, and the letters that actually break a search are the accented Latin ones a
/// Spanish or French keyboard produces. A dependency that handles every script correctly would be
/// carrying a table for scripts where the fold is the identity anyway.
pub fn fold(input: &str) -> String {
    let mut out = String::with_capacity(input.len());
    let mut pending_space = false;
    for ch in input.chars() {
        match fold_char(ch) {
            // Punctuation and whitespace both become "a gap", so `rock&roll`, `rock & roll` and
            // `rock, roll` all tokenize the same. Held rather than pushed, so trailing gaps and runs
            // never reach the output.
            Folded::Gap => pending_space = !out.is_empty(),
            // Removed without leaving a gap: the two halves belong to the same word.
            Folded::Join => {}
            Folded::As(text) => {
                if std::mem::take(&mut pending_space) {
                    out.push(' ');
                }
                out.push_str(text);
            }
            Folded::Itself => {
                if std::mem::take(&mut pending_space) {
                    out.push(' ');
                }
                for lower in ch.to_lowercase() {
                    out.push(lower);
                }
            }
        }
    }
    out
}

/// What one character folds to.
///
/// An enum rather than a `&str` with `""` overloaded to mean two different things: "a gap" and
/// "nothing at all" are opposite answers for an apostrophe, and a sentinel string cannot say which.
enum Folded {
    /// Reads as a word boundary.
    Gap,
    /// Disappears, joining what is on either side.
    Join,
    /// Replaced by this text, already lowercase.
    As(&'static str),
    /// Kept, lowercased. Anything the table says nothing about — including every non-Latin script.
    Itself,
}

/// The per-character half of [`fold`].
fn fold_char(ch: char) -> Folded {
    use Folded::{As, Gap, Itself, Join};
    match ch {
        'á' | 'à' | 'â' | 'ä' | 'ã' | 'å' | 'Á' | 'À' | 'Â' | 'Ä' | 'Ã' | 'Å' => As("a"),
        'é' | 'è' | 'ê' | 'ë' | 'É' | 'È' | 'Ê' | 'Ë' => As("e"),
        'í' | 'ì' | 'î' | 'ï' | 'Í' | 'Ì' | 'Î' | 'Ï' => As("i"),
        'ó' | 'ò' | 'ô' | 'ö' | 'õ' | 'ø' | 'Ó' | 'Ò' | 'Ô' | 'Ö' | 'Õ' | 'Ø' => As("o"),
        'ú' | 'ù' | 'û' | 'ü' | 'Ú' | 'Ù' | 'Û' | 'Ü' => As("u"),
        'ý' | 'ÿ' | 'Ý' => As("y"),
        'ñ' | 'Ñ' => As("n"),
        'ç' | 'Ç' => As("c"),
        // Not letters with a mark on them but letters of their own, and the search still has to
        // find them: nobody types `Motörhead` with the umlaut or `Mötley Crüe` with either.
        'ð' | 'Ð' => As("d"),
        'þ' | 'Þ' => As("th"),
        'ß' => As("ss"),
        'æ' | 'Æ' => As("ae"),
        'œ' | 'Œ' => As("oe"),
        'ł' | 'Ł' => As("l"),
        // An apostrophe joins rather than separates: `dont` should find `Don't`, which it does not
        // if the apostrophe becomes a gap and leaves `don` and `t` as separate tokens.
        '\'' | '\u{2019}' | '`' => Join,
        c if c.is_alphanumeric() => Itself,
        _ => Gap,
    }
}

// ============================================================================================
// Japanese
//
// Typing `sakura` should find `さくら`, and typing `tokyo` should find `トウキョウ`. Kana are
// phonetic, so that mapping is mechanical: fold both kana scripts to the romaji that spells them,
// and the two meet in the middle without either side knowing about the other.
//
// **What this does not do, and cannot without a dictionary: kanji.** `sakura` will not find `桜`,
// because nothing in the characters says how they are read — `桜` could be *sakura* or *ō*, and
// which one it is depends on the word. Spotify manages it because their index carries readings the
// metadata does not. Ours does not have them, so the honest boundary is: **titles written in kana
// become searchable in romaji; titles written in kanji do not.** Stated here rather than discovered
// by someone wondering why half their library answers and half does not.
// ============================================================================================

/// Katakana folded onto hiragana, so the two scripts stop being different strings.
///
/// The blocks are laid out identically, 96 code points apart, which is why this is arithmetic rather
/// than a table. Excludes the long-vowel mark `ー` and the small kana, handled in [`romaji`].
fn katakana_to_hiragana(ch: char) -> char {
    match ch {
        '\u{30A1}'..='\u{30F6}' => char::from_u32(ch as u32 - 0x60).unwrap_or(ch),
        _ => ch,
    }
}

/// The romaji for one hiragana, or `None` for anything that is not one.
///
/// Hepburn, because it is what a person typing on a Latin keyboard produces: `し` is `shi`, not
/// `si`. Digraphs (`きゃ`) and the doubling `っ` are handled by the caller, which is the only part
/// that needs to look at more than one character at a time.
fn romaji_of(ch: char) -> Option<&'static str> {
    Some(match ch {
        'あ' => "a", 'い' => "i", 'う' => "u", 'え' => "e", 'お' => "o",
        'か' => "ka", 'き' => "ki", 'く' => "ku", 'け' => "ke", 'こ' => "ko",
        'が' => "ga", 'ぎ' => "gi", 'ぐ' => "gu", 'げ' => "ge", 'ご' => "go",
        'さ' => "sa", 'し' => "shi", 'す' => "su", 'せ' => "se", 'そ' => "so",
        'ざ' => "za", 'じ' => "ji", 'ず' => "zu", 'ぜ' => "ze", 'ぞ' => "zo",
        'た' => "ta", 'ち' => "chi", 'つ' => "tsu", 'て' => "te", 'と' => "to",
        'だ' => "da", 'ぢ' => "ji", 'づ' => "zu", 'で' => "de", 'ど' => "do",
        'な' => "na", 'に' => "ni", 'ぬ' => "nu", 'ね' => "ne", 'の' => "no",
        'は' => "ha", 'ひ' => "hi", 'ふ' => "fu", 'へ' => "he", 'ほ' => "ho",
        'ば' => "ba", 'び' => "bi", 'ぶ' => "bu", 'べ' => "be", 'ぼ' => "bo",
        'ぱ' => "pa", 'ぴ' => "pi", 'ぷ' => "pu", 'ぺ' => "pe", 'ぽ' => "po",
        'ま' => "ma", 'み' => "mi", 'む' => "mu", 'め' => "me", 'も' => "mo",
        'や' => "ya", 'ゆ' => "yu", 'よ' => "yo",
        'ら' => "ra", 'り' => "ri", 'る' => "ru", 'れ' => "re", 'ろ' => "ro",
        'わ' => "wa", 'ゐ' => "i", 'ゑ' => "e", 'を' => "o", 'ん' => "n",
        'ゔ' => "vu",
        _ => return None,
    })
}

/// The consonant a small `ゃゅょ` attaches to, given the preceding syllable's romaji.
///
/// `きゃ` is `kya`: the `i` of `ki` drops and the `ya` follows. `し`+`ゃ` is `sha`, not `shya`,
/// which is why this drops a trailing `i` rather than pasting.
fn digraph(base: &str, small: char) -> Option<String> {
    let tail = match small {
        'ゃ' => "a",
        'ゅ' => "u",
        'ょ' => "o",
        _ => return None,
    };
    let stem = base.strip_suffix('i')?;
    // `shi` -> `sha`, `chi` -> `cha`, `ji` -> `ja`: the stem already carries the sound, so the `y`
    // would be wrong. Everything else keeps it: `ki` -> `kya`.
    let needs_y = !matches!(stem, "sh" | "ch" | "j");
    Some(if needs_y {
        format!("{stem}y{tail}")
    } else {
        format!("{stem}{tail}")
    })
}

/// Rewrites the kana in `input` as romaji, leaving everything else alone.
///
/// Anything that is not kana — kanji, Latin, punctuation — passes through untouched, which is what
/// makes this safe to run over a title that is half English.
///
/// `long_vowels` picks between the two romanisations people actually type, and **both are indexed**
/// rather than one being chosen:
///
///   * `true` → strict Hepburn: `とうきょう` is `toukyou`, `がっこう` is `gakkou`.
///   * `false` → long vowels collapsed: `tokyo`, `gakko`, and `ラーメン` as `ramen`.
///
/// Neither is more correct; they are what two different people type for the same word. Collapsing is
/// applied **only to vowels that came from kana** — never to Latin text, where it would quietly make
/// `book` and `bok` the same thing.
pub fn romaji_with(input: &str, long_vowels: bool) -> String {
    let chars: Vec<char> = input.chars().map(katakana_to_hiragana).collect();
    let mut out = String::with_capacity(input.len());
    // Whether the character just emitted came from kana. Long-vowel collapsing consults this, which
    // is what keeps it away from Latin text sitting in the same title.
    let mut from_kana = false;
    let mut i = 0;

    /// Appends `text`, dropping a leading vowel that would only lengthen the one before it.
    fn append(out: &mut String, text: &str, collapse: bool, after_kana: bool) {
        let mut chars = text.chars();
        if collapse && after_kana {
            if let (Some(first), Some(last)) = (text.chars().next(), out.chars().last()) {
                // `oo`/`aa`/… is one long vowel, and so is `ou` — which is why `とうきょう` is
                // `tokyo` to most people and `toukyou` to a purist.
                let lengthens = first == last || (last == 'o' && first == 'u');
                if lengthens && matches!(last, 'a' | 'i' | 'u' | 'e' | 'o') {
                    chars.next();
                }
            }
        }
        out.extend(chars);
    }

    while i < chars.len() {
        let ch = chars[i];

        // A small tsu doubles the consonant that follows it: `がっこう` -> `gakkou`.
        if ch == 'っ' {
            if let Some(next) = chars.get(i + 1).copied().and_then(romaji_of) {
                if let Some(first) = next.chars().next() {
                    out.push(first);
                    from_kana = true;
                }
            }
            i += 1;
            continue;
        }

        // The long-vowel mark repeats the vowel before it: `ラーメン` -> `raamen`, or `ramen` with
        // long vowels collapsed, which is what anyone typing it would write.
        if ch == 'ー' {
            if long_vowels {
                if let Some(last) = out.chars().last() {
                    if matches!(last, 'a' | 'i' | 'u' | 'e' | 'o') {
                        out.push(last);
                    }
                }
            }
            from_kana = true;
            i += 1;
            continue;
        }

        match romaji_of(ch) {
            Some(base) => {
                let combined = chars
                    .get(i + 1)
                    .copied()
                    .and_then(|small| digraph(base, small));
                match combined {
                    Some(text) => {
                        append(&mut out, &text, !long_vowels, from_kana);
                        i += 2;
                    }
                    None => {
                        append(&mut out, base, !long_vowels, from_kana);
                        i += 1;
                    }
                }
                from_kana = true;
            }
            None => {
                out.push(ch);
                from_kana = false;
                i += 1;
            }
        }
    }
    out
}

/// Strict Hepburn: long vowels written out. See [`romaji_with`].
pub fn romaji(input: &str) -> String {
    romaji_with(input, true)
}

/// Romaji with long vowels collapsed — `tokyo` rather than `toukyou`. See [`romaji_with`].
pub fn romaji_short(input: &str) -> String {
    romaji_with(input, false)
}

/// The words of a query, folded. An empty query yields no tokens, which every caller reads as
/// "match everything" rather than "match nothing" — an empty search box is not a filter.
pub fn tokens(query: &str) -> Vec<String> {
    // Kana in the *query* become romaji, so someone typing `さくら` finds a title written `Sakura`.
    // A Latin query is untouched by this — `romaji` only rewrites kana — so nothing about searching
    // in English changes. The other direction is handled on the field side, in [`Haystack::new`].
    fold(&romaji(query))
        .split(' ')
        .filter(|t| !t.is_empty())
        .map(str::to_owned)
        .collect()
}

/// One searchable thing, as the fields worth matching on in the order they matter.
///
/// The order is the ranking: `fields[0]` is the name, and a hit there beats a hit in an artist or an
/// owner. Callers build this rather than passing a struct, so a screen can decide that an album's
/// artists count and a playlist's description does not.
pub struct Haystack {
    folded: Vec<String>,
}

impl Haystack {
    pub fn new(fields: &[&str]) -> Self {
        let mut folded: Vec<String> = Vec::with_capacity(fields.len());
        // The real fields first, so the ranking in `score` still means what it says: position 0 is
        // the name, and everything after it is worth less.
        for field in fields {
            folded.push(fold(field));
        }
        // Then the romanisations of anything containing kana, appended so they rank *below* the
        // real fields — a romaji hit is a way of finding the thing, not a better match than its
        // actual title.
        //
        // **Both** romanisations are indexed, because they are what two different people type for
        // the same word: `toukyou` and `tokyo`, `gakkou` and `gakko`. Choosing one would mean the
        // other finds nothing, and there is no way to know which the person will use.
        for field in fields {
            for variant in [romaji(field), romaji_short(field)] {
                if variant == *field {
                    continue; // No kana in it; the plain fold already covers this field.
                }
                let candidate = fold(&variant);
                if !candidate.is_empty() && !folded.contains(&candidate) {
                    folded.push(candidate);
                }
            }
        }
        Haystack { folded }
    }
}

/// How well `tokens` matches, or `None` for "not a match".
///
/// Higher is better. The scale has no meaning beyond ordering; the only promise is that it is
/// stable, so a list sorted by it does not reshuffle when nothing changed.
///
/// Every token must appear in at least one field. That is the rule that makes `dark side moon`
/// work while `dark banana` correctly finds nothing — an "any token" rule would return half the
/// library for a two-word query, which is worse than no search.
pub fn score(tokens: &[String], hay: &Haystack) -> Option<u32> {
    if tokens.is_empty() {
        return Some(0);
    }
    let mut total: u32 = 0;
    for token in tokens {
        let mut best: Option<u32> = None;
        for (position, field) in hay.folded.iter().enumerate() {
            let Some(at) = field.find(token.as_str()) else {
                continue;
            };
            // Four things make one hit better than another, in this order of weight:
            //   * which field it was in — the name beats the artist beats everything after;
            //   * whether it is the whole field, so an exact title lands first;
            //   * whether it started a word, so `rock` ranks `Rock Lobster` over `Shamrock`;
            //   * whether it started the FIELD, which is what separates `Rock Lobster` from
            //     `Punk Rocker` — both begin a word, and without this they tie and the order
            //     between them falls to whichever happened to be listed first.
            let field_weight = 100u32.saturating_sub(position as u32 * 20);
            let at_word_start = at == 0 || field.as_bytes()[at - 1] == b' ';
            let whole_field = at == 0 && field.len() == token.len();
            let hit = field_weight
                + if at_word_start { 30 } else { 0 }
                + if at == 0 { 10 } else { 0 }
                + if whole_field { 50 } else { 0 };
            best = Some(best.map_or(hit, |b: u32| b.max(hit)));
        }
        // One token missing everywhere is the end of it: this is an AND, not an OR.
        total = total.saturating_add(best?);
    }
    Some(total)
}

/// The yes/no half, for callers that only filter.
pub fn matches(tokens: &[String], hay: &Haystack) -> bool {
    score(tokens, hay).is_some()
}

// ============================================================================================
// The index
//
// Why an index rather than a `matches(query, item)` bridge: a filter runs on every keystroke over
// a library that can be thousands of items. Sending those items across JNI each time would be
// slower than the `contains` this replaces, and "the new search is laggier" is not an improvement
// however much better it ranks.
//
// So the list crosses once, when the screen loads it, and is kept folded. A keystroke then sends a
// query string and gets back ids. The expensive half — folding every field of every item — happens
// once instead of once per character.
// ============================================================================================

use std::collections::HashMap;
use std::sync::Mutex;

/// One indexed item: what to return, and what to match against.
struct Entry {
    id: String,
    hay: Haystack,
}

/// A folded list, ready to be queried.
#[derive(Default)]
pub struct Index {
    entries: Vec<Entry>,
}

impl Index {
    /// `items` is `(id, fields)`, with the fields in importance order — see [`Haystack`].
    pub fn build(items: Vec<(String, Vec<String>)>) -> Index {
        Index {
            entries: items
                .into_iter()
                .map(|(id, fields)| Entry {
                    id,
                    hay: Haystack::new(
                        &fields.iter().map(String::as_str).collect::<Vec<_>>(),
                    ),
                })
                .collect(),
        }
    }

    pub fn len(&self) -> usize {
        self.entries.len()
    }

    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    /// Matching ids, best first. `limit == 0` means all of them.
    ///
    /// An empty query returns the list in its original order rather than an arbitrary one: the
    /// search box being empty must leave the screen exactly as it was.
    pub fn query(&self, query: &str, limit: usize) -> Vec<String> {
        let tokens = tokens(query);
        if tokens.is_empty() {
            let all = self.entries.iter().map(|e| e.id.clone());
            return if limit == 0 {
                all.collect()
            } else {
                all.take(limit).collect()
            };
        }
        let mut hits: Vec<(u32, usize, &str)> = self
            .entries
            .iter()
            .enumerate()
            .filter_map(|(position, entry)| {
                score(&tokens, &entry.hay).map(|s| (s, position, entry.id.as_str()))
            })
            .collect();
        // Best score first, and the original order breaks ties. Sorting by score alone would let
        // two equally good hits swap places between keystrokes, which looks like the list flickering.
        hits.sort_by(|a, b| b.0.cmp(&a.0).then(a.1.cmp(&b.1)));
        let taken = if limit == 0 { hits.len() } else { limit.min(hits.len()) };
        hits[..taken].iter().map(|(_, _, id)| id.to_string()).collect()
    }
}

/// The indexes a screen has handed over, by name (`"library.tracks"`, `"local.music"`, …).
///
/// A plain `Mutex` and not the `Env` storage: this is a cache of what the caller already holds in
/// memory, not persisted state, and it dies with the process on purpose. Nothing inside the lock
/// awaits — folding strings is arithmetic — so the rule that produced the ANR does not apply and
/// cannot start to.
static INDEXES: Mutex<Option<HashMap<String, Index>>> = Mutex::new(None);

/// Replaces the index under `name`. Building it is the caller's cost, paid once per list load.
pub fn put(name: &str, index: Index) {
    if let Ok(mut guard) = INDEXES.lock() {
        guard
            .get_or_insert_with(HashMap::new)
            .insert(name.to_string(), index);
    }
}

/// Queries a named index. An unknown name yields nothing, which is the honest answer — a screen
/// that never handed over its list has nothing to search.
pub fn query(name: &str, text: &str, limit: usize) -> Vec<String> {
    let Ok(guard) = INDEXES.lock() else {
        return Vec::new();
    };
    guard
        .as_ref()
        .and_then(|m| m.get(name))
        .map(|index| index.query(text, limit))
        .unwrap_or_default()
}

/// Drops one index, for a screen that is going away.
pub fn forget(name: &str) {
    if let Ok(mut guard) = INDEXES.lock() {
        if let Some(map) = guard.as_mut() {
            map.remove(name);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn hay(fields: &[&str]) -> Haystack {
        Haystack::new(fields)
    }

    #[test]
    fn accents_do_not_matter_in_either_direction() {
        let t = tokens("bjork");
        assert!(matches(&t, &hay(&["Björk"])));
        // And typing the accent still finds the unaccented spelling, which is the direction people
        // forget: a phone keyboard with autocorrect adds accents the metadata does not have.
        let t = tokens("Björk");
        assert!(matches(&t, &hay(&["Bjork"])));
    }

    #[test]
    fn spanish_titles_are_findable_without_a_spanish_keyboard() {
        let t = tokens("corazon espinado");
        assert!(matches(&t, &hay(&["Corazón Espinado"])));
    }

    #[test]
    fn word_order_does_not_matter_but_every_word_must_be_there() {
        let t = tokens("dark side moon");
        assert!(matches(&t, &hay(&["The Dark Side of the Moon"])));

        let t = tokens("dark banana");
        assert!(!matches(&t, &hay(&["The Dark Side of the Moon"])));
    }

    #[test]
    fn an_empty_query_matches_everything() {
        // An empty search box is not a filter that excludes the world.
        let t = tokens("   ");
        assert!(t.is_empty());
        assert!(matches(&t, &hay(&["anything at all"])));
    }

    #[test]
    fn punctuation_is_a_gap_but_an_apostrophe_is_not() {
        assert_eq!(fold("Rock & Roll"), "rock roll");
        assert_eq!(fold("Don't Stop"), "dont stop");
        assert!(matches(&tokens("dont stop"), &hay(&["Don't Stop"])));
        assert!(matches(&tokens("rock roll"), &hay(&["Rock & Roll"])));
    }

    #[test]
    fn the_name_outranks_the_artist() {
        // Both match "beatles". The one actually called that has to come first, which a plain
        // `contains` filter cannot express at all.
        let named = score(&tokens("beatles"), &hay(&["Beatles", "Someone Else"])).unwrap();
        let by = score(&tokens("beatles"), &hay(&["Some Album", "The Beatles"])).unwrap();
        assert!(named > by, "{named} should outrank {by}");
    }

    #[test]
    fn a_word_start_outranks_a_word_middle() {
        // `Punk Rocker` was the example here and it was the wrong one: `rock` begins the word
        // `Rocker`, so it *is* a word start and the two tied at exactly the same score. A real
        // word middle is one where the letter before is not a space.
        let starts = score(&tokens("rock"), &hay(&["Rock Lobster"])).unwrap();
        let inside = score(&tokens("rock"), &hay(&["Shamrock"])).unwrap();
        assert!(starts > inside, "{starts} should outrank {inside}");
    }

    #[test]
    fn starting_the_name_outranks_starting_a_later_word() {
        // Both begin a word; only one begins the title. Without this they tie, and the order
        // between two equally good hits falls to whichever happened to be listed first.
        let first = score(&tokens("rock"), &hay(&["Rock Lobster"])).unwrap();
        let later = score(&tokens("rock"), &hay(&["Punk Rocker"])).unwrap();
        assert!(first > later, "{first} should outrank {later}");
    }

    #[test]
    fn an_exact_title_wins() {
        let exact = score(&tokens("help"), &hay(&["Help"])).unwrap();
        let partial = score(&tokens("help"), &hay(&["Help Me Rhonda"])).unwrap();
        assert!(exact > partial, "{exact} should outrank {partial}");
    }

    #[test]
    fn scoring_is_stable_so_a_sorted_list_does_not_reshuffle() {
        let t = tokens("love");
        let once = score(&t, &hay(&["Love Song", "Artist"]));
        let twice = score(&t, &hay(&["Love Song", "Artist"]));
        assert_eq!(once, twice);
    }

    fn index_of(items: &[(&str, &[&str])]) -> Index {
        Index::build(
            items
                .iter()
                .map(|(id, fields)| {
                    (
                        id.to_string(),
                        fields.iter().map(|f| f.to_string()).collect(),
                    )
                })
                .collect(),
        )
    }

    #[test]
    fn an_empty_query_leaves_the_list_exactly_as_it_was() {
        let index = index_of(&[("c", &["Zebra"]), ("a", &["Apple"]), ("b", &["Mango"])]);
        // Original order, not sorted by anything: the search box being empty must not reorder the
        // screen behind it.
        assert_eq!(index.query("", 0), vec!["c", "a", "b"]);
    }

    #[test]
    fn results_come_back_best_first() {
        let index = index_of(&[
            ("covered", &["Punk Rocker", "Someone"]),
            ("exact", &["Rock", "Someone"]),
            ("starts", &["Rock Lobster", "Someone"]),
            ("by_artist", &["Some Song", "Rock Band"]),
        ]);
        let hits = index.query("rock", 0);
        assert_eq!(hits[0], "exact");
        assert_eq!(hits[1], "starts");
        // The two weaker hits are both present; what matters is that neither outranks the two above.
        assert!(hits.contains(&"covered".to_string()));
        assert!(hits.contains(&"by_artist".to_string()));
    }

    #[test]
    fn ties_keep_their_original_order_so_the_list_does_not_flicker() {
        let index = index_of(&[("first", &["Love"]), ("second", &["Love"])]);
        assert_eq!(index.query("love", 0), vec!["first", "second"]);
    }

    #[test]
    fn a_named_index_survives_being_replaced_and_forgotten() {
        put("test.tracks", index_of(&[("a", &["Hello"])]));
        assert_eq!(query("test.tracks", "hello", 0), vec!["a"]);

        put("test.tracks", index_of(&[("b", &["Hello"])]));
        assert_eq!(query("test.tracks", "hello", 0), vec!["b"]);

        forget("test.tracks");
        assert!(query("test.tracks", "hello", 0).is_empty());
        // And a name nobody ever registered answers the same way rather than panicking.
        assert!(query("test.never-registered", "hello", 0).is_empty());
    }

    #[test]
    fn kana_titles_are_findable_from_a_latin_keyboard() {
        // The thing Spotify does and a plain `contains` cannot: type what you hear.
        assert!(matches(&tokens("sakura"), &hay(&["さくら"])));
        assert!(matches(&tokens("sakura"), &hay(&["サクラ"])));
        // And the other direction, for someone with a Japanese keyboard searching a romanised title.
        assert!(matches(&tokens("さくら"), &hay(&["Sakura"])));
    }

    #[test]
    fn the_two_kana_scripts_stop_being_different_strings() {
        assert_eq!(romaji("サクラ"), romaji("さくら"));
    }

    #[test]
    fn digraphs_and_doubling_come_out_as_written() {
        assert_eq!(romaji("きゃ"), "kya");
        // `し` + small ya is `sha`, not `shya` — the stem already carries the sound.
        assert_eq!(romaji("しゃ"), "sha");
        assert_eq!(romaji("ちょ"), "cho");
        // A small tsu doubles the consonant that follows it.
        assert_eq!(romaji("がっこう"), "gakkou");
        assert_eq!(romaji("ラーメン"), "raamen");
    }

    #[test]
    fn both_romanisations_are_produced_because_both_get_typed() {
        assert_eq!(romaji("とうきょう"), "toukyou");
        assert_eq!(romaji_short("とうきょう"), "tokyo");
        assert_eq!(romaji_short("がっこう"), "gakko");
        assert_eq!(romaji_short("ラーメン"), "ramen");
    }

    #[test]
    fn either_spelling_finds_the_same_title() {
        // The point of indexing both: nobody knows which one the person will type.
        for typed in ["tokyo", "toukyou"] {
            assert!(
                matches(&tokens(typed), &hay(&["とうきょう"])),
                "{typed} should find とうきょう"
            );
        }
        for typed in ["gakko", "gakkou"] {
            assert!(matches(&tokens(typed), &hay(&["がっこう"])));
        }
    }

    #[test]
    fn collapsing_long_vowels_never_touches_latin_text() {
        // The failure this avoids: collapsing applied to everything would make `book` and `bok` the
        // same string, and quietly widen every English search in the library.
        assert_eq!(fold("Book"), "book");
        assert_eq!(romaji_short("Moon Safari"), "Moon Safari");
        assert!(!matches(&tokens("bok"), &hay(&["Book"])));
    }

    #[test]
    fn kanji_is_left_alone_and_the_limit_is_deliberate() {
        // Nothing in the characters says how they are read, so `sakura` cannot find `桜` without a
        // reading dictionary we do not have. The test exists so the boundary is a decision on
        // record rather than a surprise.
        assert_eq!(romaji("桜"), "桜");
        assert!(!matches(&tokens("sakura"), &hay(&["桜"])));
        // What must still hold: a kanji title is findable by typing the kanji.
        assert!(matches(&tokens("桜"), &hay(&["桜"])));
    }

    #[test]
    fn mixed_scripts_survive_together() {
        // Titles are routinely part kanji, part kana, part Latin. The kana half becomes searchable
        // and the rest is untouched, rather than the whole string being mangled.
        assert!(matches(&tokens("tokyo"), &hay(&["とうきょう"])));
        assert!(matches(&tokens("sakura"), &hay(&["桜 さくら Sakura"])));
    }

    #[test]
    fn non_latin_text_survives_folding_rather_than_being_dropped() {
        // The fold has no table for these, so they must pass through unchanged instead of becoming
        // gaps — otherwise a Japanese title becomes an empty string and matches every query.
        let folded = fold("東京");
        assert_eq!(folded, "東京");
        assert!(matches(&tokens("東京"), &hay(&["東京"])));
        assert!(!matches(&tokens("tokyo"), &hay(&["東京"])));
    }
}
