//! The signaling server (docs/architecture/webrtc.md, pairing.md): the
//! WebSocket the phone authenticates on and negotiates both PeerConnections
//! over. Plain data plumbing — media lives behind [`SignalingHost`], so the
//! whole protocol runs (and is tested) with WebRTC entirely absent.
//!
//! One task per connection on a single-threaded LocalSet: a silent socket
//! can never block another phone (the first version served one connection
//! at a time with no handshake timeout). Numbers and payload shapes only in
//! logs — never the session secret, trustKey, or MACs (AGENTS.md).

use crate::pairing::{self, Authorize, SessionStore, StorePaths, TrustEntry};
use crate::protocol::{self, code, Candidate, Envelope, Hello, PcId, Rejection};
use futures_util::future::LocalBoxFuture;
use futures_util::stream::SplitSink;
use futures_util::{SinkExt, StreamExt};
use serde_json::{json, Value};
use std::cell::RefCell;
use std::collections::HashMap;
use std::net::{IpAddr, SocketAddr};
use std::rc::Rc;
use std::time::Duration;
use tokio::net::TcpStream;
use tokio::sync::mpsc::{unbounded_channel, UnboundedSender};
use tokio::time::Instant;
use tokio_tungstenite::tungstenite::protocol::frame::coding::CloseCode;
use tokio_tungstenite::tungstenite::protocol::{CloseFrame, WebSocketConfig};
use tokio_tungstenite::tungstenite::Message;
use tokio_tungstenite::WebSocketStream;

/// Default port (pairing.md); the QR carries it.
pub const SIGNALING_PORT: u16 = 52_341;
pub const SIGNALING_PATH: &str = "/zfc/v1";

/// Protocol timing (pairing.md, webrtc.md); injectable for tests.
#[derive(Clone, Copy)]
pub struct Timings {
    /// The full handshake (WS upgrade → auth-ok) must finish within this.
    pub handshake_timeout: Duration,
    /// App-level `ping` cadence once authorized.
    pub heartbeat_interval: Duration,
    /// A peer silent longer than this is gone.
    pub heartbeat_timeout: Duration,
    /// How often the QR session is checked for expiry / host changes.
    pub sweep_interval: Duration,
    /// Failed-auth rate limit per address: initial block, doubling, capped.
    pub rate_limit_initial: Duration,
    pub rate_limit_max: Duration,
}

impl Default for Timings {
    fn default() -> Self {
        Self {
            handshake_timeout: Duration::from_secs(10),
            heartbeat_interval: Duration::from_secs(5),
            heartbeat_timeout: Duration::from_secs(15),
            sweep_interval: Duration::from_secs(1),
            rate_limit_initial: Duration::from_secs(1),
            rate_limit_max: Duration::from_secs(30),
        }
    }
}

/// A locally gathered candidate on its way to the phone; `None` = the
/// end-of-gathering marker for that pc.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OutboundIce {
    pub pc: PcId,
    pub candidate: Option<Candidate>,
}

pub type IceSender = UnboundedSender<OutboundIce>;

/// Requests from the desktop UI (main window / tray) to the server.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ServerCommand {
    /// Desktop-side Disconnect: `bye forget` to the phone (if connected),
    /// revoke the persistent trust, tear the cast down, show a fresh QR.
    ForgetPhone,
    /// A fresh QR while no phone is connected (the old one is invalidated).
    RegeneratePairing,
    /// The app is quitting: a plain `bye desktop-quit` to the connected
    /// phone — the persistent trust survives a quit (ADR-006).
    SayGoodbye,
}

/// The `bye` reason a quitting desktop sends (any non-`forget` reason
/// keeps the persistent session).
pub const BYE_DESKTOP_QUIT: &str = "desktop-quit";

pub type CommandSender = UnboundedSender<ServerCommand>;

/// How a connection ended on purpose — decides what the UI shows after.
#[derive(Clone, Copy, PartialEq, Eq)]
enum Farewell {
    /// A plain `bye`: the trust survives, the no-input stage stays.
    Plain,
    /// `bye forget` (either side): the QR is already on screen.
    Forget,
}

