//! Windows: stay-awake. A stub until W1 (ADR-038 §8:
//! `SetThreadExecutionState(ES_CONTINUOUS | ES_DISPLAY_REQUIRED)`).

pub fn keep_display_awake(on: bool) {
    if on {
        log::warn!(target: "power", "stay-awake is not on Windows yet (ADR-038 W1)");
    }
}
