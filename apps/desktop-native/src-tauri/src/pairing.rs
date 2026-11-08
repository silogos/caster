//! Pairing state (docs/architecture/pairing.md): the QR session store, the
//! persistent trust entry (ADR-006), the desktop's persistent identity, and
//! the handshake crypto. No sockets here — the signaling server drives it.
//!
//! The trust file and `desktop-id` are the SAME files the Electron desktop
//! uses, so a phone that trusts one desktop app resumes against the other.

use base64::Engine;
use hmac::{Hmac, Mac};
use rand::RngCore;
use serde_json::{json, Value};
use sha2::Sha256;
use std::path::{Path, PathBuf};

/// QR validity (pairing.md: `e = now + 600`).
pub const SESSION_TTL_SECONDS: u64 = 600;
pub const NONCE_BYTES: usize = 16;
const SESSION_ID_BYTES: usize = 16;
const SESSION_SECRET_BYTES: usize = 32;
const TRUST_KEY_BYTES: usize = 32;
const TRUST_INFO: &[u8] = b"zfc-trust-v1";
/// QR payload schema version and app tag (pairing.md §QR payload v1).
const QR_PAYLOAD_VERSION: u32 = 1;
const QR_APP_TAG: &str = "zfc";

/// The Electron app's userData directory — shared on purpose (see above).
const APP_SUPPORT_DIR: &str = "Library/Application Support/Zero-Friction Local Cast";
const TRUST_FILE: &str = "trust.json";
const DESKTOP_ID_FILE: &str = "desktop-id";
const TRUST_DOC_VERSION: i64 = 1;
/// Owner read/write only — the trust file holds the trustKey.
const PRIVATE_FILE_MODE: u32 = 0o600;

type HmacSha256 = Hmac<Sha256>;

// ---- crypto ---------------------------------------------------------------

pub fn b64url(data: &[u8]) -> String {
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(data)
}

fn b64url_decode(s: &str) -> Option<Vec<u8>> {
    base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(s).ok()
}

fn random_bytes(n: usize) -> Vec<u8> {
    let mut out = vec![0u8; n];
    rand::rngs::OsRng.fill_bytes(&mut out);
    out
}

/// A fresh 16-byte challenge nonce, base64url (pairing.md).
pub fn new_nonce() -> String {
    b64url(&random_bytes(NONCE_BYTES))
}

/// `mac = base64url(HMAC-SHA256(key, ASCII(sid) || ASCII(nonce)))`.
pub fn compute_mac(key: &[u8], sid: &str, nonce: &str) -> String {
    let mut mac = HmacSha256::new_from_slice(key).expect("HMAC accepts any key length");
    mac.update(sid.as_bytes());
    mac.update(nonce.as_bytes());
    b64url(&mac.finalize().into_bytes())
}

/// Constant-time comparison of the expected and received MAC strings.
pub fn verify_mac(expected: &str, received: &str) -> bool {
    let (a, b) = (expected.as_bytes(), received.as_bytes());
    if a.len() != b.len() {
        return false;
    }
    a.iter().zip(b).fold(0u8, |diff, (x, y)| diff | (x ^ y)) == 0
}

/// trustKey = HKDF-SHA256(ikm = session secret, salt = ASCII(sid),
/// info = "zfc-trust-v1", L = 32) — derived on both sides, never sent.
pub fn derive_trust_key(session_secret: &[u8], sid: &str) -> Vec<u8> {
    let hk = hkdf::Hkdf::<Sha256>::new(Some(sid.as_bytes()), session_secret);
    let mut okm = [0u8; TRUST_KEY_BYTES];
    hk.expand(TRUST_INFO, &mut okm).expect("32 bytes is a valid HKDF length");
    okm.to_vec()
}

// ---- QR session store -------------------------------------------------------

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SessionState {
    Pending,
    Authorized,
    Closed,
}

pub struct PairingSession {
    pub sid: String,
    pub secret: Vec<u8>,
    pub expires_at: u64,
    /// The LAN hosts the QR advertises — a creation-time snapshot.
    pub hosts: Vec<String>,
    payload: Value,
    qr_data_url: String,
    state: SessionState,
    /// The authorized connection while it is attached; `None` = reconnect window.
    authorized_conn: Option<u64>,
}

