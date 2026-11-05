// core_engine/src/engine/decode.rs
//
// From bytes to interleaved `f32` PCM, through Symphonia.
//
// What Symphonia reads here: FLAC, MP3, AAC and ALAC in MP4/M4A, Vorbis in Ogg, WAV/PCM, and the
// Matroska/WebM container. What it does **not** decode is Opus — and YouTube's best audio is Opus in
// WebM (itag 251). So on desktop the resolver is asked for AAC (`InnertubeAudio` with
// `prefer = "aac"`, itag 140), and an Opus stream arriving anyway fails here as
// [`EngineError::Unsupported`], which the source chain reads as "try the next one" rather than as a
// broken track. Adding Opus is a libopus binding; it is listed in docs `25` rather than done blind.

use std::io::ErrorKind as IoErrorKind;

use symphonia::core::audio::SampleBuffer;
use symphonia::core::codecs::{DecoderOptions, CODEC_TYPE_NULL};
use symphonia::core::errors::Error as SymphoniaError;
use symphonia::core::formats::{FormatOptions, FormatReader, SeekMode, SeekTo};
use symphonia::core::io::{MediaSource, MediaSourceStream};
use symphonia::core::meta::MetadataOptions;
use symphonia::core::probe::Hint;
use symphonia::core::units::Time;

use super::EngineError;

/// A block of decoded audio at the track's own rate and channel count.
#[derive(Debug, Clone, PartialEq)]
pub struct Block {
    pub samples: Vec<f32>,
    pub sample_rate: u32,
    pub channels: usize,
}

pub struct Decoder {
    format: Box<dyn FormatReader>,
    decoder: Box<dyn symphonia::core::codecs::Decoder>,
    track_id: u32,
    sample_rate: u32,
    channels: usize,
    /// In frames, when the container says.
    n_frames: Option<u64>,
    /// After an accurate seek: frames before this timestamp are decoded but not returned.
    skip_until_ts: Option<u64>,
    /// Reused between packets so a track does not allocate per block.
    scratch: Option<SampleBuffer<f32>>,
}

impl Decoder {
    /// Opens `source`. `hint` is a file extension or MIME type when one is known; probing works
    /// without it, it is just faster and less ambiguous with it.
    pub fn open(source: Box<dyn MediaSource>, hint: Option<&str>) -> Result<Self, EngineError> {
        let stream = MediaSourceStream::new(source, Default::default());
        let mut probe_hint = Hint::new();
        if let Some(h) = hint {
            if h.contains('/') {
                probe_hint.mime_type(h);
            } else {
                probe_hint.with_extension(h);
            }
        }
        let format_options = FormatOptions {
            // Encoder delay and padding trimmed, so an album plays without a gap between tracks.
            enable_gapless: true,
            ..Default::default()
        };
        let probed = symphonia::default::get_probe()
            .format(&probe_hint, stream, &format_options, &MetadataOptions::default())
            .map_err(|e| EngineError::Unsupported(format!("unrecognised stream: {e}")))?;
        let format = probed.format;

        let track = format
            .tracks()
            .iter()
            .find(|t| t.codec_params.codec != CODEC_TYPE_NULL)
            .ok_or_else(|| EngineError::Unsupported("no audio track".into()))?;
        let params = track.codec_params.clone();
        let track_id = track.id;
        let decoder = symphonia::default::get_codecs()
            .make(&params, &DecoderOptions::default())
            .map_err(|e| EngineError::Unsupported(format!("codec not supported: {e}")))?;
        let sample_rate = params
            .sample_rate
            .ok_or_else(|| EngineError::Unsupported("no sample rate".into()))?;
        let channels = params.channels.map(|c| c.count()).unwrap_or(2).max(1);

        Ok(Decoder {
            format,
            decoder,
            track_id,
            sample_rate,
            channels,
            n_frames: params.n_frames,
            skip_until_ts: None,
            scratch: None,
        })
    }

    pub fn sample_rate(&self) -> u32 {
        self.sample_rate
    }

    pub fn channels(&self) -> usize {
        self.channels
    }

    /// The length, when the container states it.
    pub fn duration_ms(&self) -> Option<u64> {
        self.n_frames
            .map(|frames| frames * 1000 / self.sample_rate.max(1) as u64)
    }

