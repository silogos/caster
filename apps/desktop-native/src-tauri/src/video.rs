//! The native video window: a raw NSWindow whose content view is backed by
//! an `AVSampleBufferDisplayLayer` (`resizeAspect` = the contain letterbox,
//! hardware-composited, no webview in the video path). OBS window-captures
//! THIS window (desktop.md: the window is the output device). It lives in
//! its own window (user decision, 2026-11-09); the main window keeps the UI.
//!
//! Thread rules — the cause of four aborts on 2026-11-09 ("Rust cannot catch
//! foreign exceptions", always in the frame task while the window was
//! occluded): AppKit window state is touched ONLY on the main thread
//! ([`create_video_window`], [`refresh_visibility`], [`show_video_window`]),
//! which publishes visibility through an atomic; the frame thread only
//! talks to the display layer, and every one of those calls runs inside a
//! single `objc2::exception::catch` guard — an Objective-C exception drops
//! one frame, it can never take the process down.
//!
//! Frames: libwebrtc's zero-copy hand-off (webrtc-sys `get_cv_pixel_buffer`)
//! leaks one ObjC wrapper + one native ref per call (every decoded IOSurface
//! pinned — 9 GB in 2 min, measured), so the I420 planes are CPU-copied
//! (~0.3 ms/frame) into OUR pooled NV12 buffers, bounded by the pool's
//! allocation threshold.
//!
//! Timing: the layer runs on a host-clock timebase and each sample carries
//! the time [`crate::pacing::Pacer`] gives it — shown on time, a few ms
//! after arrival when Wi-Fi bunched it (ADR-017), never on raw arrival.

use objc2::rc::Retained;
use objc2::runtime::{AnyClass, AnyObject, Bool, ClassBuilder, Sel};
use objc2::{class, msg_send, sel};
use objc2_core_foundation::{
    kCFTypeDictionaryKeyCallBacks, kCFTypeDictionaryValueCallBacks, CFDictionary, CFNumber, CFNumberType,
    CFRetained, CFString,
};
use objc2_core_media::{kCMTimeInvalid, CMClock, CMFormatDescription, CMSampleBuffer, CMSampleTimingInfo, CMTime, CMTimebase};
use objc2_core_video::{CVPixelBuffer, CVPixelBufferPool};
use objc2_foundation::{NSPoint, NSRect, NSSize, NSString};
use std::ffi::c_void;
use std::ptr::NonNull;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::OnceLock;
use std::time::{Duration, Instant};

type Id = *mut AnyObject;

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

impl VideoRange {
    fn pixel_format(self) -> u32 {
        match self {
            Self::Limited => objc2_core_video::kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
            Self::Full => objc2_core_video::kCVPixelFormatType_420YpCbCr8BiPlanarFullRange,
        }
    }

    fn fourcc(self) -> &'static str {
        match self {
            Self::Limited => "420v",
            Self::Full => "420f",
        }
    }
}

/// The range a decoder's own CVPixelBuffer format states. Copying its planes
/// into I420 keeps the values, so the copy has the same range.
pub fn range_of_decoder_format(format: u32) -> VideoRange {
    use objc2_core_video::*;
    const FULL_RANGE_FORMATS: [u32; 6] = [
        kCVPixelFormatType_420YpCbCr8BiPlanarFullRange,
        kCVPixelFormatType_420YpCbCr8PlanarFullRange,
        kCVPixelFormatType_422YpCbCr8BiPlanarFullRange,
        kCVPixelFormatType_444YpCbCr8BiPlanarFullRange,
        kCVPixelFormatType_420YpCbCr10BiPlanarFullRange,
        kCVPixelFormatType_422YpCbCr10BiPlanarFullRange,
    ];
    if FULL_RANGE_FORMATS.contains(&format) { VideoRange::Full } else { VideoRange::Limited }
}

/// Remembers a stream's range, so it is read from a frame only when it can
/// have changed.
///
/// Reading it costs a frame: the only place a VideoToolbox frame states its
/// range is its pixel buffer's format, and webrtc-sys' `get_cv_pixel_buffer`
/// leaves a reference on the decoded surface on every call (the module doc).
/// Called per frame it pinned every decoded frame — 3.5 MB each at
/// 1920×1200, about 12 GB a minute — until the Mac ran out of memory after
/// ~16 500 frames (found live 2026-11-10; the per-frame call came in with
/// the colour-range fix the day before). The range belongs to the encoder
/// session, so one read per stream, and one more when the dimensions change
/// (a new encoder session), is enough.
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

/// The range one decoded frame states in its pixel format.
///
/// **Pins the frame's decoded surface for the life of the process** (the
/// webrtc-sys leak above) — call it through [`RangeTracker`], never per frame.
pub fn read_range_of_frame(buffer: &dyn livekit::webrtc::video_frame::VideoBuffer) -> VideoRange {
    let Some(native) = buffer.as_native() else { return VideoRange::Limited };
    let pixel_buffer = native.get_cv_pixel_buffer() as *const CVPixelBuffer;
    if pixel_buffer.is_null() {
        return VideoRange::Limited;
    }
    // Not retained: the frame keeps it alive for this call.
    range_of_decoder_format(objc2_core_video::CVPixelBufferGetPixelFormatType(unsafe { &*pixel_buffer }))
}

