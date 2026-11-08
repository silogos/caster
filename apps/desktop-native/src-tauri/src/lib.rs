mod audio;
mod logging;
mod pairing;
mod protocol;
mod signaling;
mod video;
mod webrtc;

use futures_util::future::LocalBoxFuture;
use protocol::{Candidate, PcId};
use serde_json::Value;
use signaling::{IceSender, SignalingHost};
use std::sync::{Arc, Mutex};
use tauri::{Emitter, Manager};

/// The current QR session's view — written by the signaling thread, read by
/// the UI's `pairing_payload` pull (the setup-time emit races the webview
/// load, so the pull is the reliable path).
#[derive(Default)]
struct PairingView(Mutex<Option<Value>>);

#[tauri::command]
fn pairing_payload(view: tauri::State<'_, PairingView>) -> Result<Value, String> {
    view.0
        .lock()
        .map_err(|_| "pairing view unavailable".to_string())?
        .clone()
        .ok_or_else(|| "no pairing session yet".to_string())
}

/// The signaling server's view of the app: one answerer per pc + the UI.
struct TauriHost {
    app: tauri::AppHandle,
    media: Arc<webrtc::MediaAnswerer>,
    mic: Arc<webrtc::MediaAnswerer>,
}

impl TauriHost {
    fn answerer(&self, pc: PcId) -> Arc<webrtc::MediaAnswerer> {
        Arc::clone(match pc {
            PcId::Media => &self.media,
            PcId::Mic => &self.mic,
        })
    }
}

impl SignalingHost for TauriHost {
    fn answer(&self, pc: PcId, sdp: String, ice: IceSender) -> LocalBoxFuture<'static, Result<String, String>> {
        let answerer = self.answerer(pc);
        Box::pin(async move { answerer.answer(&sdp, ice).await })
    }

    fn add_remote_candidate(&self, pc: PcId, candidate: Candidate) -> LocalBoxFuture<'static, ()> {
        let answerer = self.answerer(pc);
        Box::pin(async move { answerer.add_remote_candidate(&candidate).await })
    }

    fn close_peers(&self) {
        self.media.close();
        self.mic.close();
    }

    fn publish_session(&self, view: Value, announce: bool) {
        if let Ok(mut current) = self.app.state::<PairingView>().0.lock() {
            *current = Some(view.clone());
        }
        if announce {
            let _ = self.app.emit("pairing:session-updated", view);
        }
    }

    fn mobile_state(&self, state: Value) {
        let _ = self.app.emit("pairing:mobile-state", state);
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    logging::init();
    tauri::Builder::default()
        .manage(PairingView::default())
        .setup(|app| {
            // Create the libwebrtc factory on the main thread — the video
            // encoder enumeration needs NSApplication context (found live:
            // NSException crash on background threads).
            webrtc::init_factory();

            let paths = pairing::StorePaths::from_home().ok_or("HOME is not set")?;
            let desktop_id = pairing::desktop_id(&paths.desktop_id)?;
            // The first session exists before the window can ask for it.
            let mut store = pairing::SessionStore::new(pairing::SESSION_TTL_SECONDS);
            let first = store.create(pairing::unix_now(), pairing::lan_ipv4_hosts())?.view();
            *app.state::<PairingView>().0.lock().map_err(|_| "pairing view lock")? = Some(first);

            let handle = app.handle().clone();
            let host = TauriHost {
                media: Arc::new(webrtc::MediaAnswerer::new(PcId::Media, handle.clone())),
                mic: Arc::new(webrtc::MediaAnswerer::new(PcId::Mic, handle.clone())),
                app: handle,
            };
            let config = signaling::ServerConfig {
                timings: signaling::Timings::default(),
                paths,
                desktop_id,
                desktop_name: pairing::desktop_name(),
                hosts: Box::new(pairing::lan_ipv4_hosts),
            };
            signaling::start(host, store, config);
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![pairing_payload])
        .build(tauri::generate_context!())
        .expect("error while building desktop-native")
        .run(|_app, _event| {});
}
