// core_engine/src/audio/embedded_lyrics.rs
//
// Lyrics the user's own file carries (`17` §4, after Gramophone): ID3 `USLT`, Vorbis `LYRICS` or
// `UNSYNCEDLYRICS`, MP4 `©lyr`. `lofty` files ID3's `USLT` under `UnsyncLyrics` only, so both keys
// are read. The text is handed back as it is — often an LRC, sometimes
// plain — and the app's LRC parser decides which. A local track used to go to LRCLIB and the rest
// for lyrics that were already inside it.
//
// ID3 `SYLT` (binary synchronised lyrics) is not read: rare in practice, and taggers that write it
// also write an LRC into `USLT`.

use lofty::config::ParseOptions;
use lofty::file::TaggedFileExt;
use lofty::probe::Probe;
use lofty::tag::{ItemKey, Tag};

/// The lyrics in the file at `path` (`/proc/self/fd/N` on Android), or `None` when it has none.
pub fn read(path: &str) -> Result<Option<String>, String> {
    let file = Probe::open(path)
        .map_err(|e| e.to_string())?
        .options(ParseOptions::new().read_properties(false).read_cover_art(false))
        .guess_file_type()
        .map_err(|e| e.to_string())?
        .read()
        .map_err(|e| e.to_string())?;
    Ok(from_tags(file.tags()))
}

/// The longest lyrics among the tags: a file with an ID3v2 and an APE tag may hold a stub in one.
fn from_tags(tags: &[Tag]) -> Option<String> {
    tags.iter()
        .flat_map(|t| t.get_strings(ItemKey::Lyrics).chain(t.get_strings(ItemKey::UnsyncLyrics)))
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .max_by_key(|s| s.len())
        .map(str::to_string)
}

#[cfg(test)]
mod tests {
    use super::*;
    use lofty::tag::TagType;

    #[test]
    fn the_longest_lyrics_of_any_tag_win() {
        let mut ape = Tag::new(TagType::Ape);
        ape.insert_text(ItemKey::Lyrics, " la ".into());
        let mut id3 = Tag::new(TagType::Id3v2);
        id3.insert_text(ItemKey::UnsyncLyrics, "[00:01.00]first line\n[00:05.00]second".into());
        assert_eq!(from_tags(&[ape, id3]).as_deref(), Some("[00:01.00]first line\n[00:05.00]second"));
    }

    #[test]
    fn no_lyrics_is_none() {
        assert_eq!(from_tags(&[Tag::new(TagType::VorbisComments)]), None);
    }
}
