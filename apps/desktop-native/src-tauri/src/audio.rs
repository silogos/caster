//! M2: the ear output — the audio engine from ADR-014 Fase A (apps/desktop/
//! audio-engine), embedded into the native receiver. The `game-audio`
//! DataChannel's raw PCM16LE chunks (10 ms stereo @48 kHz) land in a
//! lock-free SPSC ring; a CPAL stream drains it straight to the default
//! output — drop-late-never-stall, the tuned constants from the live cast
//! (keep 40 ms, shed trigger 60 ms, capacity 100 ms).

use std::sync::atomic::{AtomicI16, AtomicU64, AtomicUsize, Ordering};
use std::sync::{Arc, OnceLock};

const CHANNELS: usize = 2;
/// 100 ms @48 kHz stereo (the tuned capacity).
pub const RING_CAPACITY_FRAMES: usize = 4_800;
/// Backlog above this (60 ms) sheds oldest samples on the next callback.
pub const SHED_TRIGGER_FRAMES: usize = 2_880;
/// Frames kept after a shed (40 ms) — the playout buffer.
pub const SHED_KEEP_FRAMES: usize = 1_920;

/// The ring shared between the DataChannel thread (writer) and the CPAL
/// real-time callback (reader). Pure SPSC: writer owns head, reader owns
/// tail (per-slot atomics — safe Rust, lock-free, no allocation on the
/// real-time path).
struct SharedRing {
    buf: Box<[AtomicI16]>,
    head: AtomicUsize,
    tail: AtomicUsize,
    underruns: AtomicU64,
    frames_played: AtomicU64,
}

impl SharedRing {
    fn new() -> Self {
        Self {
            buf: (0..RING_CAPACITY_FRAMES * CHANNELS)
                .map(|_| AtomicI16::new(0))
                .collect(),
            head: AtomicUsize::new(0),
            tail: AtomicUsize::new(0),
            underruns: AtomicU64::new(0),
            frames_played: AtomicU64::new(0),
        }
    }

    fn push(&self, bytes: &[u8]) {
        let usable = bytes.len() - (bytes.len() % 4); // whole stereo frames
        let mut head = self.head.load(Ordering::Relaxed);
        let read = self.tail.load(Ordering::Acquire);
        for i in 0..usable / 4 {
            if head - read >= RING_CAPACITY_FRAMES {
                break; // full — drop the newest, never block the DC thread
            }
            let l = i16::from_le_bytes([bytes[i * 4], bytes[i * 4 + 1]]);
            let r = i16::from_le_bytes([bytes[i * 4 + 2], bytes[i * 4 + 3]]);
            let base = (head % RING_CAPACITY_FRAMES) * CHANNELS;
            self.buf[base].store(l, Ordering::Release);
            self.buf[base + 1].store(r, Ordering::Release);
            head += 1;
            self.head.store(head, Ordering::Release);
        }
    }
}

/// cpal::Stream holds a raw pointer (not Send/Sync by marker), but it is
/// an Arc'd handle internally — moving/dropping across threads is fine.
/// We only keep it alive; this newtype asserts that.
struct KeepAliveStream(cpal::Stream);
unsafe impl Send for KeepAliveStream {}
unsafe impl Sync for KeepAliveStream {}

struct AudioOut {
    ring: Arc<SharedRing>,
    _stream: KeepAliveStream,
}

