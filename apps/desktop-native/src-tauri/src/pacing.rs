//! Frame pacing: when each decoded frame is shown, with a small bounded
//! delay that evens out Wi-Fi delivery jitter (ADR-017).
//!
//! The receiver's libwebrtc runs with zero playout delay (ADR-015), so frames
//! leave the decoder exactly as unevenly as Wi-Fi delivered them — measured
//! live (2026-11-09, PUBG at 60 fps): bursts 2–5 ms apart, then gaps of
//! 50–160 ms, while the phone sent them evenly. Shown on arrival, that is the
//! stutter. libwebrtc's own jitter buffer evened it out but added a delay the
//! user could feel, so the delay here is ours and capped at [`MAX_DELAY`].
//!
//! The schedule: each frame is due one estimated frame interval after the
//! previous one, never before it arrives (it cannot be shown earlier), never
//! more than [`MAX_DELAY`] after it arrives. A late frame pushes the schedule
//! back, which builds a cushion for the next gap; the schedule runs
//! [`DRAIN`] faster than the stream so the cushion drains back toward zero
//! delay once Wi-Fi calms. Gaps longer than [`MAX_DELAY`] still show — no
//! bounded delay hides them.
//!
//! Pure (times in seconds on any monotonic clock) so the policy is testable
//! without a window; `video.rs` feeds it host-clock arrival times.

/// The most a frame is held after it arrives (product choice, 2026-11-09:
/// "pakai buffer kecil" — WebRTC's adaptive buffer was smoother but its delay
/// was felt). About three frames at 60 fps, one and a half at 30 fps.
pub const MAX_DELAY: f64 = 0.050;
/// How much faster than the stream the schedule runs, so a cushion built by a
/// late frame drains back toward zero delay (0.5 %: ~5 ms/s at 60 fps —
/// invisible as motion, back to the live edge within ~10 s).
pub const DRAIN: f64 = 0.005;
/// A pause longer than this is a still screen (Android sends no frames for an
/// unchanged screen) or a hidden window, not jitter: start a fresh schedule.
pub const RESTART_AFTER: f64 = 0.250;
/// Weight of each new arrival gap in the frame-interval estimate. Small, so a
/// burst or a gap barely moves it; the stream's rate shows through in ~1 s.
const INTERVAL_WEIGHT: f64 = 0.05;
/// A frame this far past its slot counts as late in the diagnostics (the
/// drain alone makes every on-time frame a hair "late").
const LATE_TOLERANCE: f64 = 0.002;
/// The estimate before the stream has shown its rate (60 fps).
const INITIAL_INTERVAL: f64 = 1.0 / 60.0;

#[derive(Debug, Default)]
pub struct Pacer {
    interval: Option<f64>,
    last_arrival: Option<f64>,
    last_due: Option<f64>,
    window: Window,
}

/// What the schedule did since the last [`Pacer::take_window`] — for the
/// ~1 Hz diagnostics line.
#[derive(Debug, Default, Clone, Copy, PartialEq)]
pub struct Window {
    pub frames: u32,
    /// Sum and maximum of (due − arrival), seconds.
    pub delay_sum: f64,
    pub delay_max: f64,
    /// Frames that arrived after their slot — shown on arrival, a visible hitch.
    pub late: u32,
    /// Frames pulled earlier than their slot by the [`MAX_DELAY`] cap.
    pub capped: u32,
}

impl Pacer {
    pub fn new() -> Self {
        Self::default()
    }

    /// When the frame that arrived at `arrival` should be shown.
    pub fn due(&mut self, arrival: f64) -> f64 {
        let gap = self.last_arrival.map(|last| arrival - last);
        self.last_arrival = Some(arrival);
        let continuing = gap.is_some_and(|g| (0.0..=RESTART_AFTER).contains(&g));
        if !continuing {
            self.last_due = None;
        }
        if let Some(g) = gap.filter(|_| continuing) {
            let interval = self.interval.unwrap_or(INITIAL_INTERVAL);
            self.interval = Some(interval + INTERVAL_WEIGHT * (g - interval));
        }
        let due = match self.last_due {
            None => arrival,
            Some(previous) => {
                let slot = previous + self.interval.unwrap_or(INITIAL_INTERVAL) * (1.0 - DRAIN);
                if slot < arrival {
                    if arrival - slot > LATE_TOLERANCE {
                        self.window.late += 1;
                    }
                    arrival
                } else if slot > arrival + MAX_DELAY {
                    self.window.capped += 1;
                    arrival + MAX_DELAY
                } else {
                    slot
                }
            }
        };
        self.last_due = Some(due);
        let delay = due - arrival;
        self.window.frames += 1;
        self.window.delay_sum += delay;
        self.window.delay_max = self.window.delay_max.max(delay);
        due
    }