impl PairingSession {
    /// The UI's view: QR image, payload, expiry (mirror of PairingSessionView).
    pub fn view(&self) -> Value {
        json!({ "qrDataUrl": self.qr_data_url, "payload": self.payload, "expiresAt": self.expires_at })
    }
}

#[derive(Debug, PartialEq, Eq)]
pub enum Authorize {
    Authorized,
    Busy,
}

/// One session at a time (pairing.md §Session lifecycle).
pub struct SessionStore {
    session: Option<PairingSession>,
    ttl_seconds: u64,
}

impl SessionStore {
    pub fn new(ttl_seconds: u64) -> Self {
        Self { session: None, ttl_seconds }
    }

    /// Generates a fresh session, invalidating any previous one.
    pub fn create(&mut self, now: u64, hosts: Vec<String>) -> Result<&PairingSession, String> {
        if hosts.is_empty() {
            return Err("no LAN IPv4 address found".into());
        }
        let sid = b64url(&random_bytes(SESSION_ID_BYTES));
        let secret = random_bytes(SESSION_SECRET_BYTES);
        let expires_at = now + self.ttl_seconds;
        let payload = json!({
            "v": QR_PAYLOAD_VERSION,
            "t": QR_APP_TAG,
            "h": hosts,
            "p": crate::signaling::SIGNALING_PORT,
            "s": sid,
            "k": b64url(&secret),
            "e": expires_at,
        });
        let qr_data_url = qr_data_url(&payload.to_string())?;
        self.session = Some(PairingSession {
            sid,
            secret,
            expires_at,
            hosts,
            payload,
            qr_data_url,
            state: SessionState::Pending,
            authorized_conn: None,
        });
        Ok(self.session.as_ref().expect("just set"))
    }

    /// The live (not closed) session with this sid.
    pub fn lookup(&self, sid: &str) -> Option<&PairingSession> {
        self.session
            .as_ref()
            .filter(|s| s.state != SessionState::Closed && s.sid == sid)
    }

    pub fn is_expired(session: &PairingSession, now: u64) -> bool {
        now >= session.expires_at
    }

    /// First auth authorizes; a second connection while the authorized one
    /// is still attached gets `busy`. After a drop the same sid re-authorizes.
    pub fn authorize(&mut self, sid: &str, conn: u64) -> Authorize {
        match self.session.as_mut().filter(|s| s.sid == sid) {
            Some(s) if s.state == SessionState::Authorized && s.authorized_conn.is_some() => Authorize::Busy,
            Some(s) => {
                s.state = SessionState::Authorized;
                s.authorized_conn = Some(conn);
                Authorize::Authorized
            }
            None => Authorize::Busy,
        }
    }

    /// The authorized socket went away — the reconnect window stays open.
    pub fn release(&mut self, conn: u64) {
        if let Some(s) = self.session.as_mut() {
            if s.authorized_conn == Some(conn) {
                s.authorized_conn = None;
            }
        }
    }

    /// `bye` (or a resume bypassing the QR) ends the session immediately.
    pub fn invalidate(&mut self) {
        if let Some(s) = self.session.as_mut() {
            s.state = SessionState::Closed;
            s.authorized_conn = None;
        }
    }

    /// Whether the sweep must regenerate (pairing.md): none/closed; expired —
    /// unless authorized with its socket attached (a live cast outlives the
    /// TTL); or a PENDING session whose machine IPv4 set changed (hotspot).
    pub fn needs_regeneration(&self, now: u64, hosts: &[String]) -> bool {
        let Some(s) = self.session.as_ref() else { return true };
        let live_cast = s.state == SessionState::Authorized && s.authorized_conn.is_some();
        match s.state {
            SessionState::Closed => true,
            _ if Self::is_expired(s, now) => !live_cast,
            SessionState::Pending => !same_host_set(&s.hosts, hosts),
            SessionState::Authorized => false,
        }
    }
}

