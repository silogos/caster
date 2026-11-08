//! M3: the native video window — a raw NSWindow whose content view is an
//! `AVSampleBufferDisplayLayer` fed by the decoder's CVPixelBuffers
//! (zero-copy, hardware-composited). `videoGravity = resizeAspect` gives
//! the contain letterbox natively — no CSS, no webview in the video path.
//! OBS window-captures THIS window: pure video, the "window is the output
//! device" doctrine from desktop.md.
//!
//! The layer lives in its OWN window (user decision, 2026-11-09): the
//! main Tauri window keeps the UI stages; the cast renders here. Raw
//! ObjC pointers throughout — the window must outlive the frame stream,
//! stored in a static with an asserted Send/Sync wrapper.

use objc2::runtime::AnyObject;
use objc2::{class, msg_send};
use objc2_core_media::{
    kCMTimeInvalid, CMSampleBufferCreateForImageBuffer, CMSampleTimingInfo, CMTimeMake,
};
use objc2_foundation::{NSPoint, NSRect, NSSize, NSString};
use std::ffi::c_void;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::OnceLock;

type Id = *mut AnyObject;

struct VideoWindow {
    window: Id,
    layer: Id,
}

// The window is created on the main thread and only *retained* here;
// enqueue uses the layer from arbitrary threads, which AVFoundation
// supports. The pointers are stable for the app's lifetime.
unsafe impl Send for VideoWindow {}
unsafe impl Sync for VideoWindow {}

static VIDEO_WINDOW: OnceLock<VideoWindow> = OnceLock::new();
static PTS: AtomicI64 = AtomicI64::new(0);

unsafe fn retain(obj: Id) -> Id {
    let _: () = msg_send![obj, retain];
    obj
}

/// Creates the video window on the main thread (call via
/// `app.run_on_main_thread`). Idempotent.
pub fn create_video_window() {
    if VIDEO_WINDOW.get().is_some() {
        return;
    }
    unsafe {
        let frame = NSRect::new(NSPoint::new(0.0, 0.0), NSSize::new(960.0, 640.0));

        // Content view: layer-backed, layer = AVSampleBufferDisplayLayer.
        let view: Id = msg_send![class!(NSView), alloc];
        let view: Id = msg_send![view, initWithFrame: frame];
        let _: () = msg_send![view, setWantsLayer: true];

        let layer: Id = msg_send![class!(AVSampleBufferDisplayLayer), new];
        // Native contain letterbox — the compositor keeps the video
        // proportional inside whatever the window size becomes.
        let gravity = NSString::from_str("AVLayerVideoGravityResizeAspect");
        let _: () = msg_send![layer, setVideoGravity: &*gravity];

        let window: Id = msg_send![class!(NSWindow), alloc];
        // Titled | Closable | Resizable | Miniaturizable
        let style: u64 = 0b1 | 0b10 | 0b1000 | 0b10000;
        let window: Id = msg_send![
            window,
            initWithContentRect: frame,
            styleMask: style,
            backing: 2usize, // NSBackingStoreBuffered
            defer: false
        ];
        let title = NSString::from_str("Zero-Friction Cast");
        // Layer attached BEFORE the view enters the window — attaching
        // after display left the window blank once (live M3).
        let _: () = msg_send![view, setLayer: layer];
        let _: () = msg_send![view, setNeedsDisplay: true];
        let _: () = msg_send![window, setTitle: &*title];
        let _: () = msg_send![window, setContentView: view];
        let _: () = msg_send![window, center];
        let _: () = msg_send![window, makeKeyAndOrderFront: std::ptr::null::<AnyObject>()];

        let _ = VIDEO_WINDOW.set(VideoWindow {
            window: retain(window),
            layer: retain(layer),
        });
        let _ = retain(view); // owned by the window's view hierarchy
        println!("[video] native video window created (AVSampleBufferDisplayLayer, resizeAspect)");
    }
}

/// CVPixelBuffer geometry via the CoreVideo C functions — the buffer is a
/// CF object, NOT an NSObject: msg_send on it raised a foreign exception
/// and aborted the app at cast start (found live in M3).
unsafe fn pixel_buffer_dims(cv: *mut c_void) -> (u64, u64, u64) {
    use objc2_core_video::{CVPixelBufferGetHeight, CVPixelBufferGetPixelFormatType, CVPixelBufferGetWidth};
    let buf = &*(cv as *const objc2_core_video::CVBuffer);
    (
        CVPixelBufferGetWidth(buf) as u64,
        CVPixelBufferGetHeight(buf) as u64,
        CVPixelBufferGetPixelFormatType(buf) as u64,
    )
}

/// Format description cache — keyed by (width, height, pixel format); the
/// quality ladder changes dims mid-cast, which re-creates the entry.
fn cached_format_description(cv_pixel_buffer: *mut c_void) -> *const objc2_core_media::CMFormatDescription {
    use std::sync::Mutex;
    struct DescId(Id);
    unsafe impl Send for DescId {}
    static CACHE: Mutex<Option<((u64, u64, u64), DescId)>> = Mutex::new(None);
    unsafe {
        let key = pixel_buffer_dims(cv_pixel_buffer);
        let mut cache = CACHE.lock().expect("fmt cache");
        if let Some((cached_key, cached)) = cache.as_ref() {
            if *cached_key == key {
                return cached.0 as *const _;
            }
        }
        let mut desc: Id = std::ptr::null_mut();
        let status = objc2_core_media::CMVideoFormatDescriptionCreateForImageBuffer(
            None,
            &*(cv_pixel_buffer as *const objc2_core_video::CVBuffer),
            std::ptr::NonNull::new_unchecked(&mut desc as *mut Id as *mut *const _),
        );
        if status != 0 || desc.is_null() {
            use std::sync::atomic::{AtomicU64, Ordering};
            static FAILS: AtomicU64 = AtomicU64::new(0);
            let fails = FAILS.fetch_add(1, Ordering::Relaxed);
            if fails < 3 {
                println!("[video] CMVideoFormatDescriptionCreateForImageBuffer failed status={status} dims={key:?}");
            }
            return std::ptr::null();
        }
        println!("[video] format description created for {key:?}");
        *cache = Some((key, DescId(desc)));
        desc as *const _
    }
}

