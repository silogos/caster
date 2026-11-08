//! The game-audio ear output — ADR-014 Fase A's engine, embedded in the
//! native receiver. Two sources can feed it, never both at once:
//!
//! - the `game-audio` DataChannel's raw PCM16LE (10 ms stereo @48 kHz) —
//!   the true-stereo primary (ADR-012);
//! - the dual-mono Opus track — the fallback, only while the DataChannel
//!   has been quiet ≥ 3 s (webrtc.md's ladder; both at once comb-filtered,
//!   found live).
//!
//! A lock-free ring sits between them and the CPAL real-time callback:
//! writers are serialized by a producer lock (so it is single-producer by
//! construction), the callback is the only consumer and owns the playout:
//! it starts from a 40 ms cushion, re-primes after starving, and holds the
//! buffer near that target by playing up to 1 % faster or slower — never by
//! throwing audio away. (The first version trimmed any backlog over 60 ms;
//! a 274 s PUBG session showed that trim destroying the very cushion that
//! absorbs SCTP's next delivery stall: 244 trims → 195 underruns.) Only a
//! backlog over 200 ms — a real outage — is still cut.
//! The callback adapts to the device: 48 kHz stereo when it supports it,
//! otherwise channel mapping + linear resampling (44.1 kHz DACs, 16/24 kHz
//! Bluetooth HFP). Never logs samples (AGENTS.md) — counters and dB only.

use std::sync::atomic::{AtomicI16, AtomicU32, AtomicU64, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};

/// The wire format of both sources.
const SOURCE_RATE_HZ: u32 = 48_000;
const SOURCE_CHANNELS: usize = 2;
const BYTES_PER_FRAME: usize = SOURCE_CHANNELS * 2;
/// 250 ms of source audio — room above the emergency threshold.
pub const RING_CAPACITY_FRAMES: usize = 12_000;
/// The playout target (40 ms): playback (re)starts once this much is
/// buffered, and rate control steers the smoothed depth back to it.
const TARGET_FRAMES: usize = 1_920;
/// Only a backlog this deep (200 ms — an outage, not jitter) is cut back
/// to the target; every cut skips audio audibly and is counted.
const EMERGENCY_SHED_FRAMES: usize = 9_600;
/// Rate control: a depth error of 100 % of the target asks for 1 % speed…
const RATE_GAIN: f64 = 0.01;
/// …capped at ±1 % (≈ 17 cents — inaudible as it moves gradually).
const MAX_RATE_CORRECTION: f64 = 0.01;
/// Depth smoothing per callback (~1 s time constant at ~10 ms callbacks),
/// so single SCTP bursts do not swing the playback rate.
const DEPTH_SMOOTHING: f64 = 0.01;
/// The documented fallback window: DataChannel quiet this long hands the
/// ear output to the dual-mono track.
const FALLBACK_AFTER_DC_QUIET: Duration = Duration::from_secs(3);

/// The ring: the producer (under [`Producer`]'s lock) owns `head`, the
/// callback owns `tail`. Per-slot atomics — safe Rust, no allocation on the
/// real-time path.
struct Ring {
    buf: Box<[AtomicI16]>,
    head: AtomicUsize,
    tail: AtomicUsize,
    /// A producer-requested flush (source switch / new session): the
    /// consumer jumps `tail` here and re-primes. `usize::MAX` = none.
    flush_to: AtomicUsize,
    underruns: AtomicU64,
    /// Backlog trims — each one skips audio (an audible jump).
    sheds: AtomicU64,
    frames_played: AtomicU64,
}

const NO_FLUSH: usize = usize::MAX;

impl Ring {
    fn new() -> Self {
        Self {
            buf: (0..RING_CAPACITY_FRAMES * SOURCE_CHANNELS).map(|_| AtomicI16::new(0)).collect(),
            head: AtomicUsize::new(0),
            tail: AtomicUsize::new(0),
            flush_to: AtomicUsize::new(NO_FLUSH),
            underruns: AtomicU64::new(0),
            sheds: AtomicU64::new(0),
            frames_played: AtomicU64::new(0),
        }
    }

