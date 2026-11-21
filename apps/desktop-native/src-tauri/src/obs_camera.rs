//! The OBS Virtual Camera output (ADR-039): the cast goes into OBS Studio's
//! own camera extension, so every camera app on this Mac (Zoom, Meet,
//! browsers, TikTok LIVE Studio) can pick it as "OBS Virtual Camera" —
//! while OBS itself stays closed. Syphon (ADR-021) is a separate output;
//! either or both can be on.
//!
//! A receiver/environment control (AGENTS.md): what this computer does with
//! the stream it already has, never how the phone casts.
//!
//! - The frame thread hands over the NV12 buffer it already made for the
//!   window, with the pacer's due time (`submit`). A thread releases it at
//!   that time into the camera's sink (`native/obs_camera_bridge.m`): the
//!   buffer itself, no conversion, no copy.
//! - The sink runs only while a cast is live. Between casts it stops, and
//!   the camera shows OBS's own picture, not a frozen last frame.
//! - Unlike Syphon, nobody can tell whether a camera app is reading: CMIO
//!   reports the device as running as soon as our sink alone is (measured
//!   2026-11-22, ADR-039 §5). So frames go in whenever the output is on;
//!   that costs no conversion.
//!
//! Never logs frame content — counters and timings only.

use std::ffi::c_void;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};

use objc2_core_foundation::CFRetained;
use objc2_core_video::CVPixelBuffer;

use crate::publisher::Scheduler;

extern "C" {
    fn zf_obscam_probe() -> i32;
    fn zf_obscam_start() -> bool;
    fn zf_obscam_stop();
    fn zf_obscam_send(pixel_buffer: *const c_void, seconds: f64) -> i32;
    #[cfg(test)]
    fn zf_obscam_uid_in_app(app_path: *const std::ffi::c_char, out: *mut std::ffi::c_char, capacity: usize) -> bool;
    #[cfg(test)]
    fn zf_obscam_queued() -> i32;
}

/// `zf_obscam_send`'s answers (the bridge's ZF_OBSCAM_SENT / _QUEUE_FULL).
const SENT: i32 = 0;
const QUEUE_FULL: i32 = 1;

/// Frames waiting for their due time; as for Syphon, a fuller queue means
/// the output is behind and the newest frame is dropped.
const QUEUE_FRAMES: usize = 6;
/// The output thread's idle wake-up (it has no client count to poll).
const IDLE_TICK: Duration = Duration::from_secs(1);
const LOG_EVERY_FRAMES: u64 = 3_600;
/// The event that tells the window the output switched itself off.
pub const FAILED_EVENT: &str = "obs-camera:failed";

/// Whether OBS's camera can be fed on this Mac — what the switch shows.
#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "kebab-case")]
pub enum Availability {
    Ready,
    /// OBS Studio is not installed.
    NoObs,
    /// OBS is installed but has no camera extension (before OBS 30).
    OldObs,
    /// OBS's camera is not allowed in macOS yet: OBS must start it once.
    NotSetUp,
}

impl Availability {
    /// From the bridge's ZF_OBSCAM_* code.
    fn from_code(code: i32) -> Self {
        match code {
            0 => Self::Ready,
            1 => Self::NoObs,
            2 => Self::OldObs,
            _ => Self::NotSetUp,
        }
    }
}

/// Asks the system now. Cheap: the window calls it when it comes to the front.
pub fn availability() -> Availability {
    Availability::from_code(unsafe { zf_obscam_probe() })
}

/// What goes to the output thread, in order.
enum Item {
    /// One frame. The buffer is only retained and released across threads,
    /// never mutated after the frame thread filled it.
    Frame(CFRetained<CVPixelBuffer>, f64),
    /// The cast ended: stop the sink after the frames before it.
    CastEnded,
}
unsafe impl Send for Item {}

static OUTPUT: Mutex<Option<Scheduler<Item>>> = Mutex::new(None);
static ENABLED: AtomicBool = AtomicBool::new(false);
static APP: OnceLock<tauri::AppHandle> = OnceLock::new();

/// The output thread's own state.
#[derive(Default)]
struct Feed {
    running: bool,
    sent: u64,
    dropped: u64,
    send_micros: u64,
}

/// Gives the output a way to tell the window it switched itself off.
pub fn init(app: tauri::AppHandle) {
    let _ = APP.set(app);
}

