//! The USB link (ADR-034, docs/architecture/usb-link.md v2): the receiver is
//! the USB host. It switches a plugged-in Android phone into accessory mode,
//! claims the accessory's bulk endpoints, authenticates the phone with the
//! cable pairing's key (enrolling it, with the user's Allow, on its first
//! plug-in — ADR-037), and hands the cast's frames to the same display path
//! (pacer, layer, Syphon) and game-audio engine as Wi-Fi.
//!
//! It runs only in Cable mode (ADR-037): `start` spawns it when the receiver
//! enters Cable mode, and the `stop` flag ends it when it leaves. One thread
//! owns everything here: it polls for devices, and while a link is up it
//! reads, decodes and renders inline (one link at a time; a lossless ordered
//! stream needs no queue between read and decode).

use std::collections::HashSet;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{Receiver, TryRecvError};
use std::sync::Arc;
use std::time::{Duration, Instant};

use rusb::{Context, Device, DeviceHandle, Direction, TransferType, UsbContext};
use serde_json::{json, Value};
use tauri::Emitter;

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
/// The Allow prompt's life (usb-link.md §Limits): no answer reads as Don't Allow.
const ALLOW_TIMEOUT: Duration = Duration::from_secs(60);

/// What the receiver's UI asks of the cable thread.
#[derive(Debug)]
pub enum Command {
    /// The user's answer in the cable guide (step 3).
    Answer(bool),
    /// Terminate on the deck: `bye forget` to the phone; the pairing is already removed.
    Forget,
}

pub struct Config {
    pub app: tauri::AppHandle,
    pub desktop_id: String,
    pub desktop_name: String,
    pub paths: crate::pairing::StorePaths,
    pub commands: Receiver<Command>,
    /// Set by the link owner when the receiver leaves Cable mode.
    pub stop: Arc<AtomicBool>,
}

pub fn start(config: Config) {
    let spawned = std::thread::Builder::new().name("usb-link".into()).spawn(move || run(config));
    if let Err(e) = spawned {
        log::error!(target: "usb", "usb thread: {e}");
    }
}

/// `cable:state` for the cable guide and the deck (ADR-037; design frames 17, 19–24).
fn announce_cable(app: &tauri::AppHandle, state: &str, name: Option<&str>) {
    let _ = app.emit("cable:state", json!({ "state": state, "name": name }));
}

fn run(config: Config) {
    let context = match Context::new() {
        Ok(c) => c,
        Err(e) => return log::error!(target: "usb", "libusb unavailable — no USB cast: {e}"),
    };
    log::info!(target: "usb", "Cable mode — watching for Android devices on USB");
    announce_cable(&config.app, "waiting", None);
    // Devices already sent the switch (bus, address): a phone that never
    // opens the accessory is left alone until it is replugged.
    let mut switched: HashSet<(u8, u8)> = HashSet::new();
    while !config.stop.load(Ordering::Relaxed) {
        // Commands while no link is up: nothing to answer, nobody to say bye to.
        while config.commands.try_recv().is_ok() {}
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
                if let Some(name) = switch_to_accessory(&device, &config.desktop_id) {
                    announce_cable(&config.app, "found", Some(&name));
                }
            }
        }
        std::thread::sleep(POLL_INTERVAL);
    }
    log::info!(target: "usb", "stopped (left Cable mode)");
}

