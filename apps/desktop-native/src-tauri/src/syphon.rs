//! The Syphon output (ADR-021): the cast as a Syphon server, so OBS on the
//! same Mac reads it without capturing a window — and keeps reading it
//! while the video window is closed or covered.
//!
//! A receiver/environment control (AGENTS.md): it decides what this
//! computer does with the stream it already has, never how the phone casts.
//!
//! - The frame thread hands over the NV12 buffer it already made for the
//!   window, with the pacer's due time (`submit`). Nothing is copied: the
//!   buffer is IOSurface-backed and the Objective-C bridge
//!   (`native/syphon_bridge.m`) converts it to BGRA on the GPU.
//! - A small thread publishes each frame when it is due, so OBS sees the
//!   same even cadence as the window (ADR-017), not Wi-Fi's bursts.
//! - Frames are only taken while a client is connected; with none, the
//!   output costs nothing.
//! - Between casts the server stays up and shows one "No input video"
//!   still, not the previous cast's last frame (ADR-007's finding).
//!
//! Never logs frame content — counters and timings only.

use std::ffi::{c_char, c_void, CString};
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::sync::mpsc::{sync_channel, Receiver, RecvTimeoutError, SyncSender};
use std::sync::Mutex;
use std::time::{Duration, Instant};

use objc2_core_foundation::CFRetained;
use objc2_core_video::CVPixelBuffer;

extern "C" {
    fn zf_syphon_start(name: *const c_char) -> bool;
    fn zf_syphon_stop();
    fn zf_syphon_has_clients() -> bool;
    fn zf_syphon_publish(pixel_buffer: *const c_void) -> bool;
    fn zf_syphon_publish_still(width: usize, height: usize, text: *const c_char) -> bool;
    #[cfg(test)]
    fn zf_syphon_copy_frame(out: *mut u8, capacity: usize, width: *mut usize, height: *mut usize) -> bool;
}

/// The name OBS lists. The same as the Electron receiver's, so a scene
/// made for one keeps working with the other.
pub const SERVER_NAME: &str = "Caster";
const NO_INPUT_TEXT: &str = "No input video";
/// Frames waiting for their due time. The pacer holds a frame at most
/// 50 ms (three frames at 60 fps); a fuller queue means publishing is
/// behind, and the newest frame is dropped rather than queued.
const QUEUE_FRAMES: usize = 6;
/// How often the client count is read. A client that connects gets frames
/// within this long.
const CLIENT_POLL_INTERVAL: Duration = Duration::from_millis(250);
/// No frame waits longer than this for its slot, whatever its due time says.
const MAX_WAIT: Duration = Duration::from_millis(100);
const LOG_EVERY_FRAMES: u64 = 3_600;
/// The still's size before any cast has set one.
const DEFAULT_STILL: (usize, usize) = (1_920, 1_080);

/// Runs `sink` on its own thread for each submitted item, not before the
/// item's due time (seconds on `now`'s clock). `tick` runs on the same
/// thread about every `tick_every`, frames or not.
pub struct Scheduler<T> {
    queue: SyncSender<(T, f64)>,
}

impl<T: Send + 'static> Scheduler<T> {
    pub fn spawn(
        capacity: usize,
        now: fn() -> f64,
        sink: impl FnMut(T) + Send + 'static,
        tick: impl FnMut() + Send + 'static,
        tick_every: Duration,
    ) -> Self {
        let (queue, items) = sync_channel(capacity);
        std::thread::Builder::new()
            .name("syphon-out".into())
            .spawn(move || run(items, now, sink, tick, tick_every))
            .expect("spawn the syphon output thread");
        Self { queue }
    }

    /// False when the queue is full: the item is dropped, the sink is behind.
    pub fn submit(&self, item: T, due: f64) -> bool {
        self.queue.try_send((item, due)).is_ok()
    }
}

