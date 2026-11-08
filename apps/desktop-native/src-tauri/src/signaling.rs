//! M0-r signaling — the pairing session + the WebSocket server the phone
//! pairs and negotiates against. Ported from the M0 spike, now wired to the
//! livekit::webrtc answerer and the Tauri event system. Numbers and payloads
//! only in logs — never the session secret (AGENTS.md).

use base64::Engine;
use futures_util::{SinkExt, StreamExt};
use hmac::{Mac, Hmac};
use rand::RngCore;
use serde_json::{json, Value};
use sha2::Sha256;
use std::io::Cursor as IoCursor;
use std::net::UdpSocket;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use tauri::Emitter;
use tokio_tungstenite::tungstenite::Message;

use crate::webrtc::MediaAnswerer;

const SESSION_TTL_SECONDS: u64 = 600;
const NONCE_BYTES: usize = 16;
const SESSION_ID_BYTES: usize = 16;
const SESSION_SECRET_BYTES: usize = 32;
const SIGNALING_PORT: u16 = 52_341;
const SIGNALING_PATH: &str = "/zfc/v1";
const HEARTBEAT_INTERVAL_MS: u64 = 5_000;
const HEARTBEAT_TIMEOUT_MS: u64 = 15_000;

type HmacSha256 = Hmac<Sha256>;

fn b64url(data: &[u8]) -> String {
    use base64::Engine;
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(data)
}

fn b64url_decode(s: &str) -> Result<Vec<u8>, base64::DecodeError> {
    use base64::Engine;
    base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(s)
}

fn hmac_bytes(key: &[u8], message: &[u8]) -> Vec<u8> {
    let mut mac = HmacSha256::new_from_slice(key).expect("hmac key");
    mac.update(message);
    mac.finalize().into_bytes().to_vec()
}

fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    let mut diff = 0u8;
    for (x, y) in a.iter().zip(b.iter()) {
        diff |= x ^ y;
    }
    diff == 0
}

fn lan_ipv4_hosts() -> Vec<String> {
    let mut hosts = Vec::new();
    if let Ok(ifaces) = if_addrs::get_if_addrs() {
        for iface in ifaces {
            if let if_addrs::IfAddr::V4(v4) = iface.addr {
                let ip = v4.ip.to_string();
                if !ip.starts_with("127.") {
                    hosts.push(ip);
                }
            }
        }
    }
    hosts.sort();
    hosts.dedup();
    hosts
}

#[derive(Clone)]
struct PairingSession {
    sid: String,
    secret: Vec<u8>,
    payload: Value,
    qr_data_url: String,
}

impl PairingSession {
    fn clone_impl(&self) -> PairingSession {
        self.clone()
    }
}

fn new_pairing_session() -> Result<PairingSession, String> {
    let hosts = lan_ipv4_hosts();
    if hosts.is_empty() {
        return Err("no LAN IPv4 address found".into());
    }
    let mut sid = [0u8; SESSION_ID_BYTES];
    let mut secret = vec![0u8; SESSION_SECRET_BYTES];
    rand::rngs::OsRng.fill_bytes(&mut sid);
    rand::rngs::OsRng.fill_bytes(&mut secret);
    let e = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .expect("clock")
        .as_secs()
        + SESSION_TTL_SECONDS;
    let payload = format!(
        r#"{{"v":1,"t":"zfc","h":{},"p":{},"s":"{}","k":"{}","e":{}}}"#,
        serde_json::to_string(&hosts).expect("hosts"),
        SIGNALING_PORT,
        b64url(&sid),
        b64url(&secret),
        e
    );
    let qr_data_url = qr_data_url(&payload)?;
    Ok(PairingSession {
        sid: b64url(&sid),
        secret,
        payload: serde_json::from_str(&payload).map_err(|e| e.to_string())?,
        qr_data_url,
    })
}

fn qr_data_url(payload: &str) -> Result<String, String> {
    let image = qrcode::QrCode::new(payload.as_bytes())
        .map_err(|e| e.to_string())?
        .render::<image::Luma<u8>>()
        .quiet_zone(true)
        .build();
    let mut png = Vec::new();
    image
        .write_to(&mut IoCursor::new(&mut png), image::ImageFormat::Png)
        .map_err(|e| e.to_string())?;
    Ok(format!("data:image/png;base64,{}", b64url(&png)))
}


