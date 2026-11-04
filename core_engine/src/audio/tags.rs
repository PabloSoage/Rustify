// core_engine/src/audio/tags.rs
//
// The tags of the user's own files, for the platforms that have no `MediaMetadataRetriever`: the
// desktop's local scan reads title, artists, album, numbers, duration and ISRC from here (docs
// serie-4.x/27 §2.6), and a download gets the track's metadata written into it so it shows up in
// the local library as the song it is rather than as its file name.
//
// One `lofty` probe per file, which knows the containers the app plays: MP3, M4A, FLAC, Ogg Vorbis
// and Opus, WAV. WebM is the exception, since lofty reads no Matroska: such a file is an error here,
// and the scan keeps it under its file name, as Android does when the retriever finds no title.
//
// The cover is a separate call: the scan writes one cover per album, so reading the picture of every
// file would move megabytes across the bridge to throw almost all of them away.

use base64::Engine as _;
use lofty::config::{ParseOptions, WriteOptions};
use lofty::file::{AudioFile, TaggedFile, TaggedFileExt};
use lofty::picture::{MimeType, Picture, PictureType};
use lofty::probe::Probe;
use lofty::tag::items::Timestamp;
use lofty::tag::{Accessor, ItemKey, Tag, TagExt, TagType};
use serde::{Deserialize, Serialize};

/// What a local file says about itself. Every text is trimmed, and blank reads as absent.
#[derive(Debug, Clone, Default, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LocalTags {
    pub title: Option<String>,
    /// Every value of the artist field, as the tag holds them: a Vorbis comment can carry several,
    /// ID3 and MP4 usually one ("A; B"), which the app splits by the user's separators.
    pub artists: Vec<String>,
    pub album: Option<String>,
    pub album_artist: Option<String>,
    pub track_number: Option<u32>,
    pub disc_number: Option<u32>,
    /// From the audio itself, not a tag; 0 when the container does not say.
    pub duration_ms: u64,
    pub isrc: Option<String>,
    pub year: Option<u16>,
    /// Whether any tag carries a picture: [`read_cover`] then answers one.
    pub has_cover: bool,
}

/// An embedded picture, base64 so it travels as JSON.
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Cover {
    pub mime_type: String,
    pub data: String,
}

/// What [`write`] puts into a file. Absent fields are left as the file has them.
#[derive(Debug, Clone, Default, PartialEq, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct TagsToWrite {
    pub title: Option<String>,
    pub artists: Vec<String>,
    pub album: Option<String>,
    pub album_artist: Option<String>,
    pub track_number: Option<u32>,
    pub disc_number: Option<u32>,
    pub isrc: Option<String>,
    pub year: Option<u16>,
    /// The front cover, base64.
    pub cover: Option<String>,
    /// The cover's type (`image/jpeg`); read from its first bytes when absent.
    pub cover_mime_type: Option<String>,
}

/// The separator written between several artists: the one the app splits on by default
/// (`ArtistSeparators.DEFAULT`), so a download reads back as the artists it was saved with.
const ARTIST_SEPARATOR: &str = "; ";

fn probe(path: &str, options: ParseOptions) -> Result<TaggedFile, String> {
    Probe::open(path)
        .map_err(|e| e.to_string())?
        .options(options)
        .guess_file_type()
        .map_err(|e| e.to_string())?
        .read()
        .map_err(|e| e.to_string())
}

/// Reads the tags and the duration of the file at `path`.
pub fn read(path: &str) -> Result<LocalTags, String> {
    let file = probe(path, ParseOptions::new().read_properties(true).read_cover_art(true))?;
    let duration_ms = u64::try_from(file.properties().duration().as_millis()).unwrap_or(u64::MAX);
    Ok(from_tags(file.tags(), file.primary_tag_type(), duration_ms))
}

/// The front cover of the file at `path` (else its first picture), or `None` when it has none.
pub fn read_cover(path: &str) -> Result<Option<Cover>, String> {
    let file = probe(path, ParseOptions::new().read_properties(false).read_cover_art(true))?;
    Ok(cover_of(file.tags()))
}

