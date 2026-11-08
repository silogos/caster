//! M0-r: the media answerer — a libwebrtc PeerConnection (via
//! `livekit::webrtc`, the LiveKit Rust SDKs' FFI layer) that answers the
//! phone's `media` offer: video track (CVPixelBuffer frames on macOS), the
//! `game-audio` DataChannel, and the dual-mono fallback audio track.
//!
//! The factory carries `with_zero_playout_delay()` — the native receiver's
//! video latency knob (docs: livekit::webrtc). Interop with the phone's
//! pinned libwebrtc 1.3.8 sender is libwebrtc↔libwebrtc — the best-tested
//! path in existence.

use livekit::webrtc::data_channel::DataChannel;
use livekit::webrtc::media_stream_track::MediaStreamTrack;
use livekit::webrtc::peer_connection::TrackEvent;
use livekit::webrtc::video_stream::native::NativeVideoStream;
use livekit::webrtc::{
    peer_connection::{AnswerOptions, PeerConnection},
    peer_connection_factory::{PeerConnectionFactory, RtcConfiguration},
    session_description::{SessionDescription, SdpType},
};
use futures_util::StreamExt;
use serde_json::json;
use std::sync::{Arc, Mutex};
use tauri::Emitter;

/// The PeerConnectionFactory — created ONCE on the main thread (Tauri
/// setup), accessed from the signaling thread. libwebrtc's macOS video
/// encoder enumeration dispatches internally and requires NSApplication
/// context during creation (crashed with NSException otherwise, found
/// live in M0-r).
static FACTORY: std::sync::OnceLock<PeerConnectionFactory> = std::sync::OnceLock::new();

/// Creates the factory on the CALLING thread. Must be called from the
/// main thread (Tauri setup).
pub fn init_factory() {
    let _ = FACTORY.set(PeerConnectionFactory::with_zero_playout_delay());
}

pub struct MediaAnswerer {
    factory: PeerConnectionFactory,
    pc: Arc<Mutex<Option<PeerConnection>>>,
}

impl MediaAnswerer {
    /// The zero-playout-delay factory — the native receiver's video latency
    /// knob (the thing Chromium locked at ~91 ms). NSApplication is
    /// initialized first: libwebrtc's macOS video encoder enumeration
    /// dispatches to a worker thread that needs the NSApplication context
    /// (crashed with NSException without this — found live in M0-r).
    pub fn new() -> Self {
        Self {
            factory: FACTORY.get().expect("factory must be initialized first").clone(),
            pc: Arc::new(Mutex::new(None)),
        }
    }

