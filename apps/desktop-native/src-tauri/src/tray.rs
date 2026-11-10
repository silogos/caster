//! The tray (ADR-010, ported from the Electron receiver): the receiver is an
//! appliance that keeps listening. Closing the main window hides it (and the
//! Dock icon) instead of quitting — the signaling server stays up so a saved
//! phone's one-tap Connect always lands, and a live cast keeps running.
//! Quit is the only exit, and it says a plain `bye` first (the trust
//! survives a quit, ADR-006).
//!
//! The status text and menu state are pure ([`TrayModel`]) and tested; the
//! rest is Tauri wiring.

use serde_json::Value;
use std::sync::Mutex;
use tauri::menu::{Menu, MenuItem, PredefinedMenuItem};
use tauri::tray::TrayIconBuilder;
use tauri::{AppHandle, Manager, Wry};

/// The main (UI) window's label.
pub const MAIN_WINDOW: &str = "main";
const MENU_OPEN: &str = "open-window";
const MENU_FORGET: &str = "forget-phone";
const MENU_QUIT: &str = "quit";
/// Time for the quit `bye` to leave the socket before the process exits.
const QUIT_GOODBYE_GRACE: std::time::Duration = std::time::Duration::from_millis(300);

/// Connection status in the Electron tray's vocabulary.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Status {
    /// No phone connected and none waited for — the QR hero.
    Waiting,
    Connected,
    /// The socket dropped; the trusted phone may reconnect (ADR-006).
    Reconnecting,
}

/// Everything the tray shows, updated from the signaling events.
#[derive(Clone, Debug, PartialEq)]
pub struct TrayModel {
    pub status: Status,
    pub phone: Option<String>,
    pub info: Option<Value>,
    /// A persistent pairing exists — Forget phone is armed only then.
    pub trusted: bool,
}

impl TrayModel {
    pub fn new(trusted: bool) -> Self {
        Self { status: Status::Waiting, phone: None, info: None, trusted }
    }

    /// Applies one `pairing:mobile-state` payload (the UI's own event).
    pub fn apply(&mut self, state: &Value) {
        match state["state"].as_str() {
            Some("connected") => {
                self.status = Status::Connected;
                self.phone = state["name"].as_str().map(str::to_string);
                self.info = None;
                self.trusted = true;
            }
            Some("disconnected") => self.status = Status::Reconnecting,
            Some("session-info") => self.info = Some(state["info"].clone()),
            // A forget from either side: nothing is paired any more.
            Some("forgotten") => *self = Self::new(false),
            _ => {}
        }
    }

    /// The status line (Electron's `trayStatusLine`).
    pub fn status_line(&self) -> String {
        match (self.status, self.phone.as_deref()) {
            (Status::Connected, Some(name)) => match &self.info {
                Some(info) => session_info_line(name, info),
                None => format!("Connected to {name}"),
            },
            (Status::Reconnecting, Some(name)) => format!("Waiting for {name} to reconnect…"),
            _ => "Waiting…".to_string(),
        }
    }
}

/// `Connected to <name> — 1920×1200 · 60 fps · balanced · game audio · mic`
/// — the exact separators of the Electron receiver's `sessionInfoLine.ts`
/// (× U+00D7, · U+00B7, — U+2014).
pub fn session_info_line(name: &str, info: &Value) -> String {
    let mut parts = Vec::new();
    if let (Some(w), Some(h)) = (info["width"].as_i64(), info["height"].as_i64()) {
        parts.push(format!("{w}×{h}"));
    }
    if let Some(fps) = info["fps"].as_i64() {
        parts.push(format!("{fps} fps"));
    }
    if let Some(profile) = info["profile"].as_str().filter(|p| !p.is_empty()) {
        parts.push(profile.to_string());
    }
    let sources: Vec<&str> = [("gameAudio", "game audio"), ("mic", "mic")]
        .iter()
        .filter(|(key, _)| info[*key].as_bool() == Some(true))
        .map(|(_, label)| *label)
        .collect();
    if !sources.is_empty() {
        parts.push(sources.join(" · "));
    }
    format!("Connected to {name} — {}", parts.join(" · "))
}

// ---- Tauri wiring ----------------------------------------------------------

/// The live menu items the model drives.
pub struct Tray {
    model: Mutex<TrayModel>,
    status: MenuItem<Wry>,
    forget: MenuItem<Wry>,
}

impl Tray {
    /// Re-renders the status line and Forget's armed state.
    fn render(&self, model: &TrayModel) {
        let _ = self.status.set_text(model.status_line());
        let _ = self.forget.set_enabled(model.trusted);
    }

    pub fn on_mobile_state(&self, state: &Value) {
        if let Ok(mut model) = self.model.lock() {
            model.apply(state);
            self.render(&model);
        }
    }

}

/// Builds the tray icon + menu and registers its actions. `forget` and
/// `goodbye` reach the signaling server.
pub fn install(
    app: &AppHandle,
    trusted: bool,
    forget: impl Fn() + Send + Sync + 'static,
    goodbye: impl Fn() + Send + Sync + 'static,
) -> tauri::Result<Tray> {
    let model = TrayModel::new(trusted);
    let status = MenuItem::with_id(app, "status", model.status_line(), false, None::<&str>)?;
    let open = MenuItem::with_id(app, MENU_OPEN, "Open window", true, None::<&str>)?;
    let forget_item = MenuItem::with_id(app, MENU_FORGET, "Forget phone", trusted, None::<&str>)?;
    let quit_item = MenuItem::with_id(app, MENU_QUIT, "Quit", true, None::<&str>)?;
    let menu = Menu::with_items(
        app,
        &[
            &status,
            &PredefinedMenuItem::separator(app)?,
            &open,
            &forget_item,
            &PredefinedMenuItem::separator(app)?,
            &quit_item,
        ],
    )?;
    let mut builder = TrayIconBuilder::with_id("receiver")
        .tooltip("Zero-Friction Cast")
        .menu(&menu)
        .show_menu_on_left_click(true)
        .on_menu_event(move |app, event| match event.id().as_ref() {
            MENU_OPEN => show_main_window(app),
            MENU_FORGET => forget(),
            MENU_QUIT => quit(app, &goodbye),
            _ => {}
        });
    if let Some(icon) = app.default_window_icon() {
        builder = builder.icon(icon.clone()).icon_as_template(true);
    }
    builder.build(app)?;
    Ok(Tray { model: Mutex::new(model), status, forget: forget_item })
}

