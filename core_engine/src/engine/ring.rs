// core_engine/src/engine/ring.rs
//
// The one buffer between the player thread and the audio device: single producer, single consumer,
// no lock on either side.
//
// The consumer is the device callback, which runs on a real-time thread that must never wait — a
// mutex there is a dropout the moment the player thread holds it while decoding. So the two sides
// share two counters and nothing else, and each side is the only writer of its own counter.
//
// Also here, because it belongs to the consumer: what the device hears is decided at the moment it
// hears it. Pause, volume and the fade between them are applied in `fill`, not upstream, so they
// take effect within one device buffer instead of after everything already queued has played.
//
// And the anti-click ramps, for the same reason. A flush (seek, skip, stop) jumps from the middle of
// one waveform to the middle of another; with a declick length set, the first few milliseconds of
// what is being dropped are played fading out and what follows fades in, so the jump is a dip.

use std::cell::UnsafeCell;
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicUsize, Ordering};
use std::sync::Arc;

/// No flush pending. Any real flush target is a sample count, and this one cannot be reached.
const NO_FLUSH: usize = usize::MAX;

struct Shared {
    buf: Box<[UnsafeCell<f32>]>,
    /// Samples ever written. Only the producer stores it.
    head: AtomicUsize,
    /// Samples ever read (or dropped by a flush). Only the consumer stores it.
    tail: AtomicUsize,
    /// "Drop everything written before this count" — how the producer asks the consumer to forget
    /// what is queued (a seek, a stop) without touching `tail` itself.
    flush_to: AtomicUsize,
    transport: Transport,
}

// SAFETY: each cell is written by the producer only between `tail` and `head + free`, and read by the
// consumer only between `tail` and `head`; the Release/Acquire pairs on the counters order those
// accesses. `Producer` and `Consumer` are not `Clone`, so there is exactly one of each.
unsafe impl Sync for Shared {}
unsafe impl Send for Shared {}

/// What the device side reads every buffer: whether to play, and how loud.
///
/// Atomics rather than messages because the callback must not block or allocate, and these are the
/// only three things it needs to know.
pub struct Transport {
    paused: AtomicBool,
    /// Volume as `f32` bits, 0.0..=1.0.
    volume: AtomicU32,
    /// How many samples a full fade (silence ↔ volume) takes. 0 means "jump".
    fade_samples: AtomicU32,
    /// The shortest any change of level may take, and the length of the ramps across a flush. 0
    /// turns both off.
    declick_samples: AtomicU32,
}

impl Transport {
    fn new() -> Self {
        Transport {
            paused: AtomicBool::new(true),
            volume: AtomicU32::new(1.0f32.to_bits()),
            fade_samples: AtomicU32::new(0),
            declick_samples: AtomicU32::new(0),
        }
    }

    pub fn set_paused(&self, paused: bool) {
        self.paused.store(paused, Ordering::Release);
    }

    pub fn is_paused(&self) -> bool {
        self.paused.load(Ordering::Acquire)
    }

    pub fn set_volume(&self, volume: f32) {
        self.volume
            .store(volume.clamp(0.0, 1.0).to_bits(), Ordering::Release);
    }

    pub fn volume(&self) -> f32 {
        f32::from_bits(self.volume.load(Ordering::Acquire))
    }

    /// `samples` is in interleaved samples (frames × channels), which is the unit `fill` steps in.
    pub fn set_fade_samples(&self, samples: u32) {
        self.fade_samples.store(samples, Ordering::Release);
    }

    /// In interleaved samples too: a pause with no fade still ramps over this, and a flush fades out
    /// over it and the new audio in.
    pub fn set_declick_samples(&self, samples: u32) {
        self.declick_samples.store(samples, Ordering::Release);
    }
}

/// Creates a ring holding `capacity` interleaved samples.
pub fn ring(capacity: usize) -> (Producer, Consumer) {
    let capacity = capacity.max(1);
    let buf = (0..capacity)
        .map(|_| UnsafeCell::new(0.0f32))
        .collect::<Vec<_>>()
        .into_boxed_slice();
    let shared = Arc::new(Shared {
        buf,
        head: AtomicUsize::new(0),
        tail: AtomicUsize::new(0),
        flush_to: AtomicUsize::new(NO_FLUSH),
        transport: Transport::new(),
    });
    (
        Producer {
            shared: shared.clone(),
        },
        Consumer {
            shared,
            gain: 0.0,
            declick_in: 0,
            quick: false,
        },
    )
}

