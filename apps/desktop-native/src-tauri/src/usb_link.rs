//! The USB link (ADR-034, docs/architecture/usb-link.md): the receiver is the
//! USB host. It switches a plugged-in Android phone into accessory mode,
//! claims the accessory's bulk endpoints, authenticates the phone with the
//! Wi-Fi pairing's trust key, and hands the cast's frames to the same
//! display path (pacer, layer, Syphon) and game-audio engine as Wi-Fi.
//!
//! One thread owns everything here: it polls for devices, and while a link
//! is up it reads, decodes and renders inline (one link at a time; a
//! lossless ordered stream needs no queue between read and decode).

use std::collections::HashSet;
use std::path::PathBuf;
use std::time::{Duration, Instant};

use rusb::{Context, Device, DeviceHandle, Direction, TransferType, UsbContext};
use serde_json::{json, Value};
use tauri::{Emitter, Manager};

use crate::usb_protocol::{self as proto, Frame};

/// AOA requests (source.android.com/docs/core/interaction/accessories/aoa).
const AOA_GET_PROTOCOL: u8 = 51;
const AOA_SEND_STRING: u8 = 52;
const AOA_START: u8 = 53;
const REQUEST_VENDOR_IN: u8 = 0xC0;
const REQUEST_VENDOR_OUT: u8 = 0x40;
const GOOGLE_VID: u16 = 0x18D1;
const ACCESSORY_PIDS: std::ops::RangeInclusive<u16> = 0x2D00..=0x2D05;
/// Vendor ids that get the AOA switch. Vendor requests are never sent to
/// anything else plugged in (keyboards, drives).
const ANDROID_VIDS: [u16; 9] = [
    0x17EF, // Lenovo
    0x18D1, // Google
    0x04E8, // Samsung
    0x2717, // Xiaomi
    0x22B8, // Motorola
    0x0BB4, // HTC
    0x12D1, // Huawei
    0x2A70, // OnePlus
    0x0FCE, // Sony
];
const MANUFACTURER: &str = "Caster";
const MODEL: &str = "Caster Receiver";
const DESCRIPTION: &str = "Caster desktop receiver";
const DOWNLOAD_URI: &str = "https://github.com/silogos/caster";

const POLL_INTERVAL: Duration = Duration::from_secs(1);
const CONTROL_TIMEOUT: Duration = Duration::from_millis(1000);
const HELLO_EVERY: Duration = Duration::from_secs(1);
const HELLO_WRITE_TIMEOUT: Duration = Duration::from_millis(500);
const READ_TIMEOUT: Duration = Duration::from_millis(100);
const WRITE_TIMEOUT: Duration = Duration::from_millis(1000);
const BULK_READ_BYTES: usize = 256 * 1024;
const STATS_EVERY: Duration = Duration::from_secs(5);

pub struct Config {
    pub app: tauri::AppHandle,
    pub desktop_id: String,
    pub desktop_name: String,
    pub trust_path: PathBuf,
}

pub fn start(config: Config) {
    let spawned = std::thread::Builder::new().name("usb-link".into()).spawn(move || run(config));
    if let Err(e) = spawned {
        log::error!(target: "usb", "usb thread: {e}");
    }
}

