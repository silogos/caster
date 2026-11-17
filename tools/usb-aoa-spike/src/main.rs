//! USB cast spike (docs/proposals/usb-cast.md §8, step 1).
//!
//! Switches an Android device into Android Open Accessory mode with caster's
//! identity strings, claims the accessory's bulk endpoints, and reads §4.3
//! frames, logging byte rate and per-kind counts. Optionally dumps the video
//! payload (Annex-B) to a file. Spike code: not part of either app.

use rusb::{Context, Device, DeviceHandle, Direction, TransferType, UsbContext};
use std::fs::File;
use std::io::{Read, Write};
use std::process::{Child, ChildStdin, Command, Stdio};
use std::time::{Duration, Instant};

// --- AOA protocol (source.android.com/docs/core/interaction/accessories/aoa) ---
const AOA_GET_PROTOCOL: u8 = 51;
const AOA_SEND_STRING: u8 = 52;
const AOA_START: u8 = 53;
const REQ_VENDOR_IN: u8 = 0xC0;
const REQ_VENDOR_OUT: u8 = 0x40;
const GOOGLE_VID: u16 = 0x18D1;
const ACCESSORY_PIDS: [u16; 6] = [0x2D00, 0x2D01, 0x2D02, 0x2D03, 0x2D04, 0x2D05];
const CONTROL_TIMEOUT: Duration = Duration::from_millis(1000);
const REENUMERATE_WAIT: Duration = Duration::from_secs(10);

/// Vendor IDs probed when no --vid is given (never send vendor requests to
/// arbitrary devices such as keyboards).
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

/// Caster's accessory identity (indices 0..=5 per the AOA spec).
const IDENTITY: [&str; 6] = [
    "Caster",                          // manufacturer
    "Caster Receiver",                 // model
    "Caster desktop receiver (spike)", // description
    "0.1",                             // version
    "https://github.com/silogos/caster", // URI shown when no app handles it
    "spike-0001",                      // serial
];

// --- §4.3 frame header ---
const HEADER_LEN: usize = 16;
const LINK_VERSION: u8 = 1;
const MAX_PAYLOAD: usize = 4 * 1024 * 1024;
const KIND_NAMES: [&str; 4] = ["control", "video", "game-audio", "mic-audio"];
const BULK_READ_SIZE: usize = 256 * 1024;

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let vid = arg_value(&args, "--vid").map(|v| u16::from_str_radix(v.trim_start_matches("0x"), 16).expect("hex vid"));
    let dump = arg_value(&args, "--dump").map(|p| File::create(p).expect("create dump file"));
    let probe = args.iter().any(|a| a == "--probe");
    let seconds: u64 = arg_value(&args, "--seconds").map(|s| s.parse().expect("seconds")).unwrap_or(30);
    let ctx = Context::new().expect("libusb init");

    if args.iter().any(|a| a == "list") {
        list(&ctx);
        return;
    }

    let handle = match find_accessory(&ctx) {
        Some(h) => {
            log("INFO", "device already in accessory mode");
            h
        }
        None => {
            let dev = find_android(&ctx, vid).unwrap_or_else(|| {
                log("ERROR", "no Android device found (plug it in, unlock it, or pass --vid <hex>)");
                std::process::exit(2)
            });
            switch_to_accessory(&dev);
            wait_for_accessory(&ctx)
        }
    };
    let decoder = if probe { Some(spawn_probe_decoder()) } else { None };
    stream(handle, dump, decoder, Duration::from_secs(seconds));
}

fn arg_value<'a>(args: &'a [String], name: &str) -> Option<&'a str> {
    args.iter().position(|a| a == name).and_then(|i| args.get(i + 1)).map(|s| s.as_str())
}

fn log(level: &str, msg: &str) {
    let t = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap();
    println!("{}.{:03} {level} aoa: {msg}", t.as_secs(), t.subsec_millis());
}

fn list(ctx: &Context) {
    for d in ctx.devices().expect("devices").iter() {
        if let Ok(desc) = d.device_descriptor() {
            println!("{:04x}:{:04x} bus {} addr {}", desc.vendor_id(), desc.product_id(), d.bus_number(), d.address());
        }
    }
}

