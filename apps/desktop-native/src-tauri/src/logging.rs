//! Leveled logging for the receiver (AGENTS.md: INFO / WARN / ERROR / DEBUG).
//! Every line is `HH:MM:SS.mmm LEVEL [scope] message` on stderr — local
//! wall-clock time, so a receiver log lines up with the phone's logcat.
//!
//! `ZFC_LOG` picks the level for the app's own scopes (`error`, `warn`,
//! `info` — the default — or `debug`); third-party crates (libwebrtc's Rust
//! bindings, tauri) only surface warnings and errors. Never log audio
//! samples, frames, or pairing secrets — numbers and shapes only.

use log::{Level, LevelFilter, Log, Metadata, Record};
use std::io::Write;

/// The app's own log scopes (the `target:` of every call site).
const APP_SCOPES: &[&str] = &["signaling", "webrtc", "audio", "video", "pairing", "power", "settings", "tray", "latency", "syphon"];
const LEVEL_ENV: &str = "ZFC_LOG";

struct Logger {
    app_level: LevelFilter,
}

impl Logger {
    fn enabled_for(&self, target: &str, level: Level) -> bool {
        let is_app = APP_SCOPES.iter().any(|scope| target == *scope);
        level <= if is_app { self.app_level } else { LevelFilter::Warn }
    }
}

impl Log for Logger {
    fn enabled(&self, metadata: &Metadata) -> bool {
        self.enabled_for(metadata.target(), metadata.level())
    }

    fn log(&self, record: &Record) {
        if !self.enabled(record.metadata()) {
            return;
        }
        let line = format!("{} {:<5} [{}] {}\n", local_time(), record.level(), record.target(), record.args());
        let _ = std::io::stderr().lock().write_all(line.as_bytes());
    }

    fn flush(&self) {
        let _ = std::io::stderr().flush();
    }
}

/// Installs the logger once, at startup.
pub fn init() {
    let app_level = match std::env::var(LEVEL_ENV).unwrap_or_default().to_ascii_lowercase().as_str() {
        "error" => LevelFilter::Error,
        "warn" => LevelFilter::Warn,
        "debug" => LevelFilter::Debug,
        _ => LevelFilter::Info,
    };
    if log::set_boxed_logger(Box::new(Logger { app_level })).is_ok() {
        log::set_max_level(app_level.max(LevelFilter::Warn));
    }
}

fn local_time() -> String {
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default();
    let secs = now.as_secs() as libc::time_t;
    // SAFETY: localtime_r writes only into the provided struct.
    let mut tm: libc::tm = unsafe { std::mem::zeroed() };
    unsafe { libc::localtime_r(&secs, &mut tm) };
    format!("{:02}:{:02}:{:02}.{:03}", tm.tm_hour, tm.tm_min, tm.tm_sec, now.subsec_millis())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn app_scopes_follow_the_level_and_dependencies_stay_quiet() {
        let logger = Logger { app_level: LevelFilter::Info };
        assert!(logger.enabled_for("signaling", Level::Info));
        assert!(!logger.enabled_for("signaling", Level::Debug));
        assert!(!logger.enabled_for("libwebrtc::peer_connection", Level::Info), "dependency chatter hidden");
        assert!(logger.enabled_for("libwebrtc::peer_connection", Level::Warn));
        let debug = Logger { app_level: LevelFilter::Debug };
        assert!(debug.enabled_for("video", Level::Debug));
    }

    #[test]
    fn timestamps_are_wall_clock_with_millis() {
        let t = local_time();
        assert_eq!(t.len(), "HH:MM:SS.mmm".len(), "{t}");
        assert_eq!((&t[2..3], &t[5..6], &t[8..9]), (":", ":", "."));
    }
}