fn run(config: Config) {
    let context = match Context::new() {
        Ok(c) => c,
        Err(e) => return log::error!(target: "usb", "libusb unavailable — no USB cast: {e}"),
    };
    log::info!(target: "usb", "watching for Android devices on USB");
    // Devices already sent the switch (bus, address): a phone that never
    // opens the accessory is left alone until it is replugged.
    let mut switched: HashSet<(u8, u8)> = HashSet::new();
    loop {
        let Ok(devices) = context.devices() else {
            std::thread::sleep(POLL_INTERVAL);
            continue;
        };
        let present: HashSet<(u8, u8)> = devices.iter().map(|d| (d.bus_number(), d.address())).collect();
        switched.retain(|k| present.contains(k));
        for device in devices.iter() {
            let Ok(desc) = device.device_descriptor() else { continue };
            let key = (device.bus_number(), device.address());
            if desc.vendor_id() == GOOGLE_VID && ACCESSORY_PIDS.contains(&desc.product_id()) {
                match device.open() {
                    Ok(handle) => session(&config, handle),
                    Err(e) => log::warn!(target: "usb", "accessory {:04x}:{:04x} did not open: {e}", desc.vendor_id(), desc.product_id()),
                }
            } else if ANDROID_VIDS.contains(&desc.vendor_id()) && switched.insert(key) {
                switch_to_accessory(&device, &config.desktop_id);
            }
        }
        std::thread::sleep(POLL_INTERVAL);
    }
}

/// GET_PROTOCOL → identity strings → START. Never AOA audio (request 58).
fn switch_to_accessory(device: &Device<Context>, desktop_id: &str) {
    let Ok(desc) = device.device_descriptor() else { return };
    let id = format!("{:04x}:{:04x}", desc.vendor_id(), desc.product_id());
    let handle = match device.open() {
        Ok(h) => h,
        Err(e) => return log::debug!(target: "usb", "{id}: not opened for the switch: {e}"),
    };
    let mut version = [0u8; 2];
    match handle.read_control(REQUEST_VENDOR_IN, AOA_GET_PROTOCOL, 0, 0, &mut version, CONTROL_TIMEOUT) {
        Ok(2) if u16::from_le_bytes(version) >= 1 => {}
        Ok(_) => return log::info!(target: "usb", "{id}: no accessory support"),
        Err(e) => return log::info!(target: "usb", "{id}: no accessory support ({e})"),
    }
    let link_version = proto::LINK_VERSION.to_string();
    let strings = [MANUFACTURER, MODEL, DESCRIPTION, link_version.as_str(), DOWNLOAD_URI, desktop_id];
    for (index, value) in strings.iter().enumerate() {
        let mut bytes = value.as_bytes().to_vec();
        bytes.push(0);
        if let Err(e) = handle.write_control(REQUEST_VENDOR_OUT, AOA_SEND_STRING, 0, index as u16, &bytes, CONTROL_TIMEOUT) {
            return log::warn!(target: "usb", "{id}: identity string {index} refused: {e}");
        }
    }
    match handle.write_control(REQUEST_VENDOR_OUT, AOA_START, 0, 0, &[], CONTROL_TIMEOUT) {
        Ok(_) => log::info!(target: "usb", "{id}: AOA v{} — switched to accessory mode", u16::from_le_bytes(version)),
        Err(e) => log::warn!(target: "usb", "{id}: accessory start refused: {e}"),
    }
}

struct Endpoints {
    interface: u8,
    bulk_in: u8,
    bulk_out: u8,
}

/// Interface 0 is the accessory's on every 2D0x product (2D01 adds adb as interface 1).
fn endpoints(handle: &DeviceHandle<Context>) -> Option<Endpoints> {
    let config = handle.device().active_config_descriptor().ok()?;
    let interface = config.interfaces().next()?;
    let alt = interface.descriptors().next()?;
    let (mut bulk_in, mut bulk_out) = (None, None);
    for ep in alt.endpoint_descriptors() {
        if ep.transfer_type() == TransferType::Bulk {
            match ep.direction() {
                Direction::In => bulk_in = Some(ep.address()),
                Direction::Out => bulk_out = Some(ep.address()),
            }
        }
    }
    Some(Endpoints { interface: alt.interface_number(), bulk_in: bulk_in?, bulk_out: bulk_out? })
}