/// Pool buffers alive at once (the layer's short queue + one in flight).
/// Past it, allocation fails and the frame is dropped — the real bound
/// (the first version passed a non-existent "MaximumBufferCount" key).
/// 6 dropped ~1 % of frames live (82 of 7 200) while the layer still held
/// queued samples; 8 left that headroom. Paced samples wait in the layer up
/// to `pacing::MAX_DELAY` (three more frames at 60 fps), hence 10.
const POOL_ALLOCATION_THRESHOLD: i64 = 10;
/// Sample times in nanoseconds (the host clock's own timescale).
const PTS_TIMESCALE: i32 = 1_000_000_000;
const INITIAL_WINDOW_SIZE: (f64, f64) = (960.0, 640.0);
const WINDOW_TITLE: &str = "Caster";
/// Frameless (product choice, 2026-11-09 — the Electron receiver's
/// frameless spec): no title bar, no traffic lights, square corners; OBS
/// captures pure video. Moved by dragging the video, resized from the
/// edges. Closable | Miniaturizable | Resizable without Titled draws no
/// chrome, but lets the Window menu's Minimize and Close apply to it.
const WINDOW_STYLE: u64 = (1 << 1) | (1 << 2) | (1 << 3);
const NS_BACKING_STORE_BUFFERED: usize = 2;
/// NSWindowOcclusionStateVisible.
const OCCLUSION_VISIBLE: u64 = 1 << 1;
/// AVQueuedSampleBufferRenderingStatusFailed.
const LAYER_STATUS_FAILED: isize = 2;
/// How often the frame thread asks the main thread for window visibility.
pub const VISIBILITY_POLL_INTERVAL: Duration = Duration::from_millis(250);
/// While hidden, queued samples are flushed this often (each pins a decoded
/// surface: 8.8 GB in 7 min behind another window, found live).
const HIDDEN_FLUSH_INTERVAL: Duration = Duration::from_millis(500);
/// A layer refusing samples this long is flushed to resume at the live edge.
const STALL_FLUSH_INTERVAL: Duration = Duration::from_secs(1);
/// A window that has never shown a frame this long after the stream began
/// was born buried behind the main window — raise it once.
const BURIED_RAISE_AFTER: Duration = Duration::from_secs(1);
/// Progress log cadence (~30 s at 60 fps).
const LOG_EVERY_FRAMES: u64 = 1_800;

// ---- the window (main thread only) -----------------------------------------

struct VideoWindow {
    window: Id,
    layer: Id,
}
// Created on the main thread and retained for the process. The window is
// only messaged on the main thread; the layer is messaged from the frame
// thread, which AVSampleBufferDisplayLayer's enqueue API supports.
unsafe impl Send for VideoWindow {}
unsafe impl Sync for VideoWindow {}

static VIDEO_WINDOW: OnceLock<VideoWindow> = OnceLock::new();
/// Published by the main thread ([`refresh_visibility`]), read per frame.
static WINDOW_VISIBLE: AtomicBool = AtomicBool::new(false);
/// The window is on screen but another window covers it (log only: a
/// covered window keeps rendering — see [`window_state`]).
static WINDOW_COVERED: AtomicBool = AtomicBool::new(false);
/// The user wants the video window: set by "Open video", cleared by
/// "Close video" or the window's own close button. A closed window renders
/// nothing (and the app keeps its audio silent — lib.rs).
static VIDEO_OPEN: AtomicBool = AtomicBool::new(false);
/// The user closed the window itself (Cmd+W / the Window menu), noticed by
/// the next [`refresh_visibility`]. Being off screen is not enough to
/// conclude that: a window on its way into the Dock, or one whose app is
/// hidden, is off screen too.
static CLOSE_REQUESTED: AtomicBool = AtomicBool::new(false);
/// A stream is delivering frames. While false the window shows the
/// "no input" card, like a capture card with nothing plugged in — OBS
/// records that card, never a frozen last frame.
static LIVE: AtomicBool = AtomicBool::new(false);
/// The "no input" card layer (a sublayer of the display layer).
static NO_INPUT_CARD: OnceLock<LayerRef> = OnceLock::new();

struct LayerRef(Id);
// Created and toggled on the main thread only; retained for the process.
unsafe impl Send for LayerRef {}
unsafe impl Sync for LayerRef {}

/// `CGColorRef` with its Objective-C type encoding (`^{CGColor=}`), so the
/// layer setters receive exactly what they declare.
#[repr(transparent)]
#[derive(Clone, Copy)]
struct CGColorRef(*const c_void);
unsafe impl objc2::encode::Encode for CGColorRef {
    const ENCODING: objc2::encode::Encoding =
        objc2::encode::Encoding::Pointer(&objc2::encode::Encoding::Struct("CGColor", &[]));
}

/// `CMTimebaseRef` with its Objective-C type encoding, for
/// `-setControlTimebase:`.
#[repr(transparent)]
#[derive(Clone, Copy)]
struct CMTimebaseRef(*const c_void);
unsafe impl objc2::encode::Encode for CMTimebaseRef {
    const ENCODING: objc2::encode::Encoding =
        objc2::encode::Encoding::Pointer(&objc2::encode::Encoding::Struct("OpaqueCMTimebase", &[]));
}

// objc2-core-media leaves this one unbound (a TODO in its generated code).
extern "C" {
    fn CMTimebaseCreateWithSourceClock(allocator: *const c_void, source_clock: &CMClock, timebase_out: *mut *mut CMTimebase) -> i32;
}

/// The host clock now, in seconds — the pacer's and the layer's time line.
pub(crate) fn host_now() -> f64 {
    unsafe { CMClock::host_time_clock().time().seconds() }
}

/// Puts the layer on a timebase that IS the host clock (rate 1, set to host
/// time), so a sample's host-clock pts is the moment it is shown. Without
/// it the layer shows every sample on arrival. The layer retains it.
unsafe fn attach_host_timebase(layer: Id) {
    let clock = CMClock::host_time_clock();
    let mut timebase: *mut CMTimebase = std::ptr::null_mut();
    let status = CMTimebaseCreateWithSourceClock(std::ptr::null(), &clock, &mut timebase);
    let Some(timebase) = NonNull::new(timebase).filter(|_| status == 0) else {
        log::warn!(target: "video", "timebase failed status={status} — frames show on arrival");
        return;
    };
    let timebase: CFRetained<CMTimebase> = CFRetained::from_raw(timebase);
    timebase.set_time(clock.time());
    timebase.set_rate(1.0);
    let _: () = msg_send![layer, setControlTimebase: CMTimebaseRef(&*timebase as *const CMTimebase as *const c_void)];
}