/// Everything the server needs from the rest of the app — WebRTC and the UI.
pub trait SignalingHost {
    /// Answers `pc`'s offer; local candidates go out through `ice`.
    fn answer(&self, pc: PcId, sdp: String, ice: IceSender) -> LocalBoxFuture<'static, Result<String, String>>;
    fn add_remote_candidate(&self, pc: PcId, candidate: Candidate) -> LocalBoxFuture<'static, ()>;
    /// `bye`: tear both PeerConnections down.
    fn close_peers(&self);
    /// A (re)generated QR session; `announce` = switch the UI to the QR stage.
    fn publish_session(&self, view: Value, announce: bool);
    /// `pairing:mobile-state` for the UI (connected / disconnected / session-info).
    fn mobile_state(&self, state: Value);
}

pub struct ServerConfig {
    pub timings: Timings,
    pub paths: StorePaths,
    pub desktop_id: String,
    pub desktop_name: String,
    /// The machine's current LAN IPv4 set (the sweep and `auth-ok.hosts`).
    pub hosts: Box<dyn Fn() -> Vec<String> + Send>,
}

#[derive(Default)]
struct AuthFailures {
    count: u32,
    blocked_until: Option<Instant>,
}

struct Shared {
    store: SessionStore,
    /// The one authorized connection (QR or resume) — a second gets `busy`.
    authorized: Option<u64>,
    /// Reaches the authorized connection's task (desktop-side `bye`).
    authorized_control: Option<UnboundedSender<Farewell>>,
    failures: HashMap<IpAddr, AuthFailures>,
    next_conn: u64,
}

/// Starts the server on its own thread: a current-thread runtime plus a
/// LocalSet, so every connection task shares one thread with the host.
pub fn start<H: SignalingHost + Send + 'static>(host: H, store: SessionStore, config: ServerConfig) -> CommandSender {
    let (commands_tx, commands) = unbounded_channel();
    std::thread::spawn(move || {
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .expect("signaling runtime");
        let local = tokio::task::LocalSet::new();
        local.block_on(&runtime, async move {
            let listener = match tokio::net::TcpListener::bind(("0.0.0.0", SIGNALING_PORT)).await {
                Ok(l) => l,
                Err(e) => {
                    log::error!(target: "signaling", "bind port {SIGNALING_PORT}: {e}");
                    return;
                }
            };
            log::info!(target: "signaling", "listening on port {SIGNALING_PORT} at {SIGNALING_PATH}");
            serve(listener, store, Rc::new(config), Rc::new(host), commands).await;
        });
    });
    commands_tx
}

/// Accept loop + session sweep. Must run inside a `LocalSet`.
pub async fn serve<H: SignalingHost + 'static>(
    listener: tokio::net::TcpListener,
    store: SessionStore,
    config: Rc<ServerConfig>,
    host: Rc<H>,
    mut commands: tokio::sync::mpsc::UnboundedReceiver<ServerCommand>,
) {
    let shared = Rc::new(RefCell::new(Shared {
        store,
        authorized: None,
        authorized_control: None,
        failures: HashMap::new(),
        next_conn: 0,
    }));
    // The store arrives with a fresh session — the first check is one period out.
    let period = config.timings.sweep_interval;
    let mut sweep = tokio::time::interval_at(Instant::now() + period, period);
    sweep.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    loop {
        tokio::select! {
            accepted = listener.accept() => match accepted {
                Ok((tcp, remote)) => {
                    let id = {
                        let mut s = shared.borrow_mut();
                        s.next_conn += 1;
                        s.next_conn
                    };
                    tokio::task::spawn_local(handle_connection(
                        id, tcp, remote, Rc::clone(&shared), Rc::clone(&config), Rc::clone(&host),
                    ));
                }
                Err(e) => log::warn!(target: "signaling", "accept failed: {e}"),
            },
            Some(command) = commands.recv() => on_command(command, &shared, &config, &*host),
            _ = sweep.tick() => {
                let hosts = (config.hosts)();
                let stale = shared.borrow().store.needs_regeneration(pairing::unix_now(), &hosts);
                if stale {
                    // A phone that resumed with its trust key left a spare
                    // QR session behind, and that one expires mid-cast.
                    // Replace it, but do not put it on screen: the window is
                    // showing the cast (found live 2026-11-10 — the deck
                    // flipped to the QR ten minutes into a cast).
                    let phone_connected = shared.borrow().authorized.is_some();
                    regenerate(&shared, &*host, hosts, !phone_connected);
                }
            }
        }
    }
}