/// GET_PROTOCOL → identity strings → START. Never AOA audio (request 58).
/// The phone's USB name ("Lenovo TB321FU") once it was switched, for the guide's step 1.
fn switch_to_accessory(device: &Device<Context>, desktop_id: &str) -> Option<String> {
    let desc = device.device_descriptor().ok()?;
    let id = format!("{:04x}:{:04x}", desc.vendor_id(), desc.product_id());
    let handle = match device.open() {
        Ok(h) => h,
        Err(e) => {
            log::debug!(target: "usb", "{id}: not opened for the switch: {e}");
            return None;
        }
    };
    let mut version = [0u8; 2];
    match handle.read_control(REQUEST_VENDOR_IN, AOA_GET_PROTOCOL, 0, 0, &mut version, CONTROL_TIMEOUT) {
        Ok(2) if u16::from_le_bytes(version) >= 1 => {}
        Ok(_) => {
            log::info!(target: "usb", "{id}: no accessory support");
            return None;
        }
        Err(e) => {
            log::info!(target: "usb", "{id}: no accessory support ({e})");
            return None;
        }
    }
    let name = usb_name(&handle, &desc);
    let link_version = proto::LINK_VERSION.to_string();
    let strings = [MANUFACTURER, MODEL, DESCRIPTION, link_version.as_str(), DOWNLOAD_URI, desktop_id];
    for (index, value) in strings.iter().enumerate() {
        let mut bytes = value.as_bytes().to_vec();
        bytes.push(0);
        if let Err(e) = handle.write_control(REQUEST_VENDOR_OUT, AOA_SEND_STRING, 0, index as u16, &bytes, CONTROL_TIMEOUT) {
            log::warn!(target: "usb", "{id}: identity string {index} refused: {e}");
            return None;
        }
    }
    match handle.write_control(REQUEST_VENDOR_OUT, AOA_START, 0, 0, &[], CONTROL_TIMEOUT) {
        Ok(_) => {
            log::info!(target: "usb", "{id}: AOA v{} — switched to accessory mode", u16::from_le_bytes(version));
            Some(name)
        }
        Err(e) => {
            log::warn!(target: "usb", "{id}: accessory start refused: {e}");
            None
        }
    }
}