/// One link, from claim to the cable going (or a broken stream / `bye`).
fn session(config: &Config, handle: DeviceHandle<Context>) {
    let Some(ep) = endpoints(&handle) else {
        return log::warn!(target: "usb", "accessory without bulk endpoints");
    };
    if let Err(e) = handle.claim_interface(ep.interface) {
        log::warn!(target: "usb", "accessory interface not claimed: {e}");
        std::thread::sleep(POLL_INTERVAL);
        return;
    }
    log::info!(target: "usb", "accessory claimed (bulk in {:#04x}, out {:#04x}) — waiting for the app", ep.bulk_in, ep.bulk_out);
    let link = Link { handle: &handle, out: ep.bulk_out };
    let mut desktop_nonce = crate::pairing::new_nonce();
    let mut hello = proto::control(&proto::hello(&config.desktop_id, &config.desktop_name, &desktop_nonce));
    let mut last_hello: Option<Instant> = None;
    let mut trusted = false;
    let mut reader = proto::Reader::default();
    let mut media = UsbMedia::new(config.app.clone());
    let mut buf = vec![0u8; BULK_READ_BYTES];
    let (mut bytes_window, mut window_start) = (0usize, Instant::now());
    let end = loop {
        if !trusted && last_hello.map_or(true, |t| t.elapsed() >= HELLO_EVERY) {
            last_hello = Some(Instant::now());
            // Times out until the app has opened the accessory: expected.
            let _ = handle.write_bulk(ep.bulk_out, &hello, HELLO_WRITE_TIMEOUT);
        }
        let n = match handle.read_bulk(ep.bulk_in, &mut buf, READ_TIMEOUT) {
            Ok(n) => n,
            Err(rusb::Error::Timeout) => 0,
            Err(e) => break format!("read: {e}"),
        };
        bytes_window += n;
        if window_start.elapsed() >= STATS_EVERY {
            if media.casting {
                log::info!(target: "usb", "link: {:.1} Mbit/s", bytes_window as f64 * 8.0 / window_start.elapsed().as_secs_f64() / 1e6);
            }
            bytes_window = 0;
            window_start = Instant::now();
        }
        if n == 0 {
            continue;
        }
        let frames = match reader.feed(&buf[..n]) {
            Ok(f) => f,
            Err(proto::Broken(why)) => break format!("broken stream: {why}"),
        };
        let mut bye = false;
        for frame in frames {
            match frame.kind {
                proto::KIND_CONTROL => match on_control(config, &link, &frame, &desktop_nonce, &mut trusted, &mut media) {
                    Control::Continue => {}
                    Control::Bye => bye = true,
                    Control::Restart => {
                        // A new app instance on the same cable. Mid-handshake, the
                        // hello already sent stays valid (the app may be answering
                        // it right now: a new nonce here refused that answer, found
                        // live 2026-11-18); only a session that was trusted starts
                        // over with a fresh nonce, and its media ends.
                        if trusted {
                            log::info!(target: "usb", "the app reopened the accessory — the old session ends, handshake starts over");
                            media.stop();
                            trusted = false;
                            desktop_nonce = crate::pairing::new_nonce();
                            hello = proto::control(&proto::hello(&config.desktop_id, &config.desktop_name, &desktop_nonce));
                        } else {
                            log::info!(target: "usb", "the app opened the accessory — hello sent again");
                        }
                        last_hello = None;
                    }
                },
                _ if !trusted => log::warn!(target: "usb", "media before authentication dropped"),
                proto::KIND_VIDEO => media.video(&frame),
                proto::KIND_GAME_AUDIO => media.game_audio(&frame.payload),
                proto::KIND_MIC_AUDIO => media.mic_audio(&frame.payload),
                other => log::debug!(target: "usb", "frame kind {other} ignored"),
            }
        }
        if bye {
            break "the phone said bye".to_string();
        }
    };
    log::info!(target: "usb", "link ended: {end}");
    media.stop();
    if trusted {
        announce_mobile(&config.app, json!({ "state": "disconnected", "link": "usb" }));
    }
    let _ = handle.release_interface(ep.interface);
}

