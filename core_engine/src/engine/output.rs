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

    /// The format the device runs at now, when the output can change it on its own (a new default
    /// device, a reconnection). The player compares it with what it converts to; `None` means
    /// "whatever `start` answered".
    fn current_format(&self) -> Option<Format> {
        None
    }

    /// Opens the device again if it is running, keeping what it plays. Does nothing where nothing
    /// outside the output can stop it.
    fn reopen(&mut self) {}
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
    /// Set by a test to play a device that moved to another rate ([`NullOutput::switchable`]).
    moved: Option<Arc<std::sync::Mutex<Option<Format>>>>,
    /// How many times it was asked to reopen, for a test ([`NullOutput::counting_reopens`]).
    reopens: Arc<std::sync::atomic::AtomicUsize>,
}

/// A test's handle for [`NullOutput::switchable`]: "the device now runs at this format".
#[derive(Clone)]
pub struct FormatSwitch(Arc<std::sync::Mutex<Option<Format>>>);

impl FormatSwitch {
    pub fn set(&self, format: Format) {
        if let Ok(mut f) = self.0.lock() {
            *f = Some(format);
        }
    }
}

impl NullOutput {
    pub fn new(pace: Pace) -> Self {
        NullOutput {
            pace,
            fixed: None,
            running: Arc::new(AtomicBool::new(false)),
            thread: None,
            moved: None,
            reopens: Arc::default(),
        }
    }

    /// A counter of the times it was asked to reopen.
    pub fn counting_reopens(&self) -> Arc<std::sync::atomic::AtomicUsize> {
        self.reopens.clone()
    }

