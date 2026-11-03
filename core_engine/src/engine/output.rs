// core_engine/src/engine/output.rs
//
// The audio device, behind one small trait.
//
// An output does one thing: from the moment it is started, it calls `Consumer::fill` whenever the
// device wants samples. Everything the listener can change while it plays — pause, volume, the fade —
// is in the ring's `Transport`, read inside `fill`, so no output implements any of it.
//
// Two implementations: `CpalOutput` (WASAPI on Windows, ALSA/PipeWire on Linux, CoreAudio on macOS
// and iOS), compiled with the `desktop-audio` feature; and `NullOutput`, which consumes at a chosen
// pace into nothing and is what the tests run against — CI has no sound card, and a test that needs
// one is a test that does not run.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread::JoinHandle;
use std::time::Duration;

use super::ring::Consumer;
use super::EngineError;

/// What reaches the device. Always interleaved `f32`; the output converts if the device wants else.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Format {
    pub sample_rate: u32,
    pub channels: usize,
}

pub trait AudioOutput: Send {
    /// Starts pulling from `consumer`. `wanted` is the first track's own format; the output may pick
    /// another (a shared-mode device runs at its mixer's rate), and answers what it chose — the
    /// player converts every track to that.
    fn start(&mut self, wanted: Format, consumer: Consumer) -> Result<Format, EngineError>;

    /// Stops pulling and releases the device. Starting again afterwards is allowed.
    fn stop(&mut self);
}

// =============================================================================
// NULL
// =============================================================================

/// How fast a [`NullOutput`] consumes.
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum Pace {
    /// As fast as samples arrive. For tests about *what* plays, not *when*.
    Instant,
    /// Like a device: `speed` × real time (1.0 is real time).
    RealTime { speed: f64 },
}

/// An output with no device: pulls from the ring on its own thread and throws the samples away.
pub struct NullOutput {
    pace: Pace,
    /// The format it accepts. `None` takes whatever it is offered, like a device with a resampler.
    fixed: Option<Format>,
    running: Arc<AtomicBool>,
    thread: Option<JoinHandle<()>>,
}

impl NullOutput {
    pub fn new(pace: Pace) -> Self {
        NullOutput {
            pace,
            fixed: None,
            running: Arc::new(AtomicBool::new(false)),
            thread: None,
        }
    }

    /// Pretends to be a device that only runs at `format`, so tests exercise the conversion path.
    pub fn with_format(mut self, format: Format) -> Self {
        self.fixed = Some(format);
        self
    }
}

impl AudioOutput for NullOutput {
    fn start(&mut self, wanted: Format, mut consumer: Consumer) -> Result<Format, EngineError> {
        self.stop();
        let format = self.fixed.unwrap_or(wanted);
        let running = self.running.clone();
        running.store(true, Ordering::Release);
        let pace = self.pace;
        let period = Duration::from_millis(10);
        let chunk = match pace {
            Pace::Instant => 4096,
            Pace::RealTime { speed } => {
                ((format.sample_rate as f64 * format.channels as f64 * speed * 0.010) as usize).max(1)
            }
        };
        self.thread = Some(
            std::thread::Builder::new()
                .name("rustify-null-output".into())
                .spawn(move || {
                    let mut buf = vec![0f32; chunk];
                    while running.load(Ordering::Acquire) {
                        consumer.fill(&mut buf);
                        match pace {
                            Pace::Instant => std::thread::sleep(Duration::from_micros(200)),
                            Pace::RealTime { .. } => std::thread::sleep(period),
                        }
                    }
                })
                .map_err(|e| EngineError::Output(e.to_string()))?,
        );
        Ok(format)
    }

    fn stop(&mut self) {
        self.running.store(false, Ordering::Release);
        if let Some(thread) = self.thread.take() {
            let _ = thread.join();
        }
    }
}

impl Drop for NullOutput {
    fn drop(&mut self) {
        self.stop();
    }
}

// =============================================================================
// CPAL
// =============================================================================

#[cfg(feature = "desktop-audio")]
pub use cpal_output::CpalOutput;

#[cfg(feature = "desktop-audio")]
mod cpal_output {
    use super::*;
    use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
    use std::sync::mpsc;

    /// The default output device of the default host.
    ///
    /// The `cpal::Stream` is not `Send` on every host (CoreAudio, ASIO), so it lives on a thread of
    /// its own that builds it, keeps it alive and drops it when told to stop. The player only ever
    /// holds this handle.
    pub struct CpalOutput {
        stop_tx: Option<mpsc::Sender<()>>,
        thread: Option<JoinHandle<()>>,
    }

    impl CpalOutput {
        pub fn new() -> Self {
            CpalOutput {
                stop_tx: None,
                thread: None,
            }
        }
    }

    impl Default for CpalOutput {
        fn default() -> Self {
            Self::new()
        }
    }

