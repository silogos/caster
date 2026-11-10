//! Loopback protocol tests (webrtc.md: signaling must be testable with
//! WebRTC entirely absent): a real WebSocket client against the real server,
//! with a fake host standing in for the PeerConnections and the UI.

use super::*;
use base64::Engine;
use std::cell::Cell;
use std::sync::atomic::{AtomicU32, Ordering};
use tokio::net::TcpListener;
use tokio_tungstenite::{connect_async, MaybeTlsStream};

#[derive(Default)]
struct FakeHost {
    offers: RefCell<Vec<PcId>>,
    candidates: RefCell<Vec<(PcId, Candidate)>>,
    sessions: RefCell<Vec<(Value, bool)>>,
    states: RefCell<Vec<Value>>,
    closed: Cell<u32>,
}

fn local_candidate() -> Candidate {
    Candidate { candidate: "candidate:9 1 udp 1 127.0.0.1 9 typ host".into(), sdp_mid: "1".into(), sdp_mline_index: 1 }
}

impl SignalingHost for FakeHost {
    fn answer(&self, pc: PcId, _sdp: String, ice: IceSender) -> LocalBoxFuture<'static, Result<String, String>> {
        self.offers.borrow_mut().push(pc);
        // Gather one local candidate, then the end-of-gathering marker.
        let _ = ice.send(OutboundIce { pc, candidate: Some(local_candidate()) });
        let _ = ice.send(OutboundIce { pc, candidate: None });
        Box::pin(async move { Ok(format!("answer-{}", pc.as_str())) })
    }
    fn add_remote_candidate(&self, pc: PcId, candidate: Candidate) -> LocalBoxFuture<'static, ()> {
        self.candidates.borrow_mut().push((pc, candidate));
        Box::pin(async {})
    }
    fn close_peers(&self) {
        self.closed.set(self.closed.get() + 1);
    }
    fn publish_session(&self, view: Value, announce: bool) {
        self.sessions.borrow_mut().push((view, announce));
    }
    fn mobile_state(&self, state: Value) {
        self.states.borrow_mut().push(state);
    }
}

struct Server {
    addr: SocketAddr,
    commands: CommandSender,
    host: Rc<FakeHost>,
    paths: StorePaths,
    /// The QR the phone scanned: (sid, secret).
    qr: (String, Vec<u8>),
}

fn fast_timings() -> Timings {
    Timings {
        handshake_timeout: Duration::from_millis(400),
        heartbeat_interval: Duration::from_secs(60),
        heartbeat_timeout: Duration::from_secs(60),
        sweep_interval: Duration::from_secs(3600),
        rate_limit_initial: Duration::from_millis(500),
        rate_limit_max: Duration::from_secs(2),
    }
}

async fn start_server(timings: Timings, ttl_seconds: u64) -> Server {
    static N: AtomicU32 = AtomicU32::new(0);
    let dir = std::env::temp_dir().join(format!("zfc-signaling-{}-{}", std::process::id(), N.fetch_add(1, Ordering::Relaxed)));
    let _ = std::fs::remove_dir_all(&dir);
    let paths = StorePaths { trust: dir.join("trust.json"), desktop_id: dir.join("desktop-id") };
    let mut store = SessionStore::new(ttl_seconds);
    let view = store.create(pairing::unix_now(), vec!["127.0.0.1".into()]).unwrap().view();
    let sid = view["payload"]["s"].as_str().unwrap().to_string();
    let secret = base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(view["payload"]["k"].as_str().unwrap()).unwrap();
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let host = Rc::new(FakeHost::default());
    let config = ServerConfig {
        timings,
        paths: paths.clone(),
        desktop_id: "11111111-2222-4333-8444-555555555555".into(),
        desktop_name: "Test Mac".into(),
        hosts: Box::new(|| vec!["127.0.0.1".to_string()]),
    };
    let (commands, commands_rx) = unbounded_channel();
    tokio::task::spawn_local(serve(listener, store, Rc::new(config), Rc::clone(&host), commands_rx));
    Server { addr, commands, host, paths, qr: (sid, secret) }
}