    /// Appends whole stereo frames; drops the newest when full (never
    /// blocks a network thread). Caller holds the producer lock.
    fn push(&self, bytes: &[u8]) {
        let mut head = self.head.load(Ordering::Relaxed);
        let tail = self.tail.load(Ordering::Acquire);
        for frame in bytes.chunks_exact(BYTES_PER_FRAME) {
            if head - tail >= RING_CAPACITY_FRAMES {
                break;
            }
            let base = (head % RING_CAPACITY_FRAMES) * SOURCE_CHANNELS;
            self.buf[base].store(i16::from_le_bytes([frame[0], frame[1]]), Ordering::Relaxed);
            self.buf[base + 1].store(i16::from_le_bytes([frame[2], frame[3]]), Ordering::Relaxed);
            head += 1;
        }
        self.head.store(head, Ordering::Release);
    }

    /// Asks the consumer to drop everything buffered so far.
    fn request_flush(&self) {
        self.flush_to.store(self.head.load(Ordering::Relaxed), Ordering::Release);
    }

    fn frame(&self, index: usize) -> (i16, i16) {
        let base = (index % RING_CAPACITY_FRAMES) * SOURCE_CHANNELS;
        (self.buf[base].load(Ordering::Relaxed), self.buf[base + 1].load(Ordering::Relaxed))
    }
}

/// Which source currently owns the ring.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum Source {
    DataChannel,
    Track,
}

/// Producer-side state; its lock serializes the two writers.
struct Producer {
    active: Option<Source>,
    last_dc: Option<Instant>,
}

/// The consumer's playout position — owned by the real-time callback.
struct Playout {
    primed: bool,
    /// Fractional source position between `tail` and `tail + 1` (resampling).
    phase: f64,
    /// Smoothed buffer depth in frames (the rate controller's input).
    smoothed_depth: f64,
}

impl Playout {
    fn new() -> Self {
        Self { primed: false, phase: 0.0, smoothed_depth: TARGET_FRAMES as f64 }
    }
}

struct Engine {
    ring: Arc<Ring>,
    producer: Mutex<Producer>,
    /// Side/mid energy (i16²·frames) of the DataChannel PCM for the 1 Hz
    /// stereo meter: side ≈ −∞ while the wire carries L = R.
    stereo_energy: Mutex<(f64, f64)>,
    /// The receiver's game-audio level (0.0–1.0, mute = 0) as f32 bits —
    /// a receiver/environment control, never a cast setting (AGENTS.md).
    gain_bits: AtomicU32,
    /// Sound plays only while the video window is open (product choice,
    /// 2026-11-09): the ring keeps draining either way, so opening the
    /// window is instant and never replays a backlog.
    output_enabled: std::sync::atomic::AtomicBool,
    output: OnceLock<Option<Output>>,
}

fn engine() -> &'static Engine {
    static ENGINE: OnceLock<Engine> = OnceLock::new();
    ENGINE.get_or_init(|| Engine {
        ring: Arc::new(Ring::new()),
        producer: Mutex::new(Producer { active: None, last_dc: None }),
        stereo_energy: Mutex::new((0.0, 0.0)),
        gain_bits: AtomicU32::new(1.0f32.to_bits()),
        output_enabled: std::sync::atomic::AtomicBool::new(false),
        output: OnceLock::new(),
    })
}

/// Sets the game-audio output level (clamped to 0.0–1.0); takes effect on
/// the next callback. Mute is a gain of 0 — the stats keep counting.
pub fn set_game_gain(gain: f32) {
    let gain = if gain.is_finite() { gain.clamp(0.0, 1.0) } else { 1.0 };
    engine().gain_bits.store(gain.to_bits(), Ordering::Relaxed);
}

/// Opens (`true`) or closes the ear output — it follows the video window.
pub fn set_output_enabled(enabled: bool) {
    engine().output_enabled.store(enabled, Ordering::Relaxed);
}

fn game_gain() -> f32 {
    let e = engine();
    output_gain(f32::from_bits(e.gain_bits.load(Ordering::Relaxed)), e.output_enabled.load(Ordering::Relaxed))
}

/// The gain the callback applies: the user's level, or silence while the
/// video window is closed.
fn output_gain(level: f32, enabled: bool) -> f32 {
    if enabled { level } else { 0.0 }
}