fn find_accessory(ctx: &Context) -> Option<DeviceHandle<Context>> {
    for d in ctx.devices().ok()?.iter() {
        let desc = d.device_descriptor().ok()?;
        if desc.vendor_id() == GOOGLE_VID && ACCESSORY_PIDS.contains(&desc.product_id()) {
            log("INFO", &format!("accessory {:04x}:{:04x}", desc.vendor_id(), desc.product_id()));
            return d.open().ok();
        }
    }
    None
}

fn find_android(ctx: &Context, vid: Option<u16>) -> Option<Device<Context>> {
    ctx.devices().ok()?.iter().find(|d| {
        d.device_descriptor()
            .map(|desc| match vid {
                Some(v) => desc.vendor_id() == v,
                None => ANDROID_VIDS.contains(&desc.vendor_id()),
            })
            .unwrap_or(false)
    })
}

fn switch_to_accessory(dev: &Device<Context>) {
    let desc = dev.device_descriptor().unwrap();
    log("INFO", &format!("android candidate {:04x}:{:04x}", desc.vendor_id(), desc.product_id()));
    let h = dev.open().unwrap_or_else(|e| {
        log("ERROR", &format!("open failed: {e}"));
        std::process::exit(3)
    });
    let mut buf = [0u8; 2];
    let n = h
        .read_control(REQ_VENDOR_IN, AOA_GET_PROTOCOL, 0, 0, &mut buf, CONTROL_TIMEOUT)
        .unwrap_or_else(|e| {
            log("ERROR", &format!("GET_PROTOCOL failed: {e} (device may not support AOA)"));
            std::process::exit(4)
        });
    let proto = if n == 2 { u16::from_le_bytes(buf) } else { 0 };
    log("INFO", &format!("AOA protocol version {proto}"));
    if proto < 1 {
        log("ERROR", "device does not support AOA");
        std::process::exit(4);
    }
    for (i, s) in IDENTITY.iter().enumerate() {
        let mut bytes = s.as_bytes().to_vec();
        bytes.push(0);
        h.write_control(REQ_VENDOR_OUT, AOA_SEND_STRING, 0, i as u16, &bytes, CONTROL_TIMEOUT)
            .unwrap_or_else(|e| {
                log("ERROR", &format!("SEND_STRING {i} failed: {e}"));
                std::process::exit(5)
            });
    }
    h.write_control(REQ_VENDOR_OUT, AOA_START, 0, 0, &[], CONTROL_TIMEOUT)
        .unwrap_or_else(|e| {
            log("ERROR", &format!("START failed: {e}"));
            std::process::exit(6)
        });
    log("INFO", "START sent; waiting for re-enumeration");
}

fn wait_for_accessory(ctx: &Context) -> DeviceHandle<Context> {
    let t0 = Instant::now();
    while t0.elapsed() < REENUMERATE_WAIT {
        std::thread::sleep(Duration::from_millis(200));
        if let Some(h) = find_accessory(ctx) {
            log("INFO", &format!("re-enumerated after {} ms", t0.elapsed().as_millis()));
            return h;
        }
    }
    log("ERROR", "device did not re-enumerate as an accessory");
    std::process::exit(7)
}

fn bulk_endpoints(h: &DeviceHandle<Context>) -> (u8, u8, u8) {
    let cfg = h.device().active_config_descriptor().expect("config");
    // Interface 0 is the accessory interface on every 2D0x PID.
    let iface = cfg.interfaces().next().expect("interface 0");
    let alt = iface.descriptors().next().expect("alt setting");
    let (mut ep_in, mut ep_out) = (0, 0);
    for ep in alt.endpoint_descriptors() {
        if ep.transfer_type() == TransferType::Bulk {
            match ep.direction() {
                Direction::In => ep_in = ep.address(),
                Direction::Out => ep_out = ep.address(),
            }
        }
    }
    (alt.interface_number(), ep_in, ep_out)
}

// --- latency probe: decode via ffmpeg/VideoToolbox, log black/white flips in
// the format tools/latency/measure.py reads (`video <colour> decoded_ms=… hold_ms=…`).
const PROBE_W: usize = 16;
const PROBE_H: usize = 10;
const WHITE_THRESHOLD: f64 = 128.0;
const ACCESS_UNIT_DELIMITER: [u8; 6] = [0, 0, 0, 1, 0x09, 0xF0];