/// A reordered interface list alone is not a change (pairing.md).
fn same_host_set(a: &[String], b: &[String]) -> bool {
    let mut a = a.to_vec();
    let mut b = b.to_vec();
    a.sort();
    b.sort();
    a == b
}

/// Every non-loopback IPv4 address (pairing.md: `h` lists them all).
pub fn lan_ipv4_hosts() -> Vec<String> {
    let mut hosts: Vec<String> = if_addrs::get_if_addrs()
        .unwrap_or_default()
        .into_iter()
        .filter_map(|iface| match iface.addr {
            if_addrs::IfAddr::V4(v4) if !v4.ip.is_loopback() => Some(v4.ip.to_string()),
            _ => None,
        })
        .collect();
    hosts.sort();
    hosts.dedup();
    hosts
}

fn qr_data_url(payload: &str) -> Result<String, String> {
    let image = qrcode::QrCode::new(payload.as_bytes())
        .map_err(|e| e.to_string())?
        .render::<image::Luma<u8>>()
        .quiet_zone(true)
        .build();
    let mut png = Vec::new();
    image
        .write_to(&mut std::io::Cursor::new(&mut png), image::ImageFormat::Png)
        .map_err(|e| e.to_string())?;
    // STANDARD base64: the webview's data-URL decoder rejects the URL-safe
    // alphabet the pairing fields use (a broken "?" image, found live).
    Ok(format!(
        "data:image/png;base64,{}",
        base64::engine::general_purpose::STANDARD.encode(&png)
    ))
}

// ---- trust store + desktop identity ---------------------------------------

pub struct TrustEntry {
    pub device_id: String,
    pub name: String,
    pub key: Vec<u8>,
}

/// Where the shared files live; injectable for tests.
#[derive(Clone)]
pub struct StorePaths {
    pub trust: PathBuf,
    pub desktop_id: PathBuf,
}

impl StorePaths {
    pub fn from_home() -> Option<Self> {
        let dir = PathBuf::from(std::env::var("HOME").ok()?).join(APP_SUPPORT_DIR);
        Some(Self { trust: dir.join(TRUST_FILE), desktop_id: dir.join(DESKTOP_ID_FILE) })
    }
}

/// Reads the single trust entry; anything unreadable degrades to `None`
/// (pairing.md: the honest fallback is one QR re-scan).
pub fn read_trust(path: &Path) -> Option<TrustEntry> {
    let doc: Value = serde_json::from_str(&std::fs::read_to_string(path).ok()?).ok()?;
    if doc["version"].as_i64() != Some(TRUST_DOC_VERSION) {
        return None;
    }
    let device_id = doc["deviceId"].as_str()?.to_string();
    let name = doc["name"].as_str().unwrap_or("Android device").to_string();
    let key = b64url_decode(doc["key"].as_str()?)?;
    if device_id.is_empty()
        || device_id.chars().count() > crate::protocol::MAX_DEVICE_ID_LENGTH
        || key.len() != TRUST_KEY_BYTES
    {
        return None;
    }
    Some(TrustEntry { device_id, name, key })
}

pub fn write_trust(path: &Path, entry: &TrustEntry) -> Result<(), String> {
    let now = unix_now();
    let doc = json!({
        "version": TRUST_DOC_VERSION,
        "deviceId": entry.device_id,
        "name": entry.name,
        "key": b64url(&entry.key),
        "createdAt": now,
        "lastConnectedAt": now,
    });
    write_private_file(path, doc.to_string().as_bytes())
}

/// `bye forget` — the only way trust ends (pairing.md).
pub fn clear_trust(path: &Path) -> Result<(), String> {
    match std::fs::remove_file(path) {
        Ok(()) => Ok(()),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(e) => Err(format!("remove trust file: {e}")),
    }
}

/// The desktop's persistent UUID (`auth-ok.desktopId`): read, or created
/// once. An identifier, never a secret (ADR-005).
pub fn desktop_id(path: &Path) -> Result<String, String> {
    if let Ok(text) = std::fs::read_to_string(path) {
        let id = text.trim();
        if is_uuid(id) {
            return Ok(id.to_string());
        }
    }
    let id = new_uuid_v4();
    write_private_file(path, id.as_bytes())?;
    Ok(id)
}