/// Ends when the [`Scheduler`] is dropped.
fn run<T>(items: Receiver<(T, f64)>, now: fn() -> f64, mut sink: impl FnMut(T), mut tick: impl FnMut(), tick_every: Duration) {
    let mut last_tick: Option<Instant> = None;
    loop {
        if last_tick.is_none_or(|t| t.elapsed() >= tick_every) {
            last_tick = Some(Instant::now());
            tick();
        }
        match items.recv_timeout(tick_every) {
            Ok((item, due)) => {
                let wait = due - now();
                if wait > 0.0 {
                    std::thread::sleep(Duration::from_secs_f64(wait).min(MAX_WAIT));
                }
                sink(item);
            }
            Err(RecvTimeoutError::Timeout) => {}
            Err(RecvTimeoutError::Disconnected) => return,
        }
    }
}

/// One frame on its way to the publisher thread. The buffer is only
/// retained and released across threads, never mutated after the frame
/// thread filled it.
struct Frame(CFRetained<CVPixelBuffer>);
unsafe impl Send for Frame {}

static OUTPUT: Mutex<Option<Scheduler<Frame>>> = Mutex::new(None);
static ENABLED: AtomicBool = AtomicBool::new(false);
static HAS_CLIENTS: AtomicBool = AtomicBool::new(false);
static PUBLISHED: AtomicU64 = AtomicU64::new(0);
static DROPPED: AtomicU64 = AtomicU64::new(0);
static PUBLISH_MICROS: AtomicU64 = AtomicU64::new(0);
static FRAME_WIDTH: AtomicUsize = AtomicUsize::new(0);
static FRAME_HEIGHT: AtomicUsize = AtomicUsize::new(0);

/// Turns the output on or off. Returns whether it is on afterwards (a Mac
/// without a Metal device cannot start it).
pub fn set_enabled(on: bool) -> bool {
    let mut output = OUTPUT.lock().expect("syphon output");
    if on && output.is_none() {
        let name = CString::new(SERVER_NAME).expect("server name");
        if !unsafe { zf_syphon_start(name.as_ptr()) } {
            log::error!(target: "syphon", "the Syphon server could not start (no Metal device?)");
            return false;
        }
        *output = Some(Scheduler::spawn(QUEUE_FRAMES, crate::video::host_now, publish, poll_clients, CLIENT_POLL_INTERVAL));
        ENABLED.store(true, Ordering::Relaxed);
        log::info!(target: "syphon", "server \"{SERVER_NAME}\" started");
        publish_still();
    } else if !on && output.take().is_some() {
        ENABLED.store(false, Ordering::Relaxed);
        HAS_CLIENTS.store(false, Ordering::Relaxed);
        unsafe { zf_syphon_stop() };
        log::info!(
            target: "syphon", "server stopped — {} frames published, {} dropped",
            PUBLISHED.swap(0, Ordering::Relaxed),
            DROPPED.swap(0, Ordering::Relaxed)
        );
        PUBLISH_MICROS.store(0, Ordering::Relaxed);
    }
    output.is_some()
}

/// Whether the frame thread should make a buffer for the output: it is on
/// and somebody is reading it.
pub fn wants_frames() -> bool {
    takes_frames(ENABLED.load(Ordering::Relaxed), HAS_CLIENTS.load(Ordering::Relaxed))
}

fn takes_frames(enabled: bool, has_clients: bool) -> bool {
    enabled && has_clients
}

/// Hands one frame over, to be published at `due` (host-clock seconds).
pub fn submit(pixel_buffer: &CFRetained<CVPixelBuffer>, width: u32, height: u32, due: f64) {
    FRAME_WIDTH.store(width as usize, Ordering::Relaxed);
    FRAME_HEIGHT.store(height as usize, Ordering::Relaxed);
    let output = OUTPUT.lock().expect("syphon output");
    let Some(scheduler) = output.as_ref() else { return };
    if !scheduler.submit(Frame(pixel_buffer.clone()), due) {
        let dropped = DROPPED.fetch_add(1, Ordering::Relaxed) + 1;
        if dropped == 1 || dropped.is_multiple_of(LOG_EVERY_FRAMES) {
            log::warn!(target: "syphon", "publishing is behind — {dropped} frames dropped");
        }
    }
}