    /// The device format closest to `wanted`: the track's rate if the device takes it at the wanted
    /// channel count (no resampling at all), else the device's own default.
    fn choose(device: &cpal::Device, wanted: Format) -> Result<cpal::SupportedStreamConfig, EngineError> {
        if let Ok(ranges) = device.supported_output_configs() {
            for range in ranges {
                if range.channels() as usize == wanted.channels
                    && range.sample_format() == cpal::SampleFormat::F32
                    && (range.min_sample_rate().0..=range.max_sample_rate().0)
                        .contains(&wanted.sample_rate)
                {
                    return Ok(range.with_sample_rate(cpal::SampleRate(wanted.sample_rate)));
                }
            }
        }
        device
            .default_output_config()
            .map_err(|e| EngineError::Output(format!("no output format: {e}")))
    }

    fn build<T>(
        device: &cpal::Device,
        config: &cpal::StreamConfig,
        mut consumer: Consumer,
    ) -> Result<cpal::Stream, EngineError>
    where
        T: cpal::SizedSample + cpal::FromSample<f32>,
    {
        // Grown once to the largest buffer the device asks for, then reused: no allocation per
        // callback after the first few.
        let mut scratch: Vec<f32> = Vec::new();
        device
            .build_output_stream(
                config,
                move |data: &mut [T], _: &cpal::OutputCallbackInfo| {
                    if scratch.len() < data.len() {
                        scratch.resize(data.len(), 0.0);
                    }
                    let buf = &mut scratch[..data.len()];
                    consumer.fill(buf);
                    for (out, x) in data.iter_mut().zip(buf.iter()) {
                        *out = T::from_sample(*x);
                    }
                },
                |e| {
                    <crate::env::android::AndroidEnv as crate::env::Env>::log(
                        crate::env::LogLevel::Warn,
                        "Engine",
                        &format!("output stream error: {e}"),
                    )
                },
                None,
            )
            .map_err(|e| EngineError::Output(format!("could not open the device: {e}")))
    }

    /// Opens the default device and starts it pulling from `consumer`. Runs on the output thread.
    fn open_device(
        wanted: Format,
        consumer: Consumer,
    ) -> Result<(cpal::Stream, Format), EngineError> {
        let host = cpal::default_host();
        let device = host
            .default_output_device()
            .ok_or_else(|| EngineError::Output("no output device".into()))?;
        let supported = choose(&device, wanted)?;
        let sample_format = supported.sample_format();
        let config: cpal::StreamConfig = supported.into();
        let stream = match sample_format {
            cpal::SampleFormat::F32 => build::<f32>(&device, &config, consumer)?,
            cpal::SampleFormat::I16 => build::<i16>(&device, &config, consumer)?,
            cpal::SampleFormat::U16 => build::<u16>(&device, &config, consumer)?,
            cpal::SampleFormat::I32 => build::<i32>(&device, &config, consumer)?,
            other => {
                return Err(EngineError::Output(format!(
                    "unsupported sample format {other:?}"
                )))
            }
        };
        stream
            .play()
            .map_err(|e| EngineError::Output(format!("could not start: {e}")))?;
        Ok((
            stream,
            Format {
                sample_rate: config.sample_rate.0,
                channels: config.channels as usize,
            },
        ))
    }

    impl AudioOutput for CpalOutput {
        fn start(&mut self, wanted: Format, consumer: Consumer) -> Result<Format, EngineError> {
            self.stop();
            let (ready_tx, ready_rx) = mpsc::channel::<Result<Format, EngineError>>();
            let (stop_tx, stop_rx) = mpsc::channel::<()>();
            let thread = std::thread::Builder::new()
                .name("rustify-audio-output".into())
                .spawn(move || {
                    match open_device(wanted, consumer) {
                        Ok((stream, format)) => {
                            let _ = ready_tx.send(Ok(format));
                            // Parked until stop() — or until the handle is dropped, which also
                            // closes the channel.
                            let _ = stop_rx.recv();
                            drop(stream);
                        }
                        Err(e) => {
                            let _ = ready_tx.send(Err(e));
                        }
                    }
                })
                .map_err(|e| EngineError::Output(e.to_string()))?;
            let format = ready_rx
                .recv_timeout(Duration::from_secs(5))
                .map_err(|_| EngineError::Output("the device did not open in 5 s".into()))??;
            self.stop_tx = Some(stop_tx);
            self.thread = Some(thread);
            Ok(format)
        }

        fn stop(&mut self) {
            if let Some(tx) = self.stop_tx.take() {
                let _ = tx.send(());
            }
            if let Some(thread) = self.thread.take() {
                let _ = thread.join();
            }
        }
    }

    impl Drop for CpalOutput {
        fn drop(&mut self) {
            self.stop();
        }
    }
}