/// The trust store — the SAME file the Electron desktop uses (ADR-006
/// format), so a phone that trusts one desktop resumes against the other.
const TRUST_PATH: &str = "Library/Application Support/Zero-Friction Local Cast/trust.json";

struct TrustEntry {
    device_id: String,
    name: String,
    key: Vec<u8>, // 32 bytes
}

fn read_trust() -> Option<TrustEntry> {
    let path = format!("{}/{}", std::env::var("HOME").ok()?, TRUST_PATH);
    let text = std::fs::read_to_string(path).ok()?;
    let doc: Value = serde_json::from_str(&text).ok()?;
    if doc["version"].as_i64() != Some(1) {
        return None;
    }
    let device_id = doc["deviceId"].as_str()?.to_string();
    let name = doc["name"].as_str().unwrap_or("Android device").to_string();
    let key = b64url_decode(doc["key"].as_str()?).ok()?;
    if device_id.is_empty() || device_id.len() > 128 || key.len() != 32 {
        return None;
    }
    Some(TrustEntry { device_id, name, key })
}

fn write_trust(entry: &TrustEntry) {
    let Ok(home) = std::env::var("HOME") else { return };
    let path = format!("{home}/{TRUST_PATH}");
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs();
    let doc = json!({
        "version": 1,
        "deviceId": entry.device_id,
        "name": entry.name,
        "key": b64url(&entry.key),
        "createdAt": now,
        "lastConnectedAt": now,
    });
    let _ = std::fs::write(&path, doc.to_string());
}

/// trustKey = HKDF-SHA256(ikm = session secret, salt = ASCII(sid),
/// info = "zfc-trust-v1", L = 32) — both sides derive the same key at
/// pairing; it is never transmitted (handshake.ts contract).
fn derive_trust_key(session_secret: &[u8], sid: &str) -> Vec<u8> {
    use hkdf::Hkdf;
    use sha2::Sha256;
    let hk = Hkdf::<Sha256>::new(Some(sid.as_bytes()), session_secret);
    let mut okm = [0u8; 32];
    hk.expand(b"zfc-trust-v1", &mut okm).expect("32 bytes");
    okm.to_vec()
}

/// The phone's device name from its UA — the trailing "(...)" segment.
fn device_name_from_ua(ua: &str) -> String {
    ua.rsplit('(')
        .next()
        .unwrap_or("")
        .trim_end_matches(')')
        .trim()
        .to_string()
}

/// THE pairing session — one per app run. Both the UI QR and the WS
/// server's HMAC verification MUST read the same secret; generating two
/// sessions (one for the QR, one for auth) made every scan fail with
/// bad-auth (found live in M0-r).
static SESSION: std::sync::Mutex<Option<PairingSession>> = std::sync::Mutex::new(None);

fn current_session() -> Result<PairingSession, String> {
    let mut guard = SESSION.lock().expect("session lock");
    if guard.is_none() {
        let session = new_pairing_session()?;
        // Also write the live QR PNG to /tmp so an external `open` shows
        // THE live session's QR (a stale file once made every scan fail
        // bad-auth — found live in M0-r).
        let payload_text = serde_json::to_string(&session.payload).unwrap_or_default();
        let _ = write_qr_png(&payload_text, "/tmp/zfc-native-qr.png");
        *guard = Some(session);
    }
    Ok(guard.as_ref().expect("just set").clone_impl())
}

fn write_qr_png(payload: &str, path: &str) -> Result<(), String> {
    // The renderer scales modules (default 8px) + quiet zone — a 1px/module
    // image was too small/blurry for the phone's camera after Preview's
    // smooth-scaling (found live: zero scans reached the server).
    let image = qrcode::QrCode::new(payload.as_bytes())
        .map_err(|e| e.to_string())?
        .render::<image::Luma<u8>>()
        .quiet_zone(true)
        .build();
    image.save(path).map_err(|e| e.to_string())
}