pub struct Producer {
    shared: Arc<Shared>,
}

impl Producer {
    /// Writes as much of `data` as fits and says how much that was. Never waits.
    pub fn push(&mut self, data: &[f32]) -> usize {
        let s = &*self.shared;
        let cap = s.buf.len();
        let head = s.head.load(Ordering::Relaxed);
        let tail = s.tail.load(Ordering::Acquire);
        let free = cap - head.wrapping_sub(tail);
        let n = free.min(data.len());
        for (i, &x) in data[..n].iter().enumerate() {
            // SAFETY: slots in [head, head + free) are not readable by the consumer until `head`
            // is published below.
            unsafe { *s.buf[(head + i) % cap].get() = x };
        }
        s.head.store(head.wrapping_add(n), Ordering::Release);
        n
    }

    /// Samples ever written — the position the next pushed sample will have.
    pub fn written(&self) -> usize {
        self.shared.head.load(Ordering::Relaxed)
    }

    /// Samples the device has consumed (heard, or dropped by a flush).
    pub fn played(&self) -> usize {
        self.shared.tail.load(Ordering::Acquire)
    }

    pub fn free(&self) -> usize {
        let s = &*self.shared;
        s.buf.len() - s.head.load(Ordering::Relaxed).wrapping_sub(s.tail.load(Ordering::Acquire))
    }

    pub fn capacity(&self) -> usize {
        self.shared.buf.len()
    }

    /// Asks the consumer to drop everything written so far. Anything pushed after this call is kept.
    pub fn flush(&mut self) {
        let head = self.shared.head.load(Ordering::Relaxed);
        self.shared.flush_to.store(head, Ordering::Release);
    }

    /// Whether a flush asked for is still waiting for the device to act on it.
    pub fn flush_pending(&self) -> bool {
        self.shared.flush_to.load(Ordering::Acquire) != NO_FLUSH
    }

    pub fn transport(&self) -> &Transport {
        &self.shared.transport
    }
}

pub struct Consumer {
    shared: Arc<Shared>,
    /// Where the fade currently is, 0.0..=volume. Consumer-only, so a plain field.
    gain: f32,
    /// Samples left of the fade-in after a flush.
    declick_in: usize,
    /// Since a flush, until audio plays again at full level: level changes take the declick's
    /// length, not the pause fade's. A skip pauses, flushes and plays the next song; that is not a
    /// pause the listener asked for, and the new song must not take the pause fade to come in.
    quick: bool,
}

