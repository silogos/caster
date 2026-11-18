//! Which link runs (ADR-037: the link is chosen first, and only that link
//! runs). The one place that starts and stops the two links' machinery:
//!
//! - **Wireless**: the libwebrtc factory (once, on first use), the QR
//!   session and the signaling server on port 52341. No USB thread.
//! - **Cable**: the USB thread only. No port, no QR, no WebRTC.
//! - **Nothing** (the choice screen): neither.
//!
//! At launch the saved pairing picks the mode (pairing.rs `saved_pairing`);
//! with none, the receiver opens on the choice and runs nothing until the
//! user picks. Leaving a mode (Back, Terminate, the phone's `bye forget`)
//! stops its machinery before anything else starts.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::Sender;
use std::sync::{Arc, Mutex};

use serde_json::{json, Value};
use tauri::{Emitter, Manager};

use crate::pairing::{self, Link, StorePaths};
use crate::{signaling, usb_link, webrtc, PairingView, TauriHost};

enum Running {
    Nothing,
    Wireless(signaling::CommandSender),
    Cable { commands: Sender<usb_link::Command>, stop: Arc<AtomicBool> },
}

impl Running {
    fn link(&self) -> Option<Link> {
        match self {
            Running::Nothing => None,
            Running::Wireless(_) => Some(Link::Wireless),
            Running::Cable { .. } => Some(Link::Cable),
        }
    }
}

pub struct LinkOwner {
    running: Mutex<Running>,
    desktop_id: String,
    paths: StorePaths,
}

impl LinkOwner {
    pub fn new(desktop_id: String, paths: StorePaths) -> Self {
        Self { running: Mutex::new(Running::Nothing), desktop_id, paths }
    }

    pub fn paths(&self) -> &StorePaths {
        &self.paths
    }

    pub fn current(&self) -> Option<Link> {
        self.running.lock().ok().and_then(|r| r.link())
    }

    /// The UI's view: `{mode: "choice"|"wireless"|"cable", name}` — the paired phone's name, if any.
    pub fn view(&self) -> Value {
        let mode = match self.current() {
            None => "choice",
            Some(Link::Wireless) => "wireless",
            Some(Link::Cable) => "cable",
        };
        let name = pairing::saved_pairing(&self.paths).map(|(_, e)| e.name);
        json!({ "mode": mode, "name": name })
    }

    /// Enters `link`'s mode, stopping the other first. Starting the mode already running is a no-op.
    pub fn start(&self, app: &tauri::AppHandle, link: Link) -> Result<(), String> {
        let mut running = self.running.lock().map_err(|_| "link owner unavailable")?;
        if running.link() == Some(link) {
            return Ok(());
        }
        stop_running(&mut running);
        *running = match link {
            Link::Wireless => Running::Wireless(self.start_wireless(app)?),
            Link::Cable => self.start_cable(app),
        };
        log::info!(target: "link", "{link:?} mode — only this link runs");
        drop(running);
        let _ = app.emit("link:mode", self.view());
        Ok(())
    }

    /// Back to the choice: whatever runs stops. Nothing is forgotten here (Terminate does that first).
    pub fn stop(&self, app: &tauri::AppHandle) {
        if let Ok(mut running) = self.running.lock() {
            if running.link().is_some() {
                stop_running(&mut running);
                log::info!(target: "link", "back to the choice — nothing runs");
            }
        }
        let _ = app.emit("link:mode", self.view());
    }

    /// Terminate on the deck: the phone hears `bye forget` on its link, the
    /// pairing is removed, and the receiver goes back to the choice.
    pub fn terminate(&self, app: &tauri::AppHandle) -> Result<(), String> {
        let link = self.current().ok_or("no link is running")?;
        pairing::forget_pairing(&self.paths, link)?;
        if let Ok(running) = self.running.lock() {
            match &*running {
                Running::Wireless(commands) => {
                    let _ = commands.send(signaling::ServerCommand::ForgetPhone);
                }
                Running::Cable { commands, .. } => {
                    let _ = commands.send(usb_link::Command::Forget);
                }
                Running::Nothing => {}
            }
        }
        log::info!(target: "link", "Terminate — the pairing over {link:?} ends");
        self.stop(app);
        Ok(())
    }