/// The pairing session view for the UI (mirror of PairingSessionView).
pub fn pairing_payload_json() -> Result<Value, String> {
    let session = current_session()?;
    Ok(json!({
        "qrDataUrl": session.qr_data_url,
        "payload": session.payload,
        "expiresAt": std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .expect("clock")
            .as_secs()
            + SESSION_TTL_SECONDS,
    }))
}

/// Starts the signaling WS server + the media answerer on a dedicated
/// thread with its own single-threaded tokio runtime — the libwebrtc
/// factory and connections are not `Send`, so they live entirely on this
/// thread. Events cross to the UI via `app.emit()` (thread-safe).
pub fn start(app: tauri::AppHandle, answerer: std::sync::Arc<crate::webrtc::MediaAnswerer>) {
    std::thread::spawn(move || {
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .expect("signaling runtime");
        runtime.block_on(async move {
            if let Err(error) = run(app, answerer).await {
                println!("[signaling] server error: {error:#}");
            }
        });
    });
}

async fn run(app: tauri::AppHandle, answerer: std::sync::Arc<crate::webrtc::MediaAnswerer>) -> Result<(), String> {
    let udp = UdpSocket::bind("0.0.0.0:0")
        .map_err(|e| format!("bind ICE udp socket: {e}"))?;
    let ice_port = udp
        .local_addr()
        .map_err(|e| e.to_string())?
        .port();
    let hosts = lan_ipv4_hosts();
    if hosts.is_empty() {
        return Err("no LAN IPv4 address found".into());
    }
    let candidate_ip: std::net::IpAddr = hosts[0]
        .parse()
        .map_err(|_| format!("LAN host {} is not an IPv4 address", hosts[0]))?;
    let candidate_addr = std::net::SocketAddr::new(candidate_ip, ice_port);
    println!("[signaling] ICE host candidate: {candidate_addr}");

    let listener = tokio::net::TcpListener::bind(("0.0.0.0", SIGNALING_PORT))
        .await
        .map_err(|e| format!("bind signaling port {SIGNALING_PORT}: {e}"))?;
    println!("[signaling] listening on port {SIGNALING_PORT} at {SIGNALING_PATH}");

    let session = current_session()?;
    let _ = app.emit(
        "pairing:session-updated",
        json!({
            "qrDataUrl": session.qr_data_url,
            "payload": session.payload,
            "expiresAt": std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .expect("clock")
                .as_secs()
                + SESSION_TTL_SECONDS,
        }),
    );

    while let Ok((tcp, _addr)) = listener.accept().await {
        println!("[signaling] signaling tcp connected");
        if let Err(error) =
            run_signaling(tcp, &session, hosts.clone(), candidate_addr, &app, &answerer, udp.try_clone().expect("udp clone")).await
        {
            println!("[signaling] signaling ended: {error:#}");
        }
    }
    Ok(())
}

