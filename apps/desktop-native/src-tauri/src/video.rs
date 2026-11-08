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
use std::sync::atomic::Ordering;
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

/// Set once the first frame passes every gate and is enqueued — the
/// "has the cast ever rendered" signal for the buried-window raise.
static EVER_ENQUEUED_FLAG: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);
/// One-shot: the frame thread asks the main thread to raise the window.
static RAISE_PENDING: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

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

/// The decoder's zero-copy hand-off (webrtc-sys `get_cv_pixel_buffer`)
/// cannot be used: its bridge (objc_video_frame_buffer.mm) wraps the
/// native buffer in an ObjC `RTCNativeVideoFrameBuffer` and never releases
/// the wrapper — one leaked object + one pinned native ref per call, so
/// every decoded IOSurface stayed alive in VTDecoder's pool (measured
/// 1.46 MB/frame = exactly one 1280×800 NV12 surface; 9 GB in 2 min).
/// Instead we copy through CPU I420 into OUR pooled CVPixelBuffers:
/// bounded kernel memory, the same native display layer, ~0.3 ms/frame.
pub fn enqueue_i420(
    width: u32,
    height: u32,
    y: &[u8],
    u: &[u8],
    v: &[u8],
    stride_y: u32,
    stride_u: u32,
    stride_v: u32,
) {
    if width == 0 || height == 0 {
        return;
    }
    unsafe {
        let pb = pooled_nv12_pixel_buffer(width as u64, height as u64);
        if pb.is_null() {
            return;
        }
        // Copy I420 (3 planes) → NV12 '420f' (Y + interleaved UV).
        let buf = &*(pb as *const objc2_core_video::CVPixelBuffer);
        {
            // One-shot dump: what did the pool actually give us? A blank
            // window with the layer in Rendering state means the CONTENT
            // is wrong, not the pipeline (live: 5400 enqueues, black).
            use std::sync::atomic::AtomicBool;
            static DUMPED: AtomicBool = AtomicBool::new(false);
            if !DUMPED.swap(true, Ordering::Relaxed) {
                let cvbuf = &*(pb as *const objc2_core_video::CVBuffer);
                let planes = objc2_core_video::CVPixelBufferGetPlaneCount(cvbuf);
                let cname = objc2_core_video::CVPixelBufferGetPixelFormatType(cvbuf);
                let cw = objc2_core_video::CVPixelBufferGetWidth(cvbuf);
                let ch = objc2_core_video::CVPixelBufferGetHeight(cvbuf);
                let r0 = objc2_core_video::CVPixelBufferGetBytesPerRowOfPlane(cvbuf, 0);
                let r1 = objc2_core_video::CVPixelBufferGetBytesPerRowOfPlane(cvbuf, 1);
                let b0 = objc2_core_video::CVPixelBufferGetBaseAddressOfPlane(cvbuf, 0);
                let b1 = objc2_core_video::CVPixelBufferGetBaseAddressOfPlane(cvbuf, 1);
                let y0 = y.first().copied().unwrap_or(0xff);
                println!(
                    "[video] pool dump: fmt={cname} {cw}x{ch} planes={planes} row0={r0} row1={r1} base0={} base1={} srcY0={y0}",
                    !b0.is_null(),
                    !b1.is_null()
                );
            }
        }
        let flags = objc2_core_video::CVPixelBufferLockFlags::empty();
        if objc2_core_video::CVPixelBufferLockBaseAddress(buf, flags) == 0 {
            let cvbuf = &*(pb as *const objc2_core_video::CVBuffer);
            let cw = (width as usize + 1) / 2;
            let ch = (height as usize + 1) / 2;
            let base0 = objc2_core_video::CVPixelBufferGetBaseAddressOfPlane(cvbuf, 0)
                as *mut u8;
            let row0 = objc2_core_video::CVPixelBufferGetBytesPerRowOfPlane(cvbuf, 0);
            if !base0.is_null() && row0 >= width as usize {
                for r in 0..height as usize {
                    std::ptr::copy_nonoverlapping(
                        y.as_ptr().add(r * stride_y as usize),
                        base0.add(r * row0),
                        width as usize,
                    );
                }
            }
            let base1 = objc2_core_video::CVPixelBufferGetBaseAddressOfPlane(cvbuf, 1)
                as *mut u8;
            let row1 = objc2_core_video::CVPixelBufferGetBytesPerRowOfPlane(cvbuf, 1);
            if !base1.is_null() && row1 >= cw * 2 {
                for r in 0..ch {
                    let dst = base1.add(r * row1);
                    let us = u.as_ptr().add(r * stride_u as usize);
                    let vs = v.as_ptr().add(r * stride_v as usize);
                    for x in 0..cw {
                        *dst.add(2 * x) = *us.add(x);
                        *dst.add(2 * x + 1) = *vs.add(x);
                    }
                }
            }
            objc2_core_video::CVPixelBufferUnlockBaseAddress(buf, flags);
        }
        enqueue_cv_pixel_buffer(pb); // hands our +1 to the enqueue path
    }
}