impl AudioOut {
    fn new() -> Option<Self> {
        use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
        let host = cpal::default_host();
        let device = host.default_output_device()?;
        let supported = device.default_output_config().ok()?;
        eprintln!(
            "[audio] output: {} ({} Hz, {} ch)",
            device.name().unwrap_or_default(),
            supported.sample_rate().0,
            supported.channels()
        );
        let config = cpal::StreamConfig {
            channels: supported.channels(),
            sample_rate: supported.sample_rate(),
            buffer_size: cpal::BufferSize::Default,
        };
        let out_channels = config.channels.max(1) as usize;
        let ring = Arc::new(SharedRing::new());
        let cb_ring = Arc::clone(&ring);
        let stream = device
            .build_output_stream(
                &config,
                move |data: &mut [f32], _: &cpal::OutputCallbackInfo| {
                    let max_frames = data.len() / out_channels;
                    let mut read = cb_ring.tail.load(Ordering::Acquire);
                    let head = cb_ring.head.load(Ordering::Acquire);
                    let depth = head - read;
                    let frames = if depth > SHED_TRIGGER_FRAMES {
                        let kept = SHED_KEEP_FRAMES.min(depth);
                        read = head - kept;
                        cb_ring.tail.store(read, Ordering::Release);
                        kept.min(max_frames)
                    } else {
                        depth.min(max_frames)
                    };
                    let mut written = 0usize;
                    for f in 0..frames {
                        let base = ((read + f) % RING_CAPACITY_FRAMES) * CHANNELS;
                        for c in 0..CHANNELS {
                            if written < data.len() {
                                data[written] =
                                    cb_ring.buf[base + c].load(Ordering::Acquire) as f32 / 32768.0;
                                written += 1;
                            }
                        }
                    }
                    if frames < max_frames {
                        cb_ring.underruns.fetch_add(1, Ordering::Relaxed);
                    }
                    for sample in &mut data[written..] {
                        *sample = 0.0; // underrun tail — silence, never stall
                    }
                    cb_ring.tail.store(read + frames, Ordering::Release);
                    cb_ring.frames_played.fetch_add(frames as u64, Ordering::Relaxed);
                },
                move |error| eprintln!("[audio] stream error: {error}"),
                None,
            )
            .ok()?;
        stream.play().ok()?;
        Some(Self { ring, _stream: KeepAliveStream(stream) })
    }
}

static AUDIO: OnceLock<AudioOut> = OnceLock::new();

fn out() -> Option<&'static AudioOut> {
    AUDIO.get()
}

/// Starts the audio output (idempotent).
pub fn start() -> bool {
    if AUDIO.get().is_some() {
        return true;
    }
    match AudioOut::new() {
        Some(o) => AUDIO.set(o).is_ok(),
        None => false,
    }
}

/// Feed one DataChannel PCM16LE chunk; starts the output on first call.
pub fn feed_pcm16(chunk: &[u8]) {
    if out().is_none() {
        start();
    }
    if let Some(o) = out() {
        o.ring.push(chunk);
    }
}

/// Stats for the ~1 Hz log line (numbers only): (underruns, frames_played).
pub fn stats() -> (u64, u64) {
    match out() {
        Some(o) => (
            o.ring.underruns.load(Ordering::Relaxed),
            o.ring.frames_played.load(Ordering::Relaxed),
        ),
        None => (0, 0),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn push_then_read_preserves_order_and_scale() {
        let ring = SharedRing::new();
        // L=100, R=-200 (interleaved PCM16LE)
        let mut bytes = Vec::new();
        bytes.extend_from_slice(&100i16.to_le_bytes());
        bytes.extend_from_slice(&(-200i16).to_le_bytes());
        ring.push(&bytes);
        assert_eq!(ring.head.load(Ordering::Relaxed), 1);
        let base = 0 * CHANNELS;
        assert_eq!(ring.buf[base].load(Ordering::Relaxed), 100);
        assert_eq!(ring.buf[base + 1].load(Ordering::Relaxed), -200);
    }

    #[test]
    fn odd_bytes_push_whole_frames_only() {
        let ring = SharedRing::new();
        let mut bytes = vec![0u8; 4 * 10 + 3]; // 3 trailing junk bytes
        for i in 0..10 {
            bytes[i * 4..i * 4 + 2].copy_from_slice(&(i as i16).to_le_bytes());
            bytes[i * 4 + 2..i * 4 + 4].copy_from_slice(&(i as i16).to_le_bytes());
        }
        ring.push(&bytes);
        assert_eq!(ring.head.load(Ordering::Relaxed), 10); // junk dropped
    }
}