/// Turns the output on or off. Returns whether it is on afterwards: it
/// does not turn on unless OBS's camera is there to feed.
pub fn set_enabled(on: bool) -> bool {
    let mut output = OUTPUT.lock().expect("obs camera output");
    if on && output.is_none() {
        let found = availability();
        if found != Availability::Ready {
            log::info!(target: "camera", "OBS Virtual Camera not turned on: {found:?}");
            return false;
        }
        let mut feed = Feed::default();
        *output = Some(Scheduler::spawn("obs-camera-out", QUEUE_FRAMES, crate::video::host_now, move |item| feed.take(item), || {}, IDLE_TICK));
        ENABLED.store(true, Ordering::Relaxed);
        log::info!(target: "camera", "OBS Virtual Camera on — fed while a cast is live");
    } else if !on && output.take().is_some() {
        // Dropping the scheduler ends its thread; the sink stops here
        // whether or not the thread got to it.
        ENABLED.store(false, Ordering::Relaxed);
        unsafe { zf_obscam_stop() };
        log::info!(target: "camera", "OBS Virtual Camera off");
    }
    output.is_some()
}

/// Whether the frame thread should hand frames over.
pub fn wants_frames() -> bool {
    ENABLED.load(Ordering::Relaxed)
}

/// Hands one frame over, to be sent at `due` (host-clock seconds).
pub fn submit(pixel_buffer: &CFRetained<CVPixelBuffer>, due: f64) {
    let output = OUTPUT.lock().expect("obs camera output");
    if let Some(scheduler) = output.as_ref() {
        if !scheduler.submit(Item::Frame(pixel_buffer.clone(), due), due) {
            log::debug!(target: "camera", "output behind — a frame dropped before its slot");
        }
    }
}

/// The cast ended: the camera goes back to OBS's own picture.
pub fn cast_ended() {
    let output = OUTPUT.lock().expect("obs camera output");
    if let Some(scheduler) = output.as_ref() {
        if !scheduler.submit(Item::CastEnded, 0.0) {
            // The queue is full: stop now rather than leave the last frame up.
            unsafe { zf_obscam_stop() };
        }
    }
}

impl Feed {
    /// Output thread: one item, at its due time.
    fn take(&mut self, item: Item) {
        match item {
            Item::Frame(buffer, due) => self.send(&buffer, due),
            Item::CastEnded => {
                if self.running {
                    unsafe { zf_obscam_stop() };
                    self.running = false;
                    log::info!(target: "camera", "cast ended — OBS Virtual Camera shows OBS's own picture ({} frames sent, {} dropped)", self.sent, self.dropped);
                    *self = Feed::default();
                }
            }
        }
    }

    fn send(&mut self, buffer: &CFRetained<CVPixelBuffer>, due: f64) {
        if !ENABLED.load(Ordering::Relaxed) {
            return;
        }
        if !self.running {
            if !unsafe { zf_obscam_start() } {
                fail("the camera's sink could not start");
                return;
            }
            self.running = true;
            log::info!(target: "camera", "cast live — feeding OBS Virtual Camera");
        }
        let started = Instant::now();
        let result = unsafe { zf_obscam_send(CFRetained::as_ptr(buffer).as_ptr() as *const c_void, due) };
        if result != SENT {
            self.dropped += 1;
            if self.dropped == 1 || self.dropped.is_multiple_of(LOG_EVERY_FRAMES) {
                let why = if result == QUEUE_FULL { "the camera is not taking frames" } else { "a frame could not be sent" };
                log::warn!(target: "camera", "{why} — {} dropped", self.dropped);
            }
            return;
        }
        self.send_micros += started.elapsed().as_micros() as u64;
        self.sent += 1;
        if self.sent == 1 || self.sent.is_multiple_of(LOG_EVERY_FRAMES) {
            log::info!(
                target: "camera", "{} frames sent | {} dropped, send avg {:.3} ms",
                self.sent, self.dropped, self.send_micros as f64 / 1_000.0 / self.sent as f64
            );
        }
    }
}