fn wall_ms() -> f64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_secs_f64() * 1000.0
}

fn spawn_probe_decoder() -> (Child, ChildStdin) {
    let mut child = Command::new("ffmpeg")
        .args([
            "-hide_banner", "-loglevel", "error",
            "-hwaccel", "videotoolbox", "-threads", "1",
            // not -fflags nobuffer: it makes the raw h264 demuxer drop frames
            "-flags", "low_delay",
            "-probesize", "32", "-analyzeduration", "0",
            "-f", "h264", "-i", "-",
            "-vf", &format!("scale={PROBE_W}:{PROBE_H},format=gray"),
            "-fps_mode", "passthrough", // raw h264 has no timestamps; never dup/drop to a CFR
            "-f", "rawvideo", "-",
        ])
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .spawn()
        .expect("spawn ffmpeg");
    let stdin = child.stdin.take().unwrap();
    let mut stdout = child.stdout.take().unwrap();
    std::thread::spawn(move || {
        let mut frame = [0u8; PROBE_W * PROBE_H];
        let mut last: Option<bool> = None;
        while stdout.read_exact(&mut frame).is_ok() {
            // centre half of the picture (the strip along the top changes every frame)
            let mut sum = 0u64;
            let mut n = 0u64;
            for y in PROBE_H / 4..PROBE_H * 3 / 4 {
                for x in PROBE_W / 4..PROBE_W * 3 / 4 {
                    sum += frame[y * PROBE_W + x] as u64;
                    n += 1;
                }
            }
            let white = sum as f64 / n as f64 > WHITE_THRESHOLD;
            if last != Some(white) {
                println!("video {} decoded_ms={:.1} hold_ms=0", if white { "white" } else { "black" }, wall_ms());
                last = Some(white);
            }
        }
    });
    (child, stdin)
}

