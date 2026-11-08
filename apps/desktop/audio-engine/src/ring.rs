//! SPSC interleaved i16 ring — the engine's only shared state between the
//! stdin reader thread (writer) and the CPAL real-time callback (reader).
//!
//! Discipline: the writer owns `head`, the reader owns `tail` — no lock, no
//! allocation on the real-time path (per-slot atomics keep this in safe
//! Rust; at audio rates the atomic cost is negligible). The latency contract
//! (Feature 2026-11e, the TikTok `setAudioDropPolicyByMs` analog) lives
//! here:
//! - the writer drops the NEWEST frame when the ring is full (never blocks);
//! - the reader SHEDS the oldest backlog above the shed trigger, keeping the
//!   played audio near the newest arrival (drop late, never stall);
//! - an empty ring drains as silence — the caller counts the underrun.

use std::sync::atomic::{AtomicI16, AtomicUsize, Ordering};

/// Ring capacity in frames (100 ms @48 kHz stereo).
pub const RING_CAPACITY_FRAMES: usize = 4_800;
/// Backlog above this (60 ms) is shed on the next render callback. Tuned
/// 2026-11-08 from the first live cast: 30 ms shed on every Wi-Fi burst
/// (chunks arrive stacked) crackled — bursts up to ~60 ms now absorb
/// without shedding.
pub const SHED_TRIGGER_FRAMES: usize = 2_880;
/// Frames kept after a shed (40 ms) — the playout buffer. 20 ms underran on
/// chunk gaps >20 ms; 40 ms absorbs the common burst gaps.
pub const SHED_KEEP_FRAMES: usize = 1_920;

pub struct AudioRing {
    buf: Box<[AtomicI16]>,
    channels: usize,
    capacity_frames: usize,
    head: AtomicUsize, // frames ever written (writer-owned)
    tail: AtomicUsize, // frames ever consumed (reader-owned)
}

impl AudioRing {
    pub fn new(capacity_frames: usize, channels: usize) -> Self {
        Self {
            buf: (0..capacity_frames * channels)
                .map(|_| AtomicI16::new(0))
                .collect::<Vec<_>>()
                .into_boxed_slice(),
            channels,
            capacity_frames,
            head: AtomicUsize::new(0),
            tail: AtomicUsize::new(0),
        }
    }

    /// Push interleaved i16 samples (`len % channels == 0`). When the ring is
    /// full the newest frames are dropped (never blocks, never stalls).
    /// Returns the number of dropped frames.
    pub fn push(&self, interleaved: &[i16]) -> usize {
        let channels = self.channels;
        let frames = interleaved.len() / channels;
        if frames == 0 {
            return 0;
        }
        let mut head = self.head.load(Ordering::Relaxed);
        let read = self.tail.load(Ordering::Acquire);
        let mut dropped = 0usize;
        for frame in 0..frames {
            if head - read >= self.capacity_frames {
                dropped += 1; // full — drop the newest, keep draining
                continue;
            }
            let base = (head % self.capacity_frames) * channels;
            for c in 0..channels {
                self.buf[base + c].store(interleaved[frame * channels + c], Ordering::Release);
            }
            head += 1;
            self.head.store(head, Ordering::Release);
        }
        dropped
    }

    /// Drain interleaved f32 into `out` (converted i16 → f32). Sheds the
    /// backlog above [`SHED_TRIGGER_FRAMES`] down to [`SHED_KEEP_FRAMES`].
    /// Returns the number of frames drained; the caller fills the rest with
    /// silence (and counts an underrun when that happens).
    pub fn drain_f32(&self, out: &mut [f32]) -> usize {
        let channels = self.channels;
        let max_frames = out.len() / channels;
        let mut read = self.tail.load(Ordering::Acquire);
        let head = self.head.load(Ordering::Acquire);
        let depth_frames = head - read;

        let frames = if depth_frames > SHED_TRIGGER_FRAMES {
            // Shed the oldest backlog — the reader owns `tail` (pure SPSC).
            let kept = SHED_KEEP_FRAMES.min(depth_frames);
            let new_read = head - kept;
            self.tail.store(new_read, Ordering::Release);
            read = new_read;
            kept.min(max_frames)
        } else {
            depth_frames.min(max_frames)
        };

        for frame in 0..frames {
            let base = ((read + frame) % self.capacity_frames) * channels;
            for c in 0..channels {
                out[frame * channels + c] =
                    self.buf[base + c].load(Ordering::Acquire) as f32 / 32768.0;
            }
        }
        self.tail.store(read + frames, Ordering::Release);
        frames
    }