async fn run_signaling(
    tcp: tokio::net::TcpStream,
    session: &PairingSession,
    hosts: Vec<String>,
    candidate_addr: std::net::SocketAddr,
    app: &tauri::AppHandle,
    answerer: &std::sync::Arc<crate::webrtc::MediaAnswerer>,
    udp: UdpSocket,
) -> Result<(), String> {
    let ws = tokio_tungstenite::accept_async(tcp)
        .await
        .map_err(|e| format!("ws upgrade failed: {e}"))?;
    let (mut sink, mut stream) = ws.split();
    let mut seq: u64 = 0;
    let mut sid = String::new();
    let mut nonce = String::new();
    let mut authorized = false;
    let mut last_activity = Instant::now();
    let mut last_heartbeat = Instant::now();
    let mut hello_device_id = String::new();
    let mut hello_resume = false;
    let mut hello_ua = String::new();
    let mut phone_name = "Android device".to_string();
    let _ = &udp;

    // PC → phone ICE candidates surface through this channel once the
    // answerer registers its on_ice callback.
    let (cand_tx, mut cand_rx) = tokio::sync::mpsc::channel::<String>(64);

    async fn send_envelope<S2>(
        sink: &mut futures_util::stream::SplitSink<
            tokio_tungstenite::WebSocketStream<S2>,
            Message,
        >,
        seq: &mut u64,
        sid: &str,
        msg_type: &str,
        payload: Value,
    ) -> Result<(), String>
    where
        S2: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin,
    {
        *seq += 1;
        let frame = json!({
            "v": 1,
            "type": msg_type,
            "seq": *seq,
            "sid": sid,
            "payload": payload,
        });
        sink.send(Message::Text(frame.to_string()))
            .await
            .map_err(|e| e.to_string())
    }

    async fn answer_offer(
        app: &tauri::AppHandle,
        answerer: &std::sync::Arc<crate::webrtc::MediaAnswerer>,
        cand_tx: &tokio::sync::mpsc::Sender<String>,
        pc: &str,
        sdp_text: &str,
    ) -> Result<Value, String> {
        println!("[signaling] sdp-offer for pc={pc} ({} bytes)", sdp_text.len());
        let answer_sdp = answerer
            .answer(app, sdp_text, cand_tx.clone())
            .await
            .map_err(|e| format!("answer failed: {e}"))?;
        println!("[signaling] sdp-answer sent (pc={pc}, {} bytes)", answer_sdp.len());
        let _ = app.emit(
            "pairing:mobile-state",
            json!({ "state": "session-info", "info": { "profile": "balanced", "width": 1280, "height": 720, "fps": 30, "gameAudio": true, "mic": false } }),
        );
        Ok(json!({ "pc": pc, "sdp": answer_sdp }))
    }

    loop {
        tokio::select! {
            Some(candidate_line) = cand_rx.recv() => {
                println!("[signaling] desktop ice candidate → phone: {candidate_line}");
                send_envelope(&mut sink, &mut seq, &sid, "ice", json!({
                    "pc": "media",
                    "candidate": { "candidate": candidate_line, "sdpMid": "0", "sdpMLineIndex": 0 },
                })).await?;
            }
            message = stream.next() => {
                let Some(message) = message else {
                    return Err("signaling socket closed".into());
                };
                last_activity = Instant::now();
                let message = message.map_err(|e| format!("ws read: {e}"))?;
                let Message::Text(text) = message else {
                    return Err("binary frame — close 1003 binary-not-supported".into());
                };
                let envelope: Value = serde_json::from_str(&text)
                    .map_err(|e| format!("envelope not JSON: {e} — close 1002"))?;
                if envelope["v"].as_i64() != Some(1) {
                    send_envelope(&mut sink, &mut seq, &sid, "error", json!({ "code": "bad-version" })).await?;
                    return Err("bad envelope version — close 1002".into());
                }
                let msg_type = envelope["type"].as_str().unwrap_or("").to_string();
                let inbound_sid = envelope["sid"].as_str().unwrap_or("").to_string();
                if !inbound_sid.is_empty() && sid.is_empty() {
                    sid = inbound_sid.clone();
                }

                if last_heartbeat.elapsed() >= Duration::from_millis(HEARTBEAT_INTERVAL_MS) {
                    last_heartbeat = Instant::now();
                    let t = std::time::SystemTime::now()
                        .duration_since(std::time::UNIX_EPOCH)
                        .expect("clock")
                        .as_millis() as i64;
                    send_envelope(&mut sink, &mut seq, &sid, "ping", json!({ "t": t })).await?;
                }
                if last_activity.elapsed() > Duration::from_millis(HEARTBEAT_TIMEOUT_MS) {
                    return Err("heartbeat timeout — close 1000".into());
                }

                match msg_type.as_str() {
                    "hello" => {
                        sid = inbound_sid.clone();
                        hello_device_id = envelope["payload"]["deviceId"].as_str().unwrap_or("").to_string();
                        hello_resume = envelope["payload"]["resume"].as_bool().unwrap_or(false);
                        hello_ua = envelope["payload"]["ua"].as_str().unwrap_or("").to_string();
                        let mut bytes = [0u8; NONCE_BYTES];
                        rand::rngs::OsRng.fill_bytes(&mut bytes);
                        nonce = b64url(&bytes);
                        send_envelope(&mut sink, &mut seq, &sid, "challenge", json!({ "n": nonce })).await?;
                        println!("[signaling] hello (resume={hello_resume}) — challenge sent");
                    }
                    "auth" => {
                        let mac = envelope["payload"]["mac"].as_str().unwrap_or("");
                        let received = b64url_decode(mac).map_err(|e| e.to_string())?;
                        let mut message = Vec::new();
                        message.extend_from_slice(sid.as_bytes());
                        message.extend_from_slice(nonce.as_bytes());
                        // Two keys, two paths (signaling.md): the QR session
                        // secret on a fresh scan, or the stored trustKey on a
                        // resume (sid = the phone's deviceId there).
                        let mut verified = constant_time_eq(&received, &hmac_bytes(&session.secret, &message));
                        let mut via = "qr session secret";
                        if !verified && hello_resume {
                            if let Some(trust) = read_trust() {
                                if trust.device_id == sid {
                                    verified = constant_time_eq(&received, &hmac_bytes(&trust.key, &message));
                                    if verified {
                                        via = "stored trustKey (resume)";
                                        phone_name = trust.name.clone();
                                    }
                                }
                            }
                        }
                        if !verified {
                            send_envelope(&mut sink, &mut seq, &sid, "error", json!({ "code": "bad-auth" })).await?;
                            return Err("HMAC verify failed — close 1008 bad-auth".into());
                        }
                        // A successful QR pairing derives + persists the
                        // trustKey (the phone does the same) — next time the
                        // phone resumes without scanning.
                        if via.starts_with("qr") && !hello_device_id.is_empty() {
                            let trust_key = derive_trust_key(&session.secret, &sid);
                            write_trust(&TrustEntry {
                                device_id: hello_device_id.clone(),
                                name: device_name_from_ua(&hello_ua),
                                key: trust_key,
                            });
                            println!("[signaling] trust saved (deviceId={}…)", &hello_device_id[..8.min(hello_device_id.len())]);
                        }
                        println!("[signaling] authorized via {via}");
                        authorized = true;
                        send_envelope(
                            &mut sink,
                            &mut seq,
                            &sid,
                            "auth-ok",
                            json!({ "name": "desktop-native", "proto": 2, "desktopId": "desktop-native", "hosts": hosts }),
                        ).await?;
                        let _ = app.emit("pairing:mobile-state", json!({ "state": "connected", "name": phone_name }));
                        println!("[signaling] AUTH OK — authorized ({phone_name})");
                    }
                    "sdp-offer" if authorized => {
                        let pc = envelope["payload"]["pc"].as_str().unwrap_or("").to_string();
                        let sdp_text = envelope["payload"]["sdp"].as_str().unwrap_or("").to_string();
                        let payload = answer_offer(app, answerer, &cand_tx, &pc, &sdp_text).await?;
                        send_envelope(&mut sink, &mut seq, &sid, "sdp-answer", payload).await?;
                    }
                    "ice" if authorized => {
                        let cand = envelope["payload"]["candidate"].clone();
                        let line = cand["candidate"].as_str().unwrap_or("").to_string();
                        if line.is_empty() {
                            println!("[signaling] phone finished ice gathering (end-of-gathering marker)");
                        } else {
                            let mid = cand["sdpMid"].as_str().unwrap_or("0").to_string();
                            let mline = cand["sdpMLineIndex"].as_i64().unwrap_or(0) as i32;
                            println!("[signaling] phone ice candidate (mid={mid} mline={mline}): {line}");
                            answerer.add_remote_candidate(&line, &mid, mline).await;
                        }
                    }
                    "ping" => {
                        let t = envelope["payload"]["t"].as_i64().unwrap_or(0);
                        send_envelope(&mut sink, &mut seq, &sid, "pong", json!({ "t": t })).await?;
                    }
                    "pong" => {}
                    "bye" => {
                        let reason = envelope["payload"]["reason"].as_str().unwrap_or("");
                        println!("[signaling] bye ({reason})");
                        let _ = app.emit("pairing:mobile-state", json!({ "state": "disconnected", "name": phone_name }));
                        return Ok(());
                    }
                    other => {
                        send_envelope(&mut sink, &mut seq, &sid, "error", json!({ "code": "bad-message", "msg": format!("unexpected {other}") })).await?;
                    }
                }
            }
        }
    }
}


