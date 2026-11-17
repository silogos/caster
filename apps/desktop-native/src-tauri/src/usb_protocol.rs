//! The USB link's wire format and the desktop's half of its handshake
//! (docs/architecture/usb-link.md, ADR-034). Pure — no USB, no clock — so
//! the protocol is tested without a phone; `usb_link` does the I/O.

use serde_json::{json, Value};

pub const LINK_VERSION: u8 = 1;
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
pub struct Broken(pub String);

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
                return Err(Broken(format!("link version {version}, expected {LINK_VERSION}")));
            }
            if kind > KIND_MIC_AUDIO {
                return Err(Broken(format!("unknown frame kind {kind}")));
            }
            if len > MAX_PAYLOAD {
                return Err(Broken(format!("payload length {len} over the cap")));
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

/// The trust the desktop holds for its one paired phone (pairing.rs).
pub struct Trust<'a> {
    pub device_id: &'a str,
    pub key: &'a [u8],
}

pub fn hello(desktop_id: &str, name: &str, nonce: &str) -> Value {
    json!({ "type": "hello", "link": LINK_VERSION, "desktopId": desktop_id, "name": name, "n": nonce })
}

/// The phone's `auth` against the desktop's own nonce. `Ok(auth-ok)` with the
/// desktop's proof; `Err(error)` to send back when the phone is unknown or
/// its proof is wrong (both read `unknown-device`: a wrong mac must not tell
/// an attacker which part was wrong).
pub fn answer_auth(auth: &Value, trust: Option<&Trust>, desktop_id: &str, desktop_nonce: &str) -> Result<Value, Value> {
    let refuse = || json!({ "type": "error", "code": "unknown-device" });
    let Some(trust) = trust else { return Err(refuse()) };
    let device_id = auth["deviceId"].as_str().unwrap_or_default();
    let mac = auth["mac"].as_str().unwrap_or_default();
    let phone_nonce = auth["n"].as_str().unwrap_or_default();
    if device_id != trust.device_id || phone_nonce.is_empty() {
        return Err(refuse());
    }
    let expected = crate::pairing::compute_mac(trust.key, device_id, desktop_nonce);
    if !crate::pairing::verify_mac(&expected, mac) {
        return Err(refuse());
    }
    Ok(json!({ "type": "auth-ok", "mac": crate::pairing::compute_mac(trust.key, desktop_id, phone_nonce) }))
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
            vec![1, 2, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8, 0, 0, 0, 2, 0xAA, 0xBB]
        );
    }

    #[test]
    fn a_wrong_version_an_unknown_kind_or_a_huge_length_breaks_the_stream() {
        let mut bad_version = encode(KIND_VIDEO, 0, 0, &[]);
        bad_version[0] = 2;
        assert!(Reader::default().feed(&bad_version).is_err());
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

    const KEY: [u8; 32] = [7; 32];

    fn phone_auth(device_id: &str, key: &[u8], desktop_nonce: &str, phone_nonce: &str) -> Value {
        json!({ "type": "auth", "deviceId": device_id, "n": phone_nonce,
                "mac": crate::pairing::compute_mac(key, device_id, desktop_nonce) })
    }

    #[test]
    fn the_paired_phone_is_answered_with_the_desktops_own_proof() {
        let trust = Trust { device_id: "phone-1", key: &KEY };
        let ok = answer_auth(&phone_auth("phone-1", &KEY, "dn", "pn"), Some(&trust), "desk-1", "dn").unwrap();
        assert_eq!(ok["type"], "auth-ok");
        assert_eq!(ok["mac"], crate::pairing::compute_mac(&KEY, "desk-1", "pn"));
    }

    /// The phone's UsbHandshakeTest pins the same two macs: both apps compute the proofs alike.
    #[test]
    fn the_proofs_match_the_phones_vectors() {
        assert_eq!(crate::pairing::compute_mac(&KEY, "phone-1", "dn"), "VIO7LRtb_4ZGrSSviktpCWrQe2mS2ssQvKvfjCLdGGo");
        assert_eq!(crate::pairing::compute_mac(&KEY, "desk-1", "pn"), "bCa575Km4Dj4Q8UcJQwpyM1dkQHy4uaHI2Di-LX4CDI");
    }

    #[test]
    fn a_wrong_key_another_phone_or_no_pairing_reads_unknown_device() {
        let trust = Trust { device_id: "phone-1", key: &KEY };
        let wrong_key = answer_auth(&phone_auth("phone-1", &[8; 32], "dn", "pn"), Some(&trust), "desk-1", "dn");
        let other_phone = answer_auth(&phone_auth("phone-2", &KEY, "dn", "pn"), Some(&trust), "desk-1", "dn");
        let replayed = answer_auth(&phone_auth("phone-1", &KEY, "old", "pn"), Some(&trust), "desk-1", "dn");
        let unpaired = answer_auth(&phone_auth("phone-1", &KEY, "dn", "pn"), None, "desk-1", "dn");
        for refused in [wrong_key, other_phone, replayed, unpaired] {
            assert_eq!(refused.unwrap_err()["code"], "unknown-device");
        }
    }
}