fn on_command<H: SignalingHost + ?Sized>(command: ServerCommand, shared: &RefCell<Shared>, config: &ServerConfig, host: &H) {
    match command {
        ServerCommand::ForgetPhone => {
            log::info!(target: "signaling", "desktop Disconnect — forgetting the phone");
            if let Err(e) = pairing::clear_trust(&config.paths.trust) {
                log::warn!(target: "signaling", "forget: {e}");
            }
            let control = shared.borrow_mut().authorized_control.take();
            if let Some(control) = control {
                // The connection sends `bye forget` and closes itself.
                let _ = control.send(Farewell::Forget);
            }
            shared.borrow_mut().store.invalidate();
            host.close_peers();
            host.mobile_state(json!({ "state": "forgotten" }));
            regenerate(shared, host, (config.hosts)(), true);
        }
        ServerCommand::SayGoodbye => {
            let control = shared.borrow_mut().authorized_control.take();
            if let Some(control) = control {
                log::info!(target: "signaling", "quitting — saying bye to the phone (trust kept)");
                let _ = control.send(Farewell::Plain);
            }
        }
        ServerCommand::RegeneratePairing => {
            if shared.borrow().authorized.is_some() {
                log::info!(target: "signaling", "new QR ignored — a phone is connected");
                return;
            }
            shared.borrow_mut().store.invalidate();
            regenerate(shared, host, (config.hosts)(), true);
        }
    }
}

fn regenerate<H: SignalingHost + ?Sized>(shared: &RefCell<Shared>, host: &H, hosts: Vec<String>, announce: bool) {
    let view = shared.borrow_mut().store.create(pairing::unix_now(), hosts).map(|s| s.view());
    match view {
        Ok(view) => {
            log::info!(target: "signaling", "fresh pairing session staged");
            host.publish_session(view, announce);
        }
        Err(e) => log::warn!(target: "signaling", "pairing session not regenerated: {e}"),
    }
}

#[derive(PartialEq, Eq)]
enum Phase {
    AwaitingHello,
    AwaitingAuth,
    Authorized,
}

/// The key the `auth` MAC must verify against, chosen at `hello`.
enum AuthKey {
    /// The QR session's secret (first pairing).
    Session(Vec<u8>),
    /// The stored trustKey (a trusted phone resuming, ADR-006).
    Trust(Vec<u8>),
}

struct Conn {
    id: u64,
    remote: SocketAddr,
    phase: Phase,
    sid: String,
    nonce: String,
    hello: Option<Hello>,
    auth: Option<AuthKey>,
    last_seen: Instant,
    /// Set when the connection ends with a `bye` (either side).
    farewell: Option<Farewell>,
    /// Handed to the server on auth so the desktop can say `bye`.
    control_tx: UnboundedSender<Farewell>,
}

type WsSink = SplitSink<WebSocketStream<TcpStream>, Message>;

/// The send half plus the per-sender `seq` counter.
struct Outbox {
    sink: WsSink,
    seq: u64,
}

impl Outbox {
    async fn send(&mut self, sid: &str, msg_type: &str, payload: Value) -> Result<(), ()> {
        self.seq += 1;
        let frame = json!({ "v": protocol::ENVELOPE_VERSION, "type": msg_type, "seq": self.seq, "sid": sid, "payload": payload });
        self.sink.send(Message::Text(frame.to_string())).await.map_err(|_| ())
    }

    async fn close(&mut self, code: CloseCode, reason: &str) {
        let frame = CloseFrame { code, reason: reason.to_string().into() };
        let _ = self.sink.send(Message::Close(Some(frame))).await;
    }
}

