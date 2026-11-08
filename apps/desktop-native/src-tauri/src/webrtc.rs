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
use crate::protocol::{Candidate, PcId};
use crate::signaling::{IceSender, OutboundIce};
use futures_util::StreamExt;
use livekit::webrtc::peer_connection::{IceGatheringState, PeerConnectionState};
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
/// The DataChannel stats line cadence: 100 chunks of 10 ms = ~1 s.
const DC_LOG_EVERY_CHUNKS: u64 = 100;

pub fn init_factory() {
    let _ = FACTORY.set(PeerConnectionFactory::with_zero_playout_delay());
}

/// Answers ONE of the phone's two PeerConnections (webrtc.md §Two
/// PeerConnections): `media` (screen video, game-audio DataChannel, the
/// dual-mono fallback track) or `mic`. Each pc has its own answerer, so a
/// mic offer can never replace the live media connection.
pub struct MediaAnswerer {
    role: PcId,
    app: tauri::AppHandle,
    factory: PeerConnectionFactory,
    pc: Arc<Mutex<Option<PeerConnection>>>,
    /// The phone's `game-audio` channel — owned here so its message observer
    /// lives as long as the PeerConnection (see `on_data_channel`).
    game_audio_dc: Arc<Mutex<Option<KeptChannel>>>,
}

