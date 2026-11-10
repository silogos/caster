mod audio;
mod latency_probe;
mod logging;
mod pacing;
mod pairing;
mod power;
mod protocol;
mod settings;
mod signaling;
mod syphon;
mod tray;
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

/// The UI → signaling-server channel (Forget phone, New QR).
#[derive(Default)]
struct ServerControl(Mutex<Option<signaling::CommandSender>>);

/// The receiver's persisted controls and where they live.
struct ReceiverControls {
    path: std::path::PathBuf,
    current: Mutex<settings::ReceiverSettings>,
}

fn send_command(control: &ServerControl, command: signaling::ServerCommand) -> Result<(), String> {
    let sender = control.0.lock().map_err(|_| "server control unavailable")?;
    sender
        .as_ref()
        .ok_or("signaling server not running")?
        .send(command)
        .map_err(|_| "signaling server stopped".to_string())
}

/// Desktop Disconnect: forget the phone (revokes the persistent pairing).
#[tauri::command]
fn forget_phone(control: tauri::State<'_, ServerControl>) -> Result<(), String> {
    send_command(&control, signaling::ServerCommand::ForgetPhone)
}

/// A fresh QR (only while no phone is connected).
#[tauri::command]
fn regenerate_pairing(control: tauri::State<'_, ServerControl>) -> Result<(), String> {
    send_command(&control, signaling::ServerCommand::RegeneratePairing)
}

/// "Open video": the native video window, and the sound with it.
#[tauri::command]
fn open_video(app: tauri::AppHandle) -> Result<(), String> {
    let handle = app.clone();
    app.run_on_main_thread(move || {
        video::open_video_window();
        audio::set_output_enabled(true);
        tray::sync_dock_icon(&handle);
        let _ = handle.emit("video:window", serde_json::json!({ "open": true }));
    })
    .map_err(|e| e.to_string())
}

/// "Close video": hides the window and silences the sound.
#[tauri::command]
fn close_video(app: tauri::AppHandle) -> Result<(), String> {
    let handle = app.clone();
    app.run_on_main_thread(move || {
        video::close_video_window();
        audio::set_output_enabled(false);
        tray::sync_dock_icon(&handle);
        let _ = handle.emit("video:window", serde_json::json!({ "open": false }));
    })
    .map_err(|e| e.to_string())
}

#[tauri::command]
fn receiver_settings(controls: tauri::State<'_, ReceiverControls>) -> Result<settings::ReceiverSettings, String> {
    controls.current.lock().map(|s| *s).map_err(|_| "settings unavailable".into())
}

/// Game volume / mute. Applied at once; `persist` saves it (the slider
/// sends live values while dragging and persists on release).
#[tauri::command]
fn set_game_audio(
    controls: tauri::State<'_, ReceiverControls>,
    volume: f32,
    muted: bool,
    persist: bool,
) -> Result<settings::ReceiverSettings, String> {
    let mut current = controls.current.lock().map_err(|_| "settings unavailable")?;
    *current = settings::ReceiverSettings { game_volume: volume.clamp(0.0, 1.0), game_muted: muted, ..*current };
    audio::set_game_gain(current.effective_game_gain());
    if persist {
        if let Err(e) = settings::save(&controls.path, &current) {
            log::warn!(target: "settings", "not saved: {e}");
        }
    }
    Ok(*current)
}

/// The Syphon output for OBS (ADR-021) — a receiver control. The saved
/// value is what was asked for; the returned one is what is running (a Mac
/// without a Metal device cannot start it).
#[tauri::command]
fn set_syphon_output(controls: tauri::State<'_, ReceiverControls>, enabled: bool) -> Result<settings::ReceiverSettings, String> {
    let mut current = controls.current.lock().map_err(|_| "settings unavailable")?;
    current.syphon_output = syphon::set_enabled(enabled);
    if let Err(e) = settings::save(&controls.path, &current) {
        log::warn!(target: "settings", "not saved: {e}");
    }
    Ok(*current)
}

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
        if let Some(tray) = self.app.try_state::<tray::Tray>() {
            tray.on_mobile_state(&state);
        }
        // A phone connecting brings a hidden window back (ADR-010): the
        // phone's Connect flow needs zero desktop interaction.
        if state["state"] == "connected" {
            let app = self.app.clone();
            let _ = self.app.run_on_main_thread(move || tray::show_main_window(&app));
        }
        let _ = self.app.emit("pairing:mobile-state", state);
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    logging::init();
    tauri::Builder::default()
        .manage(PairingView::default())
        .manage(ServerControl::default())
        .setup(|app| {
            // Create the libwebrtc factory on the main thread — the video
            // encoder enumeration needs NSApplication context (found live:
            // NSException crash on background threads).
            webrtc::init_factory();

            let paths = pairing::StorePaths::from_home().ok_or("HOME is not set")?;
            let desktop_id = pairing::desktop_id(&paths.desktop_id)?;
            let trusted_at_launch = pairing::read_trust(&paths.trust).is_some();
            let settings_path = settings::settings_path(paths.trust.parent().ok_or("no app support dir")?);
            let saved = settings::load(&settings_path);
            audio::set_game_gain(saved.effective_game_gain());
            let saved = settings::ReceiverSettings { syphon_output: saved.syphon_output && syphon::set_enabled(true), ..saved };
            app.manage(ReceiverControls { path: settings_path, current: Mutex::new(saved) });
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
            let commands = signaling::start(host, store, config);
            *app.state::<ServerControl>().0.lock().map_err(|_| "server control lock")? = Some(commands.clone());

            let (forget_tx, goodbye_tx) = (commands.clone(), commands);
            let tray = tray::install(
                app.handle(),
                trusted_at_launch,
                move || { let _ = forget_tx.send(signaling::ServerCommand::ForgetPhone); },
                move || { let _ = goodbye_tx.send(signaling::ServerCommand::SayGoodbye); },
            )?;
            app.manage(tray);
            Ok(())
        })
        // Close → hide: the receiver keeps listening from the menu bar.
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                if window.label() == tray::MAIN_WINDOW && !tray::quitting() {
                    api.prevent_close();
                    tray::hide_main_window(window.app_handle());
                }
            }
        })
        .invoke_handler(tauri::generate_handler![
            pairing_payload,
            forget_phone,
            regenerate_pairing,
            receiver_settings,
            set_game_audio,
            set_syphon_output,
            open_video,
            close_video
        ])
        .build(tauri::generate_context!())
        .expect("error while building desktop-native")
        .run(|app, event| match event {
            // Cmd+Q / the app menu's Quit walk the same goodbye as the tray.
            tauri::RunEvent::ExitRequested { api, .. } if !tray::quitting() => {
                api.prevent_exit();
                let control = app.state::<ServerControl>();
                tray::quit(app, &|| { let _ = send_command(&control, signaling::ServerCommand::SayGoodbye); });
            }
            // Dock icon click while hidden.
            #[cfg(target_os = "macos")]
            tauri::RunEvent::Reopen { .. } => tray::show_main_window(app),
            _ => {}
        });
}