/// Signal Deck colors for the card (designs/desktop-signal-deck.figcraft).
const CARD_BG: u32 = 0x0e1216;
const CARD_LAMP: u32 = 0x5d6672;
const CARD_TITLE: u32 = 0xeef1f4;
const CARD_SUB: u32 = 0x8a93a0;
const CARD_BRAND: u32 = 0x5d6672;
/// CALayer autoresizing: fill the parent / stay centered / stay at the bottom.
const FILL_PARENT: u32 = 2 | 16; // width | height sizable
const KEEP_CENTERED: u32 = 1 | 4 | 8 | 32; // all margins flexible
const KEEP_AT_BOTTOM: u32 = 1 | 4 | 32; // x margins + top margin flexible

unsafe fn cg_color(rgb: u32) -> CGColorRef {
    let channel = |shift: u32| f64::from((rgb >> shift) & 0xff) / 255.0;
    let color: Id = msg_send![class!(NSColor), colorWithSRGBRed: channel(16), green: channel(8), blue: channel(0), alpha: 1.0f64];
    msg_send![color, CGColor]
}

unsafe fn text_layer(text: &str, font: &str, size: f64, rgb: u32, frame: NSRect, mask: u32, scale: f64) -> Id {
    let layer: Id = msg_send![class!(CATextLayer), layer];
    let string = NSString::from_str(text);
    let font_name = NSString::from_str(font);
    let center = NSString::from_str("center");
    let _: () = msg_send![layer, setString: &*string];
    let _: () = msg_send![layer, setFont: &*font_name];
    let _: () = msg_send![layer, setFontSize: size];
    let _: () = msg_send![layer, setForegroundColor: cg_color(rgb)];
    let _: () = msg_send![layer, setAlignmentMode: &*center];
    let _: () = msg_send![layer, setContentsScale: scale];
    let _: () = msg_send![layer, setFrame: frame];
    let _: () = msg_send![layer, setAutoresizingMask: mask];
    layer
}

/// Builds the "no input" card over the display layer: graphite, a grey
/// lamp, NO INPUT, a hint, and the brand — the Signal Deck look.
unsafe fn build_no_input_card(display_layer: Id, (w, h): (f64, f64), scale: f64) -> Id {
    let card: Id = msg_send![class!(CALayer), layer];
    let _: () = msg_send![card, setBackgroundColor: cg_color(CARD_BG)];
    let _: () = msg_send![card, setFrame: NSRect::new(NSPoint::new(0.0, 0.0), NSSize::new(w, h))];
    let _: () = msg_send![card, setAutoresizingMask: FILL_PARENT];
    let (cx, cy) = (w / 2.0, h / 2.0);
    // Layer coordinates: y grows upward from the bottom edge.
    let lamp: Id = msg_send![class!(CALayer), layer];
    let _: () = msg_send![lamp, setBackgroundColor: cg_color(CARD_LAMP)];
    let _: () = msg_send![lamp, setCornerRadius: 6.0f64];
    let _: () = msg_send![lamp, setFrame: NSRect::new(NSPoint::new(cx - 6.0, cy + 46.0), NSSize::new(12.0, 12.0))];
    let _: () = msg_send![lamp, setAutoresizingMask: KEEP_CENTERED];
    let _: () = msg_send![card, addSublayer: lamp];
    let title = text_layer("NO INPUT", "Menlo-Bold", 26.0, CARD_TITLE,
        NSRect::new(NSPoint::new(cx - 200.0, cy + 4.0), NSSize::new(400.0, 34.0)), KEEP_CENTERED, scale);
    let _: () = msg_send![card, addSublayer: title];
    let sub = text_layer("Start casting from your phone", "HelveticaNeue", 15.0, CARD_SUB,
        NSRect::new(NSPoint::new(cx - 250.0, cy - 28.0), NSSize::new(500.0, 22.0)), KEEP_CENTERED, scale);
    let _: () = msg_send![card, addSublayer: sub];
    let brand = text_layer("CASTER", "Menlo-Bold", 11.0, CARD_BRAND,
        NSRect::new(NSPoint::new(cx - 100.0, 24.0), NSSize::new(200.0, 16.0)), KEEP_AT_BOTTOM, scale);
    let _: () = msg_send![card, addSublayer: brand];
    let _: () = msg_send![card, setHidden: LIVE.load(Ordering::Relaxed)];
    let _: () = msg_send![display_layer, addSublayer: card];
    card
}

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

/// Main thread: shows the NO INPUT card while no input is live (dropping
/// the last frame, so a capture never freezes on stale video) and hides it
/// while frames flow.
pub fn apply_no_input_card() {
    let no_input = !is_live();
    let Some(card) = NO_INPUT_CARD.get() else { return };
    let layer = VIDEO_WINDOW.get().map(|vw| vw.layer);
    guarded("toggle no-input card", || unsafe {
        let _: () = msg_send![card.0, setHidden: !no_input];
        if no_input {
            if let Some(layer) = layer {
                let _: () = msg_send![layer, flushAndRemoveImage];
            }
        }
    });
    log::info!(target: "video", "{}", if no_input { "no input — showing the no-input card" } else { "input live — card hidden" });
}

/// Runs an ObjC block, turning an exception into a logged `false`.
fn guarded(what: &str, f: impl FnOnce()) -> bool {
    match objc2::exception::catch(std::panic::AssertUnwindSafe(f)) {
        Ok(()) => true,
        Err(exception) => {
            log::warn!(target: "video", "{what} raised: {}", exception_reason(exception));
            false
        }
    }
}

fn exception_reason(exception: Option<Retained<objc2::exception::Exception>>) -> String {
    exception.map_or_else(|| "(no exception object)".to_string(), |e| format!("{e:?}"))
}

/// Whether the user has the video window open.
pub fn is_open() -> bool {
    VIDEO_OPEN.load(Ordering::Relaxed)
}