/// A new media session: drop the previous session's audio and its source
/// choice, so the next cast starts from a clean, primed ring.
pub fn reset_session() {
    let e = engine();
    let mut p = e.producer.lock().expect("audio producer");
    *p = Producer { active: None, last_dc: None };
    e.ring.request_flush();
    e.ring.underruns.store(0, Ordering::Relaxed);
    e.ring.sheds.store(0, Ordering::Relaxed);
    e.ring.frames_played.store(0, Ordering::Relaxed);
    *e.stereo_energy.lock().expect("stereo energy") = (0.0, 0.0);
}

/// One DataChannel PCM16LE chunk (the stereo primary).
pub fn feed_dc_pcm16(chunk: &[u8]) {
    meter_stereo(chunk);
    feed(Source::DataChannel, chunk);
}

/// One chunk of the fallback track's decoded PCM16 (48 kHz stereo).
pub fn feed_track_pcm16(chunk: &[u8]) {
    feed(Source::Track, chunk);
}

/// What the producer does with one chunk.
#[derive(Debug, PartialEq, Eq)]
enum Admit {
    /// The DataChannel is live — the fallback track stays silent.
    Drop,
    /// Push it; `switched` = a different source owned the ring (flush first).
    Push { switched: bool },
}

/// The source ladder (webrtc.md): DataChannel PCM always wins; the track
/// plays only before any DataChannel audio or after ≥ 3 s of its silence.
fn admit(p: &mut Producer, source: Source, now: Instant) -> Admit {
    if source == Source::DataChannel {
        p.last_dc = Some(now);
    } else if p.last_dc.is_some_and(|t| now.duration_since(t) < FALLBACK_AFTER_DC_QUIET) {
        return Admit::Drop;
    }
    let switched = p.active.is_some_and(|a| a != source);
    p.active = Some(source);
    Admit::Push { switched }
}

fn feed(source: Source, chunk: &[u8]) {
    let e = engine();
    {
        let mut p = e.producer.lock().expect("audio producer");
        match admit(&mut p, source, Instant::now()) {
            Admit::Drop => return,
            Admit::Push { switched } => {
                if switched {
                    log::info!(target: "audio", "source switch → {source:?} — flushing the ring");
                    e.ring.request_flush();
                }
                e.ring.push(chunk);
            }
        }
    }
    ensure_output();
}

fn meter_stereo(chunk: &[u8]) {
    let (mut side, mut mid) = (0.0f64, 0.0f64);
    for f in chunk.chunks_exact(BYTES_PER_FRAME) {
        let l = i16::from_le_bytes([f[0], f[1]]) as f64;
        let r = i16::from_le_bytes([f[2], f[3]]) as f64;
        side += (l - r) * (l - r);
        mid += ((l + r) * 0.5) * ((l + r) * 0.5);
    }
    let mut e = engine().stereo_energy.lock().expect("stereo energy");
    e.0 += side;
    e.1 += mid;
}

/// Side-vs-mid level of the DataChannel PCM since the last call, in dB
/// (≈ −∞ for L = R; +6 dB for one dead channel).
pub fn take_side_db() -> f64 {
    let (side, mid) = std::mem::take(&mut *engine().stereo_energy.lock().expect("stereo energy"));
    10.0 * (side + 1.0).log10() - 10.0 * (mid + 1.0).log10()
}

/// Session stats for the ~1 Hz log line.
pub struct Stats {
    pub underruns: u64,
    pub sheds: u64,
    pub frames_played: u64,
    /// Buffered audio right now, in ms.
    pub depth_ms: u64,
}

pub fn stats() -> Stats {
    let ring = &engine().ring;
    let depth = ring.head.load(Ordering::Relaxed).saturating_sub(ring.tail.load(Ordering::Relaxed));
    Stats {
        underruns: ring.underruns.load(Ordering::Relaxed),
        sheds: ring.sheds.load(Ordering::Relaxed),
        frames_played: ring.frames_played.load(Ordering::Relaxed),
        depth_ms: depth as u64 * 1_000 / u64::from(SOURCE_RATE_HZ),
    }
}

// ---- output ---------------------------------------------------------------