/// One pool per (w, h) — rotation changes dims mid-cast and a pool's
/// buffers are fixed-size. Capped at 4 buffers: freed buffers beyond the
/// cap are destroyed, never retained — the bound the leaky path lacked.
unsafe fn pooled_nv12_pixel_buffer(w: u64, h: u64) -> *mut c_void {
    use objc2_core_foundation::{
        CFDictionary, CFNumber, CFNumberType, CFRetained, CFString,
        kCFTypeDictionaryKeyCallBacks, kCFTypeDictionaryValueCallBacks,
    };
    use std::collections::HashMap;
    use std::sync::Mutex;

    unsafe fn cf_str(s: &str) -> CFRetained<CFString> {
        let c = std::ffi::CString::new(s).expect("cf key");
        CFString::with_c_string(
            None,
            c.as_ptr(),
            objc2_core_foundation::CFStringBuiltInEncodings::EncodingUTF8.0,
        )
        .expect("CFString")
    }
    unsafe fn cf_num(v: i64) -> CFRetained<CFNumber> {
        CFNumber::new(None, CFNumberType::SInt64Type, &v as *const _ as *const _)
            .expect("CFNumber")
    }
    #[allow(deprecated)]
    unsafe fn cf_dict(pairs: &[(*const c_void, *const c_void)]) -> CFRetained<CFDictionary> {
        let mut keys: Vec<*const c_void> = pairs.iter().map(|p| p.0).collect();
        let mut vals: Vec<*const c_void> = pairs.iter().map(|p| p.1).collect();
        objc2_core_foundation::CFDictionaryCreate(
            None,
            keys.as_mut_ptr(),
            vals.as_mut_ptr(),
            pairs.len() as _,
            &kCFTypeDictionaryKeyCallBacks,
            &kCFTypeDictionaryValueCallBacks,
        )
        .expect("CFDictionary")
    }

    struct Pool(*mut objc2_core_video::CVPixelBufferPool);
    unsafe impl Send for Pool {}
    static POOLS: Mutex<Option<HashMap<(u64, u64), Pool>>> = Mutex::new(None);

    let pool = {
        let mut pools = POOLS.lock().expect("pool cache");
        match pools.as_ref().and_then(|m| m.get(&(w, h))) {
            Some(p) => p.0,
            None => {
                // '420f' (875_704_422) — the same biplanar NV12 full-range
                // format the hardware decoder delivered; the display-layer
                // path is unchanged.
                let (k_pf, k_w, k_h, k_io, k_cap) = (
                    cf_str("PixelFormatType"),
                    cf_str("Width"),
                    cf_str("Height"),
                    cf_str("IOSurfaceProperties"),
                    cf_str("MaximumBufferCount"),
                );
                let empty = cf_dict(&[]);
                let (v_pf, v_w, v_h, v_cap) = (
                    cf_num(875_704_422),
                    cf_num(w as i64),
                    cf_num(h as i64),
                    cf_num(4),
                );
                let as_cf = |r: &CFRetained<CFString>| {
                    CFRetained::as_ptr(r).as_ptr() as *const c_void
                };
                let pb_attrs = cf_dict(&[
                    (as_cf(&k_pf), CFRetained::as_ptr(&v_pf).as_ptr() as *const c_void),
                    (as_cf(&k_w), CFRetained::as_ptr(&v_w).as_ptr() as *const c_void),
                    (as_cf(&k_h), CFRetained::as_ptr(&v_h).as_ptr() as *const c_void),
                    (
                        as_cf(&k_io),
                        CFRetained::as_ptr(&empty).as_ptr() as *const c_void,
                    ),
                ]);
                let pool_attrs = cf_dict(&[(
                    as_cf(&k_cap),
                    CFRetained::as_ptr(&v_cap).as_ptr() as *const c_void,
                )]);

                let mut pool: *mut objc2_core_video::CVPixelBufferPool =
                    std::ptr::null_mut();
                let status = objc2_core_video::CVPixelBufferPoolCreate(
                    None,
                    Some(&*pool_attrs),
                    Some(&*pb_attrs),
                    std::ptr::NonNull::new_unchecked(&mut pool as *mut _),
                );
                if status != 0 || pool.is_null() {
                    use std::sync::atomic::AtomicU64;
                    static FAILS: AtomicU64 = AtomicU64::new(0);
                    let fails = FAILS.fetch_add(1, Ordering::Relaxed);
                    if fails < 3 {
                        println!(
                            "[video] CVPixelBufferPoolCreate failed status={status} for {w}x{h}"
                        );
                    }
                    // un-pooled fallback — still bounded by our release discipline
                    let mut pb: *mut objc2_core_video::CVPixelBuffer =
                        std::ptr::null_mut();
                    let status = objc2_core_video::CVPixelBufferCreate(
                        None,
                        w as usize,
                        h as usize,
                        875_704_422,
                        Some(&*pb_attrs),
                        std::ptr::NonNull::new_unchecked(&mut pb as *mut _),
                    );
                    if status == 0 && !pb.is_null() {
                        return pb as *mut c_void;
                    }
                    return std::ptr::null_mut();
                }
                println!("[video] pixel buffer pool created for {w}x{h} (cap 4, '420f')");
                pools.get_or_insert_with(HashMap::new).insert((w, h), Pool(pool));
                pool
            }
        }
    };
    let mut pb: *mut objc2_core_video::CVPixelBuffer = std::ptr::null_mut();
    let status = objc2_core_video::CVPixelBufferPoolCreatePixelBuffer(
        None,
        &*pool,
        std::ptr::NonNull::new_unchecked(&mut pb as *mut _),
    );
    if status != 0 || pb.is_null() {
        return std::ptr::null_mut();
    }
    pb as *mut c_void
}