/// Enqueues one decoded frame's CVPixelBuffer. Callable from any thread
/// (enqueue is designed for off-main); `cv_pixel_buffer` must stay valid
/// for the duration of this call (the sample buffer retains it).
pub fn enqueue_cv_pixel_buffer(cv_pixel_buffer: *mut c_void) {
    let Some(vw) = VIDEO_WINDOW.get() else {
        return;
    };
    if cv_pixel_buffer.is_null() {
        return;
    }
    unsafe {
        let pts = PTS.fetch_add(1, Ordering::Relaxed);
        let invalid = kCMTimeInvalid;
        let timing = CMSampleTimingInfo {
            duration: invalid,
            presentationTimeStamp: CMTimeMake(pts, 600),
            decodeTimeStamp: invalid,
        };
        // The pixel buffer carries no format description — creating the
        // sample with a NULL one failed with -12731 RequiredParameterMissing
        // (found live in M3). Derive one from the buffer, cached per dims.
        let format_desc = cached_format_description(cv_pixel_buffer);
        if format_desc.is_null() {
            return; // the failure log inside cached_format_description says why
        }
        let mut sample: Id = std::ptr::null_mut();
        let status = CMSampleBufferCreateForImageBuffer(
            None,
            &*(cv_pixel_buffer as *const objc2_core_video::CVBuffer),
            true,
            None,
            std::ptr::null_mut(),
            &*format_desc,
            std::ptr::NonNull::from(&timing),
            std::ptr::NonNull::new_unchecked(&mut sample as *mut Id as *mut *mut _),
        );
        use std::sync::atomic::AtomicU64;
        static ENQ: AtomicU64 = AtomicU64::new(0);
        static FAILS: AtomicU64 = AtomicU64::new(0);
        let n = ENQ.fetch_add(1, Ordering::Relaxed);
        if status != 0 || sample.is_null() {
            let fails = FAILS.fetch_add(1, Ordering::Relaxed);
            if fails < 3 || fails % 100 == 0 {
                println!("[video] sample create FAILED status={status} (fail #{})", fails + 1);
            }
            return;
        }
        // Bounded queue + live edge: the layer buffers unboundedly if we
        // enqueue while it is not ready — 20 GB RSS on a minutes-long cast
        // (found live). For a live cast, dropping is CORRECT: the newest
        // frame is the one that matters.
        let ready: bool = msg_send![vw.layer, isReadyForMoreMediaData];
        if !ready {
            use std::sync::atomic::{AtomicI64, AtomicU64};
            static DROPPED: AtomicU64 = AtomicU64::new(0);
            static LAST_FLUSH_TICK: AtomicI64 = AtomicI64::new(0);
            let _: () = msg_send![sample, release]; // not enqueued — free now
            let dropped = DROPPED.fetch_add(1, Ordering::Relaxed);
            // If the layer stalls >1 s (occlusion, drawable starvation),
            // flush and resume at the live edge. PTS ticks at 600 Hz.
            let now = PTS.load(Ordering::Relaxed);
            let last = LAST_FLUSH_TICK.load(Ordering::Relaxed);
            if now - last > 600 {
                LAST_FLUSH_TICK.store(now, Ordering::Relaxed);
                let _: () = msg_send![vw.layer, flush];
                let _: () = msg_send![vw.layer, setNeedsDisplay: true];
            }
            if dropped == 1 || dropped % 300 == 0 {
                println!("[video] layer not ready — dropped {dropped} frames (bounded queue, live edge)");
            }
            return;
        }
        let _: () = msg_send![vw.layer, enqueueSampleBuffer: sample];
        let _: () = msg_send![sample, release]; // our ref; the layer retains
        if n < 3 {
            let layer_status: isize = msg_send![vw.layer, status];
            println!("[video] enqueue #{n} ok — layer status={layer_status}");
        }
        if n % 600 == 0 {
            let layer_status: isize = msg_send![vw.layer, status];
            if layer_status == 2 {
            // AVSampleBufferDisplayLayerStatusFailed
            let err: Id = msg_send![vw.layer, error];
            let desc: Id = if err.is_null() { std::ptr::null_mut() } else { msg_send![err, localizedDescription] };
            let desc_str = if desc.is_null() {
                "(nil)".to_string()
            } else {
                let s: Id = msg_send![desc, UTF8String];
                if s.is_null() {
                    "(utf8 nil)".to_string()
                } else {
                    std::ffi::CStr::from_ptr(s as *const std::ffi::c_char).to_string_lossy().into_owned()
                }
            };
            println!("[video] layer FAILED: {desc_str} — flushing");
                let _: () = msg_send![vw.layer, flush];
            }
        }
    }
}

/// True once the native window exists (the webview JPEG preview is the
/// fallback when it does not).
pub fn is_active() -> bool {
    VIDEO_WINDOW.get().is_some()
}