    /// Answers one `media` offer. Returns the answer SDP for the signaling
    /// layer to send. ICE candidates surface via the `on_ice` callback.
    pub async fn answer(
        &self,
        app: &tauri::AppHandle,
        sdp_text: &str,
        ice_tx: tokio::sync::mpsc::Sender<String>,
    ) -> Result<String, String> {
        let offer = SessionDescription::parse(sdp_text, SdpType::Offer)
            .map_err(|e| format!("offer parse: {e}"))?;

        let pc = self
            .factory
            .create_peer_connection(RtcConfiguration::default())
            .map_err(|e| format!("create pc: {e}"))?;

        let dc_app = app.clone();
        pc.on_data_channel(Some(Box::new(move |dc: DataChannel| {
            println!("[webrtc] data channel open");
            let dc_app = dc_app.clone();
            // Register the message handler on the channel itself.
            dc.on_message(Some(Box::new(move |msg| {
                use std::sync::atomic::{AtomicU64, Ordering};
                static MSGS: AtomicU64 = AtomicU64::new(0);
                static BYTES: AtomicU64 = AtomicU64::new(0);
                let n = MSGS.fetch_add(1, Ordering::Relaxed);
                BYTES.fetch_add(msg.data.len() as u64, Ordering::Relaxed);
                if !msg.binary {
                    // The channel's text sub-protocol (announce). Deliberately
                    // NOT acked: without an ack the phone stays on raw PCM —
                    // the engine path (ADR-013's own fallback ladder).
                    println!("[webrtc] dc control text: {}", String::from_utf8_lossy(&msg.data));
                    return;
                }
                if n == 0 {
                    println!("[webrtc] FIRST DataChannel PCM chunk ({} bytes) — feeding the engine", msg.data.len());
                    crate::audio::start();
                }
                crate::audio::feed_pcm16(&msg.data);
                if n % 100 == 0 {
                    let (underruns, played) = crate::audio::stats();
                    println!(
                        "[webrtc] dc: {} msgs, {} kB total | audio underruns={underruns} played={played}",
                        n + 1,
                        BYTES.load(Ordering::Relaxed) / 1024
                    );
                }
                let _ = dc_app.emit(
                    "engine:dc-stats",
                    json!({ "bytes": msg.data.len(), "binary": msg.binary }),
                );
            })));
        })));

        let frame_app = app.clone();
        pc.on_track(Some(Box::new(move |event: TrackEvent| {
            let track = event.track;
            match track {
                MediaStreamTrack::Video(video) => {
                    println!("[webrtc] video track arrived — consuming frames");
                    let frame_app = frame_app.clone();
                    let window_app = frame_app.clone();
                    // The frame consumer runs on the async runtime; M0-r
                    // emits dims only — the Metal subview takes over at M3.
                    tauri::async_runtime::spawn(async move {
                        use std::sync::atomic::{AtomicU64, Ordering};
                        static FRAMES: AtomicU64 = AtomicU64::new(0);
                        static BYTES: AtomicU64 = AtomicU64::new(0);
                        let mut last_log = std::time::Instant::now();
                        let mut last_jpeg = std::time::Instant::now();
                        let mut stream = NativeVideoStream::new(video);
                        let mut first = true;
                        while let Some(frame) = stream.next().await {
                            if first {
                                first = false;
                                println!(
                                    "[webrtc] first frame dims: {}x{} buffer_type={:?}",
                                    frame.buffer.width(),
                                    frame.buffer.height(),
                                    frame.buffer.buffer_type()
                                );
                                // M3: the native video window — created on the
                                // main thread on the first frame.
                                let _ = window_app.run_on_main_thread(|| {
                                    crate::video::create_video_window();
                                });
                            }
                            use livekit::webrtc::video_frame::VideoBuffer;
                            if let Some(native) = frame.buffer.as_native() {
                                let cv = native.get_cv_pixel_buffer();
                                if !cv.is_null() {
                                    crate::video::enqueue_cv_pixel_buffer(cv);
                                }
                            }
                            FRAMES.fetch_add(1, Ordering::Relaxed);
                            BYTES.fetch_add(frame.buffer.width() as u64 * frame.buffer.height() as u64 * 4, Ordering::Relaxed);
                            frame_app
                                .emit(
                                    "video:frame",
                                    json!({
                                        "width": frame.buffer.width(),
                                        "height": frame.buffer.height(),
                                    }),
                                )
                                .ok();
                            // M3-lite preview: ~12 fps downscaled JPEG data URLs
                            // to the webview — ONLY while the native video window
                            // is not live (both showing at once doubled the view).
                            if !crate::video::is_active()
                                && last_jpeg.elapsed() >= std::time::Duration::from_millis(80)
                            {
                                last_jpeg = std::time::Instant::now();
                                println!("[webrtc] JPEG-BRANCH-ENTERED");
                                let sw = frame.buffer.width();
                                let sh = frame.buffer.height();
                                if sw > 0 && sh > 0 {
                                    let scale = (640.0 / sw as f32).min(1.0);
                                    let dw = (sw as f32 * scale).max(2.0) as i32;
                                    let dh = (sh as f32 * scale).max(2.0) as i32;
                                    let mut rgba = vec![0u8; (dw * dh * 4) as usize];
                                    use livekit::webrtc::video_frame::VideoBuffer;
                                    frame.buffer.to_argb(
                                        livekit::webrtc::video_frame::VideoFormatType::RGBA,
                                        &mut rgba,
                                        (dw * 4) as u32,
                                        dw,
                                        dh,
                                    );
                                    static GATE: AtomicU64 = AtomicU64::new(0);
                                    let g = GATE.fetch_add(1, Ordering::Relaxed);
                                    // to_argb(RGBA) yields A,B,G,R memory order here
                                    // (live-evidenced twice: byte0 = alpha 255 → all-red
                                    // when read as RGBA; then R↔B swap made orange blue).
                                    // Plus BT.601/709 limited-range expansion (16-235 →
                                    // 0-255): without it bright greens read gray-ish
                                    // (found live: "hijau terang di HP, abu-abu di desktop").
                                    for px in rgba.chunks_exact_mut(4) {
                                        let expand = |v: u8| -> u8 {
                                            let f = (v as i32 - 16) * 255 / 219;
                                            f.clamp(0, 255) as u8
                                        };
                                        let a = px[0];
                                        px[0] = expand(px[3]); // R
                                        px[1] = expand(px[2]); // G
                                        px[2] = expand(px[1]); // B
                                        px[3] = a;             // A
                                    }
                                    if let Some(img) = image::RgbaImage::from_raw(dw as u32, dh as u32, rgba) {
                                        let mut jpg = Vec::new();
                                        // Explicit quality — the default encoder washed out
                                        // saturated greens (user-visible on the preview).
                                        let mut encoder = image::codecs::jpeg::JpegEncoder::new_with_quality(&mut jpg, 88);
                                        let enc = image::DynamicImage::ImageRgba8(img)
                                            .write_with_encoder(encoder);
                                        if g == 0 {
                                            match &enc { Ok(_) => println!("[webrtc] jpeg encode OK"), Err(e) => println!("[webrtc] jpeg encode ERR: {e}") };
                                        }
                                        if enc.is_ok()
                                        {
                                            use base64::Engine;
                                            static JPEGS: AtomicU64 = AtomicU64::new(0);
                                            let jn = JPEGS.fetch_add(1, Ordering::Relaxed);
                                            if jn == 0 {
                                                let _ = std::fs::write("/tmp/zf-native-preview.jpg", &jpg);
                                                println!("[webrtc] first jpeg preview written ({} kB) — emitting to ui", jpg.len() / 1024);
                                            }
                                            if jn % 100 == 0 {
                                                println!("[webrtc] jpeg previews emitted: {}", jn + 1);
                                            }
                                            let b64 = base64::engine::general_purpose::STANDARD.encode(&jpg);
                                            frame_app
                                                .emit("video:jpeg", json!({ "dataUrl": format!("data:image/jpeg;base64,{b64}") }))
                                                .ok();
                                        }
                                    }
                                }
                            }
                            if last_log.elapsed() >= std::time::Duration::from_secs(1) {
                                last_log = std::time::Instant::now();
                                println!(
                                    "[webrtc] video: {} frames, {} Mpx/s",
                                    FRAMES.load(Ordering::Relaxed),
                                    BYTES.swap(0, Ordering::Relaxed) / 1_048_576
                                );
                            }
                        }
                        println!("[webrtc] video frame stream ended");
                    });
                }
                MediaStreamTrack::Audio(audio) => {
                    println!("[webrtc] audio track arrived — feeding the engine (fallback path)");
                    tauri::async_runtime::spawn(async move {
                        use livekit::webrtc::audio_stream::native::NativeAudioStream;
                        use futures_util::StreamExt as _;
                        crate::audio::start();
                        let mut stream = NativeAudioStream::new(audio, 48_000, 2);
                        while let Some(frame) = stream.next().await {
                            let mut bytes = Vec::with_capacity(frame.data.len() * 2);
                            for sample in frame.data.iter() {
                                bytes.extend_from_slice(&sample.to_le_bytes());
                            }
                            crate::audio::feed_pcm16(&bytes);
                        }
                        println!("[webrtc] audio track stream ended");
                    });
                }
                other => println!("[webrtc] other track arrived: {other:?}"),
            }
        })));

        let ice_app = app.clone();
        let ice_tx = Arc::new(tokio::sync::Mutex::new(ice_tx));
        pc.on_ice_candidate(Some(Box::new(move |candidate| {
            let line = candidate.candidate();
            let _ = ice_app.clone();
            if let Ok(tx) = ice_tx.clone().try_lock() {
                let _ = tx.try_send(line);
            }
            let _ = ice_app;
        })));

        pc.on_connection_state_change(Some(Box::new(move |state| {
            println!("[webrtc] pc state: {state:?}");
        })));
        pc.on_ice_connection_state_change(Some(Box::new(move |state| {
            println!("[webrtc] ice state: {state:?}");
        })));

        let offer = SessionDescription::parse(sdp_text, SdpType::Offer)
            .map_err(|e| format!("offer parse: {e}"))?;
        pc.set_remote_description(offer)
            .await
            .map_err(|e| format!("set remote: {e}"))?;
        let answer = pc
            .create_answer(AnswerOptions::default())
            .await
            .map_err(|e| format!("create answer: {e}"))?;
        pc.set_local_description(answer.clone())
            .await
            .map_err(|e| format!("set local: {e}"))?;

        *self.pc.lock().expect("pc lock") = Some(pc);

        let sdp = answer.to_string();
        println!("[webrtc] answer produced ({} bytes)", sdp.len());
        Ok(sdp)
    }

    /// Adds a remote ICE candidate (trickled by the phone over signaling),
    /// with the phone-provided mid/mline — the indices are part of the
    /// candidate's identity under BUNDLE.
    pub async fn add_remote_candidate(&self, line: &str, mid: &str, mline: i32) {
        let candidate = livekit::webrtc::ice_candidate::IceCandidate::parse(line, mline, mid);
        match candidate {
            Ok(candidate) => {
                if let Some(pc) = self.pc.lock().expect("pc lock").as_ref() {
                    if let Err(e) = pc.add_ice_candidate(candidate).await {
                        println!("[webrtc] add_ice_candidate failed: {e:?}");
                    }
                }
            }
            Err(e) => {
                // IPv6/mDNS lines fail this parser on some paths — logged,
                // non-fatal: the UDP host pair carries the connection (found
                // live in M0-r; the IPv6 candidate was never needed).
                println!("[webrtc] candidate parse skipped: {e:?}")
            }
        }
    }
}
