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

struct PairingSession {
    sid: String,
    secret: Vec<u8>,
    payload: Value,
    qr_data_url: String,
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
    use image::ImageFormat;
    let code = qrcode::QrCode::new(payload.as_bytes()).map_err(|e| e.to_string())?;
    let modules = code.width();
    let mut img = image::GrayImage::new(modules as u32, modules as u32);
    for (i, color) in code.to_colors().into_iter().enumerate() {
        let v = if color == qrcode::Color::Dark { 0 } else { 255 };
        img.put_pixel((i % modules) as u32, (i / modules) as u32, image::Luma([v]));
    }
    let mut png = Vec::new();
    image::DynamicImage::ImageLuma8(img)
        .write_to(&mut IoCursor::new(&mut png), ImageFormat::Png)
        .map_err(|e| e.to_string())?;
    Ok(format!("data:image/png;base64,{}", b64url(&png)))
}


/// The pairing session view for the UI (mirror of PairingSessionView).
pub fn pairing_payload_json() -> Result<Value, String> {
    let session = new_pairing_session()?;
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

    let session = new_pairing_session()?;
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
                send_envelope(&mut sink, &mut seq, &sid, "ice", json!({
                    "pc": "media",
                    "candidate": { "candidate": candidate_line },
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
                        let mut bytes = [0u8; NONCE_BYTES];
                        rand::rngs::OsRng.fill_bytes(&mut bytes);
                        nonce = b64url(&bytes);
                        send_envelope(&mut sink, &mut seq, &sid, "challenge", json!({ "n": nonce })).await?;
                        let resume = envelope["payload"]["resume"].as_bool().unwrap_or(false);
                        println!("[signaling] hello (resume={resume}) — challenge sent");
                    }
                    "auth" => {
                        let mac = envelope["payload"]["mac"].as_str().unwrap_or("");
                        let received = b64url_decode(mac).map_err(|e| e.to_string())?;
                        let mut message = Vec::new();
                        message.extend_from_slice(sid.as_bytes());
                        message.extend_from_slice(nonce.as_bytes());
                        let expected = hmac_bytes(&session.secret, &message);
                        if !constant_time_eq(&received, &expected) {
                            send_envelope(&mut sink, &mut seq, &sid, "error", json!({ "code": "bad-auth" })).await?;
                            return Err("HMAC verify failed — close 1008 bad-auth".into());
                        }
                        authorized = true;
                        send_envelope(
                            &mut sink,
                            &mut seq,
                            &sid,
                            "auth-ok",
                            json!({ "name": "desktop-native", "proto": 2, "desktopId": "desktop-native", "hosts": hosts }),
                        ).await?;
                        let _ = app.emit("pairing:mobile-state", json!({ "state": "connected", "name": "Android device" }));
                        println!("[signaling] AUTH OK — authorized");
                    }
                    "sdp-offer" if authorized => {
                        let pc = envelope["payload"]["pc"].as_str().unwrap_or("").to_string();
                        let sdp_text = envelope["payload"]["sdp"].as_str().unwrap_or("").to_string();
                        let payload = answer_offer(app, answerer, &cand_tx, &pc, &sdp_text).await?;
                        send_envelope(&mut sink, &mut seq, &sid, "sdp-answer", payload).await?;
                    }
                    "ice" if authorized => {
                        let candidate = envelope["payload"]["candidate"]["candidate"].as_str().unwrap_or("").to_string();
                        if !candidate.is_empty() {
                            answerer.add_remote_candidate(&candidate).await;
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
                        let _ = app.emit("pairing:mobile-state", json!({ "state": "disconnected", "name": "Android device" }));
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