/// The cast ended: show the still instead of its last frame.
pub fn publish_no_input() {
    if ENABLED.load(Ordering::Relaxed) {
        publish_still();
    }
}

fn publish_still() {
    let (width, height) = match (FRAME_WIDTH.load(Ordering::Relaxed), FRAME_HEIGHT.load(Ordering::Relaxed)) {
        (0, _) | (_, 0) => DEFAULT_STILL,
        dims => dims,
    };
    let text = CString::new(NO_INPUT_TEXT).expect("still text");
    if !unsafe { zf_syphon_publish_still(width, height, text.as_ptr()) } {
        log::warn!(target: "syphon", "the no-input still could not be published");
    }
}

/// Publisher thread: one frame, at its due time.
fn publish(frame: Frame) {
    let started = Instant::now();
    let ok = unsafe { zf_syphon_publish(CFRetained::as_ptr(&frame.0).as_ptr() as *const c_void) };
    if !ok {
        DROPPED.fetch_add(1, Ordering::Relaxed);
        return;
    }
    let spent = started.elapsed().as_micros() as u64;
    let micros = PUBLISH_MICROS.fetch_add(spent, Ordering::Relaxed) + spent;
    let published = PUBLISHED.fetch_add(1, Ordering::Relaxed) + 1;
    if published == 1 || published.is_multiple_of(LOG_EVERY_FRAMES) {
        log::info!(
            target: "syphon", "{published} frames published | {} dropped, publish avg {:.2} ms",
            DROPPED.load(Ordering::Relaxed),
            micros as f64 / 1_000.0 / published as f64
        );
    }
}

