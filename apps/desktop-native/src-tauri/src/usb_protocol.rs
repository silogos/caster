//! The USB link's wire format and the desktop's half of its handshake
//! (docs/architecture/usb-link.md v2, ADR-034, ADR-037). Pure — no USB, no
//! clock, no prompt — so the protocol is tested without a phone; `usb_link`
//! does the I/O and asks the user.

use serde_json::{json, Value};

pub const LINK_VERSION: u8 = 2;
pub const HEADER_LEN: usize = 16;
pub const MAX_PAYLOAD: usize = 4 * 1024 * 1024;

pub const KIND_CONTROL: u8 = 0;
pub const KIND_VIDEO: u8 = 1;
pub const KIND_GAME_AUDIO: u8 = 2;
pub const KIND_MIC_AUDIO: u8 = 3;

#[cfg_attr(not(test), allow(dead_code))] // the phone sets it; the receiver decodes every unit
pub const FLAG_KEYFRAME: u16 = 0x1;
pub const FLAG_CONFIG: u16 = 0x2;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Frame {
    pub kind: u8,
    pub flags: u16,
    pub pts_us: u64,
    pub payload: Vec<u8>,
}

pub fn encode(kind: u8, flags: u16, pts_us: u64, payload: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(HEADER_LEN + payload.len());
    out.push(LINK_VERSION);
    out.push(kind);
    out.extend_from_slice(&flags.to_be_bytes());
    out.extend_from_slice(&pts_us.to_be_bytes());
    out.extend_from_slice(&(payload.len() as u32).to_be_bytes());
    out.extend_from_slice(payload);
    out
}

pub fn control(message: &Value) -> Vec<u8> {
    encode(KIND_CONTROL, 0, 0, message.to_string().as_bytes())
}

/// The mic's link format (48 kHz mono PCM16LE) into the engine's 48 kHz
/// stereo: each sample on both channels. An odd trailing byte is dropped.
pub fn mono_to_stereo(mono: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(mono.len() * 2);
    for sample in mono.chunks_exact(2) {
        out.extend_from_slice(sample);
        out.extend_from_slice(sample);
    }
    out
}

/// A stream that cannot be read any further: the link closes (the reader
/// never resynchronises by searching — usb-link.md).
#[derive(Debug, PartialEq, Eq)]
pub struct Broken {
    pub why: String,
    /// Set when the peer speaks another link version (usb-link.md §Version mismatch).
    pub peer_version: Option<u8>,
}

impl Broken {
    fn new(why: String) -> Self {
        Self { why, peer_version: None }
    }
}

/// Bulk reads (any split) back into frames.
#[derive(Default)]
pub struct Reader {
    buf: Vec<u8>,
}

impl Reader {
    pub fn feed(&mut self, bytes: &[u8]) -> Result<Vec<Frame>, Broken> {
        self.buf.extend_from_slice(bytes);
        let mut frames = Vec::new();
        let mut start = 0;
        while self.buf.len() - start >= HEADER_LEN {
            let h = &self.buf[start..start + HEADER_LEN];
            let (version, kind) = (h[0], h[1]);
            let flags = u16::from_be_bytes([h[2], h[3]]);
            let pts_us = u64::from_be_bytes(h[4..12].try_into().expect("8 bytes"));
            let len = u32::from_be_bytes(h[12..16].try_into().expect("4 bytes")) as usize;
            if version != LINK_VERSION {
                return Err(Broken {
                    why: format!("phone speaks link {version}, expected {LINK_VERSION}"),
                    peer_version: Some(version),
                });
            }
            if kind > KIND_MIC_AUDIO {
                return Err(Broken::new(format!("unknown frame kind {kind}")));
            }
            if len > MAX_PAYLOAD {
                return Err(Broken::new(format!("payload length {len} over the cap")));
            }
            if self.buf.len() - start < HEADER_LEN + len {
                break;
            }
            let payload = self.buf[start + HEADER_LEN..start + HEADER_LEN + len].to_vec();
            frames.push(Frame { kind, flags, pts_us, payload });
            start += HEADER_LEN + len;
        }
        self.buf.drain(..start);
        Ok(frames)
    }
}

// ---- the handshake (usb-link.md §Control messages) -------------------------

/// The cable key's HKDF info (usb-link.md §The cable key).
const CABLE_INFO: &[u8] = b"caster-cable-v2";
const CABLE_KEY_BYTES: usize = 32;
/// An uncompressed SEC1 P-256 point: 0x04 ‖ X ‖ Y.
const POINT_BYTES: usize = 65;

