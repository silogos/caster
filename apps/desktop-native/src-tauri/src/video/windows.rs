//! Windows: the video window. A stub until W1 (ADR-038 §3: a Win32 window
//! with a D3D11 flip-model swap chain), so the crate builds and the spikes
//! can run the receiver on Windows (roadmap S0). Every frame is dropped and
//! the first one of each stream says so in the log; "Open video" is
//! remembered, so the deck and the tray behave as on the Mac.

use super::{CopyReason, Followup, VideoRange};
use std::sync::atomic::{AtomicBool, Ordering};

/// A decoded picture on this platform. Empty until the Media Foundation
/// decoder lands (ADR-038 §4): nothing produces one yet.
pub struct Picture;

/// A decoded frame the window can take as is.
pub struct DecodedFrame {
    pub pixel_buffer: Picture,
    pub range: VideoRange,
}

/// The user wants the video window (set by "Open video", cleared by "Close video").
static VIDEO_OPEN: AtomicBool = AtomicBool::new(false);

const NOT_YET: &str = "the video window is not on Windows yet (ADR-038 W1)";

/// No decoder buffer behind a Wi-Fi frame on Windows yet: the frame loop takes the copy path.
pub fn decoded_frame(_buffer: &dyn livekit::webrtc::video_frame::VideoBuffer) -> Result<DecodedFrame, CopyReason> {
    Err(CopyReason::NoDecoderBuffer)
}

pub fn read_range_of_frame(_buffer: &dyn livekit::webrtc::video_frame::VideoBuffer) -> VideoRange {
    VideoRange::Limited
}

pub fn mean_luma_of(_picture: &Picture, _width: u32, _height: u32) -> Option<f64> {
    None
}

pub fn apply_no_input_card() {}

pub fn is_open() -> bool {
    VIDEO_OPEN.load(Ordering::Relaxed)
}

pub fn open_video_window() {
    VIDEO_OPEN.store(true, Ordering::Relaxed);
    log::warn!(target: "video", "Open video: {NOT_YET}");
}

pub fn close_video_window() {
    VIDEO_OPEN.store(false, Ordering::Relaxed);
}

pub fn show_video_window() {}

pub fn restore_video_window() {}

/// No window to close by its own button yet.
pub fn refresh_visibility() -> bool {
    false
}

/// Per-stream render state: drops every frame until W1.
pub struct Renderer {
    pacer: crate::pacing::Pacer,
    told: bool,
}

impl Renderer {
    pub fn new() -> Self {
        Self { pacer: crate::pacing::Pacer::new(), told: false }
    }

    /// Every frame is dropped here, before any conversion.
    pub fn drop_if_hidden(&mut self) -> Option<Followup> {
        if !self.told {
            self.told = true;
            log::warn!(target: "video", "frames arrive but are not shown: {NOT_YET}");
        }
        Some(Followup::None)
    }

    pub fn last_hold_ms(&self) -> f64 {
        0.0
    }

    pub fn take_pacing_window(&mut self) -> crate::pacing::Window {
        self.pacer.take_window()
    }

    pub fn render_decoded(&mut self, _frame: DecodedFrame, _width: u32, _height: u32) -> Followup {
        Followup::None
    }

    #[allow(clippy::too_many_arguments)]
    pub fn render_i420(&mut self, _width: u32, _height: u32, _range: VideoRange, _y: &[u8], _u: &[u8], _v: &[u8], _stride_y: u32, _stride_u: u32, _stride_v: u32) -> Followup {
        Followup::None
    }
}
