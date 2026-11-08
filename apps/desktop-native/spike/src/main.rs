//! M0 interop spike (ADR-014 Fase B gate, apps/desktop-native).
//!
//! Minimal native receiver proving the go/no-go question: can a Rust
//! WebRTC stack (str0m) interop with the pinned libwebrtc 1.3.8 sender?
//! Proof targets: (a) video RTP → depacketized frames flowing (dumped to
//! /tmp/native-video.h264), (b) the `game-audio` DataChannel binary chunks
//! flowing, (c) ICE connectivity with the sender's candidate types.
//!
//! Spike scope: fresh QR pairing only (no trust store/resume), media pc
//! answered (the mic pc offer is ignored — the phone degrades to no-mic),
//! no audio output (the DataChannel payload is counted, not played).
//! All signaling behavior is real: envelope v1, HMAC challenge-response,
//! heartbeats, trickle ICE, the exact QR payload.

use anyhow::{anyhow, Context, Result};
use futures_util::{SinkExt, StreamExt};
use hmac::{Mac, Hmac};
use qrcode::QrCode;
use rand::RngCore;
use serde_json::{json, Value};
use sha2::Sha256;
use std::io::Write;
use std::net::UdpSocket;
use std::sync::mpsc;
use std::time::{Duration, Instant};
use str0m::{change::SdpOffer, Candidate, Event, Input, Output, Rtc};
use tokio_tungstenite::tungstenite::Message;

const SIGNALING_PORT: u16 = 52_341;
const SIGNALING_PATH: &str = "/zfc/v1";
const SESSION_TTL_SECONDS: u64 = 600;
const NONCE_BYTES: usize = 16;
const SESSION_ID_BYTES: usize = 16;
const SESSION_SECRET_BYTES: usize = 32;
const LOG: &str = "native";

type HmacSha256 = Hmac<Sha256>;

// ---------------------------------------------------------------- crypto

fn b64url(data: &[u8]) -> String {
    use base64::Engine;
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(data)
}

fn b64url_decode(s: &str) -> Result<Vec<u8>> {
    use base64::Engine;
    base64::engine::general_purpose::URL_SAFE_NO_PAD
        .decode(s)
        .map_err(Into::into)
}

fn hmac_bytes(key: &[u8], message: &[u8]) -> Vec<u8> {
    let mut mac = HmacSha256::new_from_slice(key).expect("hmac key");
    mac.update(message);
    mac.finalize().into_bytes().to_vec()
}

/// Constant-time compare (the desktop contract — signaling.md).
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

// ---------------------------------------------------------------- pairing

struct PairingSession {
    sid: String,
    secret: Vec<u8>,
    payload: String,
}

