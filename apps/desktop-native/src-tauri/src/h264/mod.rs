//! The cable's H.264 decoder (ADR-034, ADR-038 §4): one file per platform,
//! the same `Decoder` on both. `mac.rs` is VideoToolbox; `windows.rs` is a
//! stub until W1 brings the Media Foundation decoder (roadmap S0).

#[cfg(target_os = "macos")]
mod mac;
#[cfg(target_os = "macos")]
pub use mac::*;

#[cfg(target_os = "windows")]
mod windows;
#[cfg(target_os = "windows")]
pub use windows::*;