struct Client {
    ws: WebSocketStream<MaybeTlsStream<TcpStream>>,
    seq: i64,
    sid: String,
}

impl Client {
    async fn connect(addr: SocketAddr) -> Self {
        let (ws, _) = connect_async(format!("ws://{addr}{SIGNALING_PATH}")).await.expect("connect");
        Self { ws, seq: 0, sid: String::new() }
    }

    async fn send(&mut self, msg_type: &str, payload: Value) {
        self.seq += 1;
        let frame = json!({ "v": 1, "type": msg_type, "seq": self.seq, "sid": self.sid, "payload": payload });
        self.ws.send(Message::Text(frame.to_string())).await.expect("send");
    }

    /// The next envelope, or `None` once the server closed the socket.
    async fn recv(&mut self) -> Option<Value> {
        loop {
            let frame = tokio::time::timeout(Duration::from_secs(3), self.ws.next()).await.expect("server went quiet");
            match frame {
                Some(Ok(Message::Text(t))) => return Some(serde_json::from_str(&t).unwrap()),
                Some(Ok(Message::Close(_))) | Some(Err(_)) | None => return None,
                Some(Ok(_)) => continue,
            }
        }
    }

    async fn expect(&mut self, msg_type: &str) -> Value {
        let env = self.recv().await.unwrap_or_else(|| panic!("closed while expecting {msg_type}"));
        assert_eq!(env["type"], msg_type, "{env}");
        env["payload"].clone()
    }

    async fn expect_error(&mut self, code: &str) {
        assert_eq!(self.expect("error").await["code"], code);
    }

    async fn expect_closed(&mut self) {
        assert!(self.recv().await.is_none(), "socket should be closed");
    }

    /// hello → challenge → auth with `key`; returns the auth reply envelope.
    async fn handshake(&mut self, sid: &str, key: &[u8], hello: Value) -> Value {
        self.sid = sid.to_string();
        self.send("hello", hello).await;
        let n = self.expect("challenge").await["n"].as_str().unwrap().to_string();
        self.send("auth", json!({ "mac": pairing::compute_mac(key, sid, &n) })).await;
        self.recv().await.expect("auth reply")
    }
}

fn hello(device_id: Option<&str>, resume: bool) -> Value {
    let mut h = json!({ "ua": "LocalCast/1.0 (Android 16; TB321FU)", "protoMin": 1, "protoMax": 2 });
    if let Some(id) = device_id {
        h["deviceId"] = json!(id);
    }
    if resume {
        h["resume"] = json!(true);
    }
    h
}

async fn paired(server: &Server) -> Client {
    let mut c = Client::connect(server.addr).await;
    let reply = c.handshake(&server.qr.0, &server.qr.1, hello(Some("phone-1"), false)).await;
    assert_eq!(reply["type"], "auth-ok", "{reply}");
    c
}

fn local<F: std::future::Future<Output = ()>>(test: F) {
    let runtime = tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
    tokio::task::LocalSet::new().block_on(&runtime, test);
}

#[test]
fn qr_pairing_authorizes_negotiates_proto_1_and_saves_trust() {
    local(async {
        let server = start_server(fast_timings(), 600).await;
        let mut c = Client::connect(server.addr).await;
        let reply = c.handshake(&server.qr.0, &server.qr.1, hello(Some("phone-1"), false)).await;
        assert_eq!(reply["type"], "auth-ok");
        let ok = &reply["payload"];
        assert_eq!(ok["proto"], 1, "a proto-2 phone gets 1: vc-*/audio-routing-* are not implemented here");
        assert_eq!(ok["desktopId"], "11111111-2222-4333-8444-555555555555");
        assert_eq!(ok["name"], "Test Mac");
        assert_eq!(ok["hosts"], json!(["127.0.0.1"]));
        let trust = pairing::read_trust(&server.paths.trust).expect("trust saved");
        assert_eq!(trust.device_id, "phone-1");
        assert_eq!(trust.key, pairing::derive_trust_key(&server.qr.1, &server.qr.0));
        assert_eq!(server.host.states.borrow()[0]["state"], "connected");
        assert_eq!(server.host.states.borrow()[0]["name"], "TB321FU");
    });
}