/// cpal::Stream is not `Send` by marker, but it is an Arc'd handle
/// internally — we only keep it alive for the process.
struct Output(#[allow(dead_code)] cpal::Stream);
unsafe impl Send for Output {}
unsafe impl Sync for Output {}

/// Opens the output once, on the first fed chunk.
fn ensure_output() {
    engine().output.get_or_init(open_output);
}

fn open_output() -> Option<Output> {
    use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
    let device = cpal::default_host().default_output_device().or_else(|| {
        log::error!(target: "audio", "no output device — game audio disabled");
        None
    })?;
    let config = choose_config(&device)?;
    let out_channels = usize::from(config.channels.max(1));
    let step = f64::from(SOURCE_RATE_HZ) / f64::from(config.sample_rate.0);
    log::info!(
        target: "audio", "output: {} ({} Hz, {} ch){}",
        device.name().unwrap_or_default(),
        config.sample_rate.0,
        out_channels,
        if (step - 1.0).abs() > f64::EPSILON || out_channels != SOURCE_CHANNELS { " — converting" } else { "" }
    );
    let ring = Arc::clone(&engine().ring);
    let mut state = Playout::new();
    let stream = device
        .build_output_stream(
            &config,
            move |data: &mut [f32], _: &cpal::OutputCallbackInfo| {
                render(&ring, &mut state, data, out_channels, step, game_gain());
            },
            |error| log::warn!(target: "audio", "output stream error: {error}"),
            None,
        )
        .map_err(|e| log::error!(target: "audio", "output stream: {e}"))
        .ok()?;
    stream.play().map_err(|e| log::error!(target: "audio", "output start: {e}")).ok()?;
    Some(Output(stream))
}

/// 48 kHz with the fewest conversions: prefer stereo f32 at 48 kHz, then
/// any channel count at 48 kHz, then the device default (resampled).
fn choose_config(device: &cpal::Device) -> Option<cpal::StreamConfig> {
    use cpal::traits::DeviceTrait;
    let rate = cpal::SampleRate(SOURCE_RATE_HZ);
    let at_48k: Vec<_> = device
        .supported_output_configs()
        .map(|configs| {
            configs
                .filter(|c| c.sample_format() == cpal::SampleFormat::F32)
                .filter(|c| c.min_sample_rate() <= rate && rate <= c.max_sample_rate())
                .collect()
        })
        .unwrap_or_default();
    let best = at_48k
        .iter()
        .find(|c| usize::from(c.channels()) == SOURCE_CHANNELS)
        .or_else(|| at_48k.first());
    match best {
        Some(range) => Some(cpal::StreamConfig { channels: range.channels(), sample_rate: rate, buffer_size: cpal::BufferSize::Default }),
        None => {
            let default = device
                .default_output_config()
                .map_err(|e| log::error!(target: "audio", "no usable output config: {e}"))
                .ok()?;
            if default.sample_format() != cpal::SampleFormat::F32 {
                log::error!(target: "audio", "output needs {:?}, only f32 is supported", default.sample_format());
                return None;
            }
            Some(default.config())
        }
    }
}

/// The real-time callback body: flush requests, priming, shedding, then
/// channel mapping + linear resampling into `data`. No locks, no allocation.
fn render(ring: &Ring, state: &mut Playout, data: &mut [f32], out_channels: usize, step: f64, gain: f32) {
    let out_frames = data.len() / out_channels;
    let mut tail = ring.tail.load(Ordering::Relaxed);
    let flush = ring.flush_to.swap(NO_FLUSH, Ordering::Acquire);
    if flush != NO_FLUSH && flush > tail {
        tail = flush;
        state.primed = false;
        state.phase = 0.0;
    }
    let head = ring.head.load(Ordering::Acquire);
    let mut depth = head - tail;
    if depth > EMERGENCY_SHED_FRAMES {
        tail = head - TARGET_FRAMES;
        depth = TARGET_FRAMES;
        state.smoothed_depth = TARGET_FRAMES as f64;
        ring.sheds.fetch_add(1, Ordering::Relaxed);
    }
    if !state.primed && depth >= TARGET_FRAMES {
        state.primed = true;
    }
    state.smoothed_depth += DEPTH_SMOOTHING * (depth as f64 - state.smoothed_depth);

    let mut written = 0;
    if state.primed {
        let step = step * (1.0 + rate_correction(state.smoothed_depth));
        let (consumed, frames) = convert(|i| ring.frame(tail + i), depth, &mut data[..], out_channels, step, &mut state.phase);
        tail += consumed;
        written = frames;
        ring.frames_played.fetch_add(consumed as u64, Ordering::Relaxed);
        if frames < out_frames {
            // Starved: count it and re-prime rather than ride the floor.
            ring.underruns.fetch_add(1, Ordering::Relaxed);
            state.primed = false;
        }
    }
    data[written * out_channels..].fill(0.0);
    if gain < 1.0 {
        for sample in &mut data[..written * out_channels] {
            *sample *= gain;
        }
    }
    ring.tail.store(tail, Ordering::Release);
}

/// Playback speed offset for a smoothed depth: positive (faster) above the
/// target, negative (slower) below, proportional and capped.
fn rate_correction(smoothed_depth: f64) -> f64 {
    let target = TARGET_FRAMES as f64;
    ((smoothed_depth - target) / target * RATE_GAIN).clamp(-MAX_RATE_CORRECTION, MAX_RATE_CORRECTION)
}

/// Renders up to `out.len() / out_channels` frames from `available` source
/// frames read through `frame`. `step` = source frames per output frame
/// (1.0 = no resampling); `phase` carries the fractional position across
/// calls. Returns (source frames consumed, output frames written).
fn convert(
    frame: impl Fn(usize) -> (i16, i16),
    available: usize,
    out: &mut [f32],
    out_channels: usize,
    step: f64,
    phase: &mut f64,
) -> (usize, usize) {
    const SCALE: f32 = 1.0 / 32_768.0;
    let out_frames = out.len() / out_channels;
    let mut pos = *phase;
    let mut written = 0;
    while written < out_frames {
        let index = pos as usize;
        // Linear interpolation needs the next frame too (except exactly on it).
        let needs_next = pos.fract() > 0.0;
        if index + usize::from(needs_next) >= available {
            break;
        }
        let (l0, r0) = frame(index);
        let (l, r) = if needs_next {
            let (l1, r1) = frame(index + 1);
            let t = pos.fract() as f32;
            (l0 as f32 + (l1 as f32 - l0 as f32) * t, r0 as f32 + (r1 as f32 - r0 as f32) * t)
        } else {
            (l0 as f32, r0 as f32)
        };
        let slot = &mut out[written * out_channels..(written + 1) * out_channels];
        match out_channels {
            1 => slot[0] = (l + r) * 0.5 * SCALE,
            _ => {
                slot[0] = l * SCALE;
                slot[1] = r * SCALE;
                slot[2..].fill(0.0); // centre/surround stay silent
            }
        }
        written += 1;
        pos += step;
    }
    let consumed = pos as usize;
    *phase = pos - consumed as f64;
    (consumed, written)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pcm(frames: &[(i16, i16)]) -> Vec<u8> {
        frames.iter().flat_map(|(l, r)| [l.to_le_bytes(), r.to_le_bytes()].concat()).collect()
    }

    fn filled(n: usize) -> Ring {
        let ring = Ring::new();
        let frames: Vec<(i16, i16)> = (0..n).map(|i| (i as i16, -(i as i16))).collect();
        ring.push(&pcm(&frames));
        ring
    }

    #[test]
    fn push_keeps_order_and_drops_partial_frames() {
        let ring = Ring::new();
        let mut bytes = pcm(&[(100, -200), (1, 2)]);
        bytes.extend_from_slice(&[9, 9, 9]); // trailing junk
        ring.push(&bytes);
        assert_eq!(ring.head.load(Ordering::Relaxed), 2);
        assert_eq!(ring.frame(0), (100, -200));
    }

    #[test]
    fn a_full_ring_drops_the_newest() {
        let ring = filled(RING_CAPACITY_FRAMES + 10);
        assert_eq!(ring.head.load(Ordering::Relaxed), RING_CAPACITY_FRAMES);
    }

    #[test]
    fn playback_waits_for_the_prime_cushion() {
        let ring = filled(TARGET_FRAMES - 1);
        let mut state = Playout::new();
        let mut out = vec![1.0f32; 2 * 256];
        render(&ring, &mut state, &mut out, 2, 1.0, 1.0);
        assert!(out.iter().all(|s| *s == 0.0), "silence until 50 ms is buffered");
        assert_eq!(ring.tail.load(Ordering::Relaxed), 0, "nothing consumed");
        ring.push(&pcm(&[(5, 5)]));
        render(&ring, &mut state, &mut out, 2, 1.0, 1.0);
        let played = ring.tail.load(Ordering::Relaxed);
        assert!((254..=257).contains(&played), "primed: plays ~1x ({played})");
    }

    #[test]
    fn starving_counts_an_underrun_and_re_primes() {
        let ring = filled(TARGET_FRAMES);
        let mut state = Playout::new();
        let mut out = vec![0.0f32; 2 * (TARGET_FRAMES + 100)];
        render(&ring, &mut state, &mut out, 2, 1.0, 1.0);
        assert_eq!(ring.underruns.load(Ordering::Relaxed), 1);
        assert!(!state.primed, "must rebuild the cushion before playing again");
        assert!(out[2 * TARGET_FRAMES..].iter().all(|s| *s == 0.0), "the gap is silence, not garbage");
    }

    #[test]
    fn a_jitter_burst_is_kept_not_thrown_away() {
        // 100 ms buffered after an SCTP burst: the old 60 ms trim would skip
        // audio here; now it is all kept to ride out the next stall.
        let ring = filled(4_800);
        let mut state = Playout::new();
        let mut out = vec![0.0f32; 2 * 480];
        render(&ring, &mut state, &mut out, 2, 1.0, 1.0);
        assert_eq!(ring.sheds.load(Ordering::Relaxed), 0);
        let consumed = ring.tail.load(Ordering::Relaxed);
        assert!((480..=490).contains(&consumed), "played ~1x, no skip: {consumed}");
    }

    #[test]
    fn a_sustained_backlog_drains_by_playing_slightly_faster() {
        let ring = filled(4_800);
        let mut state = Playout::new();
        state.primed = true;
        state.smoothed_depth = 4_800.0; // the backlog has persisted
        let mut out = vec![0.0f32; 2 * 1_000];
        render(&ring, &mut state, &mut out, 2, 1.0, 1.0);
        let consumed = ring.tail.load(Ordering::Relaxed);
        assert!(consumed > 1_000 && consumed <= 1_011, "≤1 % faster, never a jump: {consumed}");
    }

    #[test]
    fn rate_correction_is_proportional_and_capped() {
        assert_eq!(rate_correction(TARGET_FRAMES as f64), 0.0);
        assert!(rate_correction(TARGET_FRAMES as f64 * 1.5) > 0.0);
        assert!(rate_correction(TARGET_FRAMES as f64 * 0.5) < 0.0);
        assert_eq!(rate_correction(1e9), MAX_RATE_CORRECTION);
        assert_eq!(rate_correction(0.0), -MAX_RATE_CORRECTION);
    }

    #[test]
    fn only_an_outage_sized_backlog_is_cut() {
        let ring = filled(EMERGENCY_SHED_FRAMES + 500);
        let mut state = Playout::new();
        let mut out = vec![0.0f32; 2 * 10];
        render(&ring, &mut state, &mut out, 2, 1.0, 1.0);
        let head = ring.head.load(Ordering::Relaxed);
        assert_eq!(head - ring.tail.load(Ordering::Relaxed), TARGET_FRAMES - 10);
        assert_eq!(ring.sheds.load(Ordering::Relaxed), 1, "every skip is counted");
    }

    #[test]
    fn a_flush_drops_buffered_audio_and_re_primes() {
        let ring = filled(TARGET_FRAMES * 2);
        let mut state = Playout::new();
        state.primed = true;
        ring.request_flush();
        let mut out = vec![1.0f32; 2 * 64];
        render(&ring, &mut state, &mut out, 2, 1.0, 1.0);
        assert_eq!(ring.tail.load(Ordering::Relaxed), TARGET_FRAMES * 2, "old source's audio dropped");
        assert!(out.iter().all(|s| *s == 0.0));
        assert!(!state.primed);
    }

    #[test]
    fn mono_devices_get_a_mixdown_and_multichannel_keeps_l_r() {
        let frame = |_| (16_384i16, -16_384i16 / 2);
        let mut phase = 0.0;
        let mut mono = vec![9.0f32; 4];
        assert_eq!(convert(frame, 10, &mut mono, 1, 1.0, &mut phase), (4, 4));
        assert!(mono.iter().all(|s| (*s - 0.125).abs() < 1e-6), "(0.5 + -0.25) / 2");

        let mut six = vec![9.0f32; 6 * 2];
        convert(frame, 10, &mut six, 6, 1.0, &mut 0.0);
        assert_eq!(&six[..6], &[0.5, -0.25, 0.0, 0.0, 0.0, 0.0], "L, R, then silence — never sample-shifted");
    }

    #[test]
    fn resampling_consumes_source_at_the_rate_ratio() {
        let ramp = |i: usize| (i as i16, i as i16);
        // 48 kHz → 44.1 kHz: 441 output frames consume ~480 source frames.
        let mut phase = 0.0;
        let mut out = vec![0.0f32; 2 * 441];
        let (consumed, written) = convert(ramp, 1_000, &mut out, 2, 48_000.0 / 44_100.0, &mut phase);
        assert_eq!(written, 441);
        assert!((479..=481).contains(&consumed), "{consumed}");
        // Interpolated values stay on the ramp (monotonic, no garbage).
        assert!(out.chunks(2).map(|f| f[0]).collect::<Vec<_>>().windows(2).all(|w| w[1] > w[0]));
        // 48 kHz → 16 kHz (HFP): 3 source frames per output frame.
        let (consumed, written) = convert(ramp, 1_000, &mut vec![0.0f32; 2 * 100], 2, 3.0, &mut 0.0);
        assert_eq!((consumed, written), (300, 100));
    }

    #[test]
    fn resampling_stops_before_reading_past_the_buffer() {
        let mut out = vec![0.0f32; 2 * 100];
        let (consumed, written) = convert(|i| (i as i16, 0), 10, &mut out, 2, 0.5, &mut 0.25);
        assert!(consumed <= 10 && written < 100);
    }

    #[test]
    fn the_source_ladder_never_plays_both() {
        let t0 = Instant::now();
        let mut p = Producer { active: None, last_dc: None };
        // Before any DataChannel audio the fallback track plays.
        assert_eq!(admit(&mut p, Source::Track, t0), Admit::Push { switched: false });
        // DataChannel arrives: it takes over, flushing the track's audio.
        assert_eq!(admit(&mut p, Source::DataChannel, t0), Admit::Push { switched: true });
        // While it is live, track chunks are dropped (no comb filtering).
        assert_eq!(admit(&mut p, Source::Track, t0 + Duration::from_millis(2_900)), Admit::Drop);
        // After 3 s of DataChannel silence the fallback takes over again.
        assert_eq!(admit(&mut p, Source::Track, t0 + FALLBACK_AFTER_DC_QUIET), Admit::Push { switched: true });
        assert_eq!(admit(&mut p, Source::DataChannel, t0 + Duration::from_secs(4)), Admit::Push { switched: true });
    }

    #[test]
    fn the_game_level_scales_output_and_mute_is_silence() {
        let ring = filled(TARGET_FRAMES * 2);
        let mut state = Playout::new();
        state.primed = true;
        let mut full = vec![0.0f32; 2 * 64];
        render(&ring, &mut state, &mut full, 2, 1.0, 1.0);
        let ring = filled(TARGET_FRAMES * 2);
        let mut state = Playout::new();
        state.primed = true;
        let mut half = vec![0.0f32; 2 * 64];
        render(&ring, &mut state, &mut half, 2, 1.0, 0.5);
        for (h, f) in half.iter().zip(&full) {
            assert!((h - f * 0.5).abs() < 1e-6);
        }
        let ring = filled(TARGET_FRAMES * 2);
        let mut state = Playout::new();
        state.primed = true;
        let mut muted = vec![1.0f32; 2 * 64];
        render(&ring, &mut state, &mut muted, 2, 1.0, 0.0);
        assert!(muted.iter().all(|s| *s == 0.0));
        assert_eq!(ring.tail.load(Ordering::Relaxed), 64, "muted audio still advances — no backlog builds up");
    }

    #[test]
    fn sound_is_silent_until_the_video_window_opens() {
        assert_eq!(output_gain(0.8, false), 0.0);
        assert_eq!(output_gain(0.8, true), 0.8);
        assert_eq!(output_gain(0.0, true), 0.0, "mute stays mute with the window open");
    }
}