/// Enqueues one frame's CVPixelBuffer. Takes an OWNED (+1) reference and
/// releases it exactly once on every path — the sample buffer's own
/// retain is what keeps the buffer alive inside the layer.
pub fn enqueue_cv_pixel_buffer(cv_pixel_buffer: *mut c_void) {
    unsafe fn release_pb(pb: *mut c_void) {
        if !pb.is_null() {
            // CVPixelBuffer is a CF object — release by taking the +1 into
            // a Retained and dropping it.
            if let Some(r) = objc2::rc::Retained::from_raw(
                pb as *mut objc2_core_video::CVBuffer,
            ) {
                drop(r);
            }
        }
    }
    let Some(vw) = VIDEO_WINDOW.get() else {
        unsafe { release_pb(cv_pixel_buffer) };
        return;
    };
    if cv_pixel_buffer.is_null() {
        return;
    }
    unsafe {
        // Host-clock pts via CoreMedia's own clock — the layer's timeline.
        // (An epoch-based value overflows CMTime's i32 and enqueueSampleBuffer
        // raised NSException → abort; a counter-from-zero rendered but the
        // VTDecoder surface pool grew unboundedly. CMClockGetTime is the
        // correct, int32-safe host time.)
        let pts = {
            let clock = objc2_core_media::CMClockGetHostTimeClock();
            objc2_core_media::CMClockGetTime(&clock)
        };
        let invalid = kCMTimeInvalid;
        let timing = CMSampleTimingInfo {
            duration: invalid,
            presentationTimeStamp: pts,
            decodeTimeStamp: invalid,
        };
        // The pixel buffer carries no format description — creating the
        // sample with a NULL one failed with -12731 RequiredParameterMissing
        // (found live in M3). Derive one from the buffer, cached per dims.
        let format_desc = cached_format_description(cv_pixel_buffer);
        if format_desc.is_null() {
            release_pb(cv_pixel_buffer); // the failure log says why
            return;
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
            release_pb(cv_pixel_buffer);
            return;
        }
        // The sample retains the pixel buffer — our +1 ends here; every
        // path below only manages the sample.
        release_pb(cv_pixel_buffer);
        // Failed-layer guard — checked EVERY frame, BEFORE enqueue. When
        // video decoder resources are reclaimed or a decode error occurs,
        // the layer's status flips to Failed and the NEXT enqueue raises
        // an NSException, aborting the whole app ("Rust cannot catch
        // foreign exceptions" — found live ~3600 frames in, 02:04 crash;
        // the old n%600 check was structurally too late). The header's
        // documented recovery: -flush resets Failed → Unknown.
        {
            use std::sync::atomic::AtomicBool;
            static FAILED_EPISODE: AtomicBool = AtomicBool::new(false);
            // AVQueuedSampleBufferRenderingStatus: 0=Unknown 1=Rendering
            // 2=Failed
            let layer_status: isize = msg_send![vw.layer, status];
            if layer_status == 2 {
                if !FAILED_EPISODE.swap(true, Ordering::Relaxed) {
                    println!(
                        "[video] layer FAILED ({}) — flushing to reset",
                        layer_error_description(vw.layer)
                    );
                }
                let _ = objc2::exception::catch(std::panic::AssertUnwindSafe(|| {
                    let _: () = msg_send![vw.layer, flush];
                    let _: () = msg_send![vw.layer, setNeedsDisplay: true];
                }));
                let _: () = msg_send![sample, release]; // this frame is dropped
                return;
            }
            if FAILED_EPISODE.swap(false, Ordering::Relaxed) {
                println!("[video] layer recovered — resuming enqueue");
            }
        }
        // Occlusion gate: a fully-occluded (or minimized) window is not
        // composited by WindowServer, so the layer never "displays" its
        // enqueued samples — each one keeps its decoded IOSurface alive
        // forever, attributed to the VTDecoder XPC service. Live evidence:
        // 8.8 GB retained in ~7 min with the window hidden behind Activity
        // Monitor (isReady stayed YES the whole time — 0 drops), draining
        // to 13 MB the instant the window came front. While occluded:
        // flush (rate-limited) frees whatever is queued; the frame drops
        // at the live edge, same doctrine as the ready-gate below.
        {
            let occlusion: u64 = msg_send![vw.window, occlusionState];
            // NSWindowOcclusionStateVisible = 1UL << 1 in the macOS 26 SDK
            // (live-found: it is 1UL << 0 in older SDKs — checking the old
            // bit dropped every frame of a fully visible window, blank video).
            if occlusion & 2 == 0 {
                use std::sync::Mutex;
                static LAST_OCCLUDED_FLUSH: Mutex<Option<std::time::Instant>> = Mutex::new(None);
                {
                    let mut clock = LAST_OCCLUDED_FLUSH.lock().expect("occlusion clock");
                    let due = clock
                        .map(|t| t.elapsed().as_millis() >= 500)
                        .unwrap_or(true);
                    if !due {
                        drop(clock);
                    } else {
                        *clock = Some(std::time::Instant::now());
                        drop(clock);
                        let _ = objc2::exception::catch(std::panic::AssertUnwindSafe(|| {
                            let _: () = msg_send![vw.layer, flush];
                        }));
                    }
                }
                // Diagnostic once a second while occluded: WHY does the
                // window read as not visible (live: it sat behind the
                // main Tauri window from cast start — every frame dropped).
                {
                    use std::sync::Mutex;
                    static LAST_DIAG: Mutex<Option<std::time::Instant>> = Mutex::new(None);
                    let mut diag = LAST_DIAG.lock().expect("diag clock");
                    let due = diag
                        .map(|t| t.elapsed().as_secs() >= 1)
                        .unwrap_or(true);
                    if due {
                        *diag = Some(std::time::Instant::now());
                        drop(diag);
                        let vis: bool = msg_send![vw.window, isVisible];
                        let on_space: bool = msg_send![vw.window, isOnActiveSpace];
                        let num: isize = msg_send![vw.window, windowNumber];
                        println!(
                            "[video] occluded diag: occ={occlusion} visible={vis} activeSpace={on_space} win={num}"
                        );
                    }
                }
                // If the cast has NEVER rendered a frame and the window
                // reads occluded for >1 s, request a one-time raise —
                // the window was born buried (behind the main window).
                {
                    use std::sync::Mutex;
                    static CAST_START: Mutex<Option<std::time::Instant>> = Mutex::new(None);
                    static RAISE_REQUESTED: std::sync::atomic::AtomicBool =
                        std::sync::atomic::AtomicBool::new(false);
                    let start = CAST_START
                        .lock()
                        .expect("cast start")
                        .get_or_insert_with(std::time::Instant::now)
                        .clone();
                    if !EVER_ENQUEUED_FLAG.load(Ordering::Relaxed)
                        && start.elapsed().as_secs() >= 1
                        && !RAISE_REQUESTED.swap(true, Ordering::Relaxed)
                    {
                        println!("[video] no frame ever rendered + window buried — requesting raise");
                        RAISE_PENDING.store(true, Ordering::Relaxed);
                    }
                }
                use std::sync::atomic::AtomicU64;
                static OCCLUDED_DROPS: AtomicU64 = AtomicU64::new(0);
                let _: () = msg_send![sample, release]; // never enqueued
                let dropped = OCCLUDED_DROPS.fetch_add(1, Ordering::Relaxed);
                if dropped == 1 || dropped % 600 == 0 {
                    println!("[video] window occluded — dropped {dropped} frames at the live edge");
                }
                return;
            }
            EVER_ENQUEUED_FLAG.store(true, Ordering::Relaxed);
        }
        // Bounded queue + live edge: the layer buffers unboundedly if we
        // enqueue while it is not ready — 20 GB RSS on a minutes-long cast
        // (found live). For a live cast, dropping is CORRECT: the newest
        // frame is the one that matters.
        let ready: bool = msg_send![vw.layer, isReadyForMoreMediaData];
        if !ready {
            use std::sync::atomic::AtomicU64;
            use std::sync::Mutex;
            static DROPPED: AtomicU64 = AtomicU64::new(0);
            static LAST_FLUSH_TICK: Mutex<Option<std::time::Instant>> = Mutex::new(None);
            let _: () = msg_send![sample, release]; // not enqueued — free now
            let dropped = DROPPED.fetch_add(1, Ordering::Relaxed);
            // If the layer stalls >1 s (occlusion, drawable starvation),
            // flush and resume at the live edge.
            let mut clock = LAST_FLUSH_TICK.lock().expect("flush clock");
            let stalled = clock
                .map(|t| t.elapsed().as_secs() >= 1)
                .unwrap_or(true);
            if stalled {
                *clock = Some(std::time::Instant::now());
                drop(clock);
                let _: () = msg_send![vw.layer, flush];
                let _: () = msg_send![vw.layer, setNeedsDisplay: true];
            }
            if dropped == 1 || dropped % 300 == 0 {
                println!("[video] layer not ready — dropped {dropped} frames (bounded queue, live edge)");
            }
            return;
        }
        // enqueueSampleBuffer, guarded: even with healthy status, readiness
        // can flip between the check above and this call — a foreign
        // exception must never abort the process. Flush; the next frame
        // retries at the live edge.
        let enqueued = objc2::exception::catch(std::panic::AssertUnwindSafe(|| {
            let _: () = msg_send![vw.layer, enqueueSampleBuffer: sample];
        }));
        let _: () = msg_send![sample, release]; // our ref; the layer retains
        if let Err(maybe_exc) = enqueued {
            let reason = maybe_exc
                .map(|exc| {
                    let r: Id = msg_send![&*exc, reason];
                    if r.is_null() {
                        "(no reason)".to_string()
                    } else {
                        let s: Id = msg_send![r, UTF8String];
                        if s.is_null() {
                            "(utf8 nil)".to_string()
                        } else {
                            std::ffi::CStr::from_ptr(s as *const std::ffi::c_char)
                                .to_string_lossy()
                                .into_owned()
                        }
                    }
                })
                .unwrap_or_else(|| "(foreign, no exception object)".to_string());
            println!("[video] enqueue RAISED: {reason} — flushing layer");
            let _ = objc2::exception::catch(std::panic::AssertUnwindSafe(|| {
                let _: () = msg_send![vw.layer, flush];
            }));
        }
        if n < 3 {
            let layer_status: isize = msg_send![vw.layer, status];
            println!("[video] enqueue #{n} ok — layer status={layer_status}");
        }
        if n > 0 && n % 1800 == 0 {
            let layer_status: isize = msg_send![vw.layer, status];
            println!("[video] enq=#{n} — layer status={layer_status}");
        }
    }
}