    /// Frames currently buffered (head − tail).
    pub fn depth_frames(&self) -> usize {
        self.head.load(Ordering::Relaxed) - self.tail.load(Ordering::Acquire)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;
    use std::thread;

    fn frame_samples(frames: usize, channels: usize, base: i16) -> Vec<i16> {
        (0..frames * channels).map(|i| base + i as i16).collect()
    }

    #[test]
    fn push_then_drain_preserves_interleaved_order_and_scale() {
        let ring = AudioRing::new(4800, 2);
        ring.push(&frame_samples(4, 2, 100)); // L=100,101 R=102,103 interleaved
        let mut out = vec![0f32; 8];
        let drained = ring.drain_f32(&mut out);
        assert_eq!(drained, 4);
        assert_eq!(out[0], 100.0 / 32768.0);
        assert_eq!(out[1], 101.0 / 32768.0);
        assert_eq!(out[3], 103.0 / 32768.0);
        assert_eq!(ring.depth_frames(), 0);
    }

    #[test]
    fn a_full_ring_drops_the_newest_frames_and_never_blocks() {
        let ring = AudioRing::new(10, 2);
        let dropped = ring.push(&frame_samples(20, 2, 0));
        assert_eq!(dropped, 10); // half the push never made it in
        assert_eq!(ring.depth_frames(), 10);
    }

    #[test]
    fn the_reader_sheds_backlog_above_the_trigger_to_the_keep_level() {
        let ring = AudioRing::new(4_800, 2);
        ring.push(&frame_samples(4_000, 2, 0)); // depth 4000 > trigger 2880
        let mut out = vec![0f32; SHED_KEEP_FRAMES * 2]; // callback smaller than the keep
        let drained = ring.drain_f32(&mut out);
        assert_eq!(drained, SHED_KEEP_FRAMES); // shed to 1920 frames, drain 1920
        assert_eq!(ring.depth_frames(), 0);
    }

    #[test]
    fn an_empty_ring_drains_nothing() {
        let ring = AudioRing::new(100, 2);
        let mut out = vec![1f32; 256];
        let drained = ring.drain_f32(&mut out);
        assert_eq!(drained, 0);
        assert!(out.iter().all(|v| *v == 1.0)); // caller fills silence
    }

    #[test]
    fn concurrent_writer_and_reader_stay_within_capacity() {
        let ring = Arc::new(AudioRing::new(4_800, 2));
        let producer = {
            let ring = Arc::clone(&ring);
            thread::spawn(move || {
                for second in 0..50 {
                    ring.push(&frame_samples(4_800, 2, (second % 100) as i16));
                }
            })
        };
        // Drain while the producer runs — capacity must never be exceeded.
        let mut drained_total = 0usize;
        while !producer.is_finished() {
            let mut out = vec![0f32; 512];
            drained_total += ring.drain_f32(&mut out);
            assert!(ring.depth_frames() <= 4_800);
        }
        producer.join().expect("producer thread");
        // Whatever remains after the join drains deterministically: the
        // producer pushed 240k frames into a 4.8k ring — at least the last
        // 4.8k are in it (or already drained above).
        loop {
            let mut out = vec![0f32; 512];
            let drained = ring.drain_f32(&mut out);
            if drained == 0 {
                break;
            }
            drained_total += drained;
            assert!(ring.depth_frames() <= 4_800);
        }
        assert!(drained_total >= 4_800);
    }
}