/// What the connection loop does after a frame.
enum Flow {
    Continue,
    /// Close with this code/reason (any terminal error already sent).
    Close(CloseCode, &'static str),
    /// The socket is already gone.
    Gone,
}

async fn handle_connection<H: SignalingHost + 'static>(
    id: u64,
    tcp: TcpStream,
    remote: SocketAddr,
    shared: Rc<RefCell<Shared>>,
    config: Rc<ServerConfig>,
    host: Rc<H>,
) {
    let blocked = shared
        .borrow()
        .failures
        .get(&remote.ip())
        .and_then(|f| f.blocked_until)
        .is_some_and(|until| Instant::now() < until);
    if blocked {
        log::warn!(target: "signaling", "closing {remote}: rate-limited after failed auth attempts");
        return;
    }

    let deadline = Instant::now() + config.timings.handshake_timeout;
    let mut ws_config = WebSocketConfig::default();
    ws_config.max_message_size = Some(protocol::ENVELOPE_MAX_BYTES);
    ws_config.max_frame_size = Some(protocol::ENVELOPE_MAX_BYTES);
    let upgrade = tokio_tungstenite::accept_async_with_config(tcp, Some(ws_config));
    let ws = match tokio::time::timeout_at(deadline, upgrade).await {
        Ok(Ok(ws)) => ws,
        Ok(Err(e)) => {
            log::warn!(target: "signaling", "ws upgrade from {remote} failed: {e}");
            return;
        }
        Err(_) => {
            log::info!(target: "signaling", "closing {remote}: no WebSocket upgrade within the handshake window");
            return;
        }
    };
    log::info!(target: "signaling", "connection {id} from {remote}");

    let (sink, mut stream) = ws.split();
    let mut out = Outbox { sink, seq: 0 };
    let (control_tx, mut control_rx) = unbounded_channel::<Farewell>();
    let mut conn = Conn {
        id,
        remote,
        phase: Phase::AwaitingHello,
        sid: String::new(),
        nonce: String::new(),
        hello: None,
        auth: None,
        last_seen: Instant::now(),
        farewell: None,
        control_tx,
    };
    let (ice_tx, mut ice_rx) = unbounded_channel::<OutboundIce>();
    let interval = config.timings.heartbeat_interval;
    let mut heartbeat = tokio::time::interval_at(Instant::now() + interval, interval);

    let flow = loop {
        let authorized = conn.phase == Phase::Authorized;
        tokio::select! {
            _ = tokio::time::sleep_until(deadline), if !authorized => {
                log::info!(target: "signaling", "closing {remote}: handshake did not complete in time");
                break Flow::Close(CloseCode::Policy, "handshake-timeout");
            }
            _ = heartbeat.tick(), if authorized => {
                if conn.last_seen.elapsed() > config.timings.heartbeat_timeout {
                    log::info!(target: "signaling", "mobile {remote} silent > {:?} — closing", config.timings.heartbeat_timeout);
                    break Flow::Close(CloseCode::Normal, "heartbeat-timeout");
                }
                let t = pairing::unix_now() * 1000;
                if out.send(&conn.sid, "ping", json!({ "t": t })).await.is_err() {
                    break Flow::Gone;
                }
            }
            Some(farewell) = control_rx.recv() => {
                let reason = if farewell == Farewell::Forget { protocol::BYE_FORGET } else { BYE_DESKTOP_QUIT };
                conn.farewell = Some(farewell);
                let _ = out.send(&conn.sid, "bye", json!({ "reason": reason })).await;
                break Flow::Close(CloseCode::Normal, "bye");
            }
            Some(ice) = ice_rx.recv() => {
                if out.send(&conn.sid, "ice", ice_payload(&ice)).await.is_err() {
                    break Flow::Gone;
                }
            }
            frame = stream.next() => match frame {
                Some(Ok(Message::Text(text))) => {
                    conn.last_seen = Instant::now();
                    match on_text(&mut conn, &text, &mut out, &shared, &config, &*host, &ice_tx).await {
                        Flow::Continue => {}
                        other => break other,
                    }
                }
                Some(Ok(Message::Binary(_))) => {
                    log::warn!(target: "signaling", "binary frame from {remote} — closing");
                    break Flow::Close(CloseCode::Unsupported, "binary-not-supported");
                }
                // WS-level ping/pong count as liveness (tungstenite answers pings).
                Some(Ok(Message::Ping(_) | Message::Pong(_) | Message::Frame(_))) => conn.last_seen = Instant::now(),
                Some(Ok(Message::Close(_)) | Err(_)) | None => break Flow::Gone,
            },
        }
    };

    if let Flow::Close(code, reason) = flow {
        out.close(code, reason).await;
    }
    if conn.phase == Phase::Authorized {
        {
            let mut s = shared.borrow_mut();
            s.store.release(conn.id);
            if s.authorized == Some(conn.id) {
                s.authorized = None;
                s.authorized_control = None;
            }
        }
        // After a forget the fresh QR is already on screen — announcing
        // "disconnected" would flip the UI back to the no-input stage.
        if conn.farewell != Some(Farewell::Forget) {
            log::info!(target: "signaling", "authorized mobile disconnected (connection {id}) — reconnect window open");
            host.mobile_state(json!({ "state": "disconnected" }));
        }
    }
}