fn stream(mut h: DeviceHandle<Context>, mut dump: Option<File>, mut decoder: Option<(Child, ChildStdin)>, run_for: Duration) {
    let (iface, ep_in, ep_out) = bulk_endpoints(&h);
    h.claim_interface(iface).unwrap_or_else(|e| {
        log("ERROR", &format!("claim interface {iface} failed: {e}"));
        std::process::exit(8)
    });
    log("INFO", &format!("claimed interface {iface}, bulk in {ep_in:#04x} out {ep_out:#04x}"));

    // Hello control frame so the phone knows the desktop side is reading.
    let hello = br#"{"type":"hello","link":1}"#;
    let mut frame = header(0, 0, 0, hello.len() as u32).to_vec();
    frame.extend_from_slice(hello);
    match h.write_bulk(ep_out, &frame, CONTROL_TIMEOUT) {
        Ok(_) => log("INFO", "sent hello"),
        Err(e) => log("WARN", &format!("hello not accepted yet: {e}")),
    }

    let mut buf = vec![0u8; BULK_READ_SIZE];
    let mut pending: Vec<u8> = Vec::new();
    let (mut bytes_window, mut counts) = (0usize, [0u32; 4]);
    let mut last_pts: Option<u64> = None;
    let mut intervals: Vec<f64> = Vec::new();
    let mut last_arrival: Option<Instant> = None;
    let mut config: Option<Vec<u8>> = None;
    let mut arrivals: Vec<f64> = Vec::new();
    let (t0, mut window) = (Instant::now(), Instant::now());
    while t0.elapsed() < run_for {
        match h.read_bulk(ep_in, &mut buf, Duration::from_millis(500)) {
            Ok(n) => {
                bytes_window += n;
                pending.extend_from_slice(&buf[..n]);
            }
            Err(rusb::Error::Timeout) => {}
            Err(e) => {
                log("ERROR", &format!("bulk read failed: {e}"));
                break;
            }
        }
        while pending.len() >= HEADER_LEN {
            let (ver, kind, flags, pts, len) = parse_header(&pending[..HEADER_LEN]);
            if ver != LINK_VERSION || len as usize > MAX_PAYLOAD || kind as usize >= KIND_NAMES.len() {
                log("ERROR", &format!("bad header v{ver} kind {kind} len {len}; stream desynced"));
                return;
            }
            if pending.len() < HEADER_LEN + len as usize {
                break;
            }
            let payload: Vec<u8> = pending.drain(..HEADER_LEN + len as usize).skip(HEADER_LEN).collect();
            counts[kind as usize] += 1;
            if kind == 0 {
                log("INFO", &format!("control: {}", String::from_utf8_lossy(&payload)));
            }
            if kind == 1 {
                if flags & 0x2 == 0 {
                    if let Some(p) = last_pts {
                        intervals.push((pts.saturating_sub(p)) as f64 / 1000.0);
                    }
                    last_pts = Some(pts);
                    if let Some(a) = last_arrival {
                        arrivals.push(a.elapsed().as_secs_f64() * 1000.0);
                    }
                    last_arrival = Some(Instant::now());
                }
                if flags & 0x2 != 0 {
                    config = Some(payload.clone());
                }
                if let Some((_, stdin)) = decoder.as_mut() {
                    // Repeat SPS/PPS before every keyframe, as a receiver must
                    // (a decoder that starts late never saw the first config).
                    if flags & 0x1 != 0 {
                        if let Some(c) = config.as_ref() {
                            stdin.write_all(c).ok();
                        }
                    }
                    stdin.write_all(&payload).ok();
                    // An access-unit delimiter closes this frame for ffmpeg's
                    // h264 parser now, instead of when the next frame arrives.
                    stdin.write_all(&ACCESS_UNIT_DELIMITER).ok();
                    stdin.flush().ok();
                }
                if let Some(f) = dump.as_mut() {
                    f.write_all(&payload).ok();
                }
            }
        }
        if window.elapsed() >= Duration::from_secs(1) {
            let secs = window.elapsed().as_secs_f64();
            log("INFO", &format!(
                "{:.2} Mbit/s · frames {}",
                bytes_window as f64 * 8.0 / secs / 1e6,
                KIND_NAMES.iter().zip(counts).map(|(k, c)| format!("{k}={c}")).collect::<Vec<_>>().join(" ")
            ));
            bytes_window = 0;
            counts = [0; 4];
            window = Instant::now();
        }
    }
    report("video pts interval", &mut intervals);
    report("video arrival interval", &mut arrivals);
    if let Some((mut child, stdin)) = decoder {
        drop(stdin);
        std::thread::sleep(Duration::from_millis(300));
        child.kill().ok();
    }
    h.release_interface(iface).ok();
}

/// Interval percentiles plus the spread between arrival and capture spacing.
fn report(name: &str, v: &mut Vec<f64>) {
    if v.is_empty() {
        return;
    }
    v.sort_by(|a, b| a.partial_cmp(b).unwrap());
    let pct = |p: f64| v[((v.len() - 1) as f64 * p) as usize];
    log("INFO", &format!(
        "{name} ms: n={} p50={:.1} p95={:.1} p99={:.1} max={:.1}",
        v.len(), pct(0.5), pct(0.95), pct(0.99), pct(1.0)
    ));
}

fn header(kind: u8, flags: u16, pts_us: u64, len: u32) -> [u8; HEADER_LEN] {
    let mut b = [0u8; HEADER_LEN];
    b[0] = LINK_VERSION;
    b[1] = kind;
    b[2..4].copy_from_slice(&flags.to_be_bytes());
    b[4..12].copy_from_slice(&pts_us.to_be_bytes());
    b[12..16].copy_from_slice(&len.to_be_bytes());
    b
}

fn parse_header(b: &[u8]) -> (u8, u8, u16, u64, u32) {
    (
        b[0],
        b[1],
        u16::from_be_bytes([b[2], b[3]]),
        u64::from_be_bytes(b[4..12].try_into().unwrap()),
        u32::from_be_bytes(b[12..16].try_into().unwrap()),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn header_round_trips() {
        let h = header(1, 0x3, 123_456_789, 4096);
        assert_eq!(parse_header(&h), (LINK_VERSION, 1, 0x3, 123_456_789, 4096));
    }
}
