// core_engine/src/audio/replaygain.rs
//
// ReplayGain for the user's own files (4.10, Musicolet 3.3): read what the tagger already measured.
//
// YouTube says how loud each upload is and the app turns it down by that much (`LoudnessStore`).
// Local files had nothing, so an album ripped quietly in 2004 and a 2020 master played a dozen dB
// apart. Most libraries already carry the answer — foobar2000, MusicBrainz Picard, rsgain and
// beets write it — in a different place per format:
//
// - FLAC / Ogg Vorbis / APE: `REPLAYGAIN_TRACK_GAIN` comments, "-6.48 dB".
// - MP3: `TXXX:REPLAYGAIN_TRACK_GAIN` frames, same text.
// - MP4/M4A: `----:com.apple.iTunes:replaygain_track_gain` freeform atoms, same text.
// - Opus: `R128_TRACK_GAIN`, a Q7.8 integer against EBU R128's -23 LUFS rather than ReplayGain's
//   -18 LUFS (RFC 7845 §5.2.1), so 5 dB apart.
//
// `lofty` maps all of these to one `ItemKey`, which is why it is here rather than four parsers.
// Measuring a file that carries no tag (EBU R128 over decoded PCM) is a later, battery-bound step.

use lofty::config::ParseOptions;
use lofty::file::TaggedFileExt;
use lofty::probe::Probe;
use lofty::tag::{ItemKey, Tag};
use serde::Serialize;

/// The gains a file declares, in dB against ReplayGain's reference, and its peaks (1.0 = full scale).
#[derive(Debug, Clone, Default, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ReplayGain {
    pub track_gain_db: Option<f64>,
    pub album_gain_db: Option<f64>,
    pub track_peak: Option<f64>,
    pub album_peak: Option<f64>,
}

/// EBU R128's reference (-23 LUFS) is 5 dB below ReplayGain 2's (-18 LUFS).
const R128_TO_REPLAYGAIN_DB: f64 = 5.0;

/// Reads the ReplayGain of the file at `path` — on Android `/proc/self/fd/N` of an open SAF
/// descriptor. Tags only: no cover art and no audio properties are parsed.
pub fn read(path: &str) -> Result<ReplayGain, String> {
    let file = Probe::open(path)
        .map_err(|e| e.to_string())?
        .options(ParseOptions::new().read_properties(false).read_cover_art(false))
        .guess_file_type()
        .map_err(|e| e.to_string())?
        .read()
        .map_err(|e| e.to_string())?;
    Ok(from_tags(file.tags()))
}

/// The first value each tag has: a file can hold an ID3v2 and an APE tag at once.
fn from_tags(tags: &[Tag]) -> ReplayGain {
    let first = |key: ItemKey| tags.iter().find_map(|t| t.get_string(key));
    let r128 = |key: ItemKey| first(key).and_then(parse_r128);
    ReplayGain {
        track_gain_db: first(ItemKey::ReplayGainTrackGain).and_then(parse_gain).or_else(|| r128(ItemKey::R128TrackGain)),
        album_gain_db: first(ItemKey::ReplayGainAlbumGain).and_then(parse_gain).or_else(|| r128(ItemKey::R128AlbumGain)),
        track_peak: first(ItemKey::ReplayGainTrackPeak).and_then(parse_peak),
        album_peak: first(ItemKey::ReplayGainAlbumPeak).and_then(parse_peak),
    }
}

/// "-6.48 dB", "+1.2 dB", "-6.48": taggers disagree on the unit and the sign.
fn parse_gain(s: &str) -> Option<f64> {
    let n = s.trim().trim_end_matches(|c: char| c.is_ascii_alphabetic() || c.is_whitespace());
    n.trim_start_matches('+').parse::<f64>().ok().filter(|g| g.is_finite() && g.abs() <= 60.0)
}

fn parse_peak(s: &str) -> Option<f64> {
    s.trim().parse::<f64>().ok().filter(|p| p.is_finite() && *p > 0.0 && *p <= 10.0)
}

/// RFC 7845: a signed Q7.8 number of dB, here moved to ReplayGain's reference.
fn parse_r128(s: &str) -> Option<f64> {
    s.trim().parse::<i16>().ok().map(|q| f64::from(q) / 256.0 + R128_TO_REPLAYGAIN_DB)
}

#[cfg(test)]
mod tests {
    use super::*;
    use lofty::tag::TagType;

    #[test]
    fn the_gain_text_of_every_tagger_is_read() {
        assert_eq!(parse_gain("-6.48 dB"), Some(-6.48));
        assert_eq!(parse_gain("+1.20 dB"), Some(1.2));
        assert_eq!(parse_gain(" -3.5"), Some(-3.5));
        assert_eq!(parse_gain("loud"), None);
        assert_eq!(parse_gain("-900 dB"), None, "nonsense is not a gain");
    }

    #[test]
    fn an_opus_r128_gain_is_moved_to_the_replaygain_reference() {
        // -1792 / 256 = -7 dB against -23 LUFS, so -2 dB against -18 LUFS.
        assert_eq!(parse_r128("-1792"), Some(-2.0));
        assert_eq!(parse_r128("x"), None);
    }

    #[test]
    fn the_first_tag_with_a_value_wins_and_replaygain_beats_r128() {
        let mut id3 = Tag::new(TagType::Id3v2);
        id3.insert_text(ItemKey::ReplayGainAlbumGain, "-4.00 dB".into());
        let mut ape = Tag::new(TagType::Ape);
        ape.insert_text(ItemKey::ReplayGainTrackGain, "-6.00 dB".into());
        ape.insert_text(ItemKey::ReplayGainTrackPeak, "0.988".into());
        let rg = from_tags(&[id3, ape]);
        assert_eq!(rg, ReplayGain { track_gain_db: Some(-6.0), album_gain_db: Some(-4.0), track_peak: Some(0.988), album_peak: None });

        let mut vorbis = Tag::new(TagType::VorbisComments);
        vorbis.insert_text(ItemKey::R128TrackGain, "-1792".into());
        assert_eq!(from_tags(&[vorbis]).track_gain_db, Some(-2.0));
    }

    #[test]
    fn a_file_that_is_not_audio_is_an_error_not_a_panic() {
        let dir = std::env::temp_dir().join("rustify_rg_test.mp3");
        std::fs::write(&dir, b"not audio at all").unwrap();
        assert!(read(dir.to_str().unwrap()).is_err());
        let _ = std::fs::remove_file(dir);
    }
}
