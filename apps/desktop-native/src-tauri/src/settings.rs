//! The receiver's own settings — receiver/environment controls only
//! (AGENTS.md: cast settings belong to the phone). Persisted as a small
//! JSON file next to the shared trust store; anything unreadable falls
//! back to the defaults rather than failing the app.

use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};

const SETTINGS_FILE: &str = "native-receiver.json";

#[derive(Clone, Copy, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct ReceiverSettings {
    /// Game-audio level, 0.0–1.0 (linear, like the Electron mixer's GainNode).
    pub game_volume: f32,
    pub game_muted: bool,
}

impl Default for ReceiverSettings {
    fn default() -> Self {
        Self { game_volume: 1.0, game_muted: false }
    }
}

impl ReceiverSettings {
    /// The gain the audio engine applies.
    pub fn effective_game_gain(&self) -> f32 {
        if self.game_muted { 0.0 } else { self.game_volume }
    }

    fn sanitized(mut self) -> Self {
        self.game_volume = if self.game_volume.is_finite() { self.game_volume.clamp(0.0, 1.0) } else { 1.0 };
        self
    }
}

pub fn settings_path(app_support_dir: &Path) -> PathBuf {
    app_support_dir.join(SETTINGS_FILE)
}

pub fn load(path: &Path) -> ReceiverSettings {
    std::fs::read_to_string(path)
        .ok()
        .and_then(|text| serde_json::from_str::<ReceiverSettings>(&text).ok())
        .unwrap_or_default()
        .sanitized()
}

pub fn save(path: &Path, settings: &ReceiverSettings) -> Result<(), String> {
    if let Some(dir) = path.parent() {
        std::fs::create_dir_all(dir).map_err(|e| format!("create {}: {e}", dir.display()))?;
    }
    let text = serde_json::to_string(&settings.sanitized()).map_err(|e| e.to_string())?;
    let tmp = path.with_extension("tmp");
    std::fs::write(&tmp, text).map_err(|e| format!("write settings: {e}"))?;
    std::fs::rename(&tmp, path).map_err(|e| format!("replace settings: {e}"))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn scratch(tag: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("zfc-settings-{tag}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        dir
    }

    #[test]
    fn round_trips_and_mute_wins() {
        let dir = scratch("rt");
        let path = settings_path(&dir);
        assert_eq!(load(&path), ReceiverSettings::default(), "no file → defaults");
        let s = ReceiverSettings { game_volume: 0.4, game_muted: true };
        save(&path, &s).unwrap();
        assert_eq!(load(&path), s);
        assert_eq!(s.effective_game_gain(), 0.0);
        assert_eq!(ReceiverSettings { game_muted: false, ..s }.effective_game_gain(), 0.4);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn bad_values_and_files_fall_back_safely() {
        let dir = scratch("bad");
        let path = settings_path(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(&path, "{not json").unwrap();
        assert_eq!(load(&path), ReceiverSettings::default());
        std::fs::write(&path, r#"{"gameVolume": 7.5}"#).unwrap();
        assert_eq!(load(&path).game_volume, 1.0, "clamped, and a missing key takes its default");
        let _ = std::fs::remove_dir_all(&dir);
    }
}