/// `pairing:mobile-state` for the deck and the tray — what signaling sends for a Wi-Fi phone.
fn announce_mobile(app: &tauri::AppHandle, state: Value) {
    if let Some(tray) = app.try_state::<crate::tray::Tray>() {
        tray.on_mobile_state(&state);
    }
    let _ = app.emit("pairing:mobile-state", state);
}

struct Link<'a> {
    handle: &'a DeviceHandle<Context>,
    out: u8,
}

impl Link<'_> {
    fn send(&self, message: &Value) {
        if let Err(e) = self.handle.write_bulk(self.out, &proto::control(message), WRITE_TIMEOUT) {
            log::warn!(target: "usb", "control write failed: {e}");
        }
    }
}

enum Control {
    Continue,
    Bye,
    /// The phone's `ready`: start the handshake over.
    Restart,
}

fn on_control(config: &Config, link: &Link, frame: &Frame, desktop_nonce: &str, trusted: &mut bool, media: &mut UsbMedia) -> Control {
    let Ok(message) = serde_json::from_slice::<Value>(&frame.payload) else {
        log::warn!(target: "usb", "unreadable control message dropped");
        return Control::Continue;
    };
    match message["type"].as_str().unwrap_or_default() {
        "auth" => {
            let entry = crate::pairing::read_trust(&config.trust_path);
            let trust = entry.as_ref().map(|e| proto::Trust { device_id: &e.device_id, key: &e.key });
            match proto::answer_auth(&message, trust.as_ref(), &config.desktop_id, desktop_nonce) {
                Ok(ok) => {
                    *trusted = true;
                    link.send(&ok);
                    log::info!(target: "usb", "phone authenticated over USB");
                    // The deck and the tray leave the QR stage, as on a Wi-Fi connect.
                    let name = entry.as_ref().map_or("Android device", |e| e.name.as_str());
                    announce_mobile(&config.app, json!({ "state": "connected", "name": name, "link": "usb" }));
                    let app = config.app.clone();
                    let _ = config.app.run_on_main_thread(move || crate::tray::show_main_window(&app));
                }
                Err(error) => {
                    link.send(&error);
                    log::warn!(target: "usb", "phone refused: no trust for it, or a wrong proof");
                }
            }
        }
        "cast-start" if *trusted => {
            log::info!(
                target: "usb", "cast starts over USB: {}x{} @{} fps, audio {}",
                message["w"], message["h"], message["fps"], message["audio"]
            );
            media.start();
            // The deck's chips and settings line: the same shape as Wi-Fi's session-info.
            media.info = json!({
                "profile": "usb", "width": message["w"], "height": message["h"], "fps": message["fps"],
                "gameAudio": !message["audio"].is_null(), "mic": message["mic"].as_bool().unwrap_or(false),
            });
            announce_mobile(&config.app, json!({ "state": "session-info", "info": media.info }));
        }
        "mic" if *trusted => {
            let on = message["on"].as_bool().unwrap_or(false);
            log::info!(target: "usb", "mic {} over USB", if on { "on" } else { "off" });
            if on {
                crate::audio::reset_session(crate::audio::Track::Mic);
            }
            media.info["mic"] = json!(on);
            announce_mobile(&config.app, json!({ "state": "session-info", "info": media.info }));
        }
        "cast-stop" if *trusted => {
            log::info!(target: "usb", "cast stopped by the phone");
            media.stop();
        }
        "bye" => return Control::Bye,
        "ready" => return Control::Restart,
        other => log::debug!(target: "usb", "control {other:?} ignored"),
    }
    Control::Continue
}

/// One cast's media on this link: decoder, renderer, audio. Rebuilt per cast.
struct UsbMedia {
    app: tauri::AppHandle,
    casting: bool,
    config: Option<Vec<u8>>,
    decoder: Option<crate::h264::Decoder>,
    renderer: Option<crate::video::Renderer>,
    generation: u64,
    frames: u64,
    window_requested: bool,
    last_visibility_poll: Option<Instant>,
    last_log: Instant,
    frames_at_last_log: u64,
    last_picture: Option<crate::webrtc::ReceivedPicture>,
    marker: crate::latency_probe::LumaMarker,
    audio_chunks: u64,
    mic_chunks: u64,
    /// The cast's session-info for the deck (kept to update its mic flag).
    info: Value,
}