/// Writes `tags` into the file at `path`, in the tag its format calls primary (ID3v2 for MP3, the
/// `ilst` for M4A, Vorbis comments for FLAC and Ogg), keeping whatever else that tag held.
pub fn write(path: &str, tags: &TagsToWrite) -> Result<(), String> {
    let file = probe(path, ParseOptions::new().read_properties(false).read_cover_art(true))?;
    let primary = file.primary_tag_type();
    let mut tag = file.tag(primary).cloned().unwrap_or_else(|| Tag::new(primary));
    apply(&mut tag, tags)?;
    tag.save_to_path(path, WriteOptions::default()).map_err(|e| e.to_string())
}

fn clean(s: &str) -> Option<String> {
    let t = s.trim();
    (!t.is_empty()).then(|| t.to_string())
}

/// The primary tag first, then the others in the file's order: a file with an ID3v2 and an ID3v1
/// tag answers from the first and fills the gaps from the second.
fn ordered(tags: &[Tag], primary: TagType) -> Vec<&Tag> {
    let mut all: Vec<&Tag> = tags.iter().collect();
    all.sort_by_key(|t| t.tag_type() != primary);
    all
}

/// "3", "3/12", " 03 ": the number before the slash.
fn number(s: &str) -> Option<u32> {
    s.split('/').next()?.trim().parse::<u32>().ok().filter(|n| *n > 0)
}

/// "2004", "2004-05-17", "2004-05-17T10:00:00": the year it starts with.
fn year(s: &str) -> Option<u16> {
    let digits: String = s.trim().chars().take_while(char::is_ascii_digit).collect();
    if digits.len() != 4 {
        return None;
    }
    digits.parse::<u16>().ok().filter(|y| *y > 0)
}

fn from_tags(tags: &[Tag], primary: TagType, duration_ms: u64) -> LocalTags {
    let tags = ordered(tags, primary);
    let text = |key: ItemKey| tags.iter().find_map(|t| t.get_string(key).and_then(clean));
    let artists = |key: ItemKey| {
        tags.iter()
            .map(|t| t.get_strings(key).filter_map(clean).collect::<Vec<_>>())
            .find(|values| !values.is_empty())
            .unwrap_or_default()
    };
    let mut track_artists = artists(ItemKey::TrackArtist);
    if track_artists.is_empty() {
        track_artists = artists(ItemKey::TrackArtists);
    }
    LocalTags {
        title: text(ItemKey::TrackTitle),
        artists: track_artists,
        album: text(ItemKey::AlbumTitle),
        album_artist: text(ItemKey::AlbumArtist),
        track_number: tags.iter().find_map(|t| t.get_string(ItemKey::TrackNumber).and_then(number)),
        disc_number: tags.iter().find_map(|t| t.get_string(ItemKey::DiscNumber).and_then(number)),
        duration_ms,
        isrc: text(ItemKey::Isrc),
        year: tags.iter().find_map(|t| {
            t.date()
                .map(|d| d.year)
                .filter(|y| *y > 0)
                .or_else(|| t.get_string(ItemKey::Year).and_then(year))
                .or_else(|| t.get_string(ItemKey::RecordingDate).and_then(year))
        }),
        has_cover: tags.iter().any(|t| !t.pictures().is_empty()),
    }
}

/// The picture's type as written in it, else guessed from its first bytes.
fn mime_of(picture: &Picture) -> String {
    if let Some(mime) = picture.mime_type() {
        let s = mime.as_str();
        if !s.is_empty() {
            return s.to_string();
        }
    }
    sniff_image(picture.data()).to_string()
}

fn sniff_image(data: &[u8]) -> &'static str {
    if data.starts_with(&[0x89, b'P', b'N', b'G']) {
        "image/png"
    } else {
        "image/jpeg"
    }
}

fn cover_of(tags: &[Tag]) -> Option<Cover> {
    let all = || tags.iter().flat_map(|t| t.pictures().iter()).filter(|p| !p.data().is_empty());
    let picture = all().find(|p| p.pic_type() == PictureType::CoverFront).or_else(|| all().next())?;
    Some(Cover {
        mime_type: mime_of(picture),
        data: base64::engine::general_purpose::STANDARD.encode(picture.data()),
    })
}