fn is_uuid(s: &str) -> bool {
    s.len() == 36
        && s.char_indices().all(|(i, c)| match i {
            8 | 13 | 18 | 23 => c == '-',
            _ => c.is_ascii_hexdigit(),
        })
}

fn new_uuid_v4() -> String {
    let mut b = random_bytes(16);
    b[6] = (b[6] & 0x0f) | 0x40; // version 4
    b[8] = (b[8] & 0x3f) | 0x80; // RFC 4122 variant
    let hex: String = b.iter().map(|x| format!("{x:02x}")).collect();
    format!("{}-{}-{}-{}-{}", &hex[0..8], &hex[8..12], &hex[12..16], &hex[16..20], &hex[20..32])
}

/// The name the phone shows ("Connected to *MacBook*"): the Mac's
/// user-visible computer name, falling back to a generic label.
pub fn desktop_name() -> String {
    std::process::Command::new("scutil")
        .args(["--get", "ComputerName"])
        .output()
        .ok()
        .filter(|out| out.status.success())
        .and_then(|out| String::from_utf8(out.stdout).ok())
        .map(|name| name.trim().to_string())
        .filter(|name| !name.is_empty())
        .unwrap_or_else(|| "Mac".to_string())
}

/// Owner-only (0600), atomic (temp file + rename) write; creates the parent.
fn write_private_file(path: &Path, bytes: &[u8]) -> Result<(), String> {
    use std::io::Write;
    use std::os::unix::fs::OpenOptionsExt;
    if let Some(dir) = path.parent() {
        std::fs::create_dir_all(dir).map_err(|e| format!("create {}: {e}", dir.display()))?;
    }
    let tmp = path.with_extension("tmp");
    // `mode()` applies only on create: never reuse a stale, looser temp file.
    let _ = std::fs::remove_file(&tmp);
    let mut file = std::fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .mode(PRIVATE_FILE_MODE)
        .open(&tmp)
        .map_err(|e| format!("open temp file: {e}"))?;
    file.write_all(bytes)
        .and_then(|()| file.sync_all())
        .map_err(|e| format!("write temp file: {e}"))?;
    std::fs::rename(&tmp, path).map_err(|e| format!("replace {}: {e}", path.display()))
}

