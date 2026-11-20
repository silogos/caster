//! The video window and the frame path (ADR-038 §1): one file per platform,
//! the same functions on both. What does not depend on the platform lives
//! here: the stream generation, the live flag, the picture's range, and what
//! the deck is told. `mac.rs` is the AppKit window with its
//! `AVSampleBufferDisplayLayer`; `windows.rs` is the Win32 + D3D11 window
//! (a stub until W1, so the crate builds on Windows — roadmap S0).

use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

#[cfg(target_os = "macos")]
mod mac;
#[cfg(target_os = "macos")]
pub use mac::*;

#[cfg(target_os = "windows")]
mod windows;
#[cfg(target_os = "windows")]
pub use windows::*;

/// The YCbCr range of the decoded frames, followed per stream. The display
/// layer takes the range from the pool's pixel format, so a wrong guess is
/// visible: full-range data read as video range crushes dark tones to black
/// and clips highlights (an app's `#161b22` background showed as near
/// black); video-range data read as full range greys the blacks. Neither
/// side can be assumed — VideoToolbox's H.264 decoder writes its own
/// format, libwebrtc's software decoders the stream's nominal one.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum VideoRange {
    /// 16–235 luma ('420v').
    Limited,
    /// 0–255 luma ('420f').
    Full,
}

/// Remembers a stream's range, so it is read from a frame only when it can
/// have changed.
///
/// Used by the copy path only (a zero-copy frame states its range in the
/// buffer it is shown from). The only place a VideoToolbox frame states its
/// range is its pixel buffer's format. Before reads went through a drained
/// autorelease pool ([`decoder_pixel_buffer`]), each one pinned the decoded
/// surface: called per frame that was 3.5 MB each at 1920×1200, about 12 GB
/// a minute, until the Mac ran out of memory after ~16 500 frames (found
/// live 2026-11-10). The range belongs to the encoder session, so one read
/// per stream, and one more when the dimensions change (a new encoder
/// session), is still all it takes.
pub struct RangeTracker {
    known: Option<((u32, u32), VideoRange)>,
}

impl RangeTracker {
    pub fn new() -> Self {
        Self { known: None }
    }

    /// The range for a frame of these dimensions. `read` — the costly look
    /// at the frame ([`read_range_of_frame`]) — runs only when the answer
    /// is not known yet. A software-decoded frame (`native == false`) has
    /// no surface and no tag: libwebrtc's software decoders emit the
    /// stream's nominal video range.
    pub fn range(&mut self, width: u32, height: u32, native: bool, read: impl FnOnce() -> VideoRange) -> VideoRange {
        if !native {
            // A switch back to the hardware decoder reads again.
            self.known = None;
            return VideoRange::Limited;
        }
        match self.known {
            Some((dims, range)) if dims == (width, height) => range,
            _ => {
                let range = read();
                self.known = Some(((width, height), range));
                range
            }
        }
    }
}

/// Why a frame goes through the I420 copy instead of zero-copy (logged once
/// per stream and reason by the frame loop).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CopyReason {
    /// `CASTER_VIDEO_COPY` is set: the A/B switch for measuring the two paths.
    Forced,
    /// No VideoToolbox buffer behind the frame (a software decoder).
    NoDecoderBuffer,
    /// A pixel format the window's NV12 path does not take (10-bit, 4:2:2…).
    PixelFormat(u32),
    /// The buffer's size differs from the frame's (a cropped or padded frame).
    Size,
}

/// How often the frame thread asks the main thread for window visibility.
pub const VISIBILITY_POLL_INTERVAL: Duration = Duration::from_millis(250);

/// A stream is delivering frames. While false the window shows the
/// "no input" card, like a capture card with nothing plugged in — OBS
/// records that card, never a frozen last frame.
static LIVE: AtomicBool = AtomicBool::new(false);

/// Bumped for every new video stream and whenever the media pc closes;
/// a frame loop whose generation is no longer current exits (libwebrtc's
/// stream does not end when its pc closes — each cast leaked a thread).
static STREAM_GENERATION: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

/// A new video stream starts; returns its generation.
pub fn begin_stream() -> u64 {
    STREAM_GENERATION.fetch_add(1, Ordering::Relaxed) + 1
}

/// The media pc closed: whatever stream was running is over.
pub fn end_streams() {
    STREAM_GENERATION.fetch_add(1, Ordering::Relaxed);
}

pub fn is_current_stream(generation: u64) -> bool {
    STREAM_GENERATION.load(Ordering::Relaxed) == generation
}

pub fn is_live() -> bool {
    LIVE.load(Ordering::Relaxed)
}

/// Any thread: records whether input is live. True when it changed — the
/// caller then tells the UI and applies the card on the main thread.
pub fn set_live(live: bool) -> bool {
    LIVE.swap(live, Ordering::Relaxed) != live
}

/// What the frame loop must do on the main thread after a frame.
#[derive(PartialEq, Eq)]
pub enum Followup {
    None,
    /// The window has never shown a frame — raise it ([`show_video_window`]).
    RaiseWindow,
}