pub const ERROR_UNKNOWN_DEVICE: &str = "unknown-device";
pub const ERROR_NOT_ALLOWED: &str = "not-allowed";
pub const ERROR_BUSY: &str = "busy";
/// The `bye` reason that also ends the cable trust.
pub const BYE_FORGET: &str = "forget";

/// The cable pairing the desktop holds (cable-trust.json, pairing.rs).
pub struct Trust<'a> {
    pub device_id: &'a str,
    pub key: &'a [u8],
}

pub fn hello(desktop_id: &str, name: &str, nonce: &str) -> Value {
    json!({ "type": "hello", "link": LINK_VERSION, "desktopId": desktop_id, "name": name, "n": nonce })
}

pub fn error(code: &str) -> Value {
    json!({ "type": "error", "code": code })
}

pub fn bye(reason: Option<&str>) -> Value {
    match reason {
        Some(r) => json!({ "type": "bye", "reason": r }),
        None => json!({ "type": "bye" }),
    }
}

/// One side's ephemeral key, made for one enrolment and discarded after it.
pub struct Ephemeral(p256::SecretKey);

impl Ephemeral {
    pub fn random() -> Self {
        Self(p256::SecretKey::random(&mut rand::rngs::OsRng))
    }

    #[cfg(test)]
    fn from_scalar(bytes: &[u8; 32]) -> Self {
        Self(p256::SecretKey::from_slice(bytes).expect("a valid test scalar"))
    }

    /// The public point, uncompressed SEC1 (65 bytes).
    pub fn public(&self) -> Vec<u8> {
        use p256::elliptic_curve::sec1::ToEncodedPoint;
        self.0.public_key().to_encoded_point(false).as_bytes().to_vec()
    }

    /// Z, the shared point's X (32 bytes); `None` when the peer's point is not on the curve.
    pub fn agree(&self, peer: &[u8]) -> Option<Vec<u8>> {
        if peer.len() != POINT_BYTES || peer[0] != 0x04 {
            return None;
        }
        let peer = p256::PublicKey::from_sec1_bytes(peer).ok()?;
        let shared = p256::ecdh::diffie_hellman(self.0.to_nonzero_scalar(), peer.as_affine());
        Some(shared.raw_secret_bytes().to_vec())
    }
}

/// SHA-256(phone pub ‖ desktop pub ‖ deviceId ‖ 0 ‖ desktopId ‖ 0 ‖ desktop n ‖ 0 ‖ phone n).
pub fn transcript(phone_pub: &[u8], desktop_pub: &[u8], device_id: &str, desktop_id: &str, desktop_n: &str, phone_n: &str) -> Vec<u8> {
    use sha2::{Digest, Sha256};
    let mut h = Sha256::new();
    h.update(phone_pub);
    h.update(desktop_pub);
    h.update(device_id.as_bytes());
    h.update([0u8]);
    h.update(desktop_id.as_bytes());
    h.update([0u8]);
    h.update(desktop_n.as_bytes());
    h.update([0u8]);
    h.update(phone_n.as_bytes());
    h.finalize().to_vec()
}

/// cableKey = HKDF-SHA256(ikm = Z, salt = transcript, info = "caster-cable-v2", L = 32).
pub fn cable_key(z: &[u8], transcript: &[u8]) -> Vec<u8> {
    let hk = hkdf::Hkdf::<sha2::Sha256>::new(Some(transcript), z);
    let mut okm = [0u8; CABLE_KEY_BYTES];
    hk.expand(CABLE_INFO, &mut okm).expect("32 bytes is a valid HKDF length");
    okm.to_vec()
}

/// A phone the user allowed: what cable-trust.json keeps.
#[derive(Debug, PartialEq)]
pub struct Enrolled {
    pub device_id: String,
    pub name: String,
    pub key: Vec<u8>,
}

struct Enrolling {
    device_id: String,
    name: String,
    key: Vec<u8>,
    phone_nonce: String,
    /// Its `auth` checked: the user is being asked.
    asking: bool,
}

/// What the link does next.
#[derive(Debug, PartialEq)]
pub enum Outcome {
    /// Send this and carry on.
    Reply(Value),
    /// A known phone proved itself: send `reply`; the link is trusted.
    Trusted { reply: Value },
    /// An enrolling phone proved it derived the new key: ask the user about `name`
    /// (usb-link.md: the prompt opens only now, so a peer that can't derive it never reaches the user).
    Ask { name: String },
    /// Not a protocol peer (a point off the curve): close the link.
    Close(String),
}

