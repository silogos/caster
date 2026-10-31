# WebRTC & Signaling Architecture

Status: signaling **implemented in Phase4**, video transport in **Phase 5**, game audio on the `media` PC in **Phase 7**, and the `mic` PC in **Phase 8** — verification records in [features/signaling.md](../features/signaling.md), [features/screen-capture.md](../features/screen-capture.md), [features/game-audio.md](../features/game-audio.md), and [features/microphone.md](../features/microphone.md). Envelope v1, message set and lifecycle rules below are as implemented. **Protocol version 2** (2026-10-30/31, [ADR-007](../decisions/ADR-007-virtual-camera.md)) adds the `vc-*` virtual-camera and `audio-routing-*` output-routing control messages; everything else is unchanged, and proto-1↔proto-2 peers simply never exchange `vc-*`.

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
| `hello` | m→d | `{ua, deviceId?, resume?, protoMin, protoMax}` | Start auth; `ua` = app/platform string for logs; `deviceId` = the mobile's persistent install-scoped UUID ([pairing.md](pairing.md), [ADR-005](../decisions/ADR-005-device-identity-and-registry.md)) — optional (older clients omit it), never an auth secret. `resume: true` (optional, [ADR-006](../decisions/ADR-006-persistent-trust-sessions.md)): reconnect without a QR — the envelope `sid` carries the phone's `deviceId` and the auth MAC is keyed by the stored trustKey ([pairing.md](pairing.md) §Persistent sessions). |
| `challenge` | d→m | `{n}` | 16-byte base64url nonce. |
| `auth` | m→d | `{mac}` | HMAC-SHA256(k, s‖n), base64url ([pairing.md](pairing.md)). |
| `auth-ok` | d→m | `{name, proto, desktopId?, hosts?}` | Desktop name (for "Connected to *MacBook*") and negotiated protocol version. Optional since ADR-006: `desktopId` = the desktop's persistent UUID (the phone keys its saved-desktop entry on it) and `hosts` = the desktop's current LAN candidates, so the phone refreshes its entry on every successful connection. |
| `error` | both | `{code, msg?}` | Terminal (`bad-version`, `unknown-session`, `expired`, `bad-auth`, `busy`, `unknown-device`) or recoverable (`bad-message`). Terminal errors close the socket after send. `unknown-device` (ADR-006): a resume hello from a phone this desktop no longer trusts. |
| `sdp-offer` | m→d | `{pc, sdp}` | SDP offer. `pc` ∈ `"media"` \| `"mic"`. |
| `sdp-answer` | d→m | `{pc, sdp}` | SDP answer. |
| `ice` | both | `{pc, candidate}` | Trickled candidate; `candidate: null` marks end-of-gathering for that `pc`. The candidate value is the platform candidate object serialized as JSON (RTCIceCandidateInit-shaped on the desktop, the libwebrtc equivalent on mobile); signaling treats it as **opaque** and relays it verbatim — the concrete shape is exercised from Phase 5 on. |
| `session-info` | m→d | `{profile, width, height, fps, gameAudio, mic}` | Display-only summary for the desktop status line. **Not** configuration — the desktop never acts on it beyond showing text. |
| `vc-query` | m→d | `{}` | **Proto 2** ([ADR-007](../decisions/ADR-007-virtual-camera.md)): re-send `vc-status`. The phone sends it at cast start and after every re-auth, so the toggle stays truthful across reconnects. |
| `vc-start` / `vc-stop` | m→d | `{}` | **Proto 2**: turn the desktop's virtual camera on/off. `vc-start` while the DirectShow filter is unregistered first triggers the desktop's one-time elevated registration, then the camera is created — the phone just waits for the next `vc-status`. |
| `vc-status` | d→m | `{available, needsRegistration, active}` | **Proto 2**: the desktop's virtual-camera state, sent as the `vc-query` reply and on every change. `available:false` (non-Windows-x64 desktop, DLL missing) hides the phone UI; `needsRegistration:true` shows the one-time-setup copy; `active:true` mirrors the camera. The desktop only sends/processes `vc-*` on connections that negotiated proto ≥ 2. |
| `audio-routing-query` | m→d | `{}` | **Proto 2**: re-send `audio-routing-status` (the phone asks at cast start and after re-auths — the same cadence as `vc-query`). |
| `audio-routing-set` / `audio-routing-status` | m→d / d→m | `{mode}` / `{mode, cableAvailable}` | **Proto 2**: the phone's mirror of the desktop's audio output routing (`speakers` \| `cable` \| `both` — the receiver's environment setting, ported from issue #54). The desktop owns the truth: a `set` the mixer must refuse (cable without a device) simply results in the next status carrying the effective mode; `cableAvailable: false` disables those options on the phone. |
| `ping` / `pong` | both | `{t}` | App-level heartbeat every 5 s; a peer silent > 15 s is considered gone. |
| `bye` | both | `{reason}` | Graceful end; invalidates the session; desktop returns to fresh QR. The reserved reason `forget` (ADR-006) additionally revokes the persistent trust on both sides — what the desktop's Disconnect and the mobile's Terminate send; any other reason (the mobile's Disconnect, stopped casts) keeps it (the phone's next Connect is one tap, the desktop stays on its no-input stage). |

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
| Video | H.264 (hardware) → VP8 fallback | Mobile's offer lists both (the **preferred codec** is user-configurable in the Advanced settings, H.264 first by default); desktop picks the first it supports (Chromium/Electron ships proprietary codecs). If the device's HW encoder proves unreliable for a codec, mobile drops it from the offer — and since the Advanced settings, the user can also drop to **Software** encoders entirely (`c2.android`/`OMX.google`, via the factory's MediaCodecInfo predicate — risk-register R5's per-device escape hatch), or restrict H.264 to (Constrained) Baseline with the profile toggle (High is the default). |
| Game audio | Opus, 48 kHz, **stereo** | Music/game soundstage. The track is always part of the cast when RECORD_AUDIO allows; "game audio off" mutes it, never removes it (designs/mobile-app.html). |
| Microphone | Opus, 48 kHz, **mono** | Voice. The only stream ever fully removed from a cast (its own PC, built/torn down on demand). |

Initial sender targets (Phase 5, conservative, adjusted in Phases 10–12): 720p, 30 fps, 4–6 Mbps, `degradationPreference: BALANCED`. Sender-side parameters are set via `RTCRtpSender` parameters from the mobile `config` module only. Since the Advanced settings, the degradation preference (Balanced / Maintain framerate / Maintain resolution — per-preset defaults from the QualityProfile table) and an optional encoder-side fps cap (`maxFramerate`, none by default) are also config-driven.

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
| Session expired | No auto-retry; mobile prompts re-scan; desktop regenerates QR. **Not applicable to a trusted session** (ADR-006): its reconnects are bounded by attempt count (~3 min), not expiry, and end in `connect-unreachable` with the saved entry kept. |
| Trusted session's ladder exhausted | Mobile shows the unreachable failure + "Scan QR code"/"Forget this desktop" escapes; the saved entry stays (the desktop may be off). |
| `bye` from either side | Immediate, clean teardown of PCs + socket; desktop to fresh QR. |
| Mobile app killed / service stopped | OS-level socket close → desktop heartbeat timeout path. |

### Error surfacing

Signaling/ICE failures map to the simple user-facing messages defined in [pairing.md](pairing.md) §Failure modes; the raw codes stay in logs.

## Virtual camera control (proto 2, ADR-007)

The `vc-*` messages are control plumbing for the desktop's virtual camera ([ADR-007](../decisions/ADR-007-virtual-camera.md)) — the signaling channel relays them like any other message; no media logic lives here.

- **Capability gating rides version negotiation.** The desktop advertises `protoMax: 2`; a phone that also speaks 2 gets `auth-ok.proto: 2` and may show the virtual-camera toggle. A proto-1 peer on either side never sends or receives `vc-*` (the desktop tracks the negotiated proto per connection and ignores `vc-*` below 2 — it also never sends them there).
- **The phone is the only control surface** (mobile-controls invariant): toggle = `vc-start`/`vc-stop`; the desktop answers with `vc-status` on every state change (registration done, camera created/deleted, capability change). The desktop displays state only.
- **Lifecycle rule:** the camera dies with the cast. `vc-stop`, cast end, mobile gone (`bye`, socket drop past the reconnect window, `waiting`), and app quit all delete the camera — after a mid-cast reconnect the phone re-queries (`vc-query` on re-auth) and finds `active:false`; turning it back on is a fresh user action.
- Frames never touch the signaling channel — the desktop-internal frame path (renderer → main → DLL) is defined in [desktop.md](desktop.md).

## Statistics (input for adaptive quality)

Each side polls `getStats()` locally (~1 Hz) from Phase 5 onward and logs: RTT, packetsLost, jitter, framesEncoded/dropped, encoder implementation (HW/SW), bitrate. The mobile sender additionally extracts its sample (cumulative counters, RTT, the desktop receiver's `remote-inbound-rtp` loss fraction) into a plain data object — the input for Phase 12's adaptive quality ([thermal.md](thermal.md) §How thermal data is used); the desktop only logs. Since Phase 12 the sender also applies the policy's steps live (`changeQuality`: capture format + sender bitrate window, no renegotiation); the display-only `session-info` summary follows what is actually being sent.