/// Shows (and focuses) the main window, and brings the Dock icon back.
pub fn show_main_window(app: &AppHandle) {
    #[cfg(target_os = "macos")]
    let _ = app.set_activation_policy(tauri::ActivationPolicy::Regular);
    if let Some(window) = app.get_webview_window(MAIN_WINDOW) {
        let _ = window.show();
        let _ = window.unminimize();
        let _ = window.set_focus();
    }
}

/// Close → hide: the app keeps listening from the tray (ADR-010).
pub fn hide_main_window(app: &AppHandle) {
    if let Some(window) = app.get_webview_window(MAIN_WINDOW) {
        let _ = window.hide();
    }
    sync_dock_icon(app);
    log::info!(target: "tray", "window closed — still listening from the menu bar");
}

/// The Dock icon (and the Cmd+Tab entry) follows the app's windows: shown
/// while the main window or the video window is open, hidden when both are
/// closed and only the menu-bar item is left. Call after either changes.
pub fn sync_dock_icon(app: &AppHandle) {
    let main_shown = app.get_webview_window(MAIN_WINDOW).and_then(|window| window.is_visible().ok()).unwrap_or(false);
    let in_dock = wants_dock_icon(main_shown, crate::video::is_open());
    #[cfg(target_os = "macos")]
    let _ = app.set_activation_policy(if in_dock { tauri::ActivationPolicy::Regular } else { tauri::ActivationPolicy::Accessory });
    #[cfg(not(target_os = "macos"))]
    let _ = in_dock;
}

/// A menu-bar-only app has no Dock icon; an app with a window has one.
fn wants_dock_icon(main_window_shown: bool, video_window_open: bool) -> bool {
    main_window_shown || video_window_open
}

static QUITTING: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

/// True once the quit walk started (lets the exit request through).
pub fn quitting() -> bool {
    QUITTING.load(std::sync::atomic::Ordering::Relaxed)
}

/// The only exit: say a plain `bye` (trust kept), give it a moment to
/// leave the socket, then exit.
pub fn quit(app: &AppHandle, goodbye: &dyn Fn()) {
    if QUITTING.swap(true, std::sync::atomic::Ordering::Relaxed) {
        return;
    }
    log::info!(target: "tray", "quitting");
    goodbye();
    let app = app.clone();
    std::thread::spawn(move || {
        std::thread::sleep(QUIT_GOODBYE_GRACE);
        app.exit(0);
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn status_lines_follow_the_electron_vocabulary() {
        let mut m = TrayModel::new(false);
        assert_eq!(m.status_line(), "Waiting…");
        m.apply(&json!({ "state": "connected", "name": "Pixel 8" }));
        assert_eq!(m.status_line(), "Connected to Pixel 8");
        m.apply(&json!({ "state": "session-info", "info": { "profile": "balanced", "width": 1920, "height": 1200, "fps": 60, "gameAudio": true, "mic": false } }));
        assert_eq!(m.status_line(), "Connected to Pixel 8 — 1920×1200 · 60 fps · balanced · game audio");
        m.apply(&json!({ "state": "disconnected" }));
        assert_eq!(m.status_line(), "Waiting for Pixel 8 to reconnect…");
    }

    #[test]
    fn forget_is_armed_only_while_a_phone_is_trusted() {
        let mut m = TrayModel::new(false);
        assert!(!m.trusted);
        m.apply(&json!({ "state": "connected", "name": "Pixel 8" }));
        assert!(m.trusted, "a connected phone is a trusted one");
        m.apply(&json!({ "state": "forgotten" }));
        assert_eq!(m, TrayModel::new(false), "after forget: waiting, nothing armed");
        assert!(TrayModel::new(true).trusted, "a phone trusted at launch can be forgotten offline");
    }

    #[test]
    fn a_reconnect_drops_the_stale_cast_summary() {
        let mut m = TrayModel::new(true);
        m.apply(&json!({ "state": "connected", "name": "Pixel 8" }));
        m.apply(&json!({ "state": "session-info", "info": { "profile": "x", "width": 1, "height": 1, "fps": 1, "gameAudio": false, "mic": true } }));
        m.apply(&json!({ "state": "connected", "name": "Pixel 8" }));
        assert_eq!(m.status_line(), "Connected to Pixel 8");
    }

    #[test]
    fn session_info_line_matches_the_receiver_ui() {
        let info = json!({ "profile": "performance", "width": 1280, "height": 720, "fps": 30, "gameAudio": true, "mic": true });
        assert_eq!(session_info_line("TB321FU", &info), "Connected to TB321FU — 1280×720 · 30 fps · performance · game audio · mic");
    }
    #[test]
    fn the_dock_icon_follows_either_window() {
        assert!(wants_dock_icon(true, false), "main window shown");
        assert!(wants_dock_icon(false, true), "only the video window open: still an app with a window");
        assert!(wants_dock_icon(true, true));
        assert!(!wants_dock_icon(false, false), "both closed: menu-bar only");
    }

}