#[test]
fn a_silent_socket_neither_blocks_pairing_nor_lives_forever() {
    local(async {
        let server = start_server(fast_timings(), 600).await;
        // A raw TCP connect that never upgrades (a phone whose Wi-Fi dropped).
        let _silent_tcp = TcpStream::connect(server.addr).await.unwrap();
        // A WebSocket that upgrades but never says hello.
        let mut silent_ws = Client::connect(server.addr).await;
        // A real phone still pairs while both are open.
        let _phone = paired(&server).await;
        // The silent WebSocket is closed by the handshake timeout.
        silent_ws.expect_closed().await;
    });
}

#[test]
fn wrong_mac_is_bad_auth_then_the_address_is_rate_limited() {
    local(async {
        let server = start_server(fast_timings(), 600).await;
        let mut c = Client::connect(server.addr).await;
        let reply = c.handshake(&server.qr.0, b"not the secret", hello(None, false)).await;
        assert_eq!((reply["type"].as_str(), reply["payload"]["code"].as_str()), (Some("error"), Some("bad-auth")));
        c.expect_closed().await;
        // Immediately again: refused before any frame (connect may fail too).
        if let Ok((mut ws, _)) = connect_async(format!("ws://{}{SIGNALING_PATH}", server.addr)).await {
            let next = tokio::time::timeout(Duration::from_secs(2), ws.next()).await.expect("closed promptly");
            assert!(!matches!(next, Some(Ok(Message::Text(_)))), "rate-limited socket must not talk");
        }
        // After the block expires the right secret works.
        tokio::time::sleep(Duration::from_millis(600)).await;
        let _phone = paired(&server).await;
    });
}

#[test]
fn unknown_and_expired_sessions_are_terminal() {
    local(async {
        let server = start_server(fast_timings(), 600).await;
        let mut c = Client::connect(server.addr).await;
        c.sid = "stale-qr".into();
        c.send("hello", hello(None, false)).await;
        c.expect_error("unknown-session").await;
        c.expect_closed().await;

        let expired = start_server(fast_timings(), 0).await;
        let mut c = Client::connect(expired.addr).await;
        c.sid = expired.qr.0.clone();
        c.send("hello", hello(None, false)).await;
        c.expect_error("expired").await;
        c.expect_closed().await;
    });
}

#[test]
fn resume_uses_the_trust_key_and_unknown_devices_are_refused() {
    local(async {
        let server = start_server(fast_timings(), 600).await;
        let key = vec![9u8; 32];
        pairing::write_trust(&server.paths.trust, &TrustEntry { device_id: "phone-1".into(), name: "x".into(), key: key.clone() }).unwrap();

        let mut stranger = Client::connect(server.addr).await;
        stranger.sid = "phone-2".into();
        stranger.send("hello", hello(Some("phone-2"), true)).await;
        stranger.expect_error("unknown-device").await;

        let mut c = Client::connect(server.addr).await;
        let reply = c.handshake("phone-1", &key, hello(Some("phone-1"), true)).await;
        assert_eq!(reply["type"], "auth-ok", "{reply}");
        // The bypassed QR session is replaced (silently: the phone is casting).
        let sessions = server.host.sessions.borrow();
        let (fresh, announced) = sessions.last().expect("fresh QR staged");
        assert!(!announced);
        assert_ne!(fresh["payload"]["s"], server.qr.0.as_str(), "the old QR no longer works");
    });
}

#[test]
fn a_second_phone_gets_busy() {
    local(async {
        let server = start_server(fast_timings(), 600).await;
        let _first = paired(&server).await;
        let mut second = Client::connect(server.addr).await;
        let reply = second.handshake(&server.qr.0, &server.qr.1, hello(Some("phone-2"), false)).await;
        assert_eq!(reply["payload"]["code"], "busy", "{reply}");
    });
}