    /// The next block, `Ok(None)` at the end.
    ///
    /// A packet that fails to decode is skipped rather than ending the track: one corrupt frame in
    /// an MP3 is a click, not a reason to stop.
    pub fn next_block(&mut self) -> Result<Option<Block>, EngineError> {
        loop {
            let packet = match self.format.next_packet() {
                Ok(packet) => packet,
                Err(SymphoniaError::IoError(e)) if e.kind() == IoErrorKind::UnexpectedEof => {
                    return Ok(None)
                }
                Err(SymphoniaError::ResetRequired) => {
                    // A chained stream changed its parameters. Rare for music; treat as the end
                    // rather than guess at a new decoder.
                    return Ok(None);
                }
                Err(e) => return Err(EngineError::Decode(e.to_string())),
            };
            if packet.track_id() != self.track_id {
                continue;
            }
            let ts = packet.ts();
            let dur = packet.dur();
            let decoded = match self.decoder.decode(&packet) {
                Ok(decoded) => decoded,
                Err(SymphoniaError::DecodeError(_)) => continue,
                Err(SymphoniaError::IoError(e)) if e.kind() == IoErrorKind::UnexpectedEof => {
                    return Ok(None)
                }
                Err(e) => return Err(EngineError::Decode(e.to_string())),
            };
            let spec = *decoded.spec();
            let frames = decoded.frames();
            if frames == 0 {
                continue;
            }
            let needs_new = self
                .scratch
                .as_ref()
                .is_none_or(|b| b.capacity() < decoded.capacity());
            if needs_new {
                self.scratch = Some(SampleBuffer::<f32>::new(decoded.capacity() as u64, spec));
            }
            let Some(scratch) = self.scratch.as_mut() else {
                continue;
            };
            scratch.copy_interleaved_ref(decoded);
            let channels = spec.channels.count().max(1);
            let mut samples = scratch.samples().to_vec();

            // Trim what precedes an accurate seek's target inside this packet.
            if let Some(target) = self.skip_until_ts {
                if ts + dur <= target {
                    continue;
                }
                if ts < target {
                    let drop_frames = ((target - ts) as usize).min(frames);
                    samples.drain(..drop_frames * channels);
                }
                self.skip_until_ts = None;
            }
            if samples.is_empty() {
                continue;
            }
            return Ok(Some(Block {
                samples,
                sample_rate: spec.rate,
                channels,
            }));
        }
    }

    /// Seeks to `position_ms`. Answers where it actually landed, in milliseconds.
    pub fn seek(&mut self, position_ms: u64) -> Result<u64, EngineError> {
        let time = Time::new(position_ms / 1000, (position_ms % 1000) as f64 / 1000.0);
        let seeked = self
            .format
            .seek(
                SeekMode::Accurate,
                SeekTo::Time {
                    time,
                    track_id: Some(self.track_id),
                },
            )
            .map_err(|e| EngineError::Decode(format!("seek failed: {e}")))?;
        self.decoder.reset();
        self.skip_until_ts = Some(seeked.required_ts);
        Ok(seeked.required_ts * 1000 / self.sample_rate.max(1) as u64)
    }
}

