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
    /// Where this pc's local ICE candidates go — swapped on every answer, so
    /// a pc kept across a re-offer reaches the phone's current connection.
    ice_out: Arc<Mutex<Option<IceSender>>>,
    /// The offer's SDP session id (`o=` line) the current pc answered. A
    /// re-offer with the same id is the phone renegotiating the SAME pc (an
    /// ICE restart) and is applied to it; a new id is a new session.
    remote_session: Mutex<Option<String>>,
    /// The incoming video track, kept to restart frame consumption after an
    /// ICE restart (a Failed state ends the frame loop; the track survives).
    video_track: Arc<Mutex<Option<livekit::webrtc::video_track::RtcVideoTrack>>>,
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
            ice_out: Arc::new(Mutex::new(None)),
            remote_session: Mutex::new(None),
            video_track: Arc::new(Mutex::new(None)),
        }
    }

    /// Answers this pc's offer (a re-offer replaces the previous connection).
    /// Local candidates — then the end-of-gathering marker — go out via `ice`.
    pub async fn answer(&self, sdp_text: &str, ice: IceSender) -> Result<String, String> {
        // Parsed before the PeerConnection exists: a malformed offer fails
        // fast without building (and leaking callbacks onto) a new PC.
        let offer = SessionDescription::parse(sdp_text, SdpType::Offer)
            .map_err(|e| format!("offer parse: {e}"))?;
        let session = sdp_session_id(sdp_text).map(str::to_owned);
        *self.ice_out.lock().expect("ice lock") = Some(ice.clone());

        // An ICE restart renegotiates the phone's SAME pc: answer it on ours,
        // so DTLS, SCTP and the game-audio channel survive. A fresh pc here
        // would need the phone to re-open its channel, which it never does —
        // game audio fell back to the mono track for the rest of the cast.
        let same_session = session.is_some() && *self.remote_session.lock().expect("session lock") == session;
        let existing = if same_session { self.pc.lock().expect("pc lock").clone() } else { None };
        if let Some(pc) = existing {
            log::info!(target: "webrtc", "pc={} re-offer on the same session (ice restart) — keeping the pc", self.role.as_str());
            pc.set_remote_description(offer)
                .await
                .map_err(|e| format!("set remote (re-offer): {e}"))?;
            let answer = pc
                .create_answer(AnswerOptions::default())
                .await
                .map_err(|e| format!("create answer (re-offer): {e}"))?;
            pc.set_local_description(answer.clone())
                .await
                .map_err(|e| format!("set local (re-offer): {e}"))?;
            // A Failed state on the way ended the frame loop; the track lives on.
            let track = self.video_track.lock().expect("video track lock").clone();
            if let Some(video) = track {
                spawn_video_consumer(self.app.clone(), video, false);
            }
            let sdp = answer.to_string();
            log::info!(target: "webrtc", "answer produced ({} bytes, same pc)", sdp.len());
            return Ok(sdp);
        }

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
                        target: "webrtc", "dc: {chunks} chunks, {} kB | audio underruns={} ({}ms silent) sheds={} depth={}ms trough={}ms out={}f/{:.1}ms played={} side={:.0}dB",
                        bytes / 1024,
                        st.underruns,
                        st.starved_ms,
                        st.sheds,
                        st.depth_ms,
                        st.floor_ms,
                        st.callback_frames,
                        st.device_delay_ms,
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
        let kept_video = Arc::clone(&self.video_track);
        pc.on_track(Some(Box::new(move |event: TrackEvent| {
            let receiver = event.receiver;
            let track = event.track;
            match track {
                // The mic pc's track: separate from game audio end to end
                // (AGENTS.md). Its playback output lands with the audio stage.
                MediaStreamTrack::Audio(_) if role == PcId::Mic => {
                    log::info!(target: "webrtc", "mic track arrived — not played yet (receiver mic output pending)");
                }
                MediaStreamTrack::Video(video) => {
                    if crate::latency_probe::enabled() {
                        spawn_receive_stats(receiver);
                    }
                    *kept_video.lock().expect("video track lock") = Some(video.clone());
                    spawn_video_consumer(frame_app.clone(), video, true);
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

        let candidate_tx = Arc::clone(&self.ice_out);
        pc.on_ice_candidate(Some(Box::new(move |candidate| {
            // A send fails only once the signaling connection is gone.
            let Some(tx) = candidate_tx.lock().expect("ice lock").clone() else { return };
            let _ = tx.send(OutboundIce {
                pc: role,
                candidate: Some(Candidate {
                    candidate: candidate.candidate(),
                    sdp_mid: candidate.sdp_mid(),
                    sdp_mline_index: candidate.sdp_mline_index(),
                }),
            });
        })));
        let gathering_tx = Arc::clone(&self.ice_out);
        pc.on_ice_gathering_state_change(Some(Box::new(move |state| {
            if state == IceGatheringState::Complete {
                let Some(tx) = gathering_tx.lock().expect("ice lock").clone() else { return };
                let _ = tx.send(OutboundIce { pc: role, candidate: None });
            }
        })));

        let state_app = self.app.clone();
        pc.on_connection_state_change(Some(Box::new(move |state| {
            log::info!(target: "webrtc", "pc={} state: {state:?}", role.as_str());
            if role != PcId::Media {
                return;
            }
            // The display stays awake exactly while the cast is live.
            crate::power::keep_display_awake(state == PeerConnectionState::Connected);
            // The link is gone (the phone stopped, dropped, or the pc was
            // replaced): no input until frames flow again.
            if matches!(state, PeerConnectionState::Disconnected | PeerConnectionState::Failed | PeerConnectionState::Closed) {
                announce_input(&state_app, false);
            }
            if matches!(state, PeerConnectionState::Failed | PeerConnectionState::Closed) {
                crate::video::end_streams();
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
        *self.remote_session.lock().expect("session lock") = session;

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
            crate::video::end_streams();
            announce_input(&self.app, false);
        }
        *self.game_audio_dc.lock().expect("dc keep lock") = None;
        *self.remote_session.lock().expect("session lock") = None;
        *self.video_track.lock().expect("video track lock") = None;
        if let Some(pc) = self.pc.lock().expect("pc lock").take() {
            pc.close();
            log::info!(target: "webrtc", "pc={} closed", self.role.as_str());
        }
    }
}

const VIDEO_LOG_INTERVAL: std::time::Duration = std::time::Duration::from_secs(1);
/// How often a frame loop waiting for frames checks whether its stream is
/// still the current one.
const STALE_STREAM_CHECK: std::time::Duration = std::time::Duration::from_secs(1);

/// Input went live or stopped: the deck's ON AIR, and the video window's
/// NO INPUT card (main thread). No-op when nothing changed.
fn announce_input(app: &tauri::AppHandle, live: bool) {
    if crate::video::set_live(live) {
        let _ = app.emit("cast:state", json!({ "live": live }));
        let _ = app.run_on_main_thread(crate::video::apply_no_input_card);
        if !live {
            // OBS shows the still, not the last frame of the cast (ADR-021).
            crate::syphon::publish_no_input();
        }
    }
}

/// The SDP session id — the second field of the `o=` line. It stays fixed
/// for one PeerConnection across renegotiations (JSEP), so equal ids on two
/// offers mean the same remote pc.
pub fn sdp_session_id(sdp: &str) -> Option<&str> {
    sdp.lines().find_map(|line| line.strip_prefix("o=")).and_then(|origin| origin.split_whitespace().nth(1))
}

/// Starts the frame loop for `video` on its own thread (not the shared Tauri
/// runtime: the per-frame copy + layer calls must not stall signaling or
/// audio tasks). A newer loop retires an older one (`begin_stream`).
fn spawn_video_consumer(app: tauri::AppHandle, video: livekit::webrtc::video_track::RtcVideoTrack, first: bool) {
    log::info!(target: "webrtc", "video track {} — consuming frames", if first { "arrived" } else { "kept across the re-offer" });
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

/// Latency probe: libwebrtc's own receive-side timing for the video stream,
/// once a second — decode time, packet-to-decoded processing delay and the
/// jitter buffer's hold, per frame. Splits "Mac decoded" into network/assembly
/// vs decoder (docs/development/latency-measurement.md). Ends when the
/// stream stops decoding frames.
fn spawn_receive_stats(receiver: livekit::webrtc::rtp_receiver::RtpReceiver) {
    use livekit::webrtc::stats::RtcStats;
    const IDLE_POLLS_BEFORE_EXIT: u32 = 10;
    let spawned = std::thread::Builder::new().name("video-stats".into()).spawn(move || {
        let Ok(runtime) = tokio::runtime::Builder::new_current_thread().enable_all().build() else { return };
        runtime.block_on(async move {
            let mut last: Option<(u32, f64, f64, f64, u64)> = None;
            let mut idle = 0;
            loop {
                tokio::time::sleep(std::time::Duration::from_secs(1)).await;
                let Ok(stats) = receiver.get_stats().await else { break };
                let Some(v) = stats.iter().find_map(|s| match s {
                    RtcStats::InboundRtp(i) if i.stream.kind == "video" => Some(i.inbound.clone()),
                    _ => None,
                }) else { continue };
                let now = (v.frames_decoded, v.total_decode_time, v.total_processing_delay, v.jitter_buffer_delay, v.jitter_buffer_emitted_count);
                if let Some(prev) = last {
                    let frames = now.0.saturating_sub(prev.0);
                    if frames == 0 {
                        idle += 1;
                        if idle >= IDLE_POLLS_BEFORE_EXIT { break }
                    } else {
                        idle = 0;
                        let per = |d: f64| d / f64::from(frames) * 1000.0;
                        let emitted = now.4.saturating_sub(prev.4).max(1) as f64;
                        log::info!(
                            target: "latency",
                            "rx stats decode {:.1} ms/f, processing {:.1} ms/f, jitter buffer {:.1} ms/f",
                            per(now.1 - prev.1),
                            per(now.2 - prev.2),
                            (now.3 - prev.3) / emitted * 1000.0
                        );
                    }
                }
                last = Some(now);
            }
        });
    });
    if let Err(e) = spawned {
        log::warn!(target: "webrtc", "video stats thread: {e}");
    }
}

/// The video frame loop for one stream (one cast): renders into the native
/// window while the user has it open, keeps its visibility fresh from the
/// main thread, and tells the UI when the cast goes live / ends.
async fn consume_video(app: tauri::AppHandle, video: livekit::webrtc::video_track::RtcVideoTrack) {
    let mut stream = NativeVideoStream::new(video);
    let mut renderer = crate::video::Renderer::new();
    let mut range_tracker = crate::video::RangeTracker::new();
    let generation = crate::video::begin_stream();
    let mut frames: u64 = 0;
    let mut window_requested = false;
    let mut last_visibility_poll: Option<std::time::Instant> = None;
    let mut last_log = std::time::Instant::now();
    let mut frames_at_last_log: u64 = 0;
    // Per-frame cost of convert + copy + enqueue (rendered frames only).
    let mut render_cost = std::time::Duration::ZERO;
    let mut rendered: u32 = 0;
    let mut marker = crate::latency_probe::LumaMarker::default();
    loop {
        if !crate::video::is_current_stream(generation) {
            log::info!(target: "webrtc", "video stream replaced or closed — frame loop exits");
            break;
        }
        let frame = match tokio::time::timeout(STALE_STREAM_CHECK, stream.next()).await {
            Ok(Some(frame)) => frame,
            Ok(None) => break,
            Err(_) => continue, // no frame yet: re-check the generation
        };
        let (w, h) = (frame.buffer.width(), frame.buffer.height());
        if !window_requested {
            window_requested = true;
            log::info!(target: "webrtc", "first frame {w}x{h}");
            // A window the user left open comes back for the new cast.
            if crate::video::is_open() {
                let _ = app.run_on_main_thread(crate::video::open_video_window);
            }
        }
        // Frames flow: live (also after a transient disconnect recovers).
        if !crate::video::is_live() {
            announce_input(&app, true);
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
                // The decoder's own buffer states the range — asked once per
                // stream and size, because asking pins the frame (RangeTracker).
                let range = range_tracker.range(w, h, frame.buffer.as_native().is_some(), || {
                    crate::video::read_range_of_frame(frame.buffer.as_ref())
                });
                let i420 = frame.buffer.to_i420();
                let (y, u, v) = i420.data();
                let (sy, su, sv) = i420.strides();
                let followup = renderer.render_i420(w, h, range, y, u, v, sy, su, sv);
                if crate::latency_probe::enabled() {
                    let decoded_ms = crate::latency_probe::wall_ms();
                    if let Some(white) = marker.observe(crate::latency_probe::mean_luma(y, sy, w, h)) {
                        log::info!(
                            target: "latency",
                            "video {} decoded_ms={decoded_ms:.1} hold_ms={:.1}",
                            if white { "white" } else { "black" },
                            renderer.last_hold_ms()
                        );
                    }
                }
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
            let pacing = renderer.take_pacing_window();
            if pacing.frames > 0 {
                // Delay = how long frames waited for their slot (ADR-017);
                // late = shown on arrival after a delivery gap (a hitch).
                log::info!(
                    target: "webrtc",
                    "video: {frames} frames, {fps:.0} fps, {w}x{h} | pacing delay avg {:.0} max {:.0} ms, late {}, capped {}",
                    pacing.delay_sum / pacing.frames as f64 * 1000.0,
                    pacing.delay_max * 1000.0,
                    pacing.late,
                    pacing.capped
                );
            } else {
                log::info!(target: "webrtc", "video: {frames} frames, {fps:.0} fps, {w}x{h}");
            }
            if rendered > 0 {
                log::debug!(target: "video", "render cost {:?}/frame over {rendered} frames", render_cost / rendered);
            }
            render_cost = std::time::Duration::ZERO;
            rendered = 0;
            last_log = std::time::Instant::now();
            frames_at_last_log = frames;
        }
    }
    log::info!(target: "webrtc", "video frame loop ended after {frames} frames");
    if crate::video::is_current_stream(generation) {
        announce_input(&app, false);
    }
}

#[cfg(test)]
mod tests {
    use super::sdp_session_id;

    const OFFER: &str = "v=0\r\no=- 4611731400430051336 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n";

    #[test]
    fn the_session_id_is_the_origin_lines_second_field() {
        assert_eq!(sdp_session_id(OFFER), Some("4611731400430051336"));
    }

    #[test]
    fn a_renegotiation_of_the_same_pc_keeps_the_id_and_a_new_pc_changes_it() {
        let reoffer = OFFER.replace(" 2 IN IP4", " 3 IN IP4"); // session version bumps
        let new_pc = OFFER.replace("4611731400430051336", "7300219833540919102");
        assert_eq!(sdp_session_id(&reoffer), sdp_session_id(OFFER));
        assert_ne!(sdp_session_id(&new_pc), sdp_session_id(OFFER));
    }

    #[test]
    fn an_sdp_without_an_origin_line_has_no_session() {
        assert_eq!(sdp_session_id("v=0\r\ns=-\r\n"), None);
    }
}