/// Publisher thread: follows clients coming and going.
fn poll_clients() {
    let clients = unsafe { zf_syphon_has_clients() };
    if HAS_CLIENTS.swap(clients, Ordering::Relaxed) != clients {
        if clients {
            log::info!(target: "syphon", "a client connected — publishing frames");
        } else {
            log::info!(target: "syphon", "no client connected — idle");
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::mpsc::channel;

    fn clock() -> f64 {
        static START: std::sync::OnceLock<Instant> = std::sync::OnceLock::new();
        START.get_or_init(Instant::now).elapsed().as_secs_f64()
    }

    #[test]
    fn frames_are_published_in_order_and_not_before_they_are_due() {
        let (seen, results) = channel();
        let scheduler = Scheduler::spawn(8, clock, move |item: u32| seen.send((item, clock())).unwrap(), || {}, Duration::from_millis(50));
        let base = clock();
        let dues = [base + 0.030, base + 0.060, base + 0.090];
        for (i, due) in dues.iter().enumerate() {
            assert!(scheduler.submit(i as u32, *due));
        }
        for (i, due) in dues.iter().enumerate() {
            let (item, at) = results.recv_timeout(Duration::from_secs(2)).expect("published");
            assert_eq!(item, i as u32, "in the order they were handed over");
            assert!(at >= *due, "frame {i} published {:.1} ms early", (due - at) * 1000.0);
        }
    }

    #[test]
    fn a_frame_already_due_is_published_at_once() {
        let (seen, results) = channel();
        let scheduler = Scheduler::spawn(8, clock, move |item: u32| seen.send(item).unwrap(), || {}, Duration::from_millis(50));
        let started = Instant::now();
        assert!(scheduler.submit(7, clock() - 1.0));
        assert_eq!(results.recv_timeout(Duration::from_secs(2)), Ok(7));
        assert!(started.elapsed() < Duration::from_millis(50), "no wait for a late frame");
    }

    #[test]
    fn a_full_queue_drops_the_newest_frame() {
        let (release, gate) = channel::<()>();
        let (seen, results) = channel();
        // The sink blocks on the first frame, so the queue fills behind it.
        let scheduler = Scheduler::spawn(
            2,
            clock,
            move |item: u32| {
                let _ = gate.recv();
                seen.send(item).unwrap();
            },
            || {},
            Duration::from_millis(50),
        );
        assert!(scheduler.submit(0, 0.0));
        std::thread::sleep(Duration::from_millis(50)); // the sink now holds frame 0
        assert!(scheduler.submit(1, 0.0));
        assert!(scheduler.submit(2, 0.0));
        assert!(!scheduler.submit(3, 0.0), "the queue holds two; the third is dropped");
        for _ in 0..3 {
            release.send(()).unwrap();
        }
        let got: Vec<u32> = (0..3).map(|_| results.recv_timeout(Duration::from_secs(2)).unwrap()).collect();
        assert_eq!(got, [0, 1, 2]);
    }

    #[test]
    fn the_tick_runs_without_any_frames() {
        let (ticked, ticks) = channel();
        let _scheduler = Scheduler::spawn(2, clock, |_: u32| {}, move || ticked.send(()).unwrap(), Duration::from_millis(20));
        for _ in 0..3 {
            ticks.recv_timeout(Duration::from_secs(2)).expect("a tick");
        }
    }

    #[test]
    fn frames_are_taken_only_while_on_and_read() {
        assert!(!takes_frames(false, false));
        assert!(!takes_frames(true, false), "on, but nobody reads it: no work");
        assert!(!takes_frames(false, true));
        assert!(takes_frames(true, true));
    }

    /// End to end through the bridge: an NV12 frame goes in, the BGRA frame
    /// the Syphon server offers comes out — right size, right levels, and in
    /// Syphon's row order: bottom row first (OBS showed the picture upside
    /// down when it was top row first — found live 2026-11-10).
    /// Needs a Metal device (any Mac with a display); skipped without.
    #[test]
    fn the_bridge_publishes_a_converted_frame_in_syphons_row_order() {
        let name = CString::new("zfc-syphon-test").unwrap();
        if !unsafe { zf_syphon_start(name.as_ptr()) } {
            eprintln!("no Metal device — skipping the Syphon bridge test");
            return;
        }
        let (width, height) = (64u32, 48u32);
        // Video range: white on the top half, black on the bottom half.
        let y: Vec<u8> = (0..height).flat_map(|row| vec![if row < height / 2 { 235u8 } else { 16u8 }; width as usize]).collect();
        let chroma = vec![128u8; (width / 2 * height / 2) as usize];
        let buffer = crate::video::test_nv12_buffer(width, height, crate::video::VideoRange::Limited, &y, &chroma, &chroma)
            .expect("an NV12 buffer");
        assert!(unsafe { zf_syphon_publish(CFRetained::as_ptr(&buffer).as_ptr() as *const c_void) });

        let mut pixels = vec![0u8; (width * height * 4) as usize];
        let (mut w, mut h) = (0usize, 0usize);
        assert!(unsafe { zf_syphon_copy_frame(pixels.as_mut_ptr(), pixels.len(), &mut w, &mut h) });
        unsafe { zf_syphon_stop() };

        assert_eq!((w, h), (width as usize, height as usize));
        let pixel = |x: usize, row: usize| &pixels[(row * w + x) * 4..(row * w + x) * 4 + 4];
        let first_rows = pixel(w / 2, 4);
        let last_rows = pixel(w / 2, h - 5);
        assert!(first_rows[..3].iter().all(|c| *c <= 5), "the surface starts with the picture's bottom (black): {first_rows:?}");
        assert!(last_rows[..3].iter().all(|c| *c >= 250), "and ends with its top (white, video range expanded): {last_rows:?}");
        assert_eq!(last_rows[3], 255, "opaque");
    }
}
