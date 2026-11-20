//! Stay-awake while a cast is live: one file per platform, the same
//! `keep_display_awake` on both. `mac.rs` holds an IOKit assertion;
//! `windows.rs` is a stub until W1 (ADR-038 §8: `SetThreadExecutionState`).

#[cfg(target_os = "macos")]
mod mac;
#[cfg(target_os = "macos")]
pub use mac::*;

#[cfg(target_os = "windows")]
mod windows;
#[cfg(target_os = "windows")]
pub use windows::*;