impl Consumer {
    /// Fills `out` with what the device should play next: queued samples with pause and volume
    /// applied, silence where there is nothing (an underrun, or paused and faded out).
    ///
    /// Real-time safe: no lock, no allocation, no wait.
    pub fn fill(&mut self, out: &mut [f32]) {
        let s = &*self.shared;
        let cap = s.buf.len();

        let declick = s.transport.declick_samples.load(Ordering::Acquire) as usize;
        // Samples at the start of `out` already written: the dropped audio fading out.
        let mut faded_out = 0usize;

        let flush_to = s.flush_to.load(Ordering::Acquire);
        if flush_to != NO_FLUSH {
            let tail = s.tail.load(Ordering::Relaxed);
            // Only ever forwards: a flush target behind the tail has already been played past.
            let dropped = flush_to.wrapping_sub(tail);
            if dropped <= cap {
                if declick > 0 {
                    // What is being dropped is still in the buffer (the producer cannot write over
                    // anything before `tail`), so its first few milliseconds can be played out.
                    if self.gain > 0.0 {
                        let n = dropped.min(declick).min(out.len());
                        for (i, slot) in out[..n].iter_mut().enumerate() {
                            // SAFETY: [tail, flush_to) was published by the producer before the flush.
                            let x = unsafe { *s.buf[(tail + i) % cap].get() };
                            let ramp = 1.0 - (i + 1) as f32 / n as f32;
                            *slot = (x * self.gain * ramp).clamp(-1.0, 1.0);
                        }
                        faded_out = n;
                    }
                    self.declick_in = declick;
                    self.quick = true;
                }
                s.tail.store(flush_to, Ordering::Release);
            }
            // The producer may have flushed again since the load; only clear the one acted on.
            let _ = s.flush_to.compare_exchange(
                flush_to,
                NO_FLUSH,
                Ordering::AcqRel,
                Ordering::Relaxed,
            );
        }

        let paused = s.transport.paused.load(Ordering::Acquire);
        let volume = f32::from_bits(s.transport.volume.load(Ordering::Acquire));
        let fade = if self.quick {
            declick
        } else {
            (s.transport.fade_samples.load(Ordering::Acquire) as usize).max(declick)
        };
        let target = if paused { 0.0 } else { volume };
        let step = if fade == 0 {
            f32::INFINITY
        } else {
            volume.max(f32::EPSILON) / fade as f32
        };

        // Paused and already silent: hold the queue where it is, so resuming continues from here.
        if paused && self.gain <= 0.0 {
            out[faded_out..].fill(0.0);
            return;
        }

        let tail = s.tail.load(Ordering::Relaxed);
        let head = s.head.load(Ordering::Acquire);
        let available = head.wrapping_sub(tail);
        let mut taken = 0usize;
        for slot in out[faded_out..].iter_mut() {
            // Ramp towards the target one step per sample.
            if self.gain < target {
                self.gain = (self.gain + step).min(target);
            } else if self.gain > target {
                self.gain = (self.gain - step).max(target);
            }
            if paused && self.gain <= 0.0 {
                // Faded out mid-buffer: stop consuming, so nothing is lost on resume.
                *slot = 0.0;
                continue;
            }
            if taken < available {
                // SAFETY: [tail, head) was published by the producer with Release on `head`.
                let mut x = unsafe { *s.buf[(tail + taken) % cap].get() };
                taken += 1;
                if self.declick_in > 0 {
                    x *= 1.0 - self.declick_in as f32 / declick.max(1) as f32;
                    self.declick_in -= 1;
                }
                *slot = (x * self.gain).clamp(-1.0, 1.0);
            } else {
                *slot = 0.0;
            }
        }
        if self.quick && taken > 0 && !paused && self.gain >= target {
            self.quick = false;
        }
        s.tail.store(tail.wrapping_add(taken), Ordering::Release);
    }