    /// The window's figures, restarting the count.
    pub fn take_window(&mut self) -> Window {
        std::mem::take(&mut self.window)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const FRAME: f64 = 1.0 / 60.0;

    /// Runs `arrivals` through a pacer, returning the due times.
    fn pace(arrivals: &[f64]) -> Vec<f64> {
        let mut pacer = Pacer::new();
        arrivals.iter().map(|&a| pacer.due(a)).collect()
    }

    fn steady(n: usize, start: f64) -> Vec<f64> {
        (0..n).map(|i| start + i as f64 * FRAME).collect()
    }

    #[test]
    fn an_even_stream_is_shown_on_arrival() {
        let arrivals = steady(120, 0.0);
        let mut pacer = Pacer::new();
        for &arrival in &arrivals {
            let due = pacer.due(arrival);
            assert!((due - arrival).abs() < 0.001, "due {due} vs arrival {arrival}");
        }
        let w = pacer.take_window();
        assert_eq!((w.late, w.capped), (0, 0), "{w:?}");
    }

    #[test]
    fn no_frame_is_shown_before_it_arrives_or_held_past_the_cap() {
        // Wi-Fi-like: bursts and gaps around a 60 fps stream.
        let mut arrivals = Vec::new();
        let mut t = 0.0;
        for i in 0..600 {
            t += match i % 7 { 0 => 0.060, 1 | 2 => 0.002, _ => 0.012 };
            arrivals.push(t);
        }
        for (due, arrival) in pace(&arrivals).iter().zip(&arrivals) {
            assert!(*due >= *arrival - 1e-9, "shown before it arrived");
            assert!(*due <= arrival + MAX_DELAY + 1e-9, "held past the cap");
        }
    }

    #[test]
    fn due_times_never_go_backwards() {
        let mut arrivals = steady(60, 0.0);
        arrivals.extend(steady(60, 1.5)); // a still screen, then motion again
        let dues = pace(&arrivals);
        assert!(dues.windows(2).all(|w| w[1] >= w[0]));
    }

    #[test]
    fn a_burst_after_a_late_frame_is_spread_back_out() {
        // Steady, then one frame 40 ms late and the next three bunched behind it.
        let mut arrivals = steady(120, 0.0);
        let t = *arrivals.last().unwrap();
        arrivals.extend([t + FRAME + 0.040, t + FRAME + 0.042, t + FRAME + 0.044, t + FRAME + 0.046]);
        let dues = pace(&arrivals);
        let tail = &dues[dues.len() - 4..];
        // On arrival they would be 2 ms apart; paced they keep near a frame apart.
        for pair in tail.windows(2) {
            let spacing = pair[1] - pair[0];
            assert!(spacing > FRAME * 0.9, "bunched: {spacing}");
        }
    }

    #[test]
    fn the_cushion_drains_once_the_network_calms() {
        let mut arrivals = steady(120, 0.0);
        let t = *arrivals.last().unwrap();
        arrivals.push(t + FRAME + 0.040); // one late frame builds a 40 ms shift
        let resume = arrivals.last().unwrap() + 0.002;
        arrivals.extend((0..1200).map(|i| resume + i as f64 * FRAME));
        let mut pacer = Pacer::new();
        let mut last_delay = 0.0;
        for &a in &arrivals {
            last_delay = pacer.due(a) - a;
        }
        assert!(last_delay < 0.005, "still {last_delay}s behind after 20 s of calm");
    }

    #[test]
    fn a_still_screen_restarts_at_the_live_edge() {
        let mut arrivals = steady(60, 0.0);
        arrivals.push(arrivals.last().unwrap() + 0.002); // ends on a burst (cushion)
        arrivals.push(arrivals.last().unwrap() + 2.0);
        let dues = pace(&arrivals);
        assert_eq!(dues.last(), arrivals.last());
    }

    #[test]
    fn the_window_reports_late_and_capped_frames() {
        let mut pacer = Pacer::new();
        for a in steady(60, 0.0) {
            pacer.due(a);
        }
        pacer.take_window();
        let t = 59.0 * FRAME;
        pacer.due(t + 0.060); // 43 ms late
        // Six frames at once: the schedule hits the cap.
        for i in 0..6 {
            pacer.due(t + 0.061 + i as f64 * 0.0005);
        }
        let w = pacer.take_window();
        assert_eq!(w.frames, 7);
        assert_eq!(w.late, 1);
        assert!(w.capped >= 1, "{w:?}");
        assert!(w.delay_max <= MAX_DELAY + 1e-9);
        assert_eq!(pacer.take_window(), Window::default());
    }
}
