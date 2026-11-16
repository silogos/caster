//! Opus fmtp in the receiver's own answer (ADR-032).
//!
//! libwebrtc configures a receive stream's Opus decoder from the **local**
//! description (`VoiceChannel::SetLocalContent_w`), and the decoder is mono
//! unless that fmtp says `stereo=1` (`AudioDecoderOpus::SdpToConfig`:
//! "Default to mono"). The receiver answers with libwebrtc's default fmtp,
//! so a stereo Opus stream from the phone would be decoded to mono and
//! duplicated — the L = R that ADR-012 attributed to the phone's encoder.
//! Adding `stereo=1;sprop-stereo=1` to the answer made the Opus track
//! stereo (measured 2026-11-17, ADR-032).

/// Fmtp parameters that ask for (and declare) stereo Opus.
const STEREO_PARAMS: [&str; 2] = ["stereo=1", "sprop-stereo=1"];

/// The answer with every Opus payload's fmtp asking for stereo. Payloads
/// without an fmtp line get one after their rtpmap. Anything else, and an
/// fmtp that already asks, is left as it is.
pub fn prefer_stereo(sdp: &str) -> String {
    let eol = if sdp.contains("\r\n") { "\r\n" } else { "\n" };
    let lines: Vec<&str> = sdp.split(eol).collect();
    let opus: Vec<&str> = lines.iter().filter_map(|l| opus_payload(l)).collect();
    let has_fmtp = |pt: &str| lines.iter().any(|l| fmtp_payload(l) == Some(pt));
    let mut out: Vec<String> = Vec::with_capacity(lines.len() + opus.len());
    for line in &lines {
        match fmtp_payload(line) {
            Some(pt) if opus.contains(&pt) => out.push(with_stereo(line)),
            _ => out.push((*line).to_string()),
        }
        if let Some(pt) = opus_payload(line) {
            if !has_fmtp(pt) {
                out.push(format!("a=fmtp:{pt} {}", STEREO_PARAMS.join(";")));
            }
        }
    }
    out.join(eol)
}

/// The Opus fmtp line of `sdp`, for the log.
pub fn opus_fmtp(sdp: &str) -> Option<&str> {
    let pt = sdp.lines().find_map(opus_payload)?;
    sdp.lines().find(|l| fmtp_payload(l) == Some(pt))
}

/// `a=rtpmap:<pt> opus/48000/2` → `<pt>`.
fn opus_payload(line: &str) -> Option<&str> {
    let rest = line.strip_prefix("a=rtpmap:")?;
    let (pt, codec) = rest.split_once(' ')?;
    codec.to_ascii_lowercase().starts_with("opus/").then_some(pt)
}

/// `a=fmtp:<pt> ...` → `<pt>`.
fn fmtp_payload(line: &str) -> Option<&str> {
    line.strip_prefix("a=fmtp:")?.split_once(' ').map(|(pt, _)| pt)
}

fn with_stereo(fmtp: &str) -> String {
    let (head, params) = fmtp.split_once(' ').unwrap_or((fmtp, ""));
    let mut params: Vec<&str> = params.split(';').filter(|p| !p.is_empty()).collect();
    for wanted in STEREO_PARAMS {
        let key = wanted.split('=').next().unwrap_or(wanted);
        params.retain(|p| p.split('=').next() != Some(key));
        params.push(wanted);
    }
    format!("{head} {}", params.join(";"))
}

#[cfg(test)]
mod tests {
    use super::*;

    const ANSWER: &str = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111 63\r\na=rtpmap:111 opus/48000/2\r\na=fmtp:111 minptime=10;useinbandfec=1\r\na=rtpmap:63 red/48000/2\r\na=fmtp:63 111/111\r\n";

    #[test]
    fn the_opus_fmtp_asks_for_stereo_and_keeps_its_other_parameters() {
        let out = prefer_stereo(ANSWER);
        assert_eq!(opus_fmtp(&out), Some("a=fmtp:111 minptime=10;useinbandfec=1;stereo=1;sprop-stereo=1"));
        assert!(out.contains("a=fmtp:63 111/111\r\n"), "other codecs untouched");
        assert!(out.ends_with("\r\n"), "line endings kept");
    }

    #[test]
    fn an_explicit_mono_answer_is_turned_to_stereo_once() {
        let mono = ANSWER.replace("useinbandfec=1", "useinbandfec=1;stereo=0");
        let out = prefer_stereo(&prefer_stereo(&mono));
        assert_eq!(opus_fmtp(&out), Some("a=fmtp:111 minptime=10;useinbandfec=1;stereo=1;sprop-stereo=1"));
    }

    #[test]
    fn opus_without_an_fmtp_gets_one() {
        let bare = "m=audio 9 UDP/TLS/RTP/SAVPF 111\na=rtpmap:111 opus/48000/2\na=sendrecv\n";
        assert_eq!(prefer_stereo(bare), "m=audio 9 UDP/TLS/RTP/SAVPF 111\na=rtpmap:111 opus/48000/2\na=fmtp:111 stereo=1;sprop-stereo=1\na=sendrecv\n");
    }

    #[test]
    fn an_answer_without_opus_is_unchanged() {
        let video = "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=rtpmap:96 H264/90000\r\na=fmtp:96 packetization-mode=1\r\n";
        assert_eq!(prefer_stereo(video), video);
    }
}
