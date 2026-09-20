# WebRTC & Signaling Architecture

Status: signaling **implemented in Phase4**, video transport in **Phase 5**, game audio on the `media` PC in **Phase 7**, and the `mic` PC in **Phase 8** — verification records in [features/signaling.md](../features/signaling.md), [features/screen-capture.md](../features/screen-capture.md), [features/game-audio.md](../features/game-audio.md), and [features/microphone.md](../features/microphone.md). Envelope v1, message set and lifecycle rules below are as implemented.

## Separation of concerns

| Concern | Owner module | Notes |
|---|---|---|
| Pairing | mobile `pairing` / desktop `PairingServer` | Bootstrap only ([pairing.md](pairing.md)) |
| Signaling | mobile `signaling` / desktop `SignalingServer` | Dumb, reliable message plumbing — no media logic |
| Media transport | mobile `webrtc` / desktop `ReceiverSession` | Two PeerConnections, DTLS-SRTP |
| Video capture | mobile `capture` | MediaProjection → VideoSource |
| Audio capture | mobile `audio` | Two independent tracks ([audio.md](audio.md)) |
| Rendering | desktop `VideoView` / `AudioMixer` | `<video>` + Web Audio |
| Configuration | mobile `config` | The single source of cast settings |

Signaling must remain usable/testable with WebRTC entirely absent (loopback protocol tests, Phase 4).

## Signaling channel

One WebSocket per mobile connection: `ws://<host>:<port>/zfc/v1` (host/port from the QR payload).

### Envelope

Every frame is JSON text:

```json
{ "v": 1, "type": "sdp-offer", "seq": 7, "sid": "6Xk…", "payload": { … } }
```

| Field | Rules |
|---|---|
| `v` | Protocol version; currently `1`. Mismatch → `error: bad-version`, connection closed. |
| `type` | Message type (table below). Unknown type → `error: bad-message` (connection stays open for forward compatibility). |
| `seq` | Per-sender monotonic counter starting at 1. Receivers log regressions; ordering is informational, not enforced. |
| `sid` | Session ID from the QR. Required on every message; validated against the authenticated session. |
| `payload` | Type-specific object. Max envelope size 256 KiB (guards against corrupt frames). |

Until `auth-ok` succeeds, the desktop accepts **only** `hello`, `auth` — anything else is dropped with `error: bad-message`.

### Message types

| Type | Direction | Payload | Purpose |
|---|---|---|---|
| `hello` | m→d | `{ua, protoMin, protoMax}` | Start auth; `ua` = app/platform string for logs. |
| `challenge` | d→m | `{n}` | 16-byte base64url nonce. |
| `auth` | m→d | `{mac}` | HMAC-SHA256(k, s‖n), base64url ([pairing.md](pairing.md)). |
| `auth-ok` | d→m | `{name, proto}` | Desktop name (for "Connected to *MacBook*") and negotiated protocol version. |
| `error` | both | `{code, msg?}` | Terminal (`bad-version`, `unknown-session`, `expired`, `bad-auth`, `busy`) or recoverable (`bad-message`). Terminal errors close the socket after send. |
| `sdp-offer` | m→d | `{pc, sdp}` | SDP offer. `pc` ∈ `"media"` \| `"mic"`. |
| `sdp-answer` | d→m | `{pc, sdp}` | SDP answer. |
| `ice` | both | `{pc, candidate}` | Trickled candidate; `candidate: null` marks end-of-gathering for that `pc`. The candidate value is the platform candidate object serialized as JSON (RTCIceCandidateInit-shaped on the desktop, the libwebrtc equivalent on mobile); signaling treats it as **opaque** and relays it verbatim — the concrete shape is exercised from Phase 5 on. |
| `session-info` | m→d | `{profile, width, height, fps, gameAudio, mic}` | Display-only summary for the desktop status line. **Not** configuration — the desktop never acts on it beyond showing text. |
| `ping` / `pong` | both | `{t}` | App-level heartbeat every 5 s; a peer silent > 15 s is considered gone. |
| `bye` | both | `{reason}` | Graceful end; invalidates the session; desktop returns to fresh QR. |

## Media transport

### Two PeerConnections

libwebrtc/Electron constraint: one AudioDeviceModule per PeerConnectionFactory, and all local audio tracks in a factory share that single recorder — so two independent audio sources cannot live in one PeerConnection. Therefore:

- **`media` PC** (factory A): screen video + **game audio** (custom playback-capture ADM).
- **`mic` PC** (factory B): microphone (standard `JavaAudioDeviceModule`) — implemented in Phase 8, built/torn down on demand by the mobile's live mic toggle; the desktop answers it on its own answerer, independently of the media pc.

Both are signaled over the same WebSocket with the `pc` discriminator; ICE/DTLS cost of the second connection is negligible on LAN. Full rationale, alternatives, and the validation spike: [ADR-003](../decisions/ADR-003-two-audio-track-architecture.md).

### Roles and negotiation

- **The mobile is always the offerer** (both PCs). This is a deliberate consequence of *mobile controls*: the offer encodes the phone's chosen send parameters (codec order, max bitrate, resolution/fps).
- The desktop answers, never renegotiates on its own, and applies no inbound constraints beyond decoding/rendering.

### ICE strategy

- `iceServers: []` — **host candidates only, no STUN/TURN.** Peers are on the same subnet by requirement.
- **mDNS caveat (risk R4):** Chromium and recent libwebrtc obfuscate host candidates as `*.local` mDNS names. Some routers/APs block mDNS, breaking connectivity even on the same LAN. Phase 4 validated the *plumbing*; Phase5 validated the first real resolution on the test LAN — **Chromium's default mDNS-obfuscated candidates resolved successfully on the device** (mitigation 1 sufficed there). Remaining mitigations, in order of preference: 2. disable candidate obfuscation where the platform exposes a switch (Electron command line / libwebrtc field trial). 3. candidate filtering plus the QR's `hosts[]` as ground truth. Hostile-AP behavior is exercised in Phase15.
- ICE restart is preferred over full re-pairing for transient network changes.

### Codec policy

| Track | Preference | Notes |
|---|---|---|
| Video | H.264 (hardware) → VP8 fallback | Mobile's offer lists both (H.264 first); desktop picks H.264 (Chromium/Electron ships proprietary codecs). If the device's HW encoder proves unreliable for a codec, mobile drops it from the offer. |
| Game audio | Opus, 48 kHz, **stereo** | Music/game soundstage. |
| Microphone | Opus, 48 kHz, **mono** | Voice. |

Initial sender targets (Phase 5, conservative, adjusted in Phases 10–12): 720p, 30 fps, 4–6 Mbps, `degradationPreference: BALANCED`. Sender-side parameters are set via `RTCRtpSender` parameters from the mobile `config` module only.

## Connection lifecycle

```text
                ┌───────── WebSocket ─────────┐          ┌── media PC ──┐
 Pair (QR) ─▶ hello/challenge/auth ─▶ auth-ok ├─ offers ─▶│ ICE → DTLS   │
                └────────────────────────────┘          └──────────────┘
                                                          └── mic PC ───┘
States (both sides): pairing → connecting → negotiating → connecting(ICE) → streaming → closed
```

### Disconnect / reconnect rules

| Event | Behavior |
|---|---|
| ICE `disconnected` | Wait up to 10 s for recovery (Wi-Fi blips); then ICE restart on the affected PC. |
| WebSocket drop while session valid | Mobile retries with backoff 1s → 2s → 5s → 10s → 30s cap, while `e` not passed; PC objects are rebuilt on re-auth. Desktop keeps the session and shows "Waiting for mobile…". |
| Heartbeat timeout (>15 s) | Treated as WebSocket drop (above). |
| Session expired | No auto-retry; mobile prompts re-scan; desktop regenerates QR. |
| `bye` from either side | Immediate, clean teardown of PCs + socket; desktop to fresh QR. |
| Mobile app killed / service stopped | OS-level socket close → desktop heartbeat timeout path. |

### Error surfacing

Signaling/ICE failures map to the simple user-facing messages defined in [pairing.md](pairing.md) §Failure modes; the raw codes stay in logs.

## Statistics (input for later adaptive quality)

Each side polls `getStats()` locally (~1 Hz) from Phase 5 onward and logs: RTT, packetsLost, jitter, framesEncoded/dropped, encoder implementation (HW/SW), bitrate. This data is the measured basis for Phase 12 (adaptive streaming) — no quality automation ships before then.
