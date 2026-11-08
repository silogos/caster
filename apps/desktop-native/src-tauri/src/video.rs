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

use objc2::rc::Retained;
use objc2::runtime::AnyObject;
use objc2::{class, msg_send};
use objc2_core_foundation::{
    kCFTypeDictionaryKeyCallBacks, kCFTypeDictionaryValueCallBacks, CFDictionary, CFNumber, CFNumberType,
    CFRetained, CFString,
};
use objc2_core_media::{kCMTimeInvalid, CMFormatDescription, CMSampleBuffer, CMSampleTimingInfo};
use objc2_core_video::{CVPixelBuffer, CVPixelBufferPool};
use objc2_foundation::{NSPoint, NSRect, NSSize, NSString};
use std::ffi::c_void;
use std::ptr::NonNull;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::OnceLock;
use std::time::{Duration, Instant};

type Id = *mut AnyObject;

/// Biplanar 4:2:0 **video range** ('420v'): libwebrtc's I420 is limited
/// range (16–235). The first version declared full range ('420f') and the
/// window showed grey blacks and washed-out colours.
const PIXEL_FORMAT: u32 = objc2_core_video::kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange;
/// Pool buffers alive at once (the layer's short queue + one in flight).
/// Past it, allocation fails and the frame is dropped — the real bound
/// (the first version passed a non-existent "MaximumBufferCount" key).
/// 6 dropped ~1 % of frames live (82 of 7 200) while the layer still held
/// queued samples; 8 leaves that headroom and stays bounded.
const POOL_ALLOCATION_THRESHOLD: i64 = 8;
const INITIAL_WINDOW_SIZE: (f64, f64) = (960.0, 640.0);
const WINDOW_TITLE: &str = "Zero-Friction Cast";
/// NSWindowStyleMask: titled | closable | miniaturizable | resizable.
const WINDOW_STYLE: u64 = (1 << 0) | (1 << 1) | (1 << 2) | (1 << 3);
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

/// Main thread: creates the video window (idempotent) and shows it.
pub fn create_video_window() {
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
            let window: Id = msg_send![class!(NSWindow), alloc];
            let window: Id = msg_send![
                window,
                initWithContentRect: frame,
                styleMask: WINDOW_STYLE,
                backing: NS_BACKING_STORE_BUFFERED,
                defer: false
            ];
            // Closing hides the window; the next cast shows it again.
            let _: () = msg_send![window, setReleasedWhenClosed: false];
            // Layer attached BEFORE the view enters the window — attaching
            // after display left the window blank once (live M3).
            let _: () = msg_send![view, setLayer: layer];
            let title = NSString::from_str(WINDOW_TITLE);
            let _: () = msg_send![window, setTitle: &*title];
            let _: () = msg_send![window, setContentView: view];
            let _: () = msg_send![view, release]; // the window owns its content view
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

/// Main thread: publishes whether the window is actually composited.
pub fn refresh_visibility() {
    if let Some(vw) = VIDEO_WINDOW.get() {
        let mut visible = false;
        guarded("read window occlusion", || unsafe {
            let occlusion: u64 = msg_send![vw.window, occlusionState];
            visible = occlusion & OCCLUSION_VISIBLE != 0;
        });
        if WINDOW_VISIBLE.swap(visible, Ordering::Relaxed) != visible {
            log::info!(target: "video", "window {}", if visible { "visible — rendering" } else { "hidden — frames dropped at the live edge" });
        }
    }
}

/// True once the native window exists (the webview preview covers the
/// first frames until then).
pub fn is_active() -> bool {
    VIDEO_WINDOW.get().is_some()
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
    /// CMFormatDescription per (width, height) — the quality ladder and
    /// rotation change dimensions mid-cast; the old one is released.
    format: Option<((u32, u32), CFRetained<CMFormatDescription>)>,
    /// One pool for the current dimensions (a size change replaces it).
    pool: Option<((u32, u32), CFRetained<CVPixelBufferPool>)>,
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
        }
    }

    /// Handles a frame the window cannot show WITHOUT converting it —
    /// `Some(followup)` when it was dropped, `None` when it should render.
    /// Saves the decoder-buffer → I420 conversion for every hidden frame.
    pub fn drop_if_hidden(&mut self) -> Option<Followup> {
        let vw = VIDEO_WINDOW.get()?;
        if WINDOW_VISIBLE.load(Ordering::Relaxed) {
            return None;
        }
        Some(self.drop_hidden(vw))
    }

    /// Copies one I420 frame into a pooled NV12 buffer and enqueues it.
    #[allow(clippy::too_many_arguments)]
    pub fn render_i420(&mut self, width: u32, height: u32, y: &[u8], u: &[u8], v: &[u8], stride_y: u32, stride_u: u32, stride_v: u32) -> Followup {
        let Some(vw) = VIDEO_WINDOW.get() else { return Followup::None };
        if width == 0 || height == 0 {
            return Followup::None;
        }
        if !WINDOW_VISIBLE.load(Ordering::Relaxed) {
            return self.drop_hidden(vw);
        }
        let Some(pixel_buffer) = self.pixel_buffer(width, height) else {
            self.dropped_pool_full += 1;
            return Followup::None;
        };
        copy_i420_to_nv12(&pixel_buffer, width, height, y, u, v, stride_y, stride_u, stride_v);
        let Some(sample) = self.sample_buffer(&pixel_buffer, (width, height)) else { return Followup::None };
        self.enqueue(vw, &sample);
        Followup::None
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

    fn pixel_buffer(&mut self, width: u32, height: u32) -> Option<CFRetained<CVPixelBuffer>> {
        if self.pool.as_ref().map(|(dims, _)| *dims) != Some((width, height)) {
            self.pool = Some(((width, height), create_pool(width, height)?));
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

    fn sample_buffer(&mut self, pixel_buffer: &CVPixelBuffer, dims: (u32, u32)) -> Option<CFRetained<CMSampleBuffer>> {
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
            log::info!(target: "video", "format description created for {}x{}", dims.0, dims.1);
        }
        let (_, format) = self.format.as_ref()?;
        // Host-clock pts — the layer's own timeline (an epoch value overflowed
        // CMTime and raised; a zero-based counter grew the decoder pool).
        let timing = CMSampleTimingInfo {
            duration: unsafe { kCMTimeInvalid },
            presentationTimeStamp: unsafe { objc2_core_media::CMClock::host_time_clock().time() },
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

fn create_pool(width: u32, height: u32) -> Option<CFRetained<CVPixelBufferPool>> {
    use objc2_core_video::{kCVPixelBufferHeightKey, kCVPixelBufferIOSurfacePropertiesKey, kCVPixelBufferPixelFormatTypeKey, kCVPixelBufferWidthKey};
    let key = |k: &'static CFString| k as *const CFString as *const c_void;
    let (format, w, h) = (cf_num(i64::from(PIXEL_FORMAT)), cf_num(i64::from(width)), cf_num(i64::from(height)));
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
            log::info!(target: "video", "pixel buffer pool for {width}x{height} ('420v', ≤{POOL_ALLOCATION_THRESHOLD} buffers)");
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