    /// Quitting: a plain `bye` on whichever link runs (the pairing survives a quit).
    pub fn say_goodbye(&self) {
        if let Ok(mut running) = self.running.lock() {
            match &*running {
                Running::Wireless(commands) => {
                    let _ = commands.send(signaling::ServerCommand::SayGoodbye);
                }
                Running::Cable { .. } => stop_running(&mut running),
                Running::Nothing => {}
            }
        }
    }

    /// The user's answer in the cable guide.
    pub fn answer(&self, allow: bool) -> Result<(), String> {
        let running = self.running.lock().map_err(|_| "link owner unavailable")?;
        match &*running {
            Running::Cable { commands, .. } => commands.send(usb_link::Command::Answer(allow)).map_err(|_| "cable stopped".into()),
            _ => Err("not in Cable mode".into()),
        }
    }

    /// A fresh QR, Wireless mode only.
    pub fn regenerate_pairing(&self) -> Result<(), String> {
        let running = self.running.lock().map_err(|_| "link owner unavailable")?;
        match &*running {
            Running::Wireless(commands) => {
                commands.send(signaling::ServerCommand::RegeneratePairing).map_err(|_| "signaling server stopped".into())
            }
            _ => Err("not in Wireless mode".into()),
        }
    }

    fn start_wireless(&self, app: &tauri::AppHandle) -> Result<signaling::CommandSender, String> {
        // libwebrtc's factory needs the main thread (its encoder enumeration
        // wants NSApplication context — found live: NSException on a
        // background thread). Created on the first Wireless start only.
        if objc2_foundation::MainThreadMarker::new().is_some() {
            webrtc::init_factory();
        } else {
            let (tx, rx) = std::sync::mpsc::channel();
            app.run_on_main_thread(move || {
                webrtc::init_factory();
                let _ = tx.send(());
            })
            .map_err(|e| e.to_string())?;
            rx.recv().map_err(|_| "webrtc factory not created")?;
        }

        // The first session exists before the window can ask for it.
        let mut store = pairing::SessionStore::new(pairing::SESSION_TTL_SECONDS);
        let first = store.create(pairing::unix_now(), pairing::lan_ipv4_hosts())?.view();
        *app.state::<PairingView>().0.lock().map_err(|_| "pairing view lock")? = Some(first.clone());
        let _ = app.emit("pairing:session-updated", first);

        let handle = app.clone();
        let host = TauriHost {
            media: Arc::new(webrtc::MediaAnswerer::new(crate::protocol::PcId::Media, handle.clone())),
            mic: Arc::new(webrtc::MediaAnswerer::new(crate::protocol::PcId::Mic, handle.clone())),
            app: handle,
        };
        let config = signaling::ServerConfig {
            timings: signaling::Timings::default(),
            paths: self.paths.clone(),
            desktop_id: self.desktop_id.clone(),
            desktop_name: pairing::desktop_name(),
            hosts: Box::new(pairing::lan_ipv4_hosts),
        };
        Ok(signaling::start(host, store, config))
    }

    fn start_cable(&self, app: &tauri::AppHandle) -> Running {
        let (commands, receiver) = std::sync::mpsc::channel();
        let stop = Arc::new(AtomicBool::new(false));
        usb_link::start(usb_link::Config {
            app: app.clone(),
            desktop_id: self.desktop_id.clone(),
            desktop_name: pairing::desktop_name(),
            paths: self.paths.clone(),
            commands: receiver,
            stop: Arc::clone(&stop),
        });
        Running::Cable { commands, stop }
    }
}

fn stop_running(running: &mut Running) {
    match std::mem::replace(running, Running::Nothing) {
        Running::Wireless(commands) => {
            let _ = commands.send(signaling::ServerCommand::Shutdown);
        }
        Running::Cable { stop, .. } => stop.store(true, Ordering::Relaxed),
        Running::Nothing => {}
    }
}
