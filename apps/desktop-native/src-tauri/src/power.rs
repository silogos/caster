//! Stay-awake while a cast is live (the Electron receiver's
//! `powerSaveBlocker('prevent-display-sleep')`, Phase 14): a 30-minute
//! session must not stall on display sleep or a screensaver firing into
//! the captured window. One IOKit power assertion, held exactly while the
//! media PeerConnection is connected; it also keeps App Nap away.

use objc2_core_foundation::CFString;
use std::sync::Mutex;

/// `kIOPMAssertionTypePreventUserIdleDisplaySleep`.
const ASSERTION_TYPE: &str = "PreventUserIdleDisplaySleep";
/// `kIOPMAssertionLevelOn`.
const ASSERTION_LEVEL_ON: u32 = 255;
const ASSERTION_NAME: &str = "Caster — receiving a cast";
const IO_RETURN_SUCCESS: i32 = 0;

#[link(name = "IOKit", kind = "framework")]
extern "C" {
    fn IOPMAssertionCreateWithName(
        assertion_type: &CFString,
        level: u32,
        name: &CFString,
        id: *mut u32,
    ) -> i32;
    fn IOPMAssertionRelease(id: u32) -> i32;
}

static ASSERTION: Mutex<Option<u32>> = Mutex::new(None);

/// Holds (`true`) or releases (`false`) the assertion; idempotent.
pub fn keep_display_awake(on: bool) {
    let Ok(mut held) = ASSERTION.lock() else { return };
    match (on, *held) {
        (true, None) => {
            let mut id = 0u32;
            let status = unsafe {
                IOPMAssertionCreateWithName(
                    &CFString::from_str(ASSERTION_TYPE),
                    ASSERTION_LEVEL_ON,
                    &CFString::from_str(ASSERTION_NAME),
                    &mut id,
                )
            };
            if status == IO_RETURN_SUCCESS {
                *held = Some(id);
                log::info!(target: "power", "display kept awake for the cast");
            } else {
                log::warn!(target: "power", "stay-awake assertion failed (IOReturn {status:#x})");
            }
        }
        (false, Some(id)) => {
            unsafe { IOPMAssertionRelease(id) };
            *held = None;
            log::info!(target: "power", "display sleep allowed again");
        }
        _ => {}
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_assertion_is_held_once_and_released() {
        keep_display_awake(true);
        let first = *ASSERTION.lock().unwrap();
        assert!(first.is_some(), "IOKit granted the assertion");
        keep_display_awake(true);
        assert_eq!(*ASSERTION.lock().unwrap(), first, "idempotent — no second assertion");
        keep_display_awake(false);
        assert!(ASSERTION.lock().unwrap().is_none());
        keep_display_awake(false); // releasing twice is harmless
    }
}
