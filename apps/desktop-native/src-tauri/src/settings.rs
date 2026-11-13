//! The receiver's own settings — receiver/environment controls only
//! (AGENTS.md: cast settings belong to the phone). Persisted as a small
//! JSON file next to the shared trust store; anything unreadable falls
//! back to the defaults rather than failing the app.

use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};

const SETTINGS_FILE: &str = "native-receiver.json";

/// Where a track plays (ADR-029): the system default output, or the
/// track's own Caster virtual device, which OBS captures.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum PlaysOn {
    #[default]
    Speaker,
    Caster,
}

/// Anything but `"caster"` reads as the speaker: a value this version does
/// not know must not throw away the rest of the file.
fn lenient_plays_on<'de, D: serde::Deserializer<'de>>(d: D) -> Result<PlaysOn, D::Error> {
    let value = serde_json::Value::deserialize(d)?;
    Ok(if value.as_str() == Some("caster") { PlaysOn::Caster } else { PlaysOn::Speaker })
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct ReceiverSettings {
    /// Audio (the game's sound) level, 0.0–1.0 (linear, like the Electron mixer's GainNode).
    pub game_volume: f32,
    pub game_muted: bool,
    #[serde(deserialize_with = "lenient_plays_on")]
    pub game_plays_on: PlaysOn,
    /// The phone's mic: its own level, mute and output (ADR-003, ADR-029).
    pub mic_volume: f32,
    /// Muted until the user turns it on: played on the speaker, the phone
    /// hears its own mic again (an echo) — and receivers before ADR-029
    /// never played the mic, so an upgrade must not start doing it unasked.
    pub mic_muted: bool,
    #[serde(deserialize_with = "lenient_plays_on")]
    pub mic_plays_on: PlaysOn,
    /// The Syphon output for OBS (ADR-021). Off until the user turns it on.
    pub syphon_output: bool,
}

impl Default for ReceiverSettings {
    fn default() -> Self {
        Self {
            game_volume: 1.0,
            game_muted: false,
            game_plays_on: PlaysOn::Speaker,
            mic_volume: 1.0,
            mic_muted: true,
            mic_plays_on: PlaysOn::Speaker,
            syphon_output: false,
        }
    }
}

impl ReceiverSettings {
    /// The gain the audio engine applies to the game's sound.
    pub fn effective_game_gain(&self) -> f32 {
        if self.game_muted { 0.0 } else { self.game_volume }
    }

    /// The gain the audio engine applies to the mic.
    pub fn effective_mic_gain(&self) -> f32 {
        if self.mic_muted { 0.0 } else { self.mic_volume }
    }

    fn sanitized(mut self) -> Self {
        let level = |v: f32| if v.is_finite() { v.clamp(0.0, 1.0) } else { 1.0 };
        self.game_volume = level(self.game_volume);
        self.mic_volume = level(self.mic_volume);
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
    let text = serde_json::to_string(&settings.clone().sanitized()).map_err(|e| e.to_string())?;
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
        let s = ReceiverSettings {
            game_volume: 0.4,
            game_muted: true,
            game_plays_on: PlaysOn::Caster,
            mic_volume: 0.7,
            mic_muted: false,
            mic_plays_on: PlaysOn::Caster,
            syphon_output: true,
        };
        save(&path, &s).unwrap();
        assert_eq!(load(&path), s);
        assert_eq!(s.effective_game_gain(), 0.0);
        assert_eq!(s.effective_mic_gain(), 0.7, "the mic's level is its own");
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
        assert!(!load(&path).syphon_output, "a file from before the Syphon output leaves it off");
        assert_eq!(load(&path).game_plays_on, PlaysOn::Speaker, "and plays on the speaker");
        assert_eq!(load(&path).mic_volume, 1.0, "the mic at full level");
        assert!(load(&path).mic_muted, "but muted: a receiver before ADR-029 never played the mic");
        assert_eq!(load(&path).effective_mic_gain(), 0.0);
        std::fs::write(&path, r#"{"micMuted": false}"#).unwrap();
        assert_eq!(load(&path).effective_mic_gain(), 1.0, "turned on once, it stays on");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn an_unknown_output_value_keeps_the_rest_of_the_file() {
        let dir = scratch("plays-on");
        let path = settings_path(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(&path, r#"{"gameVolume": 0.3, "gamePlaysOn": "External Headphones", "micPlaysOn": "caster", "syphonOutput": true}"#).unwrap();
        let s = load(&path);
        assert_eq!(s.game_plays_on, PlaysOn::Speaker, "unknown → speaker");
        assert_eq!(s.mic_plays_on, PlaysOn::Caster);
        assert_eq!((s.game_volume, s.syphon_output), (0.3, true), "the other fields survive");
        let _ = std::fs::remove_dir_all(&dir);
    }
}