/// Main thread: "Open video" — creates the window on first use and shows it.
pub fn open_video_window() {
    VIDEO_OPEN.store(true, Ordering::Relaxed);
    CLOSE_REQUESTED.store(false, Ordering::Relaxed);
    create_video_window();
    log::info!(target: "video", "video window opened");
}

/// Main thread: "Close video" — hides the window; frames stop rendering.
pub fn close_video_window() {
    VIDEO_OPEN.store(false, Ordering::Relaxed);
    WINDOW_VISIBLE.store(false, Ordering::Relaxed);
    if let Some(vw) = VIDEO_WINDOW.get() {
        guarded("close video window", || unsafe {
            let _: () = msg_send![vw.window, orderOut: std::ptr::null::<AnyObject>()];
            let _: () = msg_send![vw.layer, flush];
        });
    }
    log::info!(target: "video", "video window closed");
}

/// The video window's class: an `NSWindow` that may become main and key.
///
/// A window without a title bar answers NO to both by default, and AppKit
/// then treats it as a helper panel: it is left out of the app's Window
/// menu and of the Dock icon's window list, and cannot be minimized
/// (found live 2026-11-10: the open video window was missing from the
/// Dock). With these answers it is an ordinary window of the app that
/// merely draws no frame. Minimize and Close are routed to the calls that
/// work without title-bar buttons.
fn video_window_class() -> &'static AnyClass {
    static CLASS: OnceLock<&'static AnyClass> = OnceLock::new();
    extern "C-unwind" fn yes(_: &AnyObject, _: Sel) -> Bool {
        Bool::YES
    }
    extern "C-unwind" fn minimize(this: &AnyObject, _: Sel, sender: Id) {
        unsafe {
            let _: () = msg_send![this, miniaturize: sender];
        }
    }
    extern "C-unwind" fn close(this: &AnyObject, _: Sel, sender: Id) {
        // Hidden, not destroyed; `refresh_visibility` then reports it as
        // closed by the user, exactly like "Close video".
        CLOSE_REQUESTED.store(true, Ordering::Relaxed);
        unsafe {
            let _: () = msg_send![this, orderOut: sender];
        }
    }
    CLASS.get_or_init(|| {
        let mut builder = ClassBuilder::new(c"ZFVideoWindow", class!(NSWindow)).expect("ZFVideoWindow is registered once");
        unsafe {
            builder.add_method(sel!(canBecomeMainWindow), yes as extern "C-unwind" fn(_, _) -> _);
            builder.add_method(sel!(canBecomeKeyWindow), yes as extern "C-unwind" fn(_, _) -> _);
            builder.add_method(sel!(performMiniaturize:), minimize as extern "C-unwind" fn(_, _, _));
            builder.add_method(sel!(performClose:), close as extern "C-unwind" fn(_, _, _));
        }
        builder.register()
    })
}

/// Main thread: creates the video window (idempotent) and shows it.
fn create_video_window() {
    if VIDEO_WINDOW.get().is_none() {
        guarded("create video window", || unsafe {
            let (w, h) = INITIAL_WINDOW_SIZE;
            let frame = NSRect::new(NSPoint::new(0.0, 0.0), NSSize::new(w, h));
            let view: Id = msg_send![class!(NSView), alloc];
            let view: Id = msg_send![view, initWithFrame: frame];
            let _: () = msg_send![view, setWantsLayer: true];
            let layer: Id = msg_send![class!(AVSampleBufferDisplayLayer), new];
            let gravity = NSString::from_str("AVLayerVideoGravityResizeAspect");
            let _: () = msg_send![layer, setVideoGravity: &*gravity];
            attach_host_timebase(layer);
            let window: Id = msg_send![video_window_class(), alloc];
            let window: Id = msg_send![
                window,
                initWithContentRect: frame,
                styleMask: WINDOW_STYLE,
                backing: NS_BACKING_STORE_BUFFERED,
                defer: false
            ];
            // Closing hides the window (the object stays for "Open video").
            let _: () = msg_send![window, setReleasedWhenClosed: false];
            // Layer attached BEFORE the view enters the window — attaching
            // after display left the window blank once (live M3).
            let _: () = msg_send![view, setLayer: layer];
            // The title names the window in the Dock's window list, the
            // Window menu, Mission Control and OBS's window list, though no
            // title bar shows it.
            let title = NSString::from_str(WINDOW_TITLE);
            let _: () = msg_send![window, setTitle: &*title];
            let _: () = msg_send![window, setMovableByWindowBackground: true];
            let _: () = msg_send![window, setHasShadow: true];
            let black: Id = msg_send![class!(NSColor), blackColor];
            let _: () = msg_send![window, setBackgroundColor: black];
            let _: () = msg_send![window, setContentView: view];
            let _: () = msg_send![view, release]; // the window owns its content view
            let scale: f64 = msg_send![window, backingScaleFactor];
            let card = build_no_input_card(layer, INITIAL_WINDOW_SIZE, scale);
            let _ = NO_INPUT_CARD.set(LayerRef(card));
            let _: () = msg_send![window, center];
            // `alloc`/`new` already returned +1: these are the process's refs.
            let _ = VIDEO_WINDOW.set(VideoWindow { window, layer });
            log::info!(target: "video", "native video window created (AVSampleBufferDisplayLayer, resizeAspect)");
        });
    }
    show_video_window();
}

/// Main thread: brings the window front — a new cast (it may have been
/// closed) or a window born buried behind the main window.
pub fn show_video_window() {
    if let Some(vw) = VIDEO_WINDOW.get() {
        guarded("show video window", || unsafe {
            let _: () = msg_send![vw.window, makeKeyAndOrderFront: std::ptr::null::<AnyObject>()];
            let _: () = msg_send![vw.window, orderFrontRegardless];
        });
        refresh_visibility();
    }
}

/// Main thread: a Dock click — the open window comes back out of the Dock
/// if it was minimized, and to the front.
pub fn restore_video_window() {
    if let Some(vw) = VIDEO_WINDOW.get() {
        guarded("restore video window", || unsafe {
            let minimized: bool = msg_send![vw.window, isMiniaturized];
            if minimized {
                let _: () = msg_send![vw.window, deminiaturize: std::ptr::null::<AnyObject>()];
            }
        });
    }
    show_video_window();
}