impl UsbMedia {
    fn new(app: tauri::AppHandle) -> Self {
        Self {
            app,
            casting: false,
            config: None,
            decoder: None,
            renderer: None,
            generation: 0,
            frames: 0,
            window_requested: false,
            last_visibility_poll: None,
            last_log: Instant::now(),
            frames_at_last_log: 0,
            last_picture: None,
            marker: Default::default(),
            audio_chunks: 0,
            mic_chunks: 0,
            info: Value::Null,
        }
    }

    fn start(&mut self) {
        self.stop();
        self.casting = true;
        // A new stream retires any other frame loop (a Wi-Fi cast's) and starts the pacer fresh.
        self.generation = crate::video::begin_stream();
        self.renderer = Some(crate::video::Renderer::new());
        crate::audio::reset_session(crate::audio::Track::Game);
        crate::audio::reset_session(crate::audio::Track::Mic);
        self.mic_chunks = 0;
        self.frames = 0;
        self.frames_at_last_log = 0;
        self.last_log = Instant::now();
        self.window_requested = false;
        self.audio_chunks = 0;
    }

    fn stop(&mut self) {
        if !self.casting {
            return;
        }
        self.casting = false;
        self.decoder = None;
        self.renderer = None;
        self.config = None;
        log::info!(target: "usb", "video ended after {} frames", self.frames);
        if crate::video::is_current_stream(self.generation) {
            crate::webrtc::announce_input(&self.app, false);
            crate::webrtc::announce_picture(&self.app, None);
        }
    }

    fn video(&mut self, frame: &Frame) {
        if !self.casting {
            return;
        }
        if !crate::video::is_current_stream(self.generation) {
            return; // a Wi-Fi cast took the window over
        }
        if frame.flags & proto::FLAG_CONFIG != 0 {
            if self.config.as_deref() != Some(&frame.payload[..]) {
                self.config = Some(frame.payload.clone());
                self.decoder = match crate::h264::Decoder::new(&frame.payload) {
                    Ok(d) => {
                        log::info!(target: "usb", "decoder ready for {}x{}", d.width, d.height);
                        Some(d)
                    }
                    Err(status) => {
                        log::error!(target: "usb", "decoder refused the stream's config (status {status})");
                        None
                    }
                };
            }
            return;
        }
        let Some(decoder) = self.decoder.as_mut() else { return };
        let picture = match decoder.decode(&frame.payload, frame.pts_us) {
            Ok(Some(p)) => p,
            Ok(None) => return,
            Err(status) => {
                log::warn!(target: "usb", "decode failed (status {status}) — waiting for the next keyframe");
                return;
            }
        };
        let (w, h) = (decoder.width, decoder.height);
        if !self.window_requested {
            self.window_requested = true;
            log::info!(target: "usb", "first frame {w}x{h}");
            if crate::video::is_open() {
                let _ = self.app.run_on_main_thread(crate::video::open_video_window);
            }
        }
        if !crate::video::is_live() {
            crate::webrtc::announce_input(&self.app, true);
        }
        crate::webrtc::poll_window_visibility(&self.app, &mut self.last_visibility_poll);
        let Some(renderer) = self.renderer.as_mut() else { return };
        let followup = match renderer.drop_if_hidden() {
            Some(f) => f,
            // VideoToolbox's own buffer, shown zero-copy like Wi-Fi's (ADR-031):
            // NV12 video range, as the decoder is asked for.
            None => renderer.render_decoded(
                crate::video::DecodedFrame { pixel_buffer: picture.clone(), range: crate::video::VideoRange::Limited },
                w,
                h,
            ),
        };
        if crate::latency_probe::enabled() {
            probe_luma(&mut self.marker, renderer.last_hold_ms(), &picture, w, h);
        }
        if followup == crate::video::Followup::RaiseWindow {
            let _ = self.app.run_on_main_thread(crate::video::show_video_window);
        }
        self.frames += 1;
        if self.last_log.elapsed() >= Duration::from_secs(1) {
            let fps = (self.frames - self.frames_at_last_log) as f64 / self.last_log.elapsed().as_secs_f64();
            let picture = crate::webrtc::ReceivedPicture { width: w, height: h, fps: fps.round() as u32 };
            if self.last_picture != Some(picture) {
                self.last_picture = Some(picture);
                crate::webrtc::announce_picture(&self.app, self.last_picture);
            }
            let pacing = renderer.take_pacing_window();
            if pacing.frames > 0 {
                log::info!(
                    target: "usb", "video: {} frames, {fps:.0} fps, {w}x{h} | pacing delay avg {:.0} max {:.0} ms, late {}",
                    self.frames, pacing.delay_sum / pacing.frames as f64 * 1000.0, pacing.delay_max * 1000.0, pacing.late
                );
            }
            self.last_log = Instant::now();
            self.frames_at_last_log = self.frames;
        }
    }

}

