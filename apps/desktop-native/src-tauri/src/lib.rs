mod audio;
mod signaling;
mod video;
mod webrtc;

use serde_json::Value;
use tauri::Emitter;

/// M0-r: the Tauri shell boots, generates the pairing session, and pushes
/// the QR payload to the UI. The livekit PeerConnection wiring lands next
/// (the offer/answer flows through the same signaling events).
#[tauri::command]
fn pairing_payload() -> Result<Value, String> {
    signaling::pairing_payload_json()
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .setup(|app| {
            // Emit the pairing session once the window is ready.
            if let Ok(payload) = signaling::pairing_payload_json() {
                let _ = app.handle().emit("pairing:session-updated", payload);
            }
            // Create the libwebrtc factory on the main thread — the video
            // encoder enumeration needs NSApplication context (found live:
            // NSException crash on background threads).
            webrtc::init_factory();
            let answerer = std::sync::Arc::new(webrtc::MediaAnswerer::new());
            signaling::start(app.handle().clone(), std::sync::Arc::clone(&answerer));
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![pairing_payload])
        .build(tauri::generate_context!())
        .expect("error while building desktop-native")
        .run(|_app, _event| {
            // Factory + signaling start in setup (above). This callback
            // handles run events that need attention in future milestones.
        });
}