/// "Lenovo" + "TB321FU" → "Lenovo TB321FU"; a product string that already names the maker stays as is.
fn usb_name(handle: &DeviceHandle<Context>, desc: &rusb::DeviceDescriptor) -> String {
    let maker = handle.read_manufacturer_string_ascii(desc).unwrap_or_default();
    let product = handle.read_product_string_ascii(desc).unwrap_or_default();
    match (maker.trim(), product.trim()) {
        (_, "") => "Android phone".into(),
        (m, p) if m.is_empty() || p.to_lowercase().starts_with(&m.to_lowercase()) => p.into(),
        (m, p) => format!("{m} {p}"),
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

/// One link, from claim to the cable going (or a broken stream / `bye` / leaving Cable mode).
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
    let mut s = Session {
        handshake: proto::Handshake::new(&config.desktop_id, crate::pairing::new_nonce()),
        trusted: false,
        asked_at: None,
        phone_name: None,
        media: UsbMedia::new(config.app.clone()),
    };
    let mut hello = s.hello(config);
    let mut last_hello: Option<Instant> = None;
    let mut reader = proto::Reader::default();
    let mut buf = vec![0u8; BULK_READ_BYTES];
    let (mut bytes_window, mut window_start) = (0usize, Instant::now());
    let end = loop {
        // Commands first: Terminate queues Forget and then stops the thread, and its `bye forget` must win.
        match config.commands.try_recv() {
            Ok(Command::Answer(allow)) => s.answer(config, &link, allow),
            Ok(Command::Forget) => {
                link.send(&proto::bye(Some(proto::BYE_FORGET)));
                break "Terminate on the deck".to_string();
            }
            Err(TryRecvError::Empty | TryRecvError::Disconnected) => {}
        }
        if config.stop.load(Ordering::Relaxed) {
            link.send(&proto::bye(None));
            break "the receiver left Cable mode".to_string();
        }
        if s.asked_at.is_some_and(|t| t.elapsed() >= ALLOW_TIMEOUT) {
            log::info!(target: "usb", "no answer in {} s — read as Don't Allow", ALLOW_TIMEOUT.as_secs());
            s.answer(config, &link, false);
        }
        if !s.trusted && s.asked_at.is_none() && last_hello.map_or(true, |t| t.elapsed() >= HELLO_EVERY) {
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
            if s.media.casting {
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
            Err(broken) => {
                if broken.peer_version.is_some_and(|v| v < proto::LINK_VERSION) {
                    announce_cable(&config.app, "old-phone", None);
                }
                break format!("broken stream: {}", broken.why);
            }
        };
        let mut close = None;
        for frame in frames {
            match frame.kind {
                proto::KIND_CONTROL => match s.on_control(config, &link, &frame) {
                    Control::Continue => {}
                    Control::Close(why) => close = Some(why),
                    Control::Restart => {
                        // A new app instance on the same cable. Mid-handshake, the
                        // hello already sent stays valid (the app may be answering
                        // it right now: a new nonce here refused that answer, found
                        // live 2026-11-18); only a session that was trusted starts
                        // over with a fresh nonce, and its media ends. An open
                        // prompt closes: the enrolment starts over.
                        if s.trusted {
                            log::info!(target: "usb", "the app reopened the accessory — the old session ends, handshake starts over");
                            s.media.stop();
                            s.trusted = false;
                            s.handshake.restart(crate::pairing::new_nonce());
                            hello = s.hello(config);
                        } else {
                            log::info!(target: "usb", "the app opened the accessory — hello sent again");
                            if s.asked_at.take().is_some() {
                                announce_cable(&config.app, "found", s.phone_name.as_deref());
                            }
                            s.handshake.drop_enrolment();
                        }
                        last_hello = None;
                    }
                },
                _ if !s.trusted => log::warn!(target: "usb", "media before authentication dropped"),
                proto::KIND_VIDEO => s.media.video(&frame),
                proto::KIND_GAME_AUDIO => s.media.game_audio(&frame.payload),
                proto::KIND_MIC_AUDIO => s.media.mic_audio(&frame.payload),
                other => log::debug!(target: "usb", "frame kind {other} ignored"),
            }
        }
        if let Some(why) = close {
            break why;
        }
    };
    log::info!(target: "usb", "link ended: {end}");
    s.media.stop();
    if s.trusted {
        announce_mobile(&config.app, json!({ "state": "disconnected", "link": "usb" }));
    }
    if !config.stop.load(Ordering::Relaxed) {
        announce_cable(&config.app, "waiting", None);
    }
    let _ = handle.release_interface(ep.interface);
}

/// `pairing:mobile-state` for the deck and the tray — what signaling sends for a Wi-Fi phone.
fn announce_mobile(app: &tauri::AppHandle, state: Value) {
    crate::on_mobile_state(app, state);
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
    Close(String),
    /// The phone's `ready`: start the handshake over.
    Restart,
}

/// One attach's state: the handshake, the open prompt, and the cast's media.
struct Session {
    handshake: proto::Handshake,
    trusted: bool,
    /// When the Allow prompt opened (None: no prompt).
    asked_at: Option<Instant>,
    phone_name: Option<String>,
    media: UsbMedia,
}

impl Session {
    fn hello(&self, config: &Config) -> Vec<u8> {
        proto::control(&proto::hello(&config.desktop_id, &config.desktop_name, self.handshake.desktop_nonce()))
    }

    /// The deck and the tray leave the guide, as on a Wi-Fi connect; a hidden window comes back.
    fn connected(&mut self, config: &Config, name: &str) {
        self.trusted = true;
        announce_cable(&config.app, "connected", Some(name));
        announce_mobile(&config.app, json!({ "state": "connected", "name": name, "link": "usb" }));
        let app = config.app.clone();
        let _ = config.app.run_on_main_thread(move || crate::tray::show_main_window(&app));
    }

    /// The user's Allow / Don't Allow (or the prompt's timeout).
    fn answer(&mut self, config: &Config, link: &Link, allow: bool) {
        if self.asked_at.take().is_none() {
            return; // nothing open: a late click
        }
        if allow {
            let Some((reply, enrolled)) = self.handshake.allow() else { return };
            let entry = crate::pairing::TrustEntry { device_id: enrolled.device_id, name: enrolled.name, key: enrolled.key };
            if let Err(e) = crate::pairing::store_pairing(&config.paths, crate::pairing::Link::Cable, &entry) {
                // Not stored: the phone must not keep a key this side forgot.
                log::error!(target: "usb", "cable pairing not saved: {e}");
                link.send(&proto::error(proto::ERROR_UNKNOWN_DEVICE));
                return;
            }
            link.send(&reply);
            log::info!(target: "usb", "phone allowed over the cable — cable pairing saved (deviceId={}…)", crate::protocol::short_id(&entry.device_id));
            self.connected(config, &entry.name);
        } else if let Some(refusal) = self.handshake.deny() {
            link.send(&refusal);
            log::info!(target: "usb", "phone not allowed over the cable");
            announce_cable(&config.app, "denied", self.phone_name.as_deref());
        }
    }

    fn on_control(&mut self, config: &Config, link: &Link, frame: &Frame) -> Control {
        let Ok(message) = serde_json::from_slice::<Value>(&frame.payload) else {
            log::warn!(target: "usb", "unreadable control message dropped");
            return Control::Continue;
        };
        let paired = crate::pairing::read_trust(&config.paths.cable_trust);
        let trust = paired.as_ref().map(|e| proto::Trust { device_id: &e.device_id, key: &e.key });
        match message["type"].as_str().unwrap_or_default() {
            "enrol" if !self.trusted => {
                log::info!(target: "usb", "a phone without a cable key for this Mac — enrolling");
                match self.handshake.on_enrol(&message, trust.as_ref(), &proto::Ephemeral::random()) {
                    proto::Outcome::Reply(reply) => {
                        if reply["code"] == proto::ERROR_BUSY {
                            log::info!(target: "usb", "refused: this Mac is paired with another phone");
                            announce_cable(&config.app, "busy", None);
                        }
                        link.send(&reply);
                    }
                    proto::Outcome::Close(why) => return Control::Close(why),
                    other => log::warn!(target: "usb", "enrol: unexpected {other:?}"),
                }
            }
            "auth" if !self.trusted => match self.handshake.on_auth(&message, trust.as_ref()) {
                proto::Outcome::Trusted { reply } => {
                    link.send(&reply);
                    log::info!(target: "usb", "phone authenticated over USB with its cable key");
                    let name = paired.as_ref().map_or("Android device".to_string(), |e| e.name.clone());
                    self.connected(config, &name);
                }
                proto::Outcome::Ask { name } => {
                    log::info!(target: "usb", "asking the user to allow the phone");
                    self.asked_at = Some(Instant::now());
                    announce_cable(&config.app, "asking", Some(&name));
                    self.phone_name = Some(name);
                    let app = config.app.clone();
                    let _ = config.app.run_on_main_thread(move || crate::tray::show_main_window(&app));
                }
                proto::Outcome::Reply(refusal) => {
                    link.send(&refusal);
                    log::warn!(target: "usb", "phone refused: no cable pairing for it, or a wrong proof");
                }
                proto::Outcome::Close(why) => return Control::Close(why),
            },
            "cast-start" if self.trusted => {
                log::info!(
                    target: "usb", "cast starts over USB: {}x{} @{} fps, audio {}",
                    message["w"], message["h"], message["fps"], message["audio"]
                );
                self.media.start();
                // The deck's chips and settings line: the same shape as Wi-Fi's session-info.
                self.media.info = json!({
                    "profile": "usb", "width": message["w"], "height": message["h"], "fps": message["fps"],
                    "gameAudio": !message["audio"].is_null(), "mic": message["mic"].as_bool().unwrap_or(false),
                });
                announce_mobile(&config.app, json!({ "state": "session-info", "info": self.media.info }));
            }
            "mic" if self.trusted => {
                let on = message["on"].as_bool().unwrap_or(false);
                log::info!(target: "usb", "mic {} over USB", if on { "on" } else { "off" });
                if on {
                    crate::audio::reset_session(crate::audio::Track::Mic);
                }
                self.media.info["mic"] = json!(on);
                announce_mobile(&config.app, json!({ "state": "session-info", "info": self.media.info }));
            }
            "cast-stop" if self.trusted => {
                log::info!(target: "usb", "cast stopped by the phone");
                self.media.stop();
            }
            "bye" => {
                if message["reason"] == proto::BYE_FORGET {
                    // Terminate on the phone (usb-link.md §Cable trust): back to the choice.
                    if let Err(e) = crate::pairing::forget_pairing(&config.paths, crate::pairing::Link::Cable) {
                        log::warn!(target: "usb", "bye forget: {e}");
                    }
                    log::info!(target: "usb", "the phone ended the cable pairing (bye forget)");
                    announce_mobile(&config.app, json!({ "state": "forgotten", "link": "usb" }));
                }
                return Control::Close("the phone said bye".into());
            }
            "ready" => return Control::Restart,
            other => log::debug!(target: "usb", "control {other:?} ignored"),
        }
        Control::Continue
    }
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
    last_picture: Option<crate::video::ReceivedPicture>,
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
            crate::video::announce_input(&self.app, false);
            crate::video::announce_picture(&self.app, None);
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
            crate::video::announce_input(&self.app, true);
        }
        crate::video::poll_window_visibility(&self.app, &mut self.last_visibility_poll);
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
            let picture = crate::video::ReceivedPicture { width: w, height: h, fps: fps.round() as u32 };
            if self.last_picture != Some(picture) {
                self.last_picture = Some(picture);
                crate::video::announce_picture(&self.app, self.last_picture);
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
