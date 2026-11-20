//! macOS: the USB cast's H.264 decoder (ADR-034), a thin owner of the VideoToolbox
//! session in `native/h264_decoder.m`. One access unit in, one NV12 pixel
//! buffer out; a new codec config (SPS/PPS) rebuilds the session.

use std::ffi::c_void;
use std::ptr::NonNull;

use objc2_core_foundation::CFRetained;
use objc2_core_video::CVPixelBuffer;

#[repr(C)]
struct ZfH264Decoder {
    _private: [u8; 0],
}

extern "C" {
    fn zf_h264_create(config: *const u8, len: usize, width: *mut i32, height: *mut i32, status: *mut i32) -> *mut ZfH264Decoder;
    fn zf_h264_decode(d: *mut ZfH264Decoder, au: *const u8, len: usize, pts_us: i64, status: *mut i32) -> *mut c_void;
    fn zf_h264_destroy(d: *mut ZfH264Decoder);
}

pub struct Decoder {
    raw: NonNull<ZfH264Decoder>,
    pub width: u32,
    pub height: u32,
}

// The session is used from one thread at a time (the USB video thread).
unsafe impl Send for Decoder {}

impl Decoder {
    /// From the stream's codec-config buffer (Annex-B SPS + PPS).
    pub fn new(config: &[u8]) -> Result<Self, i32> {
        let (mut w, mut h, mut status) = (0i32, 0i32, 0i32);
        let raw = unsafe { zf_h264_create(config.as_ptr(), config.len(), &mut w, &mut h, &mut status) };
        let raw = NonNull::new(raw).ok_or(status)?;
        Ok(Self { raw, width: w.max(0) as u32, height: h.max(0) as u32 })
    }

    /// `Ok(None)`: the unit produced no picture (e.g. an empty or parameter-only unit).
    pub fn decode(&mut self, access_unit: &[u8], pts_us: u64) -> Result<Option<CFRetained<CVPixelBuffer>>, i32> {
        let mut status = 0i32;
        let picture = unsafe { zf_h264_decode(self.raw.as_ptr(), access_unit.as_ptr(), access_unit.len(), pts_us as i64, &mut status) };
        if status != 0 {
            if let Some(p) = NonNull::new(picture as *mut CVPixelBuffer) {
                drop(unsafe { CFRetained::from_raw(p) });
            }
            return Err(status);
        }
        Ok(NonNull::new(picture as *mut CVPixelBuffer).map(|p| unsafe { CFRetained::from_raw(p) }))
    }
}

impl Drop for Decoder {
    fn drop(&mut self) {
        unsafe { zf_h264_destroy(self.raw.as_ptr()) }
    }
}