/// Decodes an audio file into a standard 16-bit PCM WAV file.
pub fn decode_to_wav<E: crate::env::Env + 'static>(src_path: &str, dst_path: &str) -> Result<(), EngineError> {
    let (source, hint) = super::source::open::<E>(src_path)?;
    let mut decoder = Decoder::open(source, hint.as_deref())?;
    let sample_rate = decoder.sample_rate();
    let channels = decoder.channels() as u16;

    let mut all_samples: Vec<i16> = Vec::new();
    while let Ok(Some(block)) = decoder.next_block() {
        for s in block.samples {
            let clamped = s.clamp(-1.0, 1.0);
            all_samples.push((clamped * 32_767.0) as i16);
        }
    }

    if all_samples.is_empty() {
        return Err(EngineError::Unsupported("empty audio".into()));
    }

    let data_len = (all_samples.len() * 2) as u32;
    let mut out = Vec::with_capacity(44 + data_len as usize);
    out.extend_from_slice(b"RIFF");
    out.extend_from_slice(&(36 + data_len).to_le_bytes());
    out.extend_from_slice(b"WAVEfmt ");
    out.extend_from_slice(&16u32.to_le_bytes());
    out.extend_from_slice(&1u16.to_le_bytes()); // PCM format
    out.extend_from_slice(&channels.to_le_bytes());
    out.extend_from_slice(&sample_rate.to_le_bytes());
    let byte_rate = sample_rate * channels as u32 * 2;
    out.extend_from_slice(&byte_rate.to_le_bytes());
    let block_align = channels * 2;
    out.extend_from_slice(&block_align.to_le_bytes());
    out.extend_from_slice(&16u16.to_le_bytes()); // bits per sample
    out.extend_from_slice(b"data");
    out.extend_from_slice(&data_len.to_le_bytes());
    for s in all_samples {
        out.extend_from_slice(&s.to_le_bytes());
    }

    std::fs::write(dst_path, out).map_err(|e| EngineError::Open(e.to_string()))?;
    Ok(())
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use std::io::Cursor;

    /// A 16-bit PCM WAV in memory: `seconds` of a sine on every channel.
    pub(crate) fn wav(rate: u32, channels: u16, seconds: f64, hz: f64) -> Vec<u8> {
        let frames = (rate as f64 * seconds) as u32;
        let data_len = frames * channels as u32 * 2;
        let mut out = Vec::with_capacity(44 + data_len as usize);
        out.extend_from_slice(b"RIFF");
        out.extend_from_slice(&(36 + data_len).to_le_bytes());
        out.extend_from_slice(b"WAVEfmt ");
        out.extend_from_slice(&16u32.to_le_bytes());
        out.extend_from_slice(&1u16.to_le_bytes()); // PCM
        out.extend_from_slice(&channels.to_le_bytes());
        out.extend_from_slice(&rate.to_le_bytes());
        out.extend_from_slice(&(rate * channels as u32 * 2).to_le_bytes());
        out.extend_from_slice(&(channels * 2).to_le_bytes());
        out.extend_from_slice(&16u16.to_le_bytes());
        out.extend_from_slice(b"data");
        out.extend_from_slice(&data_len.to_le_bytes());
        for i in 0..frames {
            let x = (2.0 * std::f64::consts::PI * hz * i as f64 / rate as f64).sin() * 0.5;
            let s = (x * i16::MAX as f64) as i16;
            for _ in 0..channels {
                out.extend_from_slice(&s.to_le_bytes());
            }
        }
        out
    }

    fn open(bytes: Vec<u8>) -> Decoder {
        Decoder::open(Box::new(Cursor::new(bytes)), Some("wav")).expect("a valid WAV opens")
    }

    fn drain(d: &mut Decoder) -> Vec<f32> {
        let mut all = Vec::new();
        while let Some(block) = d.next_block().expect("decodes") {
            all.extend(block.samples);
        }
        all
    }

    #[test]
    fn a_wav_decodes_to_every_frame_it_holds() {
        let mut d = open(wav(44_100, 2, 1.0, 440.0));
        assert_eq!(d.sample_rate(), 44_100);
        assert_eq!(d.channels(), 2);
        assert_eq!(d.duration_ms(), Some(1000));
        let samples = drain(&mut d);
        assert_eq!(samples.len(), 44_100 * 2);
        let peak = samples.iter().fold(0f32, |m, x| m.max(x.abs()));
        assert!((peak - 0.5).abs() < 0.01, "peak {peak}");
    }

    #[test]
    fn seeking_lands_where_asked_and_returns_only_what_follows() {
        let mut d = open(wav(48_000, 1, 2.0, 440.0));
        let landed = d.seek(1500).expect("seeks");
        assert_eq!(landed, 1500);
        let rest = drain(&mut d);
        assert_eq!(rest.len(), 24_000, "exactly the last half second");
    }

    #[test]
    fn garbage_is_unsupported_not_a_panic() {
        let err = Decoder::open(Box::new(Cursor::new(vec![7u8; 4096])), None)
            .err()
            .expect("refused");
        assert!(matches!(err, EngineError::Unsupported(_)), "{err:?}");
    }

    #[test]
    fn decode_to_wav_writes_playable_pcm_wav() {
        let tmp = std::env::temp_dir();
        let id = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos();
        let src = tmp.join(format!("input_{id}.wav"));
        let dst = tmp.join(format!("output_{id}.wav"));
        std::fs::write(&src, wav(44_100, 2, 0.5, 440.0)).unwrap();
        decode_to_wav::<crate::env::mock::MockEnv>(src.to_str().unwrap(), dst.to_str().unwrap()).unwrap();
        assert!(dst.is_file());
        let mut d = open(std::fs::read(&dst).unwrap());
        assert_eq!(d.sample_rate(), 44_100);
        assert_eq!(d.channels(), 2);
        assert!(!drain(&mut d).is_empty());
        let _ = std::fs::remove_file(src);
        let _ = std::fs::remove_file(dst);
    }
}