fn apply(tag: &mut Tag, tags: &TagsToWrite) -> Result<(), String> {
    if let Some(title) = tags.title.as_deref().and_then(clean) {
        tag.set_title(title);
    }
    let artists: Vec<String> = tags.artists.iter().map(String::as_str).filter_map(clean).collect();
    if !artists.is_empty() {
        tag.set_artist(artists.join(ARTIST_SEPARATOR));
    }
    if let Some(album) = tags.album.as_deref().and_then(clean) {
        tag.set_album(album);
    }
    if let Some(album_artist) = tags.album_artist.as_deref().and_then(clean) {
        tag.insert_text(ItemKey::AlbumArtist, album_artist);
    }
    if let Some(n) = tags.track_number.filter(|n| *n > 0) {
        tag.set_track(n);
    }
    if let Some(n) = tags.disc_number.filter(|n| *n > 0) {
        tag.set_disk(n);
    }
    if let Some(isrc) = tags.isrc.as_deref().and_then(clean) {
        tag.insert_text(ItemKey::Isrc, isrc);
    }
    if let Some(y) = tags.year.filter(|y| *y > 0) {
        tag.set_date(Timestamp { year: y, ..Default::default() });
    }
    if let Some(encoded) = tags.cover.as_deref().filter(|c| !c.is_empty()) {
        let data = base64::engine::general_purpose::STANDARD
            .decode(encoded)
            .map_err(|e| format!("cover: {e}"))?;
        if !data.is_empty() {
            let mime = tags.cover_mime_type.as_deref().and_then(clean).unwrap_or_else(|| sniff_image(&data).to_string());
            tag.remove_picture_type(PictureType::CoverFront);
            tag.push_picture(
                Picture::unchecked(data)
                    .pic_type(PictureType::CoverFront)
                    .mime_type(MimeType::from_str(&mime))
                    .build(),
            );
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use base64::Engine as _;

    /// One second of silence as 16-bit mono PCM at 44.1 kHz: the smallest file lofty both reads and
    /// writes a tag into (an ID3v2 chunk), with a duration it can compute.
    fn silent_wav() -> Vec<u8> {
        let samples = 44_100u32;
        let data_len = samples * 2;
        let mut out = Vec::new();
        out.extend_from_slice(b"RIFF");
        out.extend_from_slice(&(36 + data_len).to_le_bytes());
        out.extend_from_slice(b"WAVE");
        out.extend_from_slice(b"fmt ");
        out.extend_from_slice(&16u32.to_le_bytes());
        out.extend_from_slice(&1u16.to_le_bytes()); // PCM
        out.extend_from_slice(&1u16.to_le_bytes()); // mono
        out.extend_from_slice(&44_100u32.to_le_bytes());
        out.extend_from_slice(&88_200u32.to_le_bytes()); // bytes per second
        out.extend_from_slice(&2u16.to_le_bytes()); // block align
        out.extend_from_slice(&16u16.to_le_bytes()); // bits per sample
        out.extend_from_slice(b"data");
        out.extend_from_slice(&data_len.to_le_bytes());
        out.resize(out.len() + data_len as usize, 0);
        out
    }

    fn temp_file(name: &str, bytes: &[u8]) -> std::path::PathBuf {
        let path = std::env::temp_dir().join(format!("rustify-tags-{}-{name}", crate::server::random_hex(8)));
        std::fs::write(&path, bytes).unwrap();
        path
    }

    #[test]
    fn the_primary_tag_answers_first_and_the_others_fill_the_gaps() {
        let mut v1 = Tag::new(TagType::Id3v1);
        v1.insert_text(ItemKey::TrackTitle, "Truncated titl".into());
        v1.insert_text(ItemKey::AlbumTitle, "Homogenic".into());
        let mut v2 = Tag::new(TagType::Id3v2);
        v2.insert_text(ItemKey::TrackTitle, " Jóga ".into());
        v2.insert_text(ItemKey::TrackArtist, "Björk".into());
        v2.insert_text(ItemKey::TrackNumber, "3/10".into());
        v2.insert_text(ItemKey::Isrc, "GBAAN9700012".into());
        v2.insert_text(ItemKey::RecordingDate, "1997-09-22".into());
        let tags = from_tags(&[v1, v2], TagType::Id3v2, 305_000);
        assert_eq!(
            tags,
            LocalTags {
                title: Some("Jóga".into()),
                artists: vec!["Björk".into()],
                album: Some("Homogenic".into()),
                album_artist: None,
                track_number: Some(3),
                disc_number: None,
                duration_ms: 305_000,
                isrc: Some("GBAAN9700012".into()),
                year: Some(1997),
                has_cover: false,
            }
        );
    }

    #[test]
    fn several_artist_values_are_kept_apart() {
        let mut vorbis = Tag::new(TagType::VorbisComments);
        vorbis.push(lofty::tag::TagItem::new(ItemKey::TrackArtist, lofty::tag::ItemValue::Text("A".into())));
        vorbis.push(lofty::tag::TagItem::new(ItemKey::TrackArtist, lofty::tag::ItemValue::Text("B".into())));
        assert_eq!(from_tags(&[vorbis], TagType::VorbisComments, 0).artists, vec!["A".to_string(), "B".to_string()]);
    }

    #[test]
    fn numbers_and_years_are_read_as_taggers_write_them() {
        assert_eq!(number("03"), Some(3));
        assert_eq!(number("3/12"), Some(3));
        assert_eq!(number("0"), None);
        assert_eq!(number("x"), None);
        assert_eq!(year("2004-05-17T10:00:00"), Some(2004));
        assert_eq!(year("04"), None);
    }

    #[test]
    fn the_answer_is_camel_case_json() {
        let json = serde_json::to_value(LocalTags { album_artist: Some("X".into()), ..Default::default() }).unwrap();
        assert_eq!(json["albumArtist"], "X");
        assert_eq!(json["durationMs"], 0);
        assert_eq!(json["hasCover"], false);
        assert!(json["title"].is_null());
    }

    #[test]
    fn the_front_cover_wins_over_other_pictures() {
        let mut tag = Tag::new(TagType::Id3v2);
        tag.push_picture(Picture::unchecked(vec![1, 2, 3]).pic_type(PictureType::Artist).build());
        tag.push_picture(
            Picture::unchecked(vec![0x89, b'P', b'N', b'G', 0, 0]).pic_type(PictureType::CoverFront).build(),
        );
        let cover = cover_of(&[tag]).expect("a cover");
        assert_eq!(cover.mime_type, "image/png");
        assert_eq!(cover.data, base64::engine::general_purpose::STANDARD.encode([0x89, b'P', b'N', b'G', 0, 0]));
        assert_eq!(cover_of(&[Tag::new(TagType::Id3v2)]), None);
    }

    #[test]
    fn what_is_written_reads_back() {
        let path = temp_file("a.wav", &silent_wav());
        let p = path.to_str().unwrap();
        let jpeg = vec![0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10, b'J', b'F', b'I', b'F', 0, 1];
        write(
            p,
            &TagsToWrite {
                title: Some("Hunter".into()),
                artists: vec!["Björk".into(), "Guest".into()],
                album: Some("Homogenic".into()),
                track_number: Some(1),
                isrc: Some("GBAAN9700011".into()),
                year: Some(1997),
                cover: Some(base64::engine::general_purpose::STANDARD.encode(&jpeg)),
                ..Default::default()
            },
        )
        .expect("writes");

        let tags = read(p).expect("reads");
        assert_eq!(tags.title.as_deref(), Some("Hunter"));
        // One ID3 value, as a tagger writes several artists; the app splits it on `;`.
        assert_eq!(tags.artists, vec!["Björk; Guest".to_string()]);
        assert_eq!(tags.album.as_deref(), Some("Homogenic"));
        assert_eq!(tags.track_number, Some(1));
        assert_eq!(tags.isrc.as_deref(), Some("GBAAN9700011"));
        assert_eq!(tags.year, Some(1997));
        assert_eq!(tags.duration_ms, 1000);
        assert!(tags.has_cover);
        let cover = read_cover(p).expect("reads").expect("a cover");
        assert_eq!(base64::engine::general_purpose::STANDARD.decode(cover.data).unwrap(), jpeg);
        let _ = std::fs::remove_file(path);
    }

    #[test]
    fn a_file_that_is_not_audio_is_an_error_not_a_panic() {
        let path = temp_file("x.mp3", b"not audio at all");
        let p = path.to_str().unwrap();
        assert!(read(p).is_err());
        assert!(read_cover(p).is_err());
        assert!(write(p, &TagsToWrite::default()).is_err());
        let _ = std::fs::remove_file(path);
    }
}