fn ice_payload(ice: &OutboundIce) -> Value {
    let candidate = match &ice.candidate {
        Some(c) => json!({ "candidate": c.candidate, "sdpMid": c.sdp_mid, "sdpMLineIndex": c.sdp_mline_index }),
        None => Value::Null,
    };
    json!({ "pc": ice.pc.as_str(), "candidate": candidate })
}

/// Sends the error; a terminal one asks the loop to close.
async fn reject(out: &mut Outbox, sid: &str, rejection: Rejection) -> Flow {
    let payload = json!({ "code": rejection.code, "msg": rejection.reason });
    if out.send(sid, "error", payload).await.is_err() {
        return Flow::Gone;
    }
    if !rejection.close {
        return Flow::Continue;
    }
    let close = if rejection.code == code::BAD_VERSION { CloseCode::Protocol } else { CloseCode::Policy };
    Flow::Close(close, rejection.code)
}

/// A terminal handshake error (`unknown-session`, `bad-auth`, `busy`, …).
async fn fail(out: &mut Outbox, sid: &str, code: &'static str) -> Flow {
    reject(out, sid, Rejection { code, reason: code.to_string(), close: true }).await
}

fn bad_message(reason: impl Into<String>) -> Rejection {
    Rejection { code: code::BAD_MESSAGE, reason: reason.into(), close: false }
}

async fn on_text<H: SignalingHost>(
    conn: &mut Conn,
    text: &str,
    out: &mut Outbox,
    shared: &RefCell<Shared>,
    config: &ServerConfig,
    host: &H,
    ice_tx: &IceSender,
) -> Flow {
    let envelope = match protocol::parse_envelope(text) {
        Ok(e) => e,
        Err(rejection) => return reject(out, &conn.sid, rejection).await,
    };
    let msg_type = envelope.msg_type.clone();

    // webrtc.md: only hello, then auth, until auth-ok.
    match (&conn.phase, msg_type.as_str()) {
        (Phase::AwaitingHello, "hello") | (Phase::AwaitingAuth, "auth") | (Phase::Authorized, _) => {}
        (_, other) => return reject(out, &conn.sid, bad_message(format!("unexpected {other} before auth"))).await,
    }
    // After hello every frame must carry this connection's sid.
    if conn.phase != Phase::AwaitingHello && envelope.sid != conn.sid {
        return reject(out, &conn.sid, bad_message("envelope sid does not match the session")).await;
    }

    match msg_type.as_str() {
        "hello" => on_hello(conn, envelope, out, shared, config).await,
        "auth" => on_auth(conn, envelope, out, shared, config, host).await,
        "sdp-offer" => on_sdp_offer(conn, &envelope, out, host, ice_tx).await,
        "ice" => match protocol::parse_ice(&envelope.payload) {
            Err(r) => reject(out, &conn.sid, r).await,
            Ok((pc, None)) => {
                log::info!(target: "signaling", "phone finished ICE gathering (pc={})", pc.as_str());
                Flow::Continue
            }
            Ok((pc, Some(candidate))) => {
                host.add_remote_candidate(pc, candidate).await;
                Flow::Continue
            }
        },
        "session-info" => match protocol::parse_session_info(&envelope.payload) {
            Err(r) => reject(out, &conn.sid, r).await,
            Ok(info) => {
                host.mobile_state(json!({ "state": "session-info", "info": info }));
                Flow::Continue
            }
        },
        "ping" => match envelope.payload["t"].as_i64() {
            Some(t) => match out.send(&conn.sid, "pong", json!({ "t": t })).await {
                Ok(()) => Flow::Continue,
                Err(()) => Flow::Gone,
            },
            None => reject(out, &conn.sid, bad_message("malformed ping payload")).await,
        },
        "pong" => Flow::Continue, // liveness only — last_seen already moved
        "bye" => on_bye(conn, &envelope, shared, config, host),
        "error" => {
            log::info!(target: "signaling", "mobile reported {}", envelope.payload["code"].as_str().unwrap_or("error"));
            Flow::Continue
        }
        // vc-*/audio-routing-* are proto 2 (never negotiated here); the rest
        // are desktop-sent types. Recoverable, per the forward-compat rule.
        other => {
            let reason = format!("unexpected {other} at proto {}", protocol::DESKTOP_PROTO_MAX);
            reject(out, &conn.sid, bad_message(reason)).await
        }
    }
}