/// The output switches itself off and the window says so in plain words;
/// the detail stays in the log.
fn fail(detail: &str) {
    log::error!(target: "camera", "OBS Virtual Camera switched off: {detail}");
    ENABLED.store(false, Ordering::Relaxed);
    if let Some(app) = APP.get() {
        let _ = tauri::Emitter::emit(app, FAILED_EVENT, ());
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::ffi::{CStr, CString};

    const OBS_UID: &str = "7626645E-4425-469E-9D8B-97E0FA59AC75";

    /// A fake OBS.app holding one system extension with `plist` as its Info.plist.
    fn fake_app(name: &str, plist: &str) -> std::path::PathBuf {
        let app = std::env::temp_dir().join(format!("caster-obscam-{name}-{}.app", std::process::id()));
        let contents = app.join("Contents/Library/SystemExtensions/com.obsproject.obs-studio.mac-camera-extension.systemextension/Contents");
        std::fs::create_dir_all(&contents).unwrap();
        std::fs::write(contents.join("Info.plist"), plist).unwrap();
        app
    }

    fn uid_in(app: &std::path::Path) -> Option<String> {
        let path = CString::new(app.to_str().unwrap()).unwrap();
        let mut out = [0 as std::ffi::c_char; 64];
        unsafe { zf_obscam_uid_in_app(path.as_ptr(), out.as_mut_ptr(), out.len()) }
            .then(|| unsafe { CStr::from_ptr(out.as_ptr()) }.to_str().unwrap().to_owned())
    }

    fn plist(entries: &str) -> String {
        format!(r#"<?xml version="1.0" encoding="UTF-8"?><plist version="1.0"><dict>{entries}</dict></plist>"#)
    }

    #[test]
    fn the_camera_is_found_by_the_uid_in_obss_extension_bundle() {
        let app = fake_app("found", &plist(&format!("<key>CFBundleIdentifier</key><string>x</string><key>OBSCameraDeviceUUID</key><string>{OBS_UID}</string>")));
        assert_eq!(uid_in(&app).as_deref(), Some(OBS_UID));
        let _ = std::fs::remove_dir_all(app);
    }

    #[test]
    fn an_obs_without_the_uid_key_has_no_camera_to_feed() {
        let app = fake_app("old", &plist("<key>CFBundleIdentifier</key><string>x</string>"));
        assert_eq!(uid_in(&app), None, "OBS before 30, or an OBS that renamed the key");
        let _ = std::fs::remove_dir_all(app);
    }

    #[test]
    fn a_uid_that_is_not_a_uuid_is_not_trusted() {
        let app = fake_app("bad", &plist("<key>OBSCameraDeviceUUID</key><string>not-a-uuid</string>"));
        assert_eq!(uid_in(&app), None);
        let _ = std::fs::remove_dir_all(app);
    }

    #[test]
    fn the_bridges_answers_map_to_what_the_switch_shows() {
        assert_eq!(Availability::from_code(0), Availability::Ready);
        assert_eq!(Availability::from_code(1), Availability::NoObs);
        assert_eq!(Availability::from_code(2), Availability::OldObs);
        assert_eq!(Availability::from_code(3), Availability::NotSetUp);
        assert_eq!(serde_json::to_value(Availability::NotSetUp).unwrap(), "not-set-up", "the window's vocabulary");
    }

    /// End to end through the bridge and OBS's real extension: frames sent
    /// into the sink are taken by the extension (the queue drains), the
    /// sink stops cleanly. Needs OBS 30+ installed with its camera allowed;
    /// skipped, with the reason, otherwise. It cannot check what a camera
    /// app shows: reading a camera needs a permission tests don't have
    /// (that check is manual, features/virtual-camera.md).
    #[test]
    fn frames_sent_to_obss_camera_are_taken_by_its_extension() {
        let found = availability();
        if found != Availability::Ready {
            eprintln!("OBS Virtual Camera not available here ({found:?}) — skipping the bridge test");
            return;
        }
        let (width, height) = (64u32, 48u32);
        let y = vec![128u8; (width * height) as usize];
        let chroma = vec![128u8; (width / 2 * height / 2) as usize];
        let buffer = crate::video::test_nv12_buffer(width, height, crate::video::VideoRange::Limited, &y, &chroma, &chroma)
            .expect("an NV12 buffer");
        assert!(unsafe { zf_obscam_start() }, "the sink starts");
        let mut sent = 0;
        for _ in 0..30 {
            let at = crate::video::host_now();
            if unsafe { zf_obscam_send(CFRetained::as_ptr(&buffer).as_ptr() as *const c_void, at) } == SENT {
                sent += 1;
            }
            std::thread::sleep(Duration::from_millis(17));
        }
        std::thread::sleep(Duration::from_millis(100));
        let left = unsafe { zf_obscam_queued() };
        unsafe { zf_obscam_stop() };
        assert_eq!(sent, 30, "no frame refused at 60 fps");
        assert!(left <= 1, "the extension drained the queue ({left} left)");
        assert_eq!(unsafe { zf_obscam_queued() }, -1, "stopped");
    }
}