    /// An output whose format a test can change while it plays, as a real one does when the device
    /// changes under it.
    pub fn switchable(mut self) -> (Self, FormatSwitch) {
        let cell = Arc::new(std::sync::Mutex::new(None));
        self.moved = Some(cell.clone());
        (self, FormatSwitch(cell))
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
                ((format.sample_rate as f64 * format.channels as f64 * speed * 0.010) as usize)
                    .max(1)
            }
        };
        self.thread = Some(
            std::thread::Builder::new()
                .name("rustify-null-output".into())
                .spawn(move || {
                    let mut buf = vec![0f32; chunk];
                    while running.load(Ordering::Acquire) {
                        consumer.fill(&mut buf);
                        tap_push(&buf, format.channels, format.sample_rate);
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
        tap_reset();
    }

    fn current_format(&self) -> Option<Format> {
        self.moved
            .as_ref()
            .and_then(|m| m.lock().ok().and_then(|f| *f))
    }

    fn reopen(&mut self) {
        self.reopens.fetch_add(1, Ordering::Relaxed);
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
pub use cpal_output::{default_device_name, CpalOutput};

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
    ///
    /// It follows the device (docs `25` §10, F1): the stream is rebuilt on the default device when
    /// cpal reports an error (headphones or a DAC unplugged) or the system's default changes (Windows'
    /// output picker). Without that the app went silent until restarted, or kept playing to the old
    /// device. The ring's consumer is shared with the callback behind a mutex the callback only ever
    /// `try_lock`s, so a rebuild costs at most one buffer of silence and the callback never waits.
    pub struct CpalOutput {
        stop_tx: Option<mpsc::Sender<()>>,
        thread: Option<JoinHandle<()>>,
        /// The running format, packed as `rate << 8 | channels`; 0 before the first start.
        format: Arc<std::sync::atomic::AtomicU64>,
        /// Set when the stream died, or to have the output thread open it again regardless.
        lost: Arc<AtomicBool>,
    }

    impl CpalOutput {
        pub fn new() -> Self {
            CpalOutput {
                stop_tx: None,
                thread: None,
                format: Arc::new(std::sync::atomic::AtomicU64::new(0)),
                lost: Arc::new(AtomicBool::new(false)),
            }
        }
    }

    fn pack(f: Format) -> u64 {
        ((f.sample_rate as u64) << 8) | (f.channels as u64 & 0xff)
    }

    fn unpack(v: u64) -> Option<Format> {
        (v != 0).then_some(Format {
            sample_rate: (v >> 8) as u32,
            channels: (v & 0xff) as usize,
        })
    }

    /// How often the output thread looks for a lost device or a new default.
    const WATCH_EVERY: Duration = Duration::from_millis(500);

    impl Default for CpalOutput {
        fn default() -> Self {
            Self::new()
        }
    }

    /// The device format closest to `wanted`: the track's rate if the device takes it at the wanted
    /// channel count (no resampling at all), else the device's own default.
    fn choose(
        device: &cpal::Device,
        wanted: Format,
    ) -> Result<cpal::SupportedStreamConfig, EngineError> {
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

    type SharedConsumer = Arc<std::sync::Mutex<Consumer>>;

    fn build<T>(
        device: &cpal::Device,
        config: &cpal::StreamConfig,
        consumer: SharedConsumer,
        lost: Arc<AtomicBool>,
    ) -> Result<cpal::Stream, EngineError>
    where
        T: cpal::SizedSample + cpal::FromSample<f32>,
    {
        // Grown once to the largest buffer the device asks for, then reused: no allocation per
        // callback after the first few.
        let mut scratch: Vec<f32> = Vec::new();
        let tap_channels = config.channels as usize;
        let tap_sample_rate = config.sample_rate.0;
        device
            .build_output_stream(
                config,
                move |data: &mut [T], _: &cpal::OutputCallbackInfo| {
                    if scratch.len() < data.len() {
                        scratch.resize(data.len(), 0.0);
                    }
                    let buf = &mut scratch[..data.len()];
                    // Never wait in the callback: held only while a stream is being swapped.
                    match consumer.try_lock() {
                        Ok(mut c) => c.fill(buf),
                        Err(_) => buf.fill(0.0),
                    }
                    tap_push(buf, tap_channels, tap_sample_rate);
                    for (out, x) in data.iter_mut().zip(buf.iter()) {
                        *out = T::from_sample(*x);
                    }
                },
                move |e| {
                    lost.store(true, Ordering::Release);
                    <crate::env::android::AndroidEnv as crate::env::Env>::log(
                        crate::env::LogLevel::Warn,
                        "Engine",
                        &format!("output stream error, reopening: {e}"),
                    )
                },
                None,
            )
            .map_err(|e| EngineError::Output(format!("could not open the device: {e}")))
    }

    /// The name of the system's default output, which is the one the engine plays through: it moves
    /// there when the default does.
    pub fn default_device_name() -> Option<String> {
        cpal::default_host()
            .default_output_device()
            .and_then(|d| d.name().ok())
    }

    /// Opens the default device and starts it pulling from `consumer`. Runs on the output thread.
    fn open_device(
        wanted: Format,
        consumer: SharedConsumer,
        lost: Arc<AtomicBool>,
    ) -> Result<(cpal::Stream, Format), EngineError> {
        let host = cpal::default_host();
        let device = host
            .default_output_device()
            .ok_or_else(|| EngineError::Output("no output device".into()))?;
        let supported = choose(&device, wanted)?;
        let sample_format = supported.sample_format();
        let config: cpal::StreamConfig = supported.into();
        let stream = match sample_format {
            cpal::SampleFormat::F32 => build::<f32>(&device, &config, consumer, lost)?,
            cpal::SampleFormat::I16 => build::<i16>(&device, &config, consumer, lost)?,
            cpal::SampleFormat::U16 => build::<u16>(&device, &config, consumer, lost)?,
            cpal::SampleFormat::I32 => build::<i32>(&device, &config, consumer, lost)?,
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
            let current = self.format.clone();
            let lost = self.lost.clone();
            let thread = std::thread::Builder::new()
                .name("rustify-audio-output".into())
                .spawn(move || {
                    let consumer: SharedConsumer = Arc::new(std::sync::Mutex::new(consumer));
                    lost.store(false, Ordering::Release);
                    let (first, format) = match open_device(wanted, consumer.clone(), lost.clone())
                    {
                        Ok(opened) => opened,
                        Err(e) => {
                            let _ = ready_tx.send(Err(e));
                            return;
                        }
                    };
                    let mut stream = Some(first);
                    current.store(pack(format), Ordering::Release);
                    let _ = ready_tx.send(Ok(format));
                    let mut device = default_device_name();
                    // Until stop() (or the handle is dropped, which closes the channel): every
                    // WATCH_EVERY, reopen on the default device if the stream died or the default
                    // moved. Asking for the format already in use keeps the player's conversion valid
                    // whenever the new device can take it.
                    while let Err(mpsc::RecvTimeoutError::Timeout) =
                        stop_rx.recv_timeout(WATCH_EVERY)
                    {
                        let now_default = default_device_name();
                        let moved = now_default.is_some() && now_default != device;
                        if !lost.load(Ordering::Acquire) && !moved {
                            continue;
                        }
                        let running = unpack(current.load(Ordering::Acquire)).unwrap_or(format);
                        // The old stream goes first: two open on one device fight over it.
                        stream = None;
                        lost.store(false, Ordering::Release);
                        match open_device(running, consumer.clone(), lost.clone()) {
                            Ok((reopened, f)) => {
                                stream = Some(reopened);
                                current.store(pack(f), Ordering::Release);
                                device = now_default;
                                <crate::env::android::AndroidEnv as crate::env::Env>::log(
                                    crate::env::LogLevel::Info,
                                    "Engine",
                                    &format!(
                                        "output moved to {:?} at {} Hz",
                                        device, f.sample_rate
                                    ),
                                );
                            }
                            Err(e) => {
                                // No device right now (all unplugged): try again on the next turn.
                                <crate::env::android::AndroidEnv as crate::env::Env>::log(
                                    crate::env::LogLevel::Warn,
                                    "Engine",
                                    &format!("no output yet: {e}"),
                                );
                                lost.store(true, Ordering::Release);
                            }
                        }
                    }
                    drop(stream);
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
            tap_reset();
        }

        fn current_format(&self) -> Option<Format> {
            unpack(self.format.load(Ordering::Acquire))
        }

        fn reopen(&mut self) {
            self.lost.store(true, Ordering::Release);
        }
    }

    impl Drop for CpalOutput {
        fn drop(&mut self) {
            self.stop();
        }
    }
}


// =============================================================================
// TAP — live copy of what reaches the device, for the audio lab
// =============================================================================

pub const TAP_CAPACITY: usize = 16_384;

pub struct AudioTap {
    sample_rate: u32,
    ring: Vec<f32>,
    write_pos: usize,
    total_written: usize,
}

impl AudioTap {
    pub fn new(capacity: usize) -> Self {
        AudioTap {
            sample_rate: 0,
            ring: vec![0.0; capacity.max(1)],
            write_pos: 0,
            total_written: 0,
        }
    }

    pub fn reset(&mut self) {
        self.sample_rate = 0;
        self.ring.fill(0.0);
        self.write_pos = 0;
        self.total_written = 0;
    }

    pub fn push(&mut self, interleaved: &[f32], channels: usize, sample_rate: u32) {
        if channels == 0 || interleaved.is_empty() {
            return;
        }
        self.sample_rate = sample_rate;
        for frame in interleaved.chunks_exact(channels) {
            let sum: f32 = frame.iter().sum();
            self.ring[self.write_pos] = sum / (channels as f32);
            self.write_pos = (self.write_pos + 1) % self.ring.len();
            self.total_written = self.total_written.saturating_add(1);
        }
    }

    pub fn latest(&self, n: usize) -> (u32, Vec<f32>) {
        let rate = self.sample_rate;
        if n == 0 {
            return (rate, Vec::new());
        }
        let cap = self.ring.len();
        let count = n.min(cap).min(self.total_written);
        let mut out = vec![0.0; n];
        if count == 0 {
            return (rate, out);
        }
        let start_out = n - count;
        let start_idx = (self.write_pos + cap - (count % cap)) % cap;
        for i in 0..count {
            out[start_out + i] = self.ring[(start_idx + i) % cap];
        }
        (rate, out)
    }
}

static AUDIO_TAP: std::sync::Mutex<Option<AudioTap>> = std::sync::Mutex::new(None);

pub fn tap_push(interleaved: &[f32], channels: usize, sample_rate: u32) {
    if let Ok(mut guard) = AUDIO_TAP.try_lock() {
        let tap = guard.get_or_insert_with(|| AudioTap::new(TAP_CAPACITY));
        tap.push(interleaved, channels, sample_rate);
    }
}

pub fn tap_reset() {
    if let Ok(mut guard) = AUDIO_TAP.lock() {
        if let Some(tap) = guard.as_mut() {
            tap.reset();
        }
    }
}

pub fn engine_tap(n: usize) -> (u32, Vec<f32>) {
    if let Ok(guard) = AUDIO_TAP.lock() {
        if let Some(tap) = guard.as_ref() {
            return tap.latest(n);
        }
    }
    (0, vec![0.0; n])
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn audio_tap_returns_latest_in_order() {
        let mut tap = AudioTap::new(8);
        tap.push(&[1.0, 1.0, 2.0, 2.0, 3.0, 3.0, 4.0, 4.0], 2, 48000);
        let (rate, samples) = tap.latest(4);
        assert_eq!(rate, 48000);
        assert_eq!(samples, vec![1.0, 2.0, 3.0, 4.0]);

        tap.push(&[5.0, 6.0, 7.0, 8.0, 9.0, 10.0], 1, 48000);
        let (rate, samples) = tap.latest(4);
        assert_eq!(rate, 48000);
        assert_eq!(samples, vec![7.0, 8.0, 9.0, 10.0]);

        let (rate, all) = tap.latest(8);
        assert_eq!(rate, 48000);
        assert_eq!(all, vec![3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0]);

        let (rate, padded) = tap.latest(10);
        assert_eq!(rate, 48000);
        assert_eq!(padded, vec![0.0, 0.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0]);

        tap.reset();
        let (rate, empty) = tap.latest(4);
        assert_eq!(rate, 0);
        assert_eq!(empty, vec![0.0, 0.0, 0.0, 0.0]);
    }
}
