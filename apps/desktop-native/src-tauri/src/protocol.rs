//! The signaling protocol's message shapes (docs/architecture/webrtc.md,
//! pairing.md) — pure parsing and validation, no sockets, no WebRTC. The
//! server in `signaling.rs` decides what to do; this module only says
//! whether a frame is well-formed.

use serde_json::Value;

/// Envelope version (`v`); a mismatch is the terminal `bad-version`.
pub const ENVELOPE_VERSION: i64 = 1;
/// Guards against corrupt frames (webrtc.md §Envelope).
pub const ENVELOPE_MAX_BYTES: usize = 256 * 1024;
/// The protocol this receiver speaks. Proto 2 adds `vc-*` and
/// `audio-routing-*` (ADR-007), which the native receiver does not
/// implement — so it negotiates 1 and a proto-2 phone simply never sends them.
pub const DESKTOP_PROTO_MIN: i64 = 1;
pub const DESKTOP_PROTO_MAX: i64 = 1;
/// `hello.deviceId` bound (pairing.md, ADR-005).
pub const MAX_DEVICE_ID_LENGTH: usize = 128;
/// Reserved `bye` reason: end the session AND revoke trust (ADR-006).
pub const BYE_FORGET: &str = "forget";

/// Error codes (webrtc.md `error`). All but `bad-message` are terminal.
pub mod code {
    pub const BAD_VERSION: &str = "bad-version";
    pub const UNKNOWN_SESSION: &str = "unknown-session";
    pub const EXPIRED: &str = "expired";
    pub const BAD_AUTH: &str = "bad-auth";
    pub const BUSY: &str = "busy";
    pub const BAD_MESSAGE: &str = "bad-message";
    pub const UNKNOWN_DEVICE: &str = "unknown-device";
}

#[derive(Debug)]
pub struct Envelope {
    pub msg_type: String,
    pub sid: String,
    pub payload: Value,
}

/// Why a frame was rejected, and whether the socket must close.
#[derive(Debug, PartialEq, Eq)]
pub struct Rejection {
    pub code: &'static str,
    pub reason: String,
    pub close: bool,
}

impl Rejection {
    fn recoverable(reason: impl Into<String>) -> Self {
        Self { code: code::BAD_MESSAGE, reason: reason.into(), close: false }
    }
    fn terminal(code: &'static str, reason: impl Into<String>) -> Self {
        Self { code, reason: reason.into(), close: true }
    }
}

pub fn parse_envelope(raw: &str) -> Result<Envelope, Rejection> {
    if raw.len() > ENVELOPE_MAX_BYTES {
        return Err(Rejection::terminal(code::BAD_MESSAGE, "envelope exceeds 256 KiB"));
    }
    let doc: Value = serde_json::from_str(raw).map_err(|_| Rejection::recoverable("envelope is not JSON"))?;
    if doc["v"].as_i64() != Some(ENVELOPE_VERSION) {
        return Err(Rejection::terminal(code::BAD_VERSION, "unsupported envelope version"));
    }
    let (Some(msg_type), Some(seq), Some(sid)) = (doc["type"].as_str(), doc["seq"].as_i64(), doc["sid"].as_str()) else {
        return Err(Rejection::recoverable("envelope missing type/seq/sid"));
    };
    if !doc["payload"].is_object() {
        return Err(Rejection::recoverable("envelope payload must be an object"));
    }
    // `seq` is informational (webrtc.md: ordering not enforced) — only its presence is checked.
    let _ = seq;
    Ok(Envelope { msg_type: msg_type.to_string(), sid: sid.to_string(), payload: doc["payload"].clone() })
}

#[derive(Debug, Clone)]
pub struct Hello {
    pub ua: String,
    pub device_id: Option<String>,
    pub resume: bool,
    pub proto_min: i64,
    pub proto_max: i64,
}

/// `hello {ua, deviceId?, resume?, protoMin, protoMax}` — a malformed hello
/// is a terminal `bad-message`.
pub fn parse_hello(payload: &Value) -> Result<Hello, Rejection> {
    let bad = |what: &str| Rejection::terminal(code::BAD_MESSAGE, format!("malformed hello {what}"));
    let (Some(ua), Some(proto_min), Some(proto_max)) =
        (payload["ua"].as_str(), payload["protoMin"].as_i64(), payload["protoMax"].as_i64())
    else {
        return Err(bad("payload"));
    };
    let device_id = match &payload["deviceId"] {
        Value::Null => None,
        Value::String(id) if !id.is_empty() && id.chars().count() <= MAX_DEVICE_ID_LENGTH => Some(id.clone()),
        _ => return Err(bad("deviceId")),
    };
    let resume = match &payload["resume"] {
        Value::Null => false,
        Value::Bool(b) => *b,
        _ => return Err(bad("resume")),
    };
    Ok(Hello { ua: ua.to_string(), device_id, resume, proto_min, proto_max })
}