#[test]
fn a_silent_authorized_phone_is_pinged_then_dropped() {
    local(async {
        let timings = Timings {
            heartbeat_interval: Duration::from_millis(100),
            heartbeat_timeout: Duration::from_millis(350),
            ..fast_timings()
        };
        let server = start_server(timings, 600).await;
        let mut c = paired(&server).await;
        assert_eq!(c.expect("ping").await["t"].is_number(), true);
        // Never answer: the server must give up and close.
        let mut pings = 0;
        while let Some(env) = c.recv().await {
            assert_eq!(env["type"], "ping");
            pings += 1;
            assert!(pings < 20, "heartbeat timeout never fired");
        }
        assert_eq!(server.host.states.borrow().last().unwrap()["state"], "disconnected");
    });
}

#[test]
fn offers_route_by_pc_and_candidates_flow_both_ways() {
    local(async {
        let server = start_server(fast_timings(), 600).await;
        let mut c = paired(&server).await;
        for pc in ["media", "mic"] {
            c.send("sdp-offer", json!({ "pc": pc, "sdp": "v=0" })).await;
            // The answer goes out BEFORE candidates gathered while answering:
            // the phone cannot apply a candidate before the remote description.
            let answer = c.expect("sdp-answer").await;
            assert_eq!((answer["pc"].as_str(), answer["sdp"].as_str()), (Some(pc), Some(format!("answer-{pc}").as_str())));
            let ice = c.expect("ice").await;
            assert_eq!(ice["pc"], pc);
            assert_eq!(ice["candidate"]["sdpMid"], "1");
            assert_eq!(ice["candidate"]["sdpMLineIndex"], 1);
            let end = c.expect("ice").await;
            assert!(end["candidate"].is_null(), "end-of-gathering marker");
        }
        assert_eq!(*server.host.offers.borrow(), vec![PcId::Media, PcId::Mic], "a mic offer never touches media");

        c.send("ice", json!({ "pc": "mic", "candidate": { "candidate": "candidate:1 1 udp 1 10.0.0.2 5 typ host", "sdpMid": "0", "sdpMLineIndex": 0 } })).await;
        c.send("ice", json!({ "pc": "media", "candidate": null })).await;
        c.send("ping", json!({ "t": 1 })).await; // a round trip as a barrier
        c.expect("pong").await;
        let got = server.host.candidates.borrow();
        assert_eq!(got.len(), 1, "the end marker is not a candidate");
        assert_eq!(got[0].0, PcId::Mic);
        assert_eq!(got[0].1.sdp_mid, "0");
    });
}

#[test]
fn only_hello_and_auth_before_auth_ok() {
    local(async {
        let server = start_server(fast_timings(), 600).await;
        let mut c = Client::connect(server.addr).await;
        c.sid = server.qr.0.clone();
        c.send("sdp-offer", json!({ "pc": "media", "sdp": "v=0" })).await;
        c.expect_error("bad-message").await;
        assert!(server.host.offers.borrow().is_empty());
        // Recoverable: the handshake still works on the same socket.
        let reply = c.handshake(&server.qr.0.clone(), &server.qr.1, hello(None, false)).await;
        assert_eq!(reply["type"], "auth-ok");
    });
}

#[test]
fn proto_2_messages_and_wrong_sids_are_recoverable_errors() {
    local(async {
        let server = start_server(fast_timings(), 600).await;
        let mut c = paired(&server).await;
        c.send("vc-query", json!({})).await;
        c.expect_error("bad-message").await;
        let sid = std::mem::replace(&mut c.sid, "someone-else".into());
        c.send("ping", json!({ "t": 1 })).await;
        c.expect_error("bad-message").await;
        c.sid = sid;
        c.send("session-info", json!({ "profile": "balanced", "width": 1920, "height": 1200, "fps": 60, "gameAudio": true, "mic": false })).await;
        c.send("ping", json!({ "t": 2 })).await;
        c.expect("pong").await;
        let states = server.host.states.borrow();
        let info = states.iter().find(|s| s["state"] == "session-info").expect("forwarded");
        assert_eq!(info["info"]["width"], 1920, "the phone's real values, not placeholders");
    });
}