/// Main thread: publishes whether the window should be rendered.
/// Returns `true` when it found the user closed an open window with its
/// own close button (the caller silences the audio and tells the UI).
pub fn refresh_visibility() -> bool {
    let Some(vw) = VIDEO_WINDOW.get() else { return false };
    let (mut on_screen, mut unoccluded) = (false, false);
    guarded("read window state", || unsafe {
        on_screen = msg_send![vw.window, isVisible];
        let occlusion: u64 = msg_send![vw.window, occlusionState];
        unoccluded = occlusion & OCCLUSION_VISIBLE != 0;
    });
    let close_requested = CLOSE_REQUESTED.swap(false, Ordering::Relaxed);
    let (closed_by_user, visible) = window_state(is_open(), close_requested, on_screen);
    let covered = visible && !unoccluded;
    if WINDOW_COVERED.swap(covered, Ordering::Relaxed) != covered {
        log::info!(target: "video", "window {}", if covered { "covered by another window — still rendering" } else { "uncovered" });
    }
    if closed_by_user {
        VIDEO_OPEN.store(false, Ordering::Relaxed);
    }
    if WINDOW_VISIBLE.swap(visible, Ordering::Relaxed) != visible {
        log::info!(target: "video", "window {}", if visible { "visible — rendering" } else { "hidden — frames dropped at the live edge" });
    }
    if closed_by_user {
        log::info!(target: "video", "video window closed by the user");
    }
    closed_by_user
}

/// What the window's state means: (the user closed it, frames should be
/// rendered). Only an explicit close closes it. A window minimized to
/// the Dock, or hidden with its app, is still open — its sound keeps
/// playing and it comes back — it just shows nothing.
///
/// A window another window covers keeps rendering: the receiver is the
/// cast's output device and OBS Window Capture records it while it is
/// covered; dropping its frames froze that picture (issue #178, the same
/// defect the Electron receiver had). The memory the old gate guarded
/// against is bounded elsewhere (pool threshold, layer back-pressure).
fn window_state(open: bool, close_requested: bool, on_screen: bool) -> (bool, bool) {
    let closed_by_user = open && close_requested;
    let visible = open && !closed_by_user && on_screen;
    (closed_by_user, visible)
}

// ---- the frame path (one renderer per video stream) ------------------------

/// What the frame loop must do on the main thread after a frame.
#[derive(PartialEq, Eq)]
pub enum Followup {
    None,
    /// The window has never shown a frame — raise it ([`show_video_window`]).
    RaiseWindow,
}

/// Per-stream render state, owned by the frame thread (a new cast starts a
/// new stream, so every counter and cache starts fresh).
pub struct Renderer {
    /// CMFormatDescription per (width, height, range) — the quality ladder
    /// and rotation change dimensions mid-cast, a decoder switch can change
    /// the range; the old one is released.
    format: Option<((u32, u32, VideoRange), CFRetained<CMFormatDescription>)>,
    /// One pool for the current dimensions and range (a change replaces it).
    pool: Option<((u32, u32, VideoRange), CFRetained<CVPixelBufferPool>)>,
    /// `{AllocationThreshold: N}` — built once, passed per allocation.
    pool_aux: Option<CFRetained<CFDictionary>>,
    started: Instant,
    ever_rendered: bool,
    raise_requested: bool,
    layer_failed: bool,
    last_hidden_flush: Option<Instant>,
    last_stall_flush: Option<Instant>,
    enqueued: u64,
    dropped_hidden: u64,
    dropped_not_ready: u64,
    dropped_pool_full: u64,
    pacer: crate::pacing::Pacer,
    /// How long the last rendered frame was held for its slot (seconds).
    last_hold: f64,
}

impl Renderer {
    pub fn new() -> Self {
        let threshold = cf_num(POOL_ALLOCATION_THRESHOLD);
        let threshold_key = unsafe { objc2_core_video::kCVPixelBufferPoolAllocationThresholdKey };
        Self {
            format: None,
            pool: None,
            pool_aux: cf_dict(&[(threshold_key as *const CFString as *const c_void, cf_ptr(&threshold))]),
            started: Instant::now(),
            ever_rendered: false,
            raise_requested: false,
            layer_failed: false,
            last_hidden_flush: None,
            last_stall_flush: None,
            enqueued: 0,
            dropped_hidden: 0,
            dropped_not_ready: 0,
            dropped_pool_full: 0,
            pacer: crate::pacing::Pacer::new(),
            last_hold: 0.0,
        }
    }

    /// Handles a frame the window cannot show WITHOUT converting it —
    /// `Some(followup)` when it was dropped, `None` when it should render.
    /// Saves the decoder-buffer → I420 conversion for every hidden frame.
    pub fn drop_if_hidden(&mut self) -> Option<Followup> {
        // The Syphon output is being read: it needs the frame even when
        // the window does not (ADR-021).
        if crate::syphon::wants_frames() {
            return None;
        }
        // Closed (or never opened): nothing to render, nothing to raise.
        let Some(vw) = VIDEO_WINDOW.get().filter(|_| is_open()) else { return Some(Followup::None) };
        if WINDOW_VISIBLE.load(Ordering::Relaxed) {
            return None;
        }
        Some(self.drop_hidden(vw))
    }

    /// How long the last rendered frame waited for its slot, in ms.
    pub fn last_hold_ms(&self) -> f64 {
        self.last_hold * 1000.0
    }

    /// The pacing figures since the last call (the ~1 Hz video line).
    pub fn take_pacing_window(&mut self) -> crate::pacing::Window {
        self.pacer.take_window()
    }