/// libwebrtc DataChannel handle moved across the callback threads; the
/// underlying C++ object is internally synchronized.
struct KeptChannel(#[allow(dead_code)] DataChannel);
unsafe impl Send for KeptChannel {}

impl MediaAnswerer {
    /// The zero-playout-delay factory — the native receiver's video latency
    /// knob (the thing Chromium locked at ~91 ms). NSApplication is
    /// initialized first: libwebrtc's macOS video encoder enumeration
    /// dispatches to a worker thread that needs the NSApplication context
    /// (crashed with NSException without this — found live in M0-r).
    pub fn new(role: PcId, app: tauri::AppHandle) -> Self {
        Self {
            role,
            app,
            factory: FACTORY.get().expect("factory must be initialized first").clone(),
            pc: Arc::new(Mutex::new(None)),
            game_audio_dc: Arc::new(Mutex::new(None)),
        }
    }

    /// Answers this pc's offer (a re-offer replaces the previous connection).
    /// Local candidates — then the end-of-gathering marker — go out via `ice`.
    pub async fn answer(&self, sdp_text: &str, ice: IceSender) -> Result<String, String> {
        // Parsed before the PeerConnection exists: a malformed offer fails
        // fast without building (and leaking callbacks onto) a new PC.
        let offer = SessionDescription::parse(sdp_text, SdpType::Offer)
            .map_err(|e| format!("offer parse: {e}"))?;

        let pc = self
            .factory
            .create_peer_connection(RtcConfiguration::default())
            .map_err(|e| format!("create pc: {e}"))?;

        // A new offer replaces the session: close the previous connection,
        // and a new media session starts the ear output from a clean ring.
        self.close();
        if self.role == PcId::Media {
            crate::audio::reset_session();
        }
        let role = self.role;
        let kept_dc = Arc::clone(&self.game_audio_dc);
        pc.on_data_channel(Some(Box::new(move |dc: DataChannel| {
            if role != PcId::Media {
                log::warn!(target: "webrtc", "unexpected data channel on pc={}", role.as_str());
                return;
            }
            log::info!(
                target: "webrtc", "data channel arrived: label={:?} id={} state={:?}",
                dc.label(),
                dc.id(),
                dc.state()
            );
            // Channel lifecycle (OPEN/CLOSED) for diagnosing the stereo path.
            {
                let label = dc.label();
                dc.on_state_change(Some(Box::new(move |state| {
                    log::info!(target: "webrtc", "dc {label:?} state -> {state:?}");
                })));
            }
            // Per-channel counters (a reconnect starts from zero).
            let mut chunks: u64 = 0;
            let mut bytes: u64 = 0;
            dc.on_message(Some(Box::new(move |msg| {
                if !msg.binary {
                    // The channel's text sub-protocol (announce). Deliberately
                    // NOT acked: without an ack the phone stays on raw PCM —
                    // the engine path (ADR-013's own fallback ladder).
                    log::debug!(target: "webrtc", "dc control text: {}", String::from_utf8_lossy(&msg.data));
                    return;
                }
                if chunks == 0 {
                    log::info!(target: "webrtc", "first DataChannel PCM chunk ({} bytes) — feeding the engine", msg.data.len());
                }
                chunks += 1;
                bytes += msg.data.len() as u64;
                crate::audio::feed_dc_pcm16(&msg.data);
                if chunks % DC_LOG_EVERY_CHUNKS == 0 {
                    let st = crate::audio::stats();
                    log::info!(
                        target: "webrtc", "dc: {chunks} chunks, {} kB | audio underruns={} sheds={} depth={}ms played={} side={:.0}dB",
                        bytes / 1024,
                        st.underruns,
                        st.sheds,
                        st.depth_ms,
                        st.frames_played,
                        crate::audio::take_side_db()
                    );
                }
            })));
            // Hold the channel for the PeerConnection's life: dropping the
            // last Rust handle unregisters its observer, and the stereo PCM
            // stops arriving (found live 2026-11-09: a 30 s debug probe
            // thread was the only other handle — the DC went quiet at ~29 s
            // and the receiver fell back to the dual-mono Opus track).
            *kept_dc.lock().expect("dc keep lock") = Some(KeptChannel(dc));
        })));

        let frame_app = self.app.clone();
        pc.on_track(Some(Box::new(move |event: TrackEvent| {
            let track = event.track;
            match track {
                // The mic pc's track: separate from game audio end to end
                // (AGENTS.md). Its playback output lands with the audio stage.
                MediaStreamTrack::Audio(_) if role == PcId::Mic => {
                    log::info!(target: "webrtc", "mic track arrived — not played yet (receiver mic output pending)");
                }
                MediaStreamTrack::Video(video) => {
                    log::info!(target: "webrtc", "video track arrived — consuming frames");
                    let app = frame_app.clone();
                    // Its own thread (not the shared Tauri runtime): the
                    // per-frame copy + layer calls must not stall signaling
                    // or audio tasks. The stream ends when the pc closes.
                    let spawned = std::thread::Builder::new().name("video-frames".into()).spawn(move || {
                        let runtime = match tokio::runtime::Builder::new_current_thread().enable_all().build() {
                            Ok(rt) => rt,
                            Err(e) => return log::error!(target: "webrtc", "video runtime: {e}"),
                        };
                        runtime.block_on(consume_video(app, video));
                    });
                    if let Err(e) = spawned {
                        log::error!(target: "webrtc", "video thread: {e}");
                    }
                }
                MediaStreamTrack::Audio(audio) => {
                    log::info!(target: "webrtc", "audio track arrived — feeding the engine (fallback path)");
                    tauri::async_runtime::spawn(async move {
                        use livekit::webrtc::audio_stream::native::NativeAudioStream;
                        use futures_util::StreamExt as _;
                        let mut stream = NativeAudioStream::new(audio, 48_000, 2);
                        while let Some(frame) = stream.next().await {
                            let mut bytes = Vec::with_capacity(frame.data.len() * 2);
                            for sample in frame.data.iter() {
                                bytes.extend_from_slice(&sample.to_le_bytes());
                            }
                            crate::audio::feed_track_pcm16(&bytes);
                        }
                        log::info!(target: "webrtc", "audio track stream ended");
                    });
                }
            }
        })));

        let candidate_tx = ice.clone();
        pc.on_ice_candidate(Some(Box::new(move |candidate| {
            // A send fails only once the signaling connection is gone.
            let _ = candidate_tx.send(OutboundIce {
                pc: role,
                candidate: Some(Candidate {
                    candidate: candidate.candidate(),
                    sdp_mid: candidate.sdp_mid(),
                    sdp_mline_index: candidate.sdp_mline_index(),
                }),
            });
        })));
        pc.on_ice_gathering_state_change(Some(Box::new(move |state| {
            if state == IceGatheringState::Complete {
                let _ = ice.send(OutboundIce { pc: role, candidate: None });
            }
        })));

        pc.on_connection_state_change(Some(Box::new(move |state| {
            log::info!(target: "webrtc", "pc={} state: {state:?}", role.as_str());
            // The display stays awake exactly while the cast is live.
            if role == PcId::Media {
                crate::power::keep_display_awake(state == PeerConnectionState::Connected);
            }
        })));
        pc.on_ice_connection_state_change(Some(Box::new(move |state| {
            log::info!(target: "webrtc", "pc={} ice state: {state:?}", role.as_str());
        })));

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
        log::info!(target: "webrtc", "answer produced ({} bytes)", sdp.len());
        // Structure comparison (offer vs answer): mids, bundle group, setup —
        // an m-section without a mid cannot join the BUNDLE group and its
        // transport never starts (the Connecting-forever DC).
        let structure = |label: &str, text: &str| {
            let hits: Vec<&str> = text
                .lines()
                .filter(|l| {
                    l.starts_with("a=group:")
                        || l.starts_with("a=mid:")
                        || l.starts_with("a=setup:")
                        || l.starts_with("a=sctp-port")
                        || l.starts_with("m=")
                })
                .collect();
            log::debug!(target: "webrtc", "{label} structure: {hits:?}");
        };
        structure("offer", sdp_text);
        structure("answer", &sdp);
        Ok(sdp)
    }

    /// Adds a remote ICE candidate (trickled by the phone over signaling),
    /// with the phone-provided mid/mline — the indices are part of the
    /// candidate's identity under BUNDLE.
    pub async fn add_remote_candidate(&self, c: &Candidate) {
        // parse(sdp_mid, sdp_mline_index, sdp) — the arguments were once
        // passed in the reverse order and every phone candidate failed to
        // parse (`SdpParseError { line: "0" }`, found in review 2026-11-09).
        let candidate = livekit::webrtc::ice_candidate::IceCandidate::parse(&c.sdp_mid, c.sdp_mline_index, &c.candidate);
        let pc = self.pc.lock().expect("pc lock").clone();
        match candidate {
            Ok(candidate) => {
                // No std MutexGuard is held across this await (cloned above).
                if let Some(pc) = pc {
                    if let Err(e) = pc.add_ice_candidate(candidate).await {
                        log::warn!(target: "webrtc", "pc={} add_ice_candidate failed: {e:?}", self.role.as_str());
                    }
                }
            }
            Err(e) => log::warn!(target: "webrtc", "pc={} remote candidate rejected: {e:?}", self.role.as_str()),
        }
    }

    /// Tears this pc down (a `bye`, or before a re-offer replaces it).
    pub fn close(&self) {
        if self.role == PcId::Media {
            crate::power::keep_display_awake(false);
        }
        *self.game_audio_dc.lock().expect("dc keep lock") = None;
        if let Some(pc) = self.pc.lock().expect("pc lock").take() {
            pc.close();
            log::info!(target: "webrtc", "pc={} closed", self.role.as_str());
        }
    }
}

const VIDEO_LOG_INTERVAL: std::time::Duration = std::time::Duration::from_secs(1);

/// The video frame loop for one stream (one cast): renders into the native
/// window while the user has it open, keeps its visibility fresh from the
/// main thread, and tells the UI when the cast goes live / ends.
async fn consume_video(app: tauri::AppHandle, video: livekit::webrtc::video_track::RtcVideoTrack) {
    let mut stream = NativeVideoStream::new(video);
    let mut renderer = crate::video::Renderer::new();
    let mut frames: u64 = 0;
    let mut window_requested = false;
    let mut last_visibility_poll: Option<std::time::Instant> = None;
    let mut last_log = std::time::Instant::now();
    let mut frames_at_last_log: u64 = 0;
    // Per-frame cost of convert + copy + enqueue (rendered frames only).
    let mut render_cost = std::time::Duration::ZERO;
    let mut rendered: u32 = 0;
    while let Some(frame) = stream.next().await {
        let (w, h) = (frame.buffer.width(), frame.buffer.height());
        if !window_requested {
            window_requested = true;
            log::info!(target: "webrtc", "first frame {w}x{h}");
            let _ = app.emit("cast:state", json!({ "live": true }));
            let _ = app.run_on_main_thread(|| crate::video::set_no_input(false));
            // A window the user left open comes back for the new cast.
            if crate::video::is_open() {
                let _ = app.run_on_main_thread(crate::video::open_video_window);
            }
        }
        if last_visibility_poll.map_or(true, |t| t.elapsed() >= crate::video::VISIBILITY_POLL_INTERVAL) {
            last_visibility_poll = Some(std::time::Instant::now());
            let poll_app = app.clone();
            let _ = app.run_on_main_thread(move || {
                // The window's own close button: silence the sound, update the UI.
                if crate::video::refresh_visibility() {
                    crate::audio::set_output_enabled(false);
                    let _ = poll_app.emit("video:window", json!({ "open": false }));
                }
            });
        }
        let started = std::time::Instant::now();
        let followup = match renderer.drop_if_hidden() {
            Some(followup) => followup,
            None => {
                let i420 = frame.buffer.to_i420();
                let (y, u, v) = i420.data();
                let (sy, su, sv) = i420.strides();
                let followup = renderer.render_i420(w, h, y, u, v, sy, su, sv);
                render_cost += started.elapsed();
                rendered += 1;
                followup
            }
        };
        if followup == crate::video::Followup::RaiseWindow {
            let _ = app.run_on_main_thread(crate::video::show_video_window);
        }
        frames += 1;
        if last_log.elapsed() >= VIDEO_LOG_INTERVAL {
            let fps = (frames - frames_at_last_log) as f64 / last_log.elapsed().as_secs_f64();
            log::info!(target: "webrtc", "video: {frames} frames, {fps:.0} fps, {w}x{h}");
            if rendered > 0 {
                log::debug!(target: "video", "render cost {:?}/frame over {rendered} frames", render_cost / rendered);
            }
            render_cost = std::time::Duration::ZERO;
            rendered = 0;
            last_log = std::time::Instant::now();
            frames_at_last_log = frames;
        }
    }
    log::info!(target: "webrtc", "video frame stream ended after {frames} frames");
    let _ = app.emit("cast:state", json!({ "live": false }));
    let _ = app.run_on_main_thread(|| crate::video::set_no_input(true));
}