fn new_pairing_session(hosts: &[String], port: u16) -> PairingSession {
    let mut sid_bytes = [0u8; SESSION_ID_BYTES];
    let mut secret = vec![0u8; SESSION_SECRET_BYTES];
    rand::rngs::OsRng.fill_bytes(&mut sid_bytes);
    rand::rngs::OsRng.fill_bytes(&mut secret);
    let sid = b64url(&sid_bytes);
    let e = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .expect("clock")
        .as_secs()
        + SESSION_TTL_SECONDS;
    // Compact, exact key order — the phone parses this JSON (pairing.md).
    let payload = format!(
        r#"{{"v":1,"t":"zfc","h":{},"p":{},"s":"{}","k":"{}","e":{}}}"#,
        serde_json::to_string(hosts).expect("hosts"),
        port,
        sid,
        b64url(&secret),
        e
    );
    PairingSession {
        sid,
        secret,
        payload,
    }
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

// ---------------------------------------------------------------- trust store

/// Reads the Electron desktop's trust record, so a phone that already trusts
/// the old desktop can resume against the spike without a re-pair. Same file,
/// same format (trustStore.ts contract).
fn read_trust() -> Option<(String, Vec<u8>)> {
    let path = format!(
        "{}",
        dirs_home_join("Library/Application Support/Zero-Friction Local Cast/trust.json")
    );
    let text = std::fs::read_to_string(path).ok()?;
    let doc: Value = serde_json::from_str(&text).ok()?;
    let device_id = doc["deviceId"].as_str()?.to_string();
    let key = b64url_decode(doc["key"].as_str()?).ok()?;
    if key.len() != 32 {
        return None;
    }
    Some((device_id, key))
}

fn dirs_home_join(rel: &str) -> String {
    format!("{}/{}", std::env::var("HOME").unwrap_or_default(), rel)
}

// ---------------------------------------------------------------- signals

#[derive(Debug)]
enum Signal {
    RtcReady(Rtc, UdpSocket),
    RemoteCandidate(Candidate),
}

// ---------------------------------------------------------------- main

#[tokio::main]
async fn main() -> Result<()> {
    println!("[{LOG}] desktop-native M0 interop spike");

    // The ICE host-candidate socket — bound to the wildcard so packets arrive
    // on any interface, but the ADVERTISED candidate uses the real LAN IP
    // (str0m rejects 0.0.0.0 as a candidate address — found live in M0).
    let udp = UdpSocket::bind("0.0.0.0:0").context("bind ICE udp socket")?;
    let ice_port = udp.local_addr().context("ICE socket local addr")?.port();

    let hosts = lan_ipv4_hosts();
    if hosts.is_empty() {
        return Err(anyhow!("no LAN IPv4 address found — cannot build a QR payload"));
    }
    let candidate_ip: std::net::IpAddr = hosts[0]
        .parse()
        .map_err(|_| anyhow!("LAN host {} is not an IPv4 address", hosts[0]))?;
    let candidate_addr = std::net::SocketAddr::new(candidate_ip, ice_port);
    println!("[{LOG}] ICE host candidate advertised as {candidate_addr}");

    let listener = tokio::net::TcpListener::bind(("0.0.0.0", SIGNALING_PORT))
        .await
        .context("bind signaling port 52341 — is the Electron desktop still running?")?;
    let port = listener.local_addr()?.port();

    let session = new_pairing_session(&hosts, port);
    println!("[{LOG}] pairing session ready (sid={}…, ttl={SESSION_TTL_SECONDS} s)", &session.sid[..8]);

    let code = QrCode::new(session.payload.as_bytes()).context("qr payload")?;
    let image = code.render::<char>().quiet_zone(true).build();
    println!("{image}");
    code.render::<image::Luma<u8>>()
        .quiet_zone(true)
        .build()
        .save("/tmp/zfc-native-qr.png")
        .context("save QR png")?;
    println!("[{LOG}] QR also saved to /tmp/zfc-native-qr.png");

    // The media thread owns the ICE socket + the Rtc once the offer is answered.
    let (signal_tx, signal_rx) = mpsc::sync_channel::<Signal>(16);
    let media_thread = std::thread::spawn(move || media_loop(signal_rx, udp));

    // Reconnects arrive as NEW signaling connections (the phone's ladder);
    // each one answers into the same media thread — the newest Rtc wins.
    while let Ok((tcp, _addr)) = listener.accept().await {
        println!("[{LOG}] signaling tcp connected");
        if let Err(error) = run_signaling(tcp, &session, hosts.clone(), candidate_addr, signal_tx.clone()).await {
            println!("[{LOG}] signaling ended: {error:#}");
        }
    }
    let _ = media_thread.join();
    Ok(())
}

async fn run_signaling(
    tcp: tokio::net::TcpStream,
    session: &PairingSession,
    hosts: Vec<String>,
    candidate_addr: std::net::SocketAddr,
    signal_tx: mpsc::SyncSender<Signal>,
) -> Result<()> {
    let ws = tokio_tungstenite::accept_async(tcp).await.context("ws upgrade failed")?;
    let (mut sink, mut stream) = ws.split();
    let mut seq: u64 = 0;
    let mut sid = String::new();
    let mut nonce = String::new();
    let mut authorized = false;

    while let Some(message) = stream.next().await {
        let message = message?;
        let Message::Text(text) = message else {
            return Err(anyhow!("binary frame — close 1003 binary-not-supported"));
        };
        let envelope: Value = serde_json::from_str(&text).context("envelope not JSON")?;
        if envelope["v"].as_i64() != Some(1) {
            send_envelope(&mut sink, &mut seq, &sid, "error", json!({"code": "bad-version"})).await?;
            return Err(anyhow!("bad envelope version — close 1002"));
        }
        let msg_type = envelope["type"].as_str().unwrap_or("").to_string();
        let inbound_sid = envelope["sid"].as_str().unwrap_or("").to_string();
        if !inbound_sid.is_empty() && sid.is_empty() {
            sid = inbound_sid.clone();
        }

        match msg_type.as_str() {
            "hello" => {
                let resume = envelope["payload"]["resume"].as_bool().unwrap_or(false);
                let device_id = envelope["payload"]["deviceId"].as_str().unwrap_or("").to_string();
                println!(
                    "[{LOG}] hello resume={resume} deviceId={} protoMin={} protoMax={}",
                    if device_id.is_empty() { "(none)" } else { &device_id },
                    envelope["payload"]["protoMin"].as_i64().unwrap_or(0),
                    envelope["payload"]["protoMax"].as_i64().unwrap_or(0)
                );
                sid = inbound_sid.clone();
                let mut bytes = [0u8; NONCE_BYTES];
                rand::rngs::OsRng.fill_bytes(&mut bytes);
                nonce = b64url(&bytes);
                send_envelope(&mut sink, &mut seq, &sid, "challenge", json!({"n": nonce})).await?;
                println!("[{LOG}] hello (ua={}) — challenge sent", envelope["payload"]["ua"].as_str().unwrap_or("?"));
            }
            "auth" => {
                let mac = envelope["payload"]["mac"].as_str().unwrap_or("");
                let received = b64url_decode(mac).context("auth mac not base64url")?;
                let mut message = Vec::new();
                message.extend_from_slice(sid.as_bytes());
                message.extend_from_slice(nonce.as_bytes());
                let expected = hmac_bytes(&session.secret, &message);
                let mut verified = constant_time_eq(&received, &expected);
                let mut key_source = "qr session secret";
                if !verified {
                    // The phone may resume against its trusted desktop even on
                    // a fresh QR scan (ADR-006) — the key is then the stored
                    // trustKey. Same verify, different key source.
                    if let Some((_trusted_device_id, trusted_key)) = read_trust() {
                        let expected_trust = hmac_bytes(&trusted_key, &message);
                        if constant_time_eq(&received, &expected_trust) {
                            verified = true;
                            key_source = "stored trustKey (resume)";
                        }
                    }
                }
                if !verified {
                    let resume = envelope["payload"]["resume"].as_bool().unwrap_or(false);
                    send_envelope(&mut sink, &mut seq, &sid, "error", json!({"code": "bad-auth"})).await?;
                    return Err(anyhow!(
                        "HMAC verify failed — close 1008 bad-auth (sid_len={} nonce_len={} key_len={} resume={})",
                        sid.len(),
                        nonce.len(),
                        session.secret.len(),
                        resume
                    ));
                }
                println!("[{LOG}] authorized via {key_source}");
                authorized = true;
                send_envelope(
                    &mut sink,
                    &mut seq,
                    &sid,
                    "auth-ok",
                    json!({"name": "desktop-native", "proto": 2, "desktopId": "desktop-native-spike", "hosts": hosts}),
                )
                .await?;
                println!("[{LOG}] AUTH OK — authorized, awaiting sdp-offer");
            }
            "sdp-offer" if authorized => {
                let pc = envelope["payload"]["pc"].as_str().unwrap_or("").to_string();
                let sdp_text = envelope["payload"]["sdp"].as_str().unwrap_or("").to_string();
                if pc != "media" {
                    println!("[{LOG}] sdp-offer for pc={pc} — ignored in the M0 spike (phone degrades to no-mic)");
                    continue;
                }
                println!(
                    "[{LOG}] media offer received ({} bytes SDP) — accepting via str0m",
                    sdp_text.len()
                );
                let _ = std::fs::write("/tmp/native-offer.sdp", &sdp_text);
                let offer = SdpOffer::from_sdp_string(&sdp_text)
                    .context("str0m failed to parse the phone's SDP offer")?;
                let mut rtc = Rtc::builder().build(Instant::now());
                let candidate = Candidate::host(candidate_addr, "udp").context("host candidate")?;
                rtc.add_local_candidate(candidate);
                let answer = rtc
                    .sdp_api()
                    .accept_offer(offer)
                    .context("str0m failed to accept the offer")?;
                let answer_text = answer.to_sdp_string();
                let _ = std::fs::write("/tmp/native-answer.sdp", &answer_text);
                println!("[{LOG}] str0m answer produced ({} bytes) — sending", answer_text.len());
                send_envelope(
                    &mut sink,
                    &mut seq,
                    &sid,
                    "sdp-answer",
                    json!({"pc": "media", "sdp": answer_text}),
                )
                .await?;
                // The ICE socket moves to the media thread together with the Rtc;
                // trickle candidates from here on arrive over this WS loop.
                let udp = UdpSocket::bind("0.0.0.0:0").context("bind media ICE socket")?;
                signal_tx.send(Signal::RtcReady(rtc, udp)).expect("media thread");
                println!("[{LOG}] media thread owns the Rtc — listening for trickle + media");
            }
            "ice" if authorized => {
                let line = envelope["payload"]["candidate"]["candidate"].as_str().unwrap_or("");
                if line.is_empty() {
                    continue; // end-of-gathering marker
                }
                let candidate = Candidate::from_sdp_string(line).context("candidate line parse")?;
                println!("[{LOG}] trickle candidate forwarded: {candidate}");
                signal_tx.send(Signal::RemoteCandidate(candidate)).expect("media thread");
            }
            "ping" => {
                let t = envelope["payload"]["t"].as_i64().unwrap_or(0);
                send_envelope(&mut sink, &mut seq, &sid, "pong", json!({"t": t})).await?;
            }
            "bye" => {
                let reason = envelope["payload"]["reason"].as_str().unwrap_or("");
                println!("[{LOG}] bye ({reason})");
                return Ok(());
            }
            other => {
                println!("[{LOG}] message '{other}' — ignored in the M0 spike");
            }
        }
    }
    Ok(())
}

async fn send_envelope<S>(
    sink: &mut futures_util::stream::SplitSink<
        tokio_tungstenite::WebSocketStream<S>,
        Message,
    >,
    seq: &mut u64,
    sid: &str,
    msg_type: &str,
    payload: Value,
) -> Result<()>
where
    S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin,
{
    *seq += 1;
    let frame = json!({
        "v": 1,
        "type": msg_type,
        "seq": *seq,
        "sid": sid,
        "payload": payload,
    });
    sink.send(Message::Text(frame.to_string())).await?;
    Ok(())
}

// ---------------------------------------------------------------- media drive

fn media_loop(rx: mpsc::Receiver<Signal>, udp: UdpSocket) {
    let mut rtc: Option<Rtc> = None;
    let mut destination = udp.local_addr().expect("media socket addr");
    let mut buf = vec![0u8; 4_000];
    let mut video_file: Option<std::fs::File> = None;
    let mut video_bytes: u64 = 0;
    let mut video_frames: u64 = 0;
    let mut dc_bytes: u64 = 0;
    let mut dc_messages: u64 = 0;
    let mut udp_debug = 0u32;
    let mut last_stats = Instant::now();
    let started = Instant::now();

    loop {
        // Apply newly signaled Rtc / remote candidates.
        while let Ok(signal) = rx.try_recv() {
            match signal {
                Signal::RtcReady(new_rtc, socket) => {
                    destination = socket.local_addr().expect("media socket");
                    rtc = Some(new_rtc);
                    println!("[{LOG}] media thread owns the Rtc — driving");
                }
                Signal::RemoteCandidate(candidate) => {
                    if let Some(active) = rtc.as_mut() {
                        println!("[{LOG}] remote candidate added");
                        active.add_remote_candidate(candidate);
                        active.handle_input(Input::Timeout(Instant::now()));
                    }
                }
            }
        }
        let Some(active) = rtc.as_mut() else {
            std::thread::sleep(Duration::from_millis(50));
            continue;
        };

        // Drive str0m: poll outputs until it reports its next timeout.
        let mut timeout = Instant::now() + Duration::from_millis(100);
        loop {
            match active.poll_output() {
                Ok(Output::Transmit(transmit)) => {
                    let _ = udp.send_to(&transmit.contents, transmit.destination);
                }
                Ok(Output::Event(event)) => {
                    handle_event(event, &mut video_file, &mut video_bytes, &mut video_frames, &mut dc_bytes, &mut dc_messages);
                }
                Ok(Output::Timeout(t)) => {
                    timeout = timeout.min(t);
                    break;
                }
                Err(e) => {
                    println!("[{LOG}] poll_output error: {e}");
                    break;
                }
            }
        }

        let read_timeout = timeout.saturating_duration_since(Instant::now()).max(Duration::from_millis(1));
        udp.set_read_timeout(Some(read_timeout)).expect("read timeout");
        match udp.recv_from(&mut buf) {
            Ok((n, source)) => {
                if udp_debug < 30 {
                    udp_debug += 1;
                    let magic: [u8; 4] = [buf[0], buf[1], buf[2], buf[3]];
                    println!(
                        "[{LOG}] udp #{udp_debug}: {n} B from {source} first={magic:02x?}"
                    );
                }
                buf.truncate(n);
                let Ok(contents) = buf.as_slice().try_into() else {
                    continue;
                };
                active.handle_input(Input::Receive(
                    Instant::now(),
                    str0m::net::Receive {
                        proto: str0m::net::Protocol::Udp,
                        source,
                        destination,
                        contents,
                    },
                ));
            }
            Err(ref e) if e.kind() == std::io::ErrorKind::WouldBlock || e.kind() == std::io::ErrorKind::TimedOut => {}
            Err(e) => println!("[{LOG}] udp read error: {e}"),
        }
        active.handle_input(Input::Timeout(Instant::now()));

        if last_stats.elapsed() >= Duration::from_secs(1) {
            let secs = last_stats.elapsed().as_secs_f64().max(0.001);
            last_stats = Instant::now();
            println!(
                "[{LOG}] stats video {:.0} kB/s frames {video_frames} | dc {:.0} kB/s msgs {dc_messages} | uptime {:.0} s",
                video_bytes as f64 / 1000.0 / secs,
                dc_bytes as f64 / 1000.0 / secs,
                started.elapsed().as_secs_f64()
            );
            video_bytes = 0;
            dc_bytes = 0;
        }
    }
}

fn handle_event(
    event: Event,
    video_file: &mut Option<std::fs::File>,
    video_bytes: &mut u64,
    video_frames: &mut u64,
    dc_bytes: &mut u64,
    dc_messages: &mut u64,
) {
    match event {
        Event::Connected => println!("[{LOG}] ICE + DTLS CONNECTED"),
        Event::IceConnectionStateChange(state) => println!("[{LOG}] ice state: {state:?}"),
        Event::MediaAdded(media) => println!("[{LOG}] media added: mid={} kind={:?}", media.mid, media.kind),
        Event::MediaData(data) => {
            if video_file.is_none() {
                *video_file = std::fs::File::create("/tmp/native-video.h264").ok();
                println!("[{LOG}] dumping video bitstream to /tmp/native-video.h264");
            }
            if let Some(file) = video_file.as_mut() {
                let _ = file.write_all(&data.data);
                *video_bytes += data.data.len() as u64;
                *video_frames += 1;
            }
        }
        Event::ChannelData(data) => {
            if *dc_messages == 0 {
                println!("[{LOG}] first DataChannel chunk received — game-audio DC INTEROP OK");
            }
            *dc_bytes += data.data.len() as u64;
            *dc_messages += 1;
        }
        _ => {}
    }
}