    /// Copies one I420 frame into a pooled NV12 buffer, due when the pacer
    /// says: enqueued on the window's layer while it is visible, handed to
    /// the Syphon output while that is being read — the same buffer for both.
    #[allow(clippy::too_many_arguments)]
    pub fn render_i420(&mut self, width: u32, height: u32, range: VideoRange, y: &[u8], u: &[u8], v: &[u8], stride_y: u32, stride_u: u32, stride_v: u32) -> Followup {
        if width == 0 || height == 0 {
            return Followup::None;
        }
        let window = VIDEO_WINDOW.get();
        let visible = window.is_some() && WINDOW_VISIBLE.load(Ordering::Relaxed);
        let syphon = crate::syphon::wants_frames();
        if !visible && !syphon {
            return window.map_or(Followup::None, |vw| self.drop_hidden(vw));
        }
        let arrival = host_now();
        let due = self.pacer.due(arrival);
        self.last_hold = due - arrival;
        let Some(pixel_buffer) = self.pixel_buffer(width, height, range) else {
            self.dropped_pool_full += 1;
            return Followup::None;
        };
        copy_i420_to_nv12(&pixel_buffer, width, height, y, u, v, stride_y, stride_u, stride_v);
        if syphon {
            crate::syphon::submit(&pixel_buffer, width, height, due);
        }
        match window {
            Some(vw) if visible => {
                if let Some(sample) = self.sample_buffer(&pixel_buffer, (width, height, range), due) {
                    self.enqueue(vw, &sample);
                }
                Followup::None
            }
            // Syphon only: an open but covered window still gets its
            // hidden-frame housekeeping (flush, raise once).
            Some(vw) if is_open() => self.drop_hidden(vw),
            _ => Followup::None,
        }
    }

    /// A hidden window is not composited: enqueued samples would pin their
    /// surfaces forever. Drop at the live edge, flush what is queued, and
    /// raise once if the window was born buried.
    fn drop_hidden(&mut self, vw: &VideoWindow) -> Followup {
        self.dropped_hidden += 1;
        if self.dropped_hidden == 1 || self.dropped_hidden % LOG_EVERY_FRAMES == 0 {
            log::info!(target: "video", "window hidden — {} frames dropped at the live edge", self.dropped_hidden);
        }
        if due(&mut self.last_hidden_flush, HIDDEN_FLUSH_INTERVAL) {
            guarded("flush hidden layer", || unsafe {
                let _: () = msg_send![vw.layer, flush];
            });
        }
        if !self.ever_rendered && !self.raise_requested && self.started.elapsed() >= BURIED_RAISE_AFTER {
            self.raise_requested = true;
            log::info!(target: "video", "no frame shown yet and the window is buried — raising it");
            return Followup::RaiseWindow;
        }
        Followup::None
    }

    fn pixel_buffer(&mut self, width: u32, height: u32, range: VideoRange) -> Option<CFRetained<CVPixelBuffer>> {
        if self.pool.as_ref().map(|(key, _)| *key) != Some((width, height, range)) {
            self.pool = Some(((width, height, range), create_pool(width, height, range)?));
        }
        let (_, pool) = self.pool.as_ref()?;
        let mut out: *mut CVPixelBuffer = std::ptr::null_mut();
        let aux = self.pool_aux.as_deref();
        let status = unsafe { CVPixelBufferPool::create_pixel_buffer_with_aux_attributes(None, pool, aux, NonNull::from(&mut out)) };
        if status != 0 {
            if status != objc2_core_video::kCVReturnWouldExceedAllocationThreshold {
                log::warn!(target: "video", "pool buffer failed status={status}");
            }
            return None;
        }
        // Create rule: we own the +1.
        NonNull::new(out).map(|p| unsafe { CFRetained::from_raw(p) })
    }

    fn sample_buffer(&mut self, pixel_buffer: &CVPixelBuffer, dims: (u32, u32, VideoRange), due: f64) -> Option<CFRetained<CMSampleBuffer>> {
        if self.format.as_ref().map(|(d, _)| *d) != Some(dims) {
            let mut desc: *const CMFormatDescription = std::ptr::null();
            let status = unsafe {
                objc2_core_media::CMVideoFormatDescriptionCreateForImageBuffer(None, pixel_buffer, NonNull::from(&mut desc))
            };
            let desc = NonNull::new(desc as *mut CMFormatDescription).filter(|_| status == 0);
            let Some(desc) = desc else {
                log::warn!(target: "video", "format description failed status={status} dims={dims:?}");
                return None;
            };
            // Replacing the Option releases the previous description.
            self.format = Some((dims, unsafe { CFRetained::from_raw(desc) }));
            log::info!(target: "video", "format description created for {}x{} ({:?} range)", dims.0, dims.1, dims.2);
        }
        let (_, format) = self.format.as_ref()?;
        // Host-clock pts — the layer's timebase is the host clock (an epoch
        // value overflowed CMTime and raised; a zero-based counter grew the
        // decoder pool).
        let timing = CMSampleTimingInfo {
            duration: unsafe { kCMTimeInvalid },
            presentationTimeStamp: unsafe { CMTime::with_seconds(due, PTS_TIMESCALE) },
            decodeTimeStamp: unsafe { kCMTimeInvalid },
        };
        let mut sample: *mut CMSampleBuffer = std::ptr::null_mut();
        let status = unsafe {
            CMSampleBuffer::create_for_image_buffer(
                None,
                pixel_buffer,
                true,
                None,
                std::ptr::null_mut(),
                format,
                NonNull::from(&timing),
                NonNull::from(&mut sample),
            )
        };
        if status != 0 {
            log::warn!(target: "video", "sample buffer failed status={status}");
            return None;
        }
        NonNull::new(sample).map(|p| unsafe { CFRetained::from_raw(p) })
    }

