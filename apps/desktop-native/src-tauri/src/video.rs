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
        let _: () = msg_send![window, setTitle: &*title];
        let _: () = msg_send![window, setContentView: view];
        let _: () = msg_send![window, center];
        let _: () = msg_send![window, makeKeyAndOrderFront: std::ptr::null::<AnyObject>()];
        let _: () = msg_send![view, setLayer: layer];
        let _: () = msg_send![view, setNeedsDisplay: true];

        let _ = VIDEO_WINDOW.set(VideoWindow {
            window: retain(window),
            layer: retain(layer),
        });
        let _ = retain(view); // owned by the window's view hierarchy
        println!("[video] native video window created (AVSampleBufferDisplayLayer, resizeAspect)");
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
        let mut sample: Id = std::ptr::null_mut();
        // The formatDescription/timing refs are per-call; format is derived
        // when null — the bindings want raw refs, so build them unsafely.
        let format_desc: *const objc2_core_media::CMFormatDescription = std::ptr::null();
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
        if status != 0 || sample.is_null() {
            return;
        }
        let _: () = msg_send![vw.layer, enqueueSampleBuffer: sample];
        let _: () = msg_send![sample, release];
    }
}

/// True once the native window exists (the webview JPEG preview is the
/// fallback when it does not).
pub fn is_active() -> bool {
    VIDEO_WINDOW.get().is_some()
}