/// The latency pattern's flips, read from the decoded picture's luma plane
/// (latency-measurement.md) — the same mark the Wi-Fi path logs.
fn probe_luma(marker: &mut crate::latency_probe::LumaMarker, hold_ms: f64, picture: &objc2_core_video::CVPixelBuffer, w: u32, h: u32) {
    {
        use objc2_core_video::{
            CVPixelBufferGetBaseAddressOfPlane, CVPixelBufferGetBytesPerRowOfPlane, CVPixelBufferLockBaseAddress,
            CVPixelBufferLockFlags, CVPixelBufferUnlockBaseAddress,
        };
        let decoded_ms = crate::latency_probe::wall_ms();
        let mean = unsafe {
            if CVPixelBufferLockBaseAddress(picture, CVPixelBufferLockFlags::ReadOnly) != 0 {
                return;
            }
            let base = CVPixelBufferGetBaseAddressOfPlane(picture, 0) as *const u8;
            let stride = CVPixelBufferGetBytesPerRowOfPlane(picture, 0);
            let mean = if base.is_null() {
                None
            } else {
                let plane = std::slice::from_raw_parts(base, stride * h as usize);
                Some(crate::latency_probe::mean_luma(plane, stride as u32, w, h))
            };
            CVPixelBufferUnlockBaseAddress(picture, CVPixelBufferLockFlags::ReadOnly);
            mean
        };
        if let Some(white) = mean.and_then(|m| marker.observe(m)) {
            log::info!(target: "latency", "video {} decoded_ms={decoded_ms:.1} hold_ms={hold_ms:.1}", if white { "white" } else { "black" });
        }
    }
}

impl UsbMedia {
    /// The mic's own track (ADR-003): 48 kHz mono on the cable, the engine's 48 kHz stereo here.
    fn mic_audio(&mut self, mono: &[u8]) {
        if !self.casting {
            return;
        }
        if self.mic_chunks == 0 {
            log::info!(target: "usb", "first mic chunk ({} bytes) — feeding the mic engine", mono.len());
        }
        self.mic_chunks += 1;
        crate::audio::feed_track_pcm16(crate::audio::Track::Mic, &proto::mono_to_stereo(mono));
    }

    fn game_audio(&mut self, pcm: &[u8]) {
        if !self.casting {
            return;
        }
        if self.audio_chunks == 0 {
            log::info!(target: "usb", "first game-audio chunk ({} bytes) — feeding the engine", pcm.len());
        }
        self.audio_chunks += 1;
        crate::audio::feed_dc_pcm16(pcm);
    }
}