    pub fn transport(&self) -> &Transport {
        &self.shared.transport
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn playing(cons: &Consumer) {
        cons.transport().set_paused(false);
    }

    #[test]
    fn what_is_pushed_comes_out_in_order() {
        let (mut prod, mut cons) = ring(8);
        playing(&cons);
        assert_eq!(prod.push(&[0.1, 0.2, 0.3]), 3);
        let mut out = [9.0f32; 5];
        cons.fill(&mut out);
        assert_eq!(out, [0.1, 0.2, 0.3, 0.0, 0.0], "an underrun is silence, not garbage");
        assert_eq!(prod.played(), 3);
    }

    #[test]
    fn a_full_ring_takes_only_what_fits_and_wraps_around() {
        let (mut prod, mut cons) = ring(4);
        playing(&cons);
        assert_eq!(prod.push(&[1.0, 2.0, 3.0, 4.0, 5.0]), 4);
        let mut out = [0.0f32; 3];
        cons.fill(&mut out);
        // 2.0 and 3.0 are past full scale, and the device side clamps.
        assert_eq!(out, [1.0, 1.0, 1.0]);
        // Wraps: three slots free again.
        assert_eq!(prod.push(&[0.5, 0.5, 0.5]), 3);
        let mut rest = [0.0f32; 4];
        cons.fill(&mut rest);
        assert_eq!(rest, [1.0, 0.5, 0.5, 0.5], "4.0 clamps to full scale; then the wrapped writes");
    }

    #[test]
    fn a_flush_drops_what_was_queued_and_keeps_what_comes_after() {
        let (mut prod, mut cons) = ring(16);
        playing(&cons);
        prod.push(&[0.1; 6]);
        prod.flush();
        prod.push(&[0.7, 0.8]);
        let mut out = [0.0f32; 4];
        cons.fill(&mut out);
        assert_eq!(out, [0.7, 0.8, 0.0, 0.0]);
        assert!(!prod.flush_pending());
    }

    #[test]
    fn paused_holds_the_queue_and_resuming_continues_from_there() {
        let (mut prod, mut cons) = ring(16);
        prod.push(&[0.25, 0.5]);
        let mut out = [1.0f32; 2];
        cons.fill(&mut out); // starts paused
        assert_eq!(out, [0.0, 0.0]);
        assert_eq!(prod.played(), 0, "nothing consumed while paused");
        playing(&cons);
        cons.fill(&mut out);
        assert_eq!(out, [0.25, 0.5]);
    }

    #[test]
    fn a_fade_ramps_instead_of_jumping() {
        let (mut prod, mut cons) = ring(64);
        cons.transport().set_fade_samples(4);
        playing(&cons);
        prod.push(&[1.0; 8]);
        let mut out = [0.0f32; 8];
        cons.fill(&mut out);
        assert_eq!(out[..4], [0.25, 0.5, 0.75, 1.0]);
        assert_eq!(out[4..], [1.0; 4]);
    }

    #[test]
    fn a_flush_with_declick_fades_the_old_audio_out_and_the_new_in() {
        let (mut prod, mut cons) = ring(64);
        cons.transport().set_declick_samples(4);
        playing(&cons);
        prod.push(&[1.0; 8]);
        let mut out = [0.0f32; 8];
        cons.fill(&mut out); // ramps up over the declick, then full
        assert_eq!(out[4..], [1.0; 4]);
        prod.push(&[1.0; 8]);
        prod.flush();
        prod.push(&[0.5; 8]);
        let mut out = [0.0f32; 12];
        cons.fill(&mut out);
        // Four of the dropped samples, falling to silence; then the new ones rising from it.
        assert_eq!(out[..4], [0.75, 0.5, 0.25, 0.0]);
        assert_eq!(out[4..8], [0.0, 0.125, 0.25, 0.375]);
        assert_eq!(out[8..], [0.5; 4]);
        assert_eq!(prod.played(), 8 + 8 + 8, "the dropped audio counts as gone, not as queued");
    }

    #[test]
    fn after_a_flush_the_next_song_comes_in_at_the_declicks_pace_not_the_pause_fades() {
        let (mut prod, mut cons) = ring(64);
        cons.transport().set_fade_samples(8);
        cons.transport().set_declick_samples(2);
        playing(&cons);
        prod.push(&[1.0; 16]);
        let mut out = [0.0f32; 16];
        cons.fill(&mut out);
        assert_eq!(out[7], 1.0, "a resume takes the eight samples of the fade");
        // A skip: flush and pause, then the next song arrives and plays.
        prod.flush();
        cons.transport().set_paused(true);
        let mut out = [0.0f32; 4];
        cons.fill(&mut out);
        prod.push(&[1.0; 8]);
        playing(&cons);
        cons.fill(&mut out);
        assert_eq!(out, [0.0, 0.5, 1.0, 1.0]);
        // A pause the listener asks for still fades over the full length.
        cons.transport().set_paused(true);
        cons.fill(&mut out);
        assert_eq!(out, [0.875, 0.75, 0.625, 0.5]);
    }

    #[test]
    fn a_pause_with_no_fade_still_ramps_over_the_declick() {
        let (mut prod, mut cons) = ring(64);
        cons.transport().set_declick_samples(4);
        playing(&cons);
        prod.push(&[1.0; 32]);
        let mut out = [0.0f32; 8];
        cons.fill(&mut out);
        cons.transport().set_paused(true);
        let mut out = [0.0f32; 6];
        cons.fill(&mut out);
        assert_eq!(out, [0.75, 0.5, 0.25, 0.0, 0.0, 0.0]);
        assert!(out.windows(2).all(|w| w[1] <= w[0]), "monotonic");
        assert_eq!(prod.played(), 8 + 3, "consumption stops once silent");
    }

    #[test]
    fn volume_scales_and_never_exceeds_full_scale() {
        let (mut prod, mut cons) = ring(8);
        cons.transport().set_volume(0.5);
        playing(&cons);
        prod.push(&[1.0, -1.0]);
        let mut out = [0.0f32; 2];
        cons.fill(&mut out);
        assert_eq!(out, [0.5, -0.5]);
    }
}
