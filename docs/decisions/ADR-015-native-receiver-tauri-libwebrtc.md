# ADR-015: The native desktop receiver — Tauri shell, libwebrtc transport, native video and audio

- **Status:** accepted (2026-11-09) — records the decision that `apps/desktop-native` was built on (commits `0f3141b`…`80d895d` cite "ADR-015", which was never written until now) and its first hardening pass. **Amends [ADR-014](ADR-014-native-low-latency-audio-output.md)**: Fase B's transport is libwebrtc rather than str0m/webrtc-rs, and Fase C's shell is Tauri rather than the favored GPUI. **Supersedes the Electron receiver** (`apps/desktop`) as the desktop going forward — the product owner's call, 2026-11-09: Electron is obsolete and receives no further work.

## Context

ADR-014 measured two walls the Electron receiver cannot cross: Chromium's ~91 ms video playout floor and its 33 ms audio output chain. It planned a native receiver in phases — the audio engine first (Fase A, shipped as an Electron sidecar), native video later (Fase B, gated), the shell last (Fase C) — and pre-selected **str0m or webrtc-rs** as the transport and **GPUI** as the favored shell.

The work went ahead as one native app instead of an Electron sidecar, and two of those pre-selections did not survive contact with the phone:

- **str0m never connected.** The M0 interop spike answered the phone's offer, but the phone never sent STUN checks to its candidates; whether the phone even accepted the answer SDP was never proven (commit `744f0c7`). The spike is removed from the tree (git history keeps it).
- **libwebrtc via `livekit::webrtc` connected on the first gate** (commit `d498464`, "M0-r interop gate GO"): auth, offer/answer, trickle ICE, DTLS, ~55 fps decoded video, the `game-audio` DataChannel open. The phone runs the pinned libwebrtc 1.3.8 sender, so this is libwebrtc talking to libwebrtc — the best-tested interop path there is.

## Decision

`apps/desktop-native` is the desktop receiver: a **Tauri v2** app on macOS whose media path never touches the webview.

| Concern | Choice | Module |
|---|---|---|
| Transport | `livekit::webrtc` (libwebrtc via FFI, prebuilt), factory with zero playout delay | `webrtc.rs` |
| Video | decoded I420 → CPU copy (~0.3 ms/frame) into pooled NV12 `'420v'` CVPixelBuffers → `AVSampleBufferDisplayLayer` in its own `NSWindow` (OBS window-captures it) | `video.rs` |
| Game audio | the ADR-014 engine embedded: lock-free ring → CPAL, 40 ms rate-controlled playout | `audio.rs` |
| Signaling | the documented protocol (pairing.md, webrtc.md), one task per connection | `signaling.rs`, `protocol.rs`, `pairing.rs` |
| UI | Tauri webview, vanilla JS stages (QR → no-input → receiving) — never in the media path | `ui/` |

**Interop contract.** The native receiver speaks the same envelope v1, QR payload v1, HMAC handshake, persistent trust (ADR-006 — it reads and writes the same `trust.json` and `desktop-id` as the Electron app, so a phone moves between them without re-pairing), and two-PeerConnection model (ADR-003) as before. It **negotiates `proto` 1**: the proto-2 messages (`vc-*`, `audio-routing-*`, ADR-007) are not implemented here, and offering 2 would make the phone send messages the desktop must reject.

**Game audio wire.** The receiver never acks the DataChannel's `announce`, so the phone stays on raw PCM16 (ADR-012) — ADR-013's own fallback ladder, and the lowest-latency path.

## Why not the ADR-014 pre-selections

| Option | Verdict |
|---|---|
| str0m (ADR-014's transport candidate) | **Dropped** — the spike never got a STUN check from the phone; libwebrtc↔libwebrtc connected immediately. Revisit only if the FFI binaries become a liability. |
| webrtc-rs | **Not evaluated** — the libwebrtc path met the gate before it was needed. Recorded honestly as untested, not rejected on evidence. |
| GPUI (ADR-014's favored shell) | **Not chosen** — the receiver's UI is a few static stages; Tauri ran the existing vanilla UI unchanged. No GPUI prototype was built, so this is a cost decision, not a measured comparison. |
| eframe/egui | **Superseded** — the first native UI shell (`744f0c7`), replaced by Tauri in `0f3141b`. |
| ADR-014's objection to Tauri (WKWebView lacks `setSinkId`) | **Does not apply** — audio is played by CPAL in Rust, not by the webview. |

## Consequences

**Positive:** no Chromium in the media path — video via the system compositor, audio via CoreAudio; one process instead of Electron + sidecar; the protocol and pairing logic is now pure Rust with loopback tests (WebRTC entirely absent, as webrtc.md requires).

**Hardening pass (2026-11-09, review of the first implementation):** the first version demoed one phone and was not shippable. Fixed and verified on the TB321FU — details in [architecture/desktop-native.md](../architecture/desktop-native.md):

- **Security:** the pairing QR (session secret) and the first screen frame were written to world-readable `/tmp`; the trust file was world-readable. Now: nothing written to `/tmp`; trust and `desktop-id` written 0600, atomically.
- **Signaling:** one connection at a time with no handshake timeout (a silent socket blocked pairing forever); a heartbeat that could never fire; phone ICE candidates all rejected (`IceCandidate::parse` arguments reversed); a `mic` offer replaced the `media` PeerConnection (turning the mic on killed the cast); expiry, `busy`, `unknown-device`, `bye forget`, sid checks and rate limiting missing; fake `session-info` sent to the UI.
- **Audio:** the DataChannel was dropped after ~30 s (only a debug thread held it — the cast fell back to the dual-mono track); two writers on a single-producer ring; the device's sample rate/channel count ignored; the prime gate never ran; a 60 ms backlog trim that threw away the very cushion absorbing the next SCTP stall (244 trims → 195 underruns in a 274 s PUBG session; after: 1 trim, ~0.24 underruns/s at ~70 ms median playout).
- **Video:** AppKit window getters called off the main thread aborted the app four times on 2026-11-09 ("Rust cannot catch foreign exceptions"); full-range pixel format for limited-range data; a pool bound that used a non-existent key; per-stream state in ~12 process-wide statics.

**Costs / accepted:**
- **macOS only** — `video.rs` is AppKit/CoreVideo. A Windows receiver needs its own video module (the rest is portable Rust).
- **Prebuilt libwebrtc binaries** via livekit (aarch64-apple-darwin) — the version is livekit's, not ours.
- **Missing vs the Electron receiver:** microphone playback (the `mic` pc is answered and connected, its track not yet played), the virtual camera and audio routing (proto 2), tray. (Game volume/mute, stay-awake and the desktop Disconnect landed in Feature 2026-11g.) The mic is currently routed to the laptop headset by the user, so its playback is deferred, not urgent.
- **Video pool drops ~0.7 %** of frames at 8 buffers (a frame every ~2 s) — open.
- **The video crash fix** held a 6.4-minute run with the window hidden for most of it (14,400+ frames dropped while hidden — the exact condition of the four aborts, which hit at ~5–6 minutes), with no abort and no exception caught. Longer sessions remain worth watching.

**Revisit if:** Windows becomes a target (video module), the livekit binaries lag a needed libwebrtc fix, or proto 2 features (virtual camera, routing) are wanted on the native receiver.
