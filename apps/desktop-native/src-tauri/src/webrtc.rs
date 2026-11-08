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

pub struct MediaAnswerer {
    factory: PeerConnectionFactory,
    pc: Arc<Mutex<Option<PeerConnection>>>,
}

impl MediaAnswerer {
    /// The zero-playout-delay factory — the native receiver's video latency
    /// knob (the thing Chromium locked at ~91 ms).
    pub fn new() -> Self {
        Self {
            factory: PeerConnectionFactory::with_zero_playout_delay(),
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
                    // The frame consumer runs on the async runtime; M0-r
                    // emits dims only — the Metal subview takes over at M3.
                    tauri::async_runtime::spawn(async move {
                        let mut stream = NativeVideoStream::new(video);
                        while let Some(frame) = stream.next().await {
                            frame_app
                                .emit(
                                    "video:frame",
                                    json!({
                                        "width": frame.buffer.width(),
                                        "height": frame.buffer.height(),
                                    }),
                                )
                                .ok();
                        }
                        println!("[webrtc] video frame stream ended");
                    });
                }
                other => println!("[webrtc] non-video track arrived: {other:?}"),
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

    /// Adds a remote ICE candidate (trickled by the phone over signaling).
    pub async fn add_remote_candidate(&self, line: &str) {
        let candidate = livekit::webrtc::ice_candidate::IceCandidate::parse(line, 0, "0");
        if let Ok(candidate) = candidate {
            if let Some(pc) = self.pc.lock().expect("pc lock").as_ref() {
                pc.add_ice_candidate(candidate).await;
            }
        }
    }
}