/// The highest version both sides support, or `None` (→ `bad-version`).
pub fn negotiate_protocol(hello: &Hello) -> Option<i64> {
    let lo = hello.proto_min.max(DESKTOP_PROTO_MIN);
    let hi = hello.proto_max.min(DESKTOP_PROTO_MAX);
    (lo <= hi).then_some(hi)
}

/// The two PeerConnections (webrtc.md §Two PeerConnections).
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum PcId {
    Media,
    Mic,
}

impl PcId {
    pub fn parse(value: &Value) -> Option<Self> {
        match value.as_str()? {
            "media" => Some(Self::Media),
            "mic" => Some(Self::Mic),
            _ => None,
        }
    }
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Media => "media",
            Self::Mic => "mic",
        }
    }
}

pub fn parse_sdp_offer(payload: &Value) -> Result<(PcId, String), Rejection> {
    match (PcId::parse(&payload["pc"]), payload["sdp"].as_str()) {
        (Some(pc), Some(sdp)) if !sdp.is_empty() => Ok((pc, sdp.to_string())),
        _ => Err(Rejection::recoverable("malformed sdp-offer payload")),
    }
}

/// A trickled candidate with its BUNDLE identity (mid + m-line index).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Candidate {
    pub candidate: String,
    pub sdp_mid: String,
    pub sdp_mline_index: i32,
}

/// `ice {pc, candidate}`; `candidate: null` (or an empty line) is the
/// end-of-gathering marker → `None`.
pub fn parse_ice(payload: &Value) -> Result<(PcId, Option<Candidate>), Rejection> {
    let pc = PcId::parse(&payload["pc"]).ok_or_else(|| Rejection::recoverable("malformed ice payload"))?;
    let Some(object) = payload.as_object() else {
        return Err(Rejection::recoverable("malformed ice payload"));
    };
    let candidate = match object.get("candidate") {
        None => return Err(Rejection::recoverable("ice payload missing candidate")),
        Some(Value::Null) => None,
        Some(c) => match c["candidate"].as_str() {
            Some("") => None,
            Some(line) => Some(Candidate {
                candidate: line.to_string(),
                sdp_mid: c["sdpMid"].as_str().unwrap_or("0").to_string(),
                sdp_mline_index: c["sdpMLineIndex"].as_i64().and_then(|i| i32::try_from(i).ok()).unwrap_or(0),
            }),
            None => return Err(Rejection::recoverable("malformed ice candidate")),
        },
    };
    Ok((pc, candidate))
}

/// `session-info` — display-only; returned verbatim when well-formed.
pub fn parse_session_info(payload: &Value) -> Result<Value, Rejection> {
    let well_formed = payload["profile"].is_string()
        && payload["width"].is_number()
        && payload["height"].is_number()
        && payload["fps"].is_number()
        && payload["gameAudio"].is_boolean()
        && payload["mic"].is_boolean();
    if well_formed {
        Ok(payload.clone())
    } else {
        Err(Rejection::recoverable("malformed session-info payload"))
    }
}

/// The phone's model from its UA: the last `;`-separated part of the
/// trailing "(...)" group — `LocalCast/1.0 (Android 16; TB321FU)` →
/// `TB321FU`, exactly like the Electron receiver's `deviceNameFromUa`.
pub fn device_name_from_ua(ua: &str) -> String {
    let group = ua
        .trim_end()
        .strip_suffix(')')
        .and_then(|rest| rest.rsplit_once('('))
        .map(|(_, inside)| inside);
    group
        .and_then(|inside| inside.rsplit(';').next())
        .map(str::trim)
        .filter(|name| !name.is_empty() && !name.contains(['(', ')']))
        .unwrap_or("Android device")
        .to_string()
}