pub fn unix_now() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::unix::fs::PermissionsExt;

    fn scratch(tag: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("zfc-pairing-{tag}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        dir
    }

    fn hosts() -> Vec<String> {
        vec!["192.168.1.10".into()]
    }

    fn entry() -> TrustEntry {
        TrustEntry { device_id: "device-1234".into(), name: "Pixel".into(), key: vec![7u8; 32] }
    }

    #[test]
    fn mac_matches_the_documented_construction_and_verifies() {
        // HMAC(k, sid || n) — the same bytes the phone signs.
        let key = [1u8; 32];
        let mac = compute_mac(&key, "sid", "nonce");
        let mut expected = HmacSha256::new_from_slice(&key).unwrap();
        expected.update(b"sidnonce");
        assert_eq!(mac, b64url(&expected.finalize().into_bytes()));
        assert!(verify_mac(&mac, &mac));
        assert!(!verify_mac(&mac, &compute_mac(&key, "sid", "other")));
        assert!(!verify_mac(&mac, ""));
    }

    #[test]
    fn qr_payload_carries_the_documented_fields() {
        let mut store = SessionStore::new(SESSION_TTL_SECONDS);
        let s = store.create(1_000, hosts()).unwrap();
        let p = &s.view()["payload"];
        assert_eq!(p["v"], 1);
        assert_eq!(p["t"], "zfc");
        assert_eq!(p["h"], json!(["192.168.1.10"]));
        assert_eq!(p["e"], 1_000 + SESSION_TTL_SECONDS);
        assert_eq!(b64url_decode(p["k"].as_str().unwrap()).unwrap(), s.secret);
        assert_eq!(p["s"], s.sid.as_str());
    }

    #[test]
    fn no_hosts_is_an_error_not_an_empty_qr() {
        assert!(SessionStore::new(SESSION_TTL_SECONDS).create(0, vec![]).is_err());
    }

    #[test]
    fn second_connection_is_busy_until_the_first_releases() {
        let mut store = SessionStore::new(SESSION_TTL_SECONDS);
        let sid = store.create(0, hosts()).unwrap().sid.clone();
        assert_eq!(store.authorize(&sid, 1), Authorize::Authorized);
        assert_eq!(store.authorize(&sid, 2), Authorize::Busy);
        store.release(1);
        assert_eq!(store.authorize(&sid, 2), Authorize::Authorized, "reconnect window");
    }

    #[test]
    fn a_closed_session_is_unknown() {
        let mut store = SessionStore::new(SESSION_TTL_SECONDS);
        let sid = store.create(0, hosts()).unwrap().sid.clone();
        store.invalidate();
        assert!(store.lookup(&sid).is_none());
        assert!(store.needs_regeneration(0, &hosts()));
    }

    #[test]
    fn expiry_regenerates_except_during_a_live_cast() {
        let mut store = SessionStore::new(10);
        let sid = store.create(0, hosts()).unwrap().sid.clone();
        assert!(!store.needs_regeneration(9, &hosts()));
        assert!(store.needs_regeneration(10, &hosts()), "pending + expired");
        store.authorize(&sid, 1);
        assert!(!store.needs_regeneration(10_000, &hosts()), "a live cast outlives the TTL");
        store.release(1);
        assert!(store.needs_regeneration(10_000, &hosts()), "dropped past TTL → re-scan");
    }

    #[test]
    fn host_change_regenerates_only_a_pending_session() {
        let mut store = SessionStore::new(SESSION_TTL_SECONDS);
        let sid = store.create(0, vec!["10.0.0.2".into(), "192.168.1.10".into()]).unwrap().sid.clone();
        let reordered = vec!["192.168.1.10".to_string(), "10.0.0.2".to_string()];
        assert!(!store.needs_regeneration(1, &reordered), "order alone is not a change");
        assert!(store.needs_regeneration(1, &hosts()), "pending + new IPv4 set");
        store.authorize(&sid, 1);
        assert!(!store.needs_regeneration(1, &hosts()), "authorized sessions never regenerate for hosts");
    }

    #[test]
    fn trust_file_is_owner_only_round_trips_and_clears() {
        let dir = scratch("trust");
        let path = dir.join("nested/trust.json"); // parent does not exist yet
        write_trust(&path, &entry()).expect("write");
        let mode = std::fs::metadata(&path).unwrap().permissions().mode() & 0o777;
        assert_eq!(mode, 0o600, "the trustKey file must not be group/world readable");
        let back = read_trust(&path).expect("read back");
        assert_eq!((back.device_id.as_str(), back.key.as_slice()), ("device-1234", &[7u8; 32][..]));
        assert!(!path.with_extension("tmp").exists(), "no temp file left behind");
        clear_trust(&path).unwrap();
        assert!(read_trust(&path).is_none());
        clear_trust(&path).expect("clearing twice is fine");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn rewriting_trust_tightens_a_loose_existing_file() {
        let dir = scratch("rewrite");
        let path = dir.join("trust.json");
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(&path, "{}").unwrap();
        std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o644)).unwrap();
        std::fs::write(path.with_extension("tmp"), "stale").unwrap();
        std::fs::set_permissions(path.with_extension("tmp"), std::fs::Permissions::from_mode(0o644)).unwrap();
        write_trust(&path, &entry()).expect("write");
        assert_eq!(std::fs::metadata(&path).unwrap().permissions().mode() & 0o777, 0o600);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn desktop_id_is_created_once_and_stable() {
        let dir = scratch("id");
        let path = dir.join("desktop-id");
        let first = desktop_id(&path).unwrap();
        assert!(is_uuid(&first), "{first}");
        assert_eq!(desktop_id(&path).unwrap(), first);
        std::fs::write(&path, "garbage").unwrap();
        assert_ne!(desktop_id(&path).unwrap(), "garbage", "an unreadable id is replaced");
        let _ = std::fs::remove_dir_all(&dir);
    }
}