    /// Every layer call in one exception guard: an Objective-C exception
    /// drops this frame and flushes; it never aborts the process.
    fn enqueue(&mut self, vw: &VideoWindow, sample: &CMSampleBuffer) {
        let sample = sample as *const CMSampleBuffer as Id;
        let layer = vw.layer;
        let mut outcome = Enqueue::Enqueued;
        let ok = guarded("enqueue", || unsafe {
            // A Failed layer raises on the next enqueue; -flush is the
            // documented reset (found live: abort ~3600 frames in).
            let status: isize = msg_send![layer, status];
            if status == LAYER_STATUS_FAILED {
                let _: () = msg_send![layer, flush];
                outcome = Enqueue::LayerFailed;
                return;
            }
            // Bounded queue at the live edge: enqueueing while not ready
            // buffered without limit (20 GB RSS, found live).
            let ready: bool = msg_send![layer, isReadyForMoreMediaData];
            if !ready {
                outcome = Enqueue::NotReady;
                return;
            }
            let _: () = msg_send![layer, enqueueSampleBuffer: sample];
        });
        if !ok {
            guarded("flush after exception", || unsafe {
                let _: () = msg_send![layer, flush];
            });
            return;
        }
        match outcome {
            Enqueue::Enqueued => {
                if self.layer_failed {
                    self.layer_failed = false;
                    log::info!(target: "video", "layer recovered — rendering again");
                }
                self.ever_rendered = true;
                self.enqueued += 1;
                if self.enqueued == 1 || self.enqueued % LOG_EVERY_FRAMES == 0 {
                    log::info!(
                        target: "video", "{} frames shown | dropped: {} hidden, {} layer busy, {} pool full",
                        self.enqueued, self.dropped_hidden, self.dropped_not_ready, self.dropped_pool_full
                    );
                }
            }
            Enqueue::LayerFailed => {
                if !self.layer_failed {
                    self.layer_failed = true;
                    log::warn!(target: "video", "display layer failed — flushed, recovering");
                }
            }
            Enqueue::NotReady => {
                self.dropped_not_ready += 1;
                if due(&mut self.last_stall_flush, STALL_FLUSH_INTERVAL) {
                    guarded("flush stalled layer", || unsafe {
                        let _: () = msg_send![layer, flush];
                    });
                }
            }
        }
    }
}

enum Enqueue {
    Enqueued,
    LayerFailed,
    NotReady,
}

/// True (and restarts the clock) once `interval` has passed since the last time.
fn due(last: &mut Option<Instant>, interval: Duration) -> bool {
    if last.is_some_and(|t| t.elapsed() < interval) {
        return false;
    }
    *last = Some(Instant::now());
    true
}

#[allow(clippy::too_many_arguments)]
fn copy_i420_to_nv12(pb: &CVPixelBuffer, width: u32, height: u32, y: &[u8], u: &[u8], v: &[u8], stride_y: u32, stride_u: u32, stride_v: u32) {
    use objc2_core_video::{
        CVPixelBufferGetBaseAddressOfPlane, CVPixelBufferGetBytesPerRowOfPlane, CVPixelBufferLockBaseAddress,
        CVPixelBufferLockFlags, CVPixelBufferUnlockBaseAddress,
    };
    let (w, h) = (width as usize, height as usize);
    let (cw, ch) = (w.div_ceil(2), h.div_ceil(2));
    let (sy, su, sv) = (stride_y as usize, stride_u as usize, stride_v as usize);
    // Source bounds: the planes must hold every row we read.
    if y.len() < sy * (h - 1) + w || u.len() < su * (ch - 1) + cw || v.len() < sv * (ch - 1) + cw {
        log::warn!(target: "video", "I420 planes smaller than {w}x{h} — frame skipped");
        return;
    }
    unsafe {
        if CVPixelBufferLockBaseAddress(pb, CVPixelBufferLockFlags::empty()) != 0 {
            return;
        }
        let luma = CVPixelBufferGetBaseAddressOfPlane(pb, 0) as *mut u8;
        let luma_row = CVPixelBufferGetBytesPerRowOfPlane(pb, 0);
        if !luma.is_null() && luma_row >= w {
            for r in 0..h {
                std::ptr::copy_nonoverlapping(y.as_ptr().add(r * sy), luma.add(r * luma_row), w);
            }
        }
        let chroma = CVPixelBufferGetBaseAddressOfPlane(pb, 1) as *mut u8;
        let chroma_row = CVPixelBufferGetBytesPerRowOfPlane(pb, 1);
        if !chroma.is_null() && chroma_row >= cw * 2 {
            for r in 0..ch {
                let dst = std::slice::from_raw_parts_mut(chroma.add(r * chroma_row), cw * 2);
                let (us, vs) = (&u[r * su..r * su + cw], &v[r * sv..r * sv + cw]);
                for (x, pair) in dst.chunks_exact_mut(2).enumerate() {
                    pair[0] = us[x];
                    pair[1] = vs[x];
                }
            }
        }
        CVPixelBufferUnlockBaseAddress(pb, CVPixelBufferLockFlags::empty());
    }
}

/// One NV12 buffer filled from I420 planes, as the frame thread makes them.
#[cfg(test)]
pub(crate) fn test_nv12_buffer(width: u32, height: u32, range: VideoRange, y: &[u8], u: &[u8], v: &[u8]) -> Option<CFRetained<CVPixelBuffer>> {
    let buffer = Renderer::new().pixel_buffer(width, height, range)?;
    copy_i420_to_nv12(&buffer, width, height, y, u, v, width, width.div_ceil(2), width.div_ceil(2));
    Some(buffer)
}

