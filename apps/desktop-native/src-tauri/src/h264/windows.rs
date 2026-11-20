//! Windows: the cable's H.264 decoder. A stub until W1 (ADR-038 §4: the
//! Media Foundation decoder on the window's D3D11 device): it refuses every
//! stream config, so the cable link runs and logs while no picture is made.

/// The status the stub refuses a config with (not a Media Foundation code).
const NOT_YET_STATUS: i32 = -1;

pub struct Decoder {
    pub width: u32,
    pub height: u32,
}

impl Decoder {
    pub fn new(_config: &[u8]) -> Result<Self, i32> {
        log::warn!(target: "usb", "no H.264 decoder on Windows yet (ADR-038 W1)");
        Err(NOT_YET_STATUS)
    }

    pub fn decode(&mut self, _access_unit: &[u8], _pts_us: u64) -> Result<Option<crate::video::Picture>, i32> {
        Err(NOT_YET_STATUS)
    }
}