#[test]
fn bye_forget_revokes_trust_and_shows_a_fresh_qr() {
    local(async {
        let server = start_server(fast_timings(), 600).await;
        let mut c = paired(&server).await;
        assert!(pairing::read_trust(&server.paths.trust).is_some());
        c.send("bye", json!({ "reason": "forget" })).await;
        c.expect_closed().await;
        assert!(pairing::read_trust(&server.paths.trust).is_none(), "trust revoked");
        assert_eq!(server.host.closed.get(), 1, "both peers torn down");
        let sessions = server.host.sessions.borrow();
        assert!(sessions.last().unwrap().1, "no trusted phone left: the UI shows the QR");
        drop(sessions);
        // The old QR is dead.
        let mut again = Client::connect(server.addr).await;
        again.sid = server.qr.0.clone();
        again.send("hello", hello(None, false)).await;
        again.expect_error("unknown-session").await;
    });
}

#[test]
fn a_plain_bye_keeps_trust_and_the_no_input_stage() {
    local(async {
        let server = start_server(fast_timings(), 600).await;
        let mut c = paired(&server).await;
        c.send("bye", json!({ "reason": "user-ended" })).await;
        c.expect_closed().await;
        assert!(pairing::read_trust(&server.paths.trust).is_some());
        assert!(!server.host.sessions.borrow().last().unwrap().1, "QR staged silently");
    });
}

#[test]
fn an_expired_spare_qr_is_replaced_silently_while_a_phone_is_connected() {
    local(async {
        // A resumed phone leaves a spare pending session behind; give it a
        // one-second life and let the sweep find it expired mid-cast.
        let timings = Timings { sweep_interval: Duration::from_millis(50), ..fast_timings() };
        let server = start_server(timings, 1).await;
        let key = vec![9u8; 32];
        pairing::write_trust(&server.paths.trust, &TrustEntry { device_id: "phone-1".into(), name: "x".into(), key: key.clone() }).unwrap();
        let mut c = Client::connect(server.addr).await;
        let reply = c.handshake("phone-1", &key, hello(Some("phone-1"), true)).await;
        assert_eq!(reply["type"], "auth-ok", "{reply}");
        let staged_at_resume = server.host.sessions.borrow().len();

        tokio::time::sleep(Duration::from_millis(2_300)).await;
        let sessions = server.host.sessions.borrow();
        let after_resume = &sessions[staged_at_resume..];
        assert!(!after_resume.is_empty(), "the expired spare session was replaced");
        assert!(after_resume.iter().all(|(_, announced)| !announced), "never on screen: the window is showing the cast");
    });
}

#[test]
fn the_sweep_replaces_an_expired_qr_and_announces_it() {
    local(async {
        let timings = Timings { sweep_interval: Duration::from_millis(50), ..fast_timings() };
        let server = start_server(timings, 0).await;
        tokio::time::sleep(Duration::from_millis(200)).await;
        let sessions = server.host.sessions.borrow();
        let (fresh, announced) = sessions.first().expect("regenerated");
        assert!(*announced, "a pending QR that expired is replaced on screen");
        assert_ne!(fresh["payload"]["s"], server.qr.0.as_str());
    });
}

#[test]
fn a_phone_side_forget_shows_the_qr_not_the_reconnect_stage() {
    local(async {
        let server = start_server(fast_timings(), 600).await;
        let mut c = paired(&server).await;
        c.send("bye", json!({ "reason": "forget" })).await;
        c.expect_closed().await;
        tokio::time::sleep(Duration::from_millis(50)).await;
        let states = server.host.states.borrow();
        assert!(states.iter().all(|s| s["state"] != "disconnected"), "after forget the UI keeps the fresh QR: {states:?}");
    });
}