/// A short, char-safe log prefix of an identifier (never a byte slice of
/// network input: that panics on a multi-byte boundary).
pub fn short_id(id: &str) -> String {
    id.chars().take(8).collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn envelope(v: i64, ty: &str) -> String {
        json!({ "v": v, "type": ty, "seq": 1, "sid": "s", "payload": {} }).to_string()
    }

    #[test]
    fn envelope_rules() {
        assert_eq!(parse_envelope(&envelope(1, "hello")).unwrap().msg_type, "hello");
        let bad_v = parse_envelope(&envelope(2, "hello")).unwrap_err();
        assert_eq!((bad_v.code, bad_v.close), (code::BAD_VERSION, true));
        let not_json = parse_envelope("{").unwrap_err();
        assert_eq!((not_json.code, not_json.close), (code::BAD_MESSAGE, false));
        let missing = parse_envelope(r#"{"v":1,"type":"x","payload":{}}"#).unwrap_err();
        assert!(!missing.close);
        let huge = format!(r#"{{"v":1,"type":"x","seq":1,"sid":"s","payload":{{"p":"{}"}}}}"#, "a".repeat(ENVELOPE_MAX_BYTES));
        assert!(parse_envelope(&huge).unwrap_err().close, "oversized frames close the socket");
    }

    #[test]
    fn hello_validation() {
        let ok = parse_hello(&json!({ "ua": "zfc/1 (Pixel 8)", "protoMin": 1, "protoMax": 2 })).unwrap();
        assert_eq!((ok.device_id, ok.resume), (None, false), "older clients omit deviceId");
        let resume = parse_hello(&json!({ "ua": "x", "protoMin": 1, "protoMax": 2, "deviceId": "d", "resume": true })).unwrap();
        assert!(resume.resume);
        for bad in [
            json!({ "protoMin": 1, "protoMax": 2 }),
            json!({ "ua": "x", "protoMin": 1, "protoMax": 2, "deviceId": "" }),
            json!({ "ua": "x", "protoMin": 1, "protoMax": 2, "deviceId": "é".repeat(129) }),
            json!({ "ua": "x", "protoMin": 1, "protoMax": 2, "resume": "yes" }),
        ] {
            assert!(parse_hello(&bad).unwrap_err().close, "{bad}");
        }
        // 128 multi-byte chars is within the bound (chars, not bytes).
        assert!(parse_hello(&json!({ "ua": "x", "protoMin": 1, "protoMax": 2, "deviceId": "é".repeat(128) })).is_ok());
    }

    #[test]
    fn negotiation_picks_the_highest_common_version() {
        let hello = |min, max| Hello { ua: String::new(), device_id: None, resume: false, proto_min: min, proto_max: max };
        assert_eq!(negotiate_protocol(&hello(1, 2)), Some(1), "a proto-2 phone gets 1 here");
        assert_eq!(negotiate_protocol(&hello(1, 1)), Some(1));
        assert_eq!(negotiate_protocol(&hello(2, 3)), None);
    }

    #[test]
    fn offers_and_candidates_carry_their_pc() {
        assert_eq!(parse_sdp_offer(&json!({ "pc": "mic", "sdp": "v=0" })).unwrap().0, PcId::Mic);
        assert!(parse_sdp_offer(&json!({ "pc": "screen", "sdp": "v=0" })).is_err());
        assert!(parse_sdp_offer(&json!({ "pc": "media", "sdp": "" })).is_err());

        let (pc, c) = parse_ice(&json!({ "pc": "media", "candidate": { "candidate": "candidate:1 1 udp", "sdpMid": "2", "sdpMLineIndex": 2 } })).unwrap();
        assert_eq!(pc, PcId::Media);
        assert_eq!(c.unwrap(), Candidate { candidate: "candidate:1 1 udp".into(), sdp_mid: "2".into(), sdp_mline_index: 2 });
        assert_eq!(parse_ice(&json!({ "pc": "mic", "candidate": null })).unwrap(), (PcId::Mic, None));
        assert_eq!(parse_ice(&json!({ "pc": "mic", "candidate": { "candidate": "" } })).unwrap().1, None);
        assert!(parse_ice(&json!({ "pc": "mic" })).is_err(), "the key must be present");
    }

    #[test]
    fn session_info_and_names() {
        let info = json!({ "profile": "balanced", "width": 1920, "height": 1200, "fps": 60, "gameAudio": true, "mic": false });
        assert_eq!(parse_session_info(&info).unwrap(), info);
        assert!(parse_session_info(&json!({ "profile": "balanced" })).is_err());
        assert_eq!(device_name_from_ua("LocalCast/1.0 (Android 16; TB321FU)"), "TB321FU");
        assert_eq!(device_name_from_ua("LocalCast/1.0 (Pixel 8)"), "Pixel 8");
        assert_eq!(device_name_from_ua("no-parens"), "Android device");
        assert_eq!(device_name_from_ua("x (Android 16; )"), "Android device");
        assert_eq!(short_id("ééééééééé-rest"), "éééééééé");
    }
}