/// The desktop's half of one attach's handshake. One hello nonce per
/// handshake; one prompt per phone per attach.
pub struct Handshake {
    desktop_id: String,
    desktop_nonce: String,
    enrolling: Option<Enrolling>,
    refused: std::collections::HashSet<String>,
}

impl Handshake {
    pub fn new(desktop_id: &str, desktop_nonce: String) -> Self {
        Self { desktop_id: desktop_id.into(), desktop_nonce, enrolling: None, refused: Default::default() }
    }

    pub fn desktop_nonce(&self) -> &str {
        &self.desktop_nonce
    }

    /// The user is being asked about this phone (its name).
    #[cfg_attr(not(test), allow(dead_code))] // the session keeps its own prompt clock
    pub fn asking(&self) -> Option<&str> {
        self.enrolling.as_ref().filter(|e| e.asking).map(|e| e.name.as_str())
    }

    /// A new handshake on the same attach (a trusted session's app reopened
    /// the accessory): a fresh nonce, anything half-done dropped. Refusals
    /// stay for the attach.
    pub fn restart(&mut self, desktop_nonce: String) {
        self.desktop_nonce = desktop_nonce;
        self.enrolling = None;
    }

    /// `ready` mid-handshake: an open prompt closes and the enrolment starts over; the nonce stays.
    pub fn drop_enrolment(&mut self) {
        self.enrolling = None;
    }

    /// `enrol {deviceId, ua, pub, n}`. `paired` is the receiver's cable pairing, if any; `own` a fresh key.
    pub fn on_enrol(&mut self, msg: &Value, paired: Option<&Trust>, own: &Ephemeral) -> Outcome {
        let device_id = msg["deviceId"].as_str().unwrap_or_default();
        let phone_nonce = msg["n"].as_str().unwrap_or_default();
        if device_id.is_empty() || device_id.chars().count() > crate::protocol::MAX_DEVICE_ID_LENGTH || phone_nonce.is_empty() {
            return Outcome::Reply(error(ERROR_UNKNOWN_DEVICE));
        }
        if paired.is_some_and(|t| t.device_id != device_id) {
            return Outcome::Reply(error(ERROR_BUSY));
        }
        if self.refused.contains(device_id) {
            return Outcome::Reply(error(ERROR_NOT_ALLOWED));
        }
        let phone_pub = msg["pub"].as_str().and_then(crate::pairing::b64url_decode).unwrap_or_default();
        let Some(z) = own.agree(&phone_pub) else {
            return Outcome::Close("enrol: the phone's key is not a P-256 point".into());
        };
        let own_pub = own.public();
        let t = transcript(&phone_pub, &own_pub, device_id, &self.desktop_id, &self.desktop_nonce, phone_nonce);
        self.enrolling = Some(Enrolling {
            device_id: device_id.into(),
            name: crate::protocol::device_name_from_ua(msg["ua"].as_str().unwrap_or_default()),
            key: cable_key(&z, &t),
            phone_nonce: phone_nonce.into(),
            asking: false,
        });
        Outcome::Reply(json!({ "type": "enrol-key", "pub": crate::pairing::b64url(&own_pub) }))
    }

    /// `auth {deviceId, n, mac}`: the enrolling phone's (with its new key) or a known phone's
    /// (with the stored key). A wrong key and an unknown phone both read `unknown-device`:
    /// a wrong mac must not tell an attacker which part was wrong.
    pub fn on_auth(&mut self, msg: &Value, paired: Option<&Trust>) -> Outcome {
        let device_id = msg["deviceId"].as_str().unwrap_or_default();
        let mac = msg["mac"].as_str().unwrap_or_default();
        let phone_nonce = msg["n"].as_str().unwrap_or_default();
        if let Some(e) = self.enrolling.as_mut().filter(|e| e.device_id == device_id && !e.asking) {
            let expected = crate::pairing::compute_mac(&e.key, device_id, &self.desktop_nonce);
            if phone_nonce == e.phone_nonce && crate::pairing::verify_mac(&expected, mac) {
                e.asking = true;
                return Outcome::Ask { name: e.name.clone() };
            }
            self.enrolling = None;
            return Outcome::Reply(error(ERROR_UNKNOWN_DEVICE));
        }
        let Some(trust) = paired.filter(|t| t.device_id == device_id && !phone_nonce.is_empty()) else {
            return Outcome::Reply(error(ERROR_UNKNOWN_DEVICE));
        };
        let expected = crate::pairing::compute_mac(trust.key, device_id, &self.desktop_nonce);
        if !crate::pairing::verify_mac(&expected, mac) {
            return Outcome::Reply(error(ERROR_UNKNOWN_DEVICE));
        }
        Outcome::Trusted { reply: auth_ok(trust.key, &self.desktop_id, phone_nonce) }
    }