fn create_pool(width: u32, height: u32, range: VideoRange) -> Option<CFRetained<CVPixelBufferPool>> {
    use objc2_core_video::{kCVPixelBufferHeightKey, kCVPixelBufferIOSurfacePropertiesKey, kCVPixelBufferPixelFormatTypeKey, kCVPixelBufferWidthKey};
    let key = |k: &'static CFString| k as *const CFString as *const c_void;
    let (format, w, h) = (cf_num(i64::from(range.pixel_format())), cf_num(i64::from(width)), cf_num(i64::from(height)));
    let iosurface = cf_dict(&[])?;
    let attrs = unsafe {
        cf_dict(&[
            (key(kCVPixelBufferPixelFormatTypeKey), cf_ptr(&format)),
            (key(kCVPixelBufferWidthKey), cf_ptr(&w)),
            (key(kCVPixelBufferHeightKey), cf_ptr(&h)),
            // IOSurface-backed: the display layer composites without a copy.
            (key(kCVPixelBufferIOSurfacePropertiesKey), cf_ptr(&iosurface)),
        ])?
    };
    let mut pool: *mut CVPixelBufferPool = std::ptr::null_mut();
    let status = unsafe { CVPixelBufferPool::create(None, None, Some(&attrs), NonNull::from(&mut pool)) };
    match NonNull::new(pool).filter(|_| status == 0) {
        Some(pool) => {
            log::info!(target: "video", "pixel buffer pool for {width}x{height} ('{}' — {range:?} range, ≤{POOL_ALLOCATION_THRESHOLD} buffers)", range.fourcc());
            Some(unsafe { CFRetained::from_raw(pool) })
        }
        None => {
            log::warn!(target: "video", "pixel buffer pool failed status={status} for {width}x{height}");
            None
        }
    }
}

fn cf_num(value: i64) -> CFRetained<CFNumber> {
    unsafe { CFNumber::new(None, CFNumberType::SInt64Type, &value as *const i64 as *const c_void) }.expect("CFNumber")
}

fn cf_ptr<T>(value: &CFRetained<T>) -> *const c_void
where
    T: objc2_core_foundation::Type,
{
    CFRetained::as_ptr(value).as_ptr() as *const c_void
}

#[allow(deprecated)]
fn cf_dict(pairs: &[(*const c_void, *const c_void)]) -> Option<CFRetained<CFDictionary>> {
    let mut keys: Vec<*const c_void> = pairs.iter().map(|p| p.0).collect();
    let mut values: Vec<*const c_void> = pairs.iter().map(|p| p.1).collect();
    unsafe {
        objc2_core_foundation::CFDictionaryCreate(
            None,
            keys.as_mut_ptr(),
            values.as_mut_ptr(),
            pairs.len() as _,
            &kCFTypeDictionaryKeyCallBacks,
            &kCFTypeDictionaryValueCallBacks,
        )
    }
}

#[cfg(test)]
mod bench {
    /// Why the I420 → NV12 copy stays a plain loop: measured 2026-11-09 on
    /// the M-series dev Mac, 1280×800 — the loop (vectorized by rustc)
    /// ~18 µs/frame, libyuv's `i420_to_nv12` ~54 µs/frame.
    /// `cargo test --release --lib video::bench -- --ignored --nocapture`
    #[test]
    #[ignore]
    fn i420_to_nv12_scalar_vs_libyuv() {
        let (w, h) = (1280usize, 800usize);
        let (cw, ch) = (w / 2, h / 2);
        let (y, u, v) = (vec![16u8; w * h], vec![128u8; cw * ch], vec![130u8; cw * ch]);
        let (mut dy, mut duv) = (vec![0u8; w * h], vec![0u8; w * h / 2]);
        let runs = 300;
        let t = std::time::Instant::now();
        for _ in 0..runs {
            for r in 0..h {
                dy[r * w..r * w + w].copy_from_slice(&y[r * w..r * w + w]);
            }
            for r in 0..ch {
                let dst = &mut duv[r * w..r * w + cw * 2];
                let (us, vs) = (&u[r * cw..r * cw + cw], &v[r * cw..r * cw + cw]);
                for (x, pair) in dst.chunks_exact_mut(2).enumerate() {
                    pair[0] = us[x];
                    pair[1] = vs[x];
                }
            }
        }
        let scalar = t.elapsed() / runs;
        let t = std::time::Instant::now();
        for _ in 0..runs {
            livekit::webrtc::native::yuv_helper::i420_to_nv12(
                &y, w as u32, &u, cw as u32, &v, cw as u32, &mut dy, w as u32, &mut duv, w as u32, w as i32, h as i32,
            );
        }
        let simd = t.elapsed() / runs;
        println!("1280x800 I420→NV12: scalar {scalar:?}/frame, libyuv {simd:?}/frame");
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// FourCC as CoreVideo spells it ('420f' = 0x34323066).
    fn fourcc(code: &[u8; 4]) -> u32 {
        u32::from_be_bytes(*code)
    }

    #[test]
    fn a_full_range_decoder_buffer_is_shown_as_full_range() {
        assert_eq!(range_of_decoder_format(fourcc(b"420f")), VideoRange::Full);
        assert_eq!(range_of_decoder_format(fourcc(b"xf20")), VideoRange::Full, "10-bit full range");
    }

    #[test]
    fn a_video_range_or_unknown_decoder_buffer_stays_limited() {
        assert_eq!(range_of_decoder_format(fourcc(b"420v")), VideoRange::Limited);
        assert_eq!(range_of_decoder_format(fourcc(b"x420")), VideoRange::Limited, "10-bit video range");
        assert_eq!(range_of_decoder_format(fourcc(b"BGRA")), VideoRange::Limited);
    }

    #[test]
    fn the_pool_format_matches_the_range() {
        assert_eq!(VideoRange::Full.pixel_format(), fourcc(b"420f"));
        assert_eq!(VideoRange::Limited.pixel_format(), fourcc(b"420v"));
    }

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

    #[test]
    fn a_minimized_window_is_open_but_shows_nothing() {
        // (open, close requested, on screen) → (closed by the user, rendered)
        assert_eq!(window_state(true, false, true), (false, true), "on screen: rendering");
        assert_eq!(window_state(true, false, false), (false, false), "in the Dock or app hidden: still open — the sound stays on");
        assert_eq!(window_state(true, true, false), (true, false), "Cmd+W: the user closed it");
        assert_eq!(window_state(true, true, true), (true, false), "closing wins even if the window is still on screen this instant");
        assert_eq!(window_state(false, true, false), (false, false), "already closed: nothing to report");
        assert_eq!(window_state(false, false, true), (false, false), "never opened: nothing to render");
    }
}