async fn on_hello(conn: &mut Conn, envelope: Envelope, out: &mut Outbox, shared: &RefCell<Shared>, config: &ServerConfig) -> Flow {
    // Own the sid first so every reply below — failures included — carries it
    // (the phone ignores sid-less errors; found live with the Electron desktop).
    conn.sid = envelope.sid.clone();
    let hello = match protocol::parse_hello(&envelope.payload) {
        Ok(h) => h,
        Err(r) => return reject(out, &conn.sid, r).await,
    };
    let session = shared
        .borrow()
        .store
        .lookup(&envelope.sid)
        .map(|s| (SessionStore::is_expired(s, pairing::unix_now()), s.secret.clone()));
    let auth = match session {
        Some((true, _)) => {
            log::info!(target: "signaling", "hello from {}: session expired", conn.remote);
            return fail(out, &conn.sid, code::EXPIRED).await;
        }
        Some((false, secret)) => AuthKey::Session(secret),
        // Not the live QR session: a trusted phone resuming (the envelope
        // sid IS its deviceId, ADR-006) or a stale QR.
        None if hello.resume => match pairing::read_trust(&config.paths.trust) {
            Some(trust) if trust.device_id == envelope.sid => AuthKey::Trust(trust.key),
            _ => {
                log::info!(target: "signaling", "resume from {}: no trusted device matches", conn.remote);
                return fail(out, &conn.sid, code::UNKNOWN_DEVICE).await;
            }
        },
        None => {
            log::info!(target: "signaling", "hello from {}: unknown session", conn.remote);
            return fail(out, &conn.sid, code::UNKNOWN_SESSION).await;
        }
    };
    log::info!(target: "signaling", "hello from {} (resume={}) — challenge sent", conn.remote, hello.resume);
    conn.auth = Some(auth);
    conn.hello = Some(hello);
    conn.nonce = pairing::new_nonce();
    conn.phase = Phase::AwaitingAuth;
    match out.send(&conn.sid, "challenge", json!({ "n": conn.nonce })).await {
        Ok(()) => Flow::Continue,
        Err(()) => Flow::Gone,
    }
}

async fn on_auth<H: SignalingHost>(
    conn: &mut Conn,
    envelope: Envelope,
    out: &mut Outbox,
    shared: &RefCell<Shared>,
    config: &ServerConfig,
    host: &H,
) -> Flow {
    let (Some(auth), Some(hello)) = (conn.auth.take(), conn.hello.clone()) else {
        return fail(out, &conn.sid, code::UNKNOWN_SESSION).await;
    };
    let key = match &auth {
        AuthKey::Session(k) | AuthKey::Trust(k) => k,
    };
    let mac = envelope.payload["mac"].as_str().unwrap_or("");
    if !pairing::verify_mac(&pairing::compute_mac(key, &conn.sid, &conn.nonce), mac) {
        record_auth_failure(shared, conn.remote.ip(), &config.timings);
        log::warn!(target: "signaling", "auth rejected from {}", conn.remote);
        return fail(out, &conn.sid, code::BAD_AUTH).await;
    }
    shared.borrow_mut().failures.remove(&conn.remote.ip());

    let Some(proto) = protocol::negotiate_protocol(&hello) else {
        log::info!(target: "signaling", "no overlapping protocol version with {}", conn.remote);
        return fail(out, &conn.sid, code::BAD_VERSION).await;
    };
    // One authorized connection at a time, across QR sessions and resumes.
    let busy = {
        let mut s = shared.borrow_mut();
        let busy = s.authorized.is_some()
            || (matches!(auth, AuthKey::Session(_)) && s.store.authorize(&conn.sid, conn.id) == Authorize::Busy);
        if !busy {
            s.authorized = Some(conn.id);
            s.authorized_control = Some(conn.control_tx.clone());
        }
        busy
    };
    if busy {
        log::warn!(target: "signaling", "second device attempted to pair ({})", conn.remote);
        return fail(out, &conn.sid, code::BUSY).await;
    }

    conn.phase = Phase::Authorized;
    let auth_ok = json!({
        "name": config.desktop_name,
        "proto": proto,
        "desktopId": config.desktop_id,
        "hosts": (config.hosts)(),
    });
    if out.send(&conn.sid, "auth-ok", auth_ok).await.is_err() {
        return Flow::Gone;
    }
    let name = protocol::device_name_from_ua(&hello.ua);
    match auth {
        AuthKey::Trust(_) => {
            // A resume bypasses the QR session: close it and stage a fresh
            // one, so a photographed old QR cannot piggyback (pairing.md).
            shared.borrow_mut().store.invalidate();
            regenerate(shared, host, (config.hosts)(), false);
            log::info!(target: "signaling", "mobile re-authorized via saved session ({name})");
        }
        AuthKey::Session(secret) => match &hello.device_id {
            Some(device_id) => {
                let entry = TrustEntry {
                    device_id: device_id.clone(),
                    name: name.clone(),
                    key: pairing::derive_trust_key(&secret, &conn.sid),
                };
                let id = protocol::short_id(device_id);
                match pairing::write_trust(&config.paths.trust, &entry) {
                    Ok(()) => log::info!(target: "signaling", "mobile authorized — persistent session saved (deviceId={id}…)"),
                    Err(e) => log::warn!(target: "signaling", "mobile authorized — trust NOT saved (deviceId={id}…): {e}"),
                }
            }
            None => log::info!(target: "signaling", "mobile authorized (unidentified older client)"),
        },
    }
    host.mobile_state(json!({ "state": "connected", "name": name }));
    Flow::Continue
}