    /// The user tapped Allow: `auth-ok` to send and the pairing to store. `None` when nobody was being asked.
    pub fn allow(&mut self) -> Option<(Value, Enrolled)> {
        let e = self.enrolling.take().filter(|e| e.asking)?;
        let reply = auth_ok(&e.key, &self.desktop_id, &e.phone_nonce);
        Some((reply, Enrolled { device_id: e.device_id, name: e.name, key: e.key }))
    }

    /// Don't Allow, a closed prompt, or 60 s: `not-allowed`, and no second prompt for this phone on this attach.
    pub fn deny(&mut self) -> Option<Value> {
        let e = self.enrolling.take().filter(|e| e.asking)?;
        self.refused.insert(e.device_id);
        Some(error(ERROR_NOT_ALLOWED))
    }
}

fn auth_ok(key: &[u8], desktop_id: &str, phone_nonce: &str) -> Value {
    json!({ "type": "auth-ok", "mac": crate::pairing::compute_mac(key, desktop_id, phone_nonce) })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_frame_round_trips_through_any_split() {
        let a = encode(KIND_VIDEO, FLAG_KEYFRAME, 123_456_789, &[1, 2, 3, 4, 5]);
        let b = control(&json!({"type": "cast-stop"}));
        let stream: Vec<u8> = a.iter().chain(b.iter()).copied().collect();
        for split in 0..stream.len() {
            let mut r = Reader::default();
            let mut frames = r.feed(&stream[..split]).unwrap();
            frames.extend(r.feed(&stream[split..]).unwrap());
            assert_eq!(frames.len(), 2, "split at {split}");
            assert_eq!(frames[0], Frame { kind: KIND_VIDEO, flags: FLAG_KEYFRAME, pts_us: 123_456_789, payload: vec![1, 2, 3, 4, 5] });
            assert_eq!(frames[1].kind, KIND_CONTROL);
        }
    }

    /// The same bytes the phone's UsbFrameTest pins: both apps agree on the header.
    #[test]
    fn the_header_matches_the_phones_vector() {
        assert_eq!(
            encode(KIND_GAME_AUDIO, 0, 0x0102_0304_0506_0708, &[0xAA, 0xBB]),
            vec![2, 2, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8, 0, 0, 0, 2, 0xAA, 0xBB]
        );
    }

    #[test]
    fn a_wrong_version_an_unknown_kind_or_a_huge_length_breaks_the_stream() {
        let mut v1_phone = encode(KIND_CONTROL, 0, 0, &[]);
        v1_phone[0] = 1;
        assert_eq!(Reader::default().feed(&v1_phone).unwrap_err().peer_version, Some(1));
        let mut bad_kind = encode(KIND_VIDEO, 0, 0, &[]);
        bad_kind[1] = 9;
        assert!(Reader::default().feed(&bad_kind).is_err());
        let mut huge = encode(KIND_VIDEO, 0, 0, &[]);
        huge[12..16].copy_from_slice(&((MAX_PAYLOAD as u32) + 1).to_be_bytes());
        assert!(Reader::default().feed(&huge).is_err());
    }

    #[test]
    fn the_mono_mic_becomes_the_same_sample_on_both_channels() {
        assert_eq!(mono_to_stereo(&[0x01, 0x02, 0xFF, 0x7F, 0x09]), vec![0x01, 0x02, 0x01, 0x02, 0xFF, 0x7F, 0xFF, 0x7F]);
    }

    // ---- the cable key: the vectors the phone's UsbHandshakeTest pins too ----

    const PHONE_SCALAR: [u8; 32] = [0x11; 32];
    const DESKTOP_SCALAR: [u8; 32] = [0x22; 32];
    const PHONE_PUB: &str = "BAIX5hfwtkQ5KCePlpmeaaI6TywVK99tbN9m5bgCgtTtGUp968uXcS0t2jyoWqh2Wlb0X8dYWZZS8ol8ZTBuV5Q";
    const DESKTOP_PUB: &str = "BNZak5d8qj0bCBhS_1ennkZfFmBXcwS66tUF3TpIWJzzUBheiVNy32Ih6joTdVfkc_3bZ1XwW9UHw8Uz_OnJEoU";
    const Z_HEX: &str = "ccfc261f58193c98ca4ad4a53bbac6f0ee29bc4d48438090446908622ca79af6";
    const TRANSCRIPT_HEX: &str = "3589c3627c23d56f37d297ba8996a06830b64d1df90bcfdf311e69fc30f0d11c";
    const KEY_HEX: &str = "53e857ae5d1b056b3158cba7b2d8891499cd5ead1b619af37495aa35544386f3";
    const PHONE_MAC: &str = "Nn_7TRD77Kya3nITRbptE0VGAc0fKsn2dPEkJnkl7Rc";
    const DESKTOP_MAC: &str = "i9A5anRUqmWDuD8v3MF4X2BKgrAI179ysvo8e83LhS0";

    fn hex(b: &[u8]) -> String {
        b.iter().map(|x| format!("{x:02x}")).collect()
    }

    #[test]
    fn the_cable_key_matches_the_phones_vectors() {
        let (phone, desktop) = (Ephemeral::from_scalar(&PHONE_SCALAR), Ephemeral::from_scalar(&DESKTOP_SCALAR));
        let (pp, dp) = (phone.public(), desktop.public());
        let z = desktop.agree(&pp).unwrap();
        assert_eq!(z, phone.agree(&dp).unwrap(), "both sides agree on Z");
        let t = transcript(&pp, &dp, "phone-1", "desk-1", "dn", "pn");
        let key = cable_key(&z, &t);
        let got = [
            crate::pairing::b64url(&pp), crate::pairing::b64url(&dp), hex(&z), hex(&t), hex(&key),
            crate::pairing::compute_mac(&key, "phone-1", "dn"), crate::pairing::compute_mac(&key, "desk-1", "pn"),
        ];
        assert_eq!(got, [PHONE_PUB, DESKTOP_PUB, Z_HEX, TRANSCRIPT_HEX, KEY_HEX, PHONE_MAC, DESKTOP_MAC]);
    }

    // ---- the handshake ----

    /// The phone's half, as UsbHandshake.kt does it: enrol → derive → auth.
    struct Phone {
        key: Ephemeral,
        device_id: &'static str,
        n: &'static str,
    }

    impl Phone {
        fn new(device_id: &'static str) -> Self {
            Self { key: Ephemeral::from_scalar(&PHONE_SCALAR), device_id, n: "pn" }
        }
        fn enrol(&self) -> Value {
            json!({ "type": "enrol", "deviceId": self.device_id, "ua": "Caster/0.3 (Android 15; Lenovo TB321FU)",
                    "pub": crate::pairing::b64url(&self.key.public()), "n": self.n })
        }
        fn derive(&self, enrol_key: &Value, desktop_nonce: &str) -> Vec<u8> {
            let dp = crate::pairing::b64url_decode(enrol_key["pub"].as_str().unwrap()).unwrap();
            let z = self.key.agree(&dp).unwrap();
            cable_key(&z, &transcript(&self.key.public(), &dp, self.device_id, "desk-1", desktop_nonce, self.n))
        }
        fn auth(&self, key: &[u8], desktop_nonce: &str) -> Value {
            json!({ "type": "auth", "deviceId": self.device_id, "n": self.n,
                    "mac": crate::pairing::compute_mac(key, self.device_id, desktop_nonce) })
        }
    }

    fn reply(o: Outcome) -> Value {
        match o {
            Outcome::Reply(v) => v,
            other => panic!("expected a reply, got {other:?}"),
        }
    }

    /// Enrol up to the prompt; returns the phone's key.
    fn up_to_the_prompt(h: &mut Handshake, phone: &Phone) -> Vec<u8> {
        let enrol_key = reply(h.on_enrol(&phone.enrol(), None, &Ephemeral::from_scalar(&DESKTOP_SCALAR)));
        assert_eq!(enrol_key["type"], "enrol-key");
        let key = phone.derive(&enrol_key, "dn");
        assert_eq!(h.on_auth(&phone.auth(&key, "dn"), None), Outcome::Ask { name: "Lenovo TB321FU".into() });
        key
    }

    #[test]
    fn a_new_phone_is_asked_about_once_its_auth_checks_and_allow_pairs_it() {
        let mut h = Handshake::new("desk-1", "dn".into());
        let phone = Phone::new("phone-1");
        let key = up_to_the_prompt(&mut h, &phone);
        assert_eq!(h.asking(), Some("Lenovo TB321FU"));
        let (ok, enrolled) = h.allow().unwrap();
        assert_eq!(ok, json!({ "type": "auth-ok", "mac": crate::pairing::compute_mac(&key, "desk-1", "pn") }));
        assert_eq!(enrolled, Enrolled { device_id: "phone-1".into(), name: "Lenovo TB321FU".into(), key });
        assert!(h.allow().is_none(), "one answer per prompt");
    }

    #[test]
    fn the_next_plug_in_is_trusted_with_the_stored_key_and_no_prompt() {
        let phone = Phone::new("phone-1");
        let key = {
            let mut first = Handshake::new("desk-1", "dn".into());
            up_to_the_prompt(&mut first, &phone);
            first.allow().unwrap().1.key
        };
        let mut h = Handshake::new("desk-1", "dn2".into());
        let trust = Trust { device_id: "phone-1", key: &key };
        let out = h.on_auth(&phone.auth(&key, "dn2"), Some(&trust));
        assert_eq!(out, Outcome::Trusted { reply: auth_ok(&key, "desk-1", "pn") });
        assert_eq!(h.asking(), None);
    }

    #[test]
    fn dont_allow_refuses_and_the_same_phone_is_not_asked_again_on_this_attach() {
        let mut h = Handshake::new("desk-1", "dn".into());
        let phone = Phone::new("phone-1");
        up_to_the_prompt(&mut h, &phone);
        assert_eq!(h.deny(), Some(error(ERROR_NOT_ALLOWED)));
        let again = reply(h.on_enrol(&phone.enrol(), None, &Ephemeral::random()));
        assert_eq!(again, error(ERROR_NOT_ALLOWED));
    }

    #[test]
    fn a_receiver_paired_with_another_phone_answers_busy() {
        let mut h = Handshake::new("desk-1", "dn".into());
        let trust = Trust { device_id: "phone-0", key: &[7; 32] };
        let out = reply(h.on_enrol(&Phone::new("phone-1").enrol(), Some(&trust), &Ephemeral::random()));
        assert_eq!(out, error(ERROR_BUSY));
    }

    #[test]
    fn a_peer_that_cant_derive_the_key_never_reaches_the_prompt() {
        let mut h = Handshake::new("desk-1", "dn".into());
        let phone = Phone::new("phone-1");
        reply(h.on_enrol(&phone.enrol(), None, &Ephemeral::from_scalar(&DESKTOP_SCALAR)));
        assert_eq!(reply(h.on_auth(&phone.auth(&[9; 32], "dn"), None)), error(ERROR_UNKNOWN_DEVICE));
        assert_eq!(h.asking(), None);
        assert!(h.allow().is_none());
    }

    #[test]
    fn a_point_off_the_curve_closes_the_link() {
        let mut h = Handshake::new("desk-1", "dn".into());
        let mut msg = Phone::new("phone-1").enrol();
        let mut bad = vec![0x04];
        bad.extend([0xFF; 64]);
        msg["pub"] = json!(crate::pairing::b64url(&bad));
        assert!(matches!(h.on_enrol(&msg, None, &Ephemeral::random()), Outcome::Close(_)));
    }

    #[test]
    fn a_wrong_key_another_phone_a_replay_or_no_pairing_reads_unknown_device() {
        let key = [7u8; 32];
        let trust = Trust { device_id: "phone-1", key: &key };
        let p1 = Phone::new("phone-1");
        let cases = [
            (p1.auth(&[8; 32], "dn"), Some(&trust)),
            (Phone::new("phone-2").auth(&key, "dn"), Some(&trust)),
            (p1.auth(&key, "old"), Some(&trust)),
            (p1.auth(&key, "dn"), None),
        ];
        for (auth, paired) in cases {
            let mut h = Handshake::new("desk-1", "dn".into());
            assert_eq!(reply(h.on_auth(&auth, paired)), error(ERROR_UNKNOWN_DEVICE));
        }
    }

    #[test]
    fn ready_mid_prompt_drops_the_enrolment() {
        let mut h = Handshake::new("desk-1", "dn".into());
        up_to_the_prompt(&mut h, &Phone::new("phone-1"));
        h.drop_enrolment();
        assert_eq!(h.asking(), None);
        assert!(h.deny().is_none() && h.allow().is_none());
    }
}
