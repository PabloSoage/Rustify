// core_engine/src/engine/bpm.rs
//
// Tempo (BPM) analysis of an audio file or stream through Symphonia and [`crate::tempo`].

use crate::env::Env;
use crate::tempo::{Estimate, Tempo};
use super::decode::Decoder;
use super::source;
use super::EngineError;

/// Analyzes up to 60 seconds of audio from `uri` (file or URL) and estimates its tempo.
///
/// Returns `Ok(Some(Estimate))` if a clear beat is found, `Ok(None)` if no beat or under 6s of audio,
/// or `Err(EngineError)` on I/O or decode failure.
pub fn analyze<E: Env + 'static>(uri: &str) -> Result<Option<Estimate>, EngineError> {
    let (source, hint) = source::open::<E>(uri)?;
    let mut decoder = Decoder::open(source, hint.as_deref())?;

    // If the track is long (> 2 minutes), start 1/3 of the way in, past the intro.
    if let Some(dur_ms) = decoder.duration_ms() {
        if dur_ms > 120_000 {
            let _ = decoder.seek(dur_ms / 3);
        }
    }

    let rate = decoder.sample_rate();
    let mut tempo = Tempo::new(rate);

    while tempo.seconds() < 60.0 {
        match decoder.next_block() {
            Ok(Some(block)) => {
                if block.channels == 1 {
                    tempo.push(&block.samples);
                } else {
                    let ch = block.channels as f32;
                    let mono: Vec<f32> = block
                        .samples
                        .chunks_exact(block.channels)
                        .map(|chunk| chunk.iter().sum::<f32>() / ch)
                        .collect();
                    tempo.push(&mono);
                }
            }
            Ok(None) => break,
            Err(e) => {
                if tempo.seconds() >= 6.0 {
                    break;
                }
                return Err(e);
            }
        }
    }

    Ok(tempo.estimate())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::env::mock::MockEnv;

    /// Generates a synthetic 16-bit PCM WAV at the specified BPM and duration.
    pub fn synthetic_wav(bpm: f32, seconds: f32, rate: u32) -> Vec<u8> {
        let n = (seconds * rate as f32) as usize;
        let beat = 60.0 / bpm * rate as f32;
        let mut seed = 12345u32;
        let mut noise = move || {
            seed = seed.wrapping_mul(1_103_515_245).wrapping_add(12345);
            (seed >> 16) as f32 / 32_768.0 - 1.0
        };

        let mut samples = Vec::with_capacity(n);
        for i in 0..n {
            let phase = i as f32 % beat;
            let kick = if phase < 0.03 * rate as f32 {
                let t = phase / rate as f32;
                (2.0 * std::f32::consts::PI * 60.0 * t).sin() * (-t * 80.0).exp()
            } else {
                0.0
            };
            let off = (i as f32 + beat / 2.0) % beat;
            let hat = if off < 0.01 * rate as f32 {
                noise() * 0.3 * (-(off / rate as f32) * 300.0).exp()
            } else {
                0.0
            };
            let sample_f = 0.8 * kick + hat + noise() * 0.01;
            let clamped = sample_f.clamp(-1.0, 1.0);
            let s = (clamped * 32_767.0) as i16;
            samples.push(s);
        }

        let channels = 1u16;
        let data_len = (samples.len() * 2) as u32;
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
        for s in samples {
            out.extend_from_slice(&s.to_le_bytes());
        }
        out
    }

    #[test]
    fn synthetic_120_bpm_wav_analyzes_as_120() {
        let wav_data = synthetic_wav(120.0, 15.0, 44_100);
        let dir = std::env::temp_dir();
        let path = dir.join("test_120_bpm.wav");
        std::fs::write(&path, &wav_data).unwrap();

        let path_str = path.to_str().unwrap();
        let res = analyze::<MockEnv>(path_str).expect("analyze succeeds");
        let est = res.expect("produces an estimate");
        assert!((est.bpm - 120.0).abs() <= 1.0, "expected ~120 BPM, got {}", est.bpm);
        assert!(est.confidence >= 2.0, "confidence {} should be >= 2.0", est.confidence);

        let _ = std::fs::remove_file(&path);
    }
}