async fn on_sdp_offer<H: SignalingHost>(conn: &Conn, envelope: &Envelope, out: &mut Outbox, host: &H, ice_tx: &IceSender) -> Flow {
    let (pc, sdp) = match protocol::parse_sdp_offer(&envelope.payload) {
        Ok(offer) => offer,
        Err(r) => return reject(out, &conn.sid, r).await,
    };
    log::info!(target: "signaling", "sdp-offer for pc={} ({} bytes)", pc.as_str(), sdp.len());
    match host.answer(pc, sdp, ice_tx.clone()).await {
        Ok(answer) => {
            log::info!(target: "signaling", "sdp-answer sent (pc={}, {} bytes)", pc.as_str(), answer.len());
            match out.send(&conn.sid, "sdp-answer", json!({ "pc": pc.as_str(), "sdp": answer })).await {
                Ok(()) => Flow::Continue,
                Err(()) => Flow::Gone,
            }
        }
        Err(e) => {
            log::warn!(target: "signaling", "answer for pc={} failed: {e}", pc.as_str());
            reject(out, &conn.sid, bad_message("answer failed")).await
        }
    }
}

fn on_bye<H: SignalingHost>(conn: &mut Conn, envelope: &Envelope, shared: &RefCell<Shared>, config: &ServerConfig, host: &H) -> Flow {
    let reason = envelope.payload["reason"].as_str().unwrap_or("");
    let forget = reason == protocol::BYE_FORGET;
    conn.farewell = Some(if forget { Farewell::Forget } else { Farewell::Plain });
    if forget {
        if let Err(e) = pairing::clear_trust(&config.paths.trust) {
            log::warn!(target: "signaling", "bye forget: {e}");
        }
        host.mobile_state(json!({ "state": "forgotten" }));
    }
    log::info!(target: "signaling", "bye from {} ({reason})", conn.remote);
    shared.borrow_mut().store.invalidate();
    host.close_peers();
    // A fresh QR right away; the UI switches to it only when no trusted
    // phone remains (a plain bye keeps the persistent session — the
    // no-input stage stays up, pairing.md §bye forget).
    let trusted = !forget && pairing::read_trust(&config.paths.trust).is_some();
    regenerate(shared, host, (config.hosts)(), !trusted);
    Flow::Close(CloseCode::Normal, "bye")
}

fn record_auth_failure(shared: &RefCell<Shared>, ip: IpAddr, timings: &Timings) {
    let mut s = shared.borrow_mut();
    let f = s.failures.entry(ip).or_default();
    f.count += 1;
    let backoff = timings
        .rate_limit_initial
        .saturating_mul(1u32 << (f.count - 1).min(16))
        .min(timings.rate_limit_max);
    f.blocked_until = Some(Instant::now() + backoff);
}

#[cfg(test)]
mod tests;
