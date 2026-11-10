//! Latency probe: marks when a test pattern's changes reach the receiver, so
//! glass-to-glass latency can be measured against the phone's own marks
//! (docs/development/latency-measurement.md).
//!
//! The phone's debug-only `LatencyTestActivity` flips the screen between
//! black and white once a second and beeps on every white flip, logging the
//! wall-clock time of each. With `ZFC_LATENCY_PROBE=1` the receiver logs the
//! wall-clock time each flip is decoded (plus how long the pacer holds it)
//! and each beep arrives (plus the playout ring's depth ahead of it).
//! `tools/latency/measure.py` pairs the two logs. Off by default: no frame
//! or sample is inspected unless the variable is set.
//!
//! Only a few sampled luma values and a peak amplitude are read — no frame
//! or audio content is logged (AGENTS.md logging rules).

use std::sync::OnceLock;
use std::time::{SystemTime, UNIX_EPOCH};

const ENV: &str = "ZFC_LATENCY_PROBE";
/// Mean luma below this is the pattern's black, above `BRIGHT` its white;
/// in between (a game, a transition) changes nothing — hysteresis.
const DARK: f64 = 64.0;
const BRIGHT: f64 = 192.0;
/// Luma is sampled on a GRID × GRID lattice over the frame's central half.
const GRID: u32 = 16;
/// A beep: a sample this loud (of 32 767) after at least `QUIET_BEFORE` of
/// quieter audio. The test beep is loud and the pattern is otherwise silent.
const ONSET_LEVEL: u16 = 8_000;
const QUIET_BEFORE: f64 = 0.300;

pub fn enabled() -> bool {
    static ON: OnceLock<bool> = OnceLock::new();
    *ON.get_or_init(|| std::env::var_os(ENV).is_some())
}

/// Wall-clock milliseconds — the clock the phone's marks use too.
pub fn wall_ms() -> f64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map_or(0.0, |d| d.as_secs_f64() * 1000.0)
}

/// Mean of a lattice of luma samples over the central half of the frame.
pub fn mean_luma(y: &[u8], stride: u32, width: u32, height: u32) -> f64 {
    let (x0, y0) = (width / 4, height / 4);
    let (w, h) = ((width / 2).max(1), (height / 2).max(1));
    let mut sum = 0u64;
    let mut n = 0u64;
    for gy in 0..GRID {
        for gx in 0..GRID {
            let px = x0 + gx * w / GRID;
            let py = y0 + gy * h / GRID;
            if let Some(v) = y.get((py * stride + px) as usize) {
                sum += u64::from(*v);
                n += 1;
            }
        }
    }
    if n == 0 { 0.0 } else { sum as f64 / n as f64 }
}

/// The test pattern's black/white state, reporting each change once.
#[derive(Debug, Default)]
pub struct LumaMarker {
    white: Option<bool>,
}

impl LumaMarker {
    /// `Some(is_white)` when the pattern just changed.
    pub fn observe(&mut self, mean: f64) -> Option<bool> {
        let now = if mean >= BRIGHT {
            true
        } else if mean <= DARK {
            false
        } else {
            return None;
        };
        let changed = self.white.is_some_and(|w| w != now);
        self.white = Some(now);
        changed.then_some(now)
    }
}

/// Peak absolute sample of an interleaved PCM16LE chunk.
pub fn peak_pcm16(chunk: &[u8]) -> u16 {
    chunk.chunks_exact(2).map(|b| i16::from_le_bytes([b[0], b[1]]).unsigned_abs()).max().unwrap_or(0)
}

/// Detects a beep's start: loud after a quiet stretch.
#[derive(Debug, Default)]
pub struct OnsetMarker {
    last_loud: Option<f64>,
}

impl OnsetMarker {
    pub const fn new() -> Self {
        Self { last_loud: None }
    }

    /// True when a chunk with `peak` at `now` (seconds) starts a beep.
    pub fn observe(&mut self, peak: u16, now: f64) -> bool {
        if peak < ONSET_LEVEL {
            return false;
        }
        let onset = self.last_loud.map_or(true, |t| now - t >= QUIET_BEFORE);
        self.last_loud = Some(now);
        onset
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn plane(w: u32, h: u32, value: u8) -> Vec<u8> {
        vec![value; (w * h) as usize]
    }

    #[test]
    fn a_plain_frame_reads_its_own_luma() {
        assert_eq!(mean_luma(&plane(64, 48, 235), 64, 64, 48), 235.0);
        assert_eq!(mean_luma(&plane(64, 48, 16), 64, 64, 48), 16.0);
    }

    #[test]
    fn the_luma_marker_reports_each_flip_once() {
        let mut m = LumaMarker::default();
        assert_eq!(m.observe(16.0), None); // first sight: no change yet
        assert_eq!(m.observe(16.0), None);
        assert_eq!(m.observe(235.0), Some(true));
        assert_eq!(m.observe(235.0), None);
        assert_eq!(m.observe(128.0), None); // mid-grey: ignored
        assert_eq!(m.observe(16.0), Some(false));
    }

    #[test]
    fn a_beep_is_one_onset_until_the_audio_goes_quiet() {
        let mut m = OnsetMarker::default();
        assert!(!m.observe(100, 0.0));
        assert!(m.observe(20_000, 1.000));
        assert!(!m.observe(20_000, 1.010)); // same beep
        assert!(!m.observe(100, 1.100));
        assert!(!m.observe(20_000, 1.200)); // only 190 ms after the last loud chunk
        assert!(m.observe(20_000, 2.000));
    }

    #[test]
    fn the_peak_is_the_loudest_sample_either_sign() {
        let samples: Vec<u8> = [100i16, -12_000, 5_000].iter().flat_map(|s| s.to_le_bytes()).collect();
        assert_eq!(peak_pcm16(&samples), 12_000);
    }
}