/// The display layer's NSError description, for the FAILED log line.
unsafe fn layer_error_description(layer: Id) -> String {
    let err: Id = msg_send![layer, error];
    if err.is_null() {
        return "(nil error)".to_string();
    }
    let desc: Id = msg_send![err, localizedDescription];
    if desc.is_null() {
        return "(nil desc)".to_string();
    }
    let s: Id = msg_send![desc, UTF8String];
    if s.is_null() {
        return "(utf8 nil)".to_string();
    }
    std::ffi::CStr::from_ptr(s as *const std::ffi::c_char)
        .to_string_lossy()
        .into_owned()
}

/// True once the native window exists (the webview JPEG preview is the
/// fallback when it does not).
pub fn is_active() -> bool {
    VIDEO_WINDOW.get().is_some()
}

/// Main-thread only: raises the video window above the main window.
pub fn order_video_window_front() {
    if let Some(vw) = VIDEO_WINDOW.get() {
        unsafe {
            let _: () = msg_send![vw.window, makeKeyAndOrderFront: std::ptr::null::<AnyObject>()];
            let _: () = msg_send![vw.window, orderFrontRegardless];
        }
        println!("[video] window raised (makeKeyAndOrderFront + orderFrontRegardless)");
    }
}

/// One-shot raise request from the frame thread (consumed by webrtc.rs,
/// which owns the AppHandle that can reach the main thread).
pub fn take_raise_request() -> bool {
    RAISE_PENDING.swap(false, Ordering::Relaxed)
}