#[test]
fn desktop_disconnect_says_bye_forget_and_revokes_trust() {
    local(async {
        let server = start_server(fast_timings(), 600).await;
        let mut c = paired(&server).await;
        server.commands.send(ServerCommand::ForgetPhone).unwrap();
        let bye = c.expect("bye").await;
        assert_eq!(bye["reason"], "forget", "the phone must drop its saved entry too");
        c.expect_closed().await;
        tokio::time::sleep(Duration::from_millis(50)).await;
        assert!(pairing::read_trust(&server.paths.trust).is_none(), "trust revoked");
        assert_eq!(server.host.closed.get(), 1, "the cast is torn down");
        assert!(server.host.sessions.borrow().last().unwrap().1, "a fresh QR is shown");
        assert!(server.host.states.borrow().iter().all(|s| s["state"] != "disconnected"));
        // A new phone can pair right away with the fresh QR (no busy).
        let (view, _) = server.host.sessions.borrow().last().unwrap().clone();
        let sid = view["payload"]["s"].as_str().unwrap().to_string();
        let secret = base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(view["payload"]["k"].as_str().unwrap()).unwrap();
        let mut next = Client::connect(server.addr).await;
        assert_eq!(next.handshake(&sid, &secret, hello(Some("phone-2"), false)).await["type"], "auth-ok");
    });
}

#[test]
fn desktop_disconnect_with_no_phone_online_still_forgets() {
    local(async {
        let server = start_server(fast_timings(), 600).await;
        pairing::write_trust(&server.paths.trust, &TrustEntry { device_id: "phone-1".into(), name: "x".into(), key: vec![1; 32] }).unwrap();
        server.commands.send(ServerCommand::ForgetPhone).unwrap();
        tokio::time::sleep(Duration::from_millis(50)).await;
        assert!(pairing::read_trust(&server.paths.trust).is_none());
        let mut c = Client::connect(server.addr).await;
        c.sid = "phone-1".into();
        c.send("hello", hello(Some("phone-1"), true)).await;
        c.expect_error("unknown-device").await;
    });
}

#[test]
fn new_qr_replaces_an_idle_session_but_not_a_live_cast() {
    local(async {
        let server = start_server(fast_timings(), 600).await;
        server.commands.send(ServerCommand::RegeneratePairing).unwrap();
        tokio::time::sleep(Duration::from_millis(50)).await;
        let fresh = server.host.sessions.borrow().last().expect("regenerated").0.clone();
        assert_ne!(fresh["payload"]["s"], server.qr.0.as_str());
        let mut stale = Client::connect(server.addr).await;
        stale.sid = server.qr.0.clone();
        stale.send("hello", hello(None, false)).await;
        stale.expect_error("unknown-session").await;

        // While a phone is connected the request is ignored.
        let sid = fresh["payload"]["s"].as_str().unwrap().to_string();
        let secret = base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(fresh["payload"]["k"].as_str().unwrap()).unwrap();
        let mut phone = Client::connect(server.addr).await;
        assert_eq!(phone.handshake(&sid, &secret, hello(None, false)).await["type"], "auth-ok");
        let before = server.host.sessions.borrow().len();
        server.commands.send(ServerCommand::RegeneratePairing).unwrap();
        phone.send("ping", json!({ "t": 1 })).await;
        phone.expect("pong").await;
        assert_eq!(server.host.sessions.borrow().len(), before, "a live cast keeps its session");
    });
}

#[test]
fn quitting_says_a_plain_bye_and_keeps_the_trust() {
    local(async {
        let server = start_server(fast_timings(), 600).await;
        let mut c = paired(&server).await;
        server.commands.send(ServerCommand::SayGoodbye).unwrap();
        assert_eq!(c.expect("bye").await["reason"], BYE_DESKTOP_QUIT);
        c.expect_closed().await;
        assert!(pairing::read_trust(&server.paths.trust).is_some(), "a quit is not a forget");
        // With no phone connected it is a harmless no-op.
        server.commands.send(ServerCommand::SayGoodbye).unwrap();
        tokio::time::sleep(Duration::from_millis(50)).await;
    });
}