// ---- the picture's state for the deck, shared by both links' frame loops (Wi-Fi and USB) ----

/// The picture the receiver is actually getting, measured once a second:
/// what the decoder hands over, not what the phone said it captures. The
/// two differ whenever the sender scales the frame down or drops frames
/// under pressure (found 2026-11-11: 640×400 arrived under a status that
/// read 1920×1200 · 60 fps).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct ReceivedPicture {
    pub(crate) width: u32,
    pub(crate) height: u32,
    pub(crate) fps: u32,
}

/// The picture that is arriving changed, or stopped arriving (None): the
/// deck's live readout shows it. The cast's settings stay the phone's
/// `session-info`, on the pill and the tray.
pub(crate) fn announce_picture(app: &tauri::AppHandle, picture: Option<ReceivedPicture>) {
    let payload = picture.map(|p| serde_json::json!({ "width": p.width, "height": p.height, "fps": p.fps }));
    let _ = tauri::Emitter::emit(app, "cast:picture", payload);
}

/// Input went live or stopped: the deck's ON AIR, and the video window's
/// NO INPUT card (main thread). No-op when nothing changed.
pub(crate) fn announce_input(app: &tauri::AppHandle, live: bool) {
    if crate::video::set_live(live) {
        let _ = tauri::Emitter::emit(app, "cast:state", serde_json::json!({ "live": live }));
        let _ = app.run_on_main_thread(crate::video::apply_no_input_card);
        #[cfg(target_os = "macos")]
        if !live {
            // OBS shows the still, not the last frame of the cast (ADR-021).
            crate::syphon::publish_no_input();
            // The camera goes back to OBS's own picture (ADR-039).
            crate::obs_camera::cast_ended();
        }
    }
}

/// Keeps the video window's visibility fresh from the main thread, at most
/// every [`crate::video::VISIBILITY_POLL_INTERVAL`] — called by every frame
/// loop (Wi-Fi and USB).
pub(crate) fn poll_window_visibility(app: &tauri::AppHandle, last: &mut Option<std::time::Instant>) {
    if last.map_or(false, |t| t.elapsed() < crate::video::VISIBILITY_POLL_INTERVAL) {
        return;
    }
    *last = Some(std::time::Instant::now());
    let poll_app = app.clone();
    let _ = app.run_on_main_thread(move || {
        // The window's own close button: silence the sound, update the UI.
        if crate::video::refresh_visibility() {
            crate::remember_video_open(&poll_app, false);
            crate::audio::set_window_open(false);
            crate::tray::sync_dock_icon(&poll_app);
            let _ = tauri::Emitter::emit(&poll_app, "video:window", serde_json::json!({ "open": false }));
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_closed_pc_retires_the_running_frame_loop() {
        let first = begin_stream();
        assert!(is_current_stream(first));
        end_streams(); // the phone stopped casting (bye → pc closed)
        assert!(!is_current_stream(first), "the old loop must exit, not wait forever");
        let second = begin_stream();
        assert!(is_current_stream(second) && !is_current_stream(first));
    }

    #[test]
    fn live_changes_are_reported_once() {
        set_live(false);
        assert!(set_live(true), "no input → live: show the video");
        assert!(!set_live(true), "already live: nothing to announce");
        assert!(set_live(false), "live → no input: show the card");
        assert!(!is_live());
    }

    #[test]
    fn the_range_is_read_once_per_stream_and_again_when_the_size_changes() {
        let reads = std::cell::Cell::new(0);
        let read = |range: VideoRange| {
            let reads = &reads;
            move || {
                reads.set(reads.get() + 1);
                range
            }
        };
        let mut tracker = RangeTracker::new();
        // A minute of frames at one size: one read, not 3 600.
        for _ in 0..3_600 {
            assert_eq!(tracker.range(1920, 1200, true, read(VideoRange::Full)), VideoRange::Full);
        }
        assert_eq!(reads.get(), 1, "every read pins a decoded frame — it must not happen per frame");
        // The quality ladder or a rotation starts a new encoder session.
        assert_eq!(tracker.range(1280, 800, true, read(VideoRange::Limited)), VideoRange::Limited);
        assert_eq!(tracker.range(1280, 800, true, read(VideoRange::Full)), VideoRange::Limited, "remembered, not re-read");
        assert_eq!(reads.get(), 2);
    }

    #[test]
    fn software_frames_are_video_range_without_a_read() {
        let reads = std::cell::Cell::new(0);
        let mut tracker = RangeTracker::new();
        let mut read = || {
            reads.set(reads.get() + 1);
            VideoRange::Full
        };
        assert_eq!(tracker.range(1920, 1200, true, &mut read), VideoRange::Full);
        assert_eq!(tracker.range(1920, 1200, false, &mut read), VideoRange::Limited, "a software frame has no surface to ask");
        assert_eq!(reads.get(), 1);
        assert_eq!(tracker.range(1920, 1200, true, &mut read), VideoRange::Full);
        assert_eq!(reads.get(), 2, "back on the hardware decoder: asked once more");
    }
}
