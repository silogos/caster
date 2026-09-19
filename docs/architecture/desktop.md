# Desktop Application Architecture (Electron)

Status: Phase 0 (planned; the app is initialized in Phase 2).

## Role

The desktop app is a **receiver**. It pairs, receives, renders, and plays. It must be almost zero-configuration: open it, show a QR code, receive the cast.

It must **not** expose cast configuration. Forbidden on the desktop: resolution, FPS, bitrate, codec, audio-capture, microphone, or thermal settings (see [overview.md](overview.md) — *Mobile controls. Desktop receives.*).

Allowed desktop controls are **receiver/environment** controls only:

- Game-audio volume, microphone volume (independent `GainNode`s — Phase 9)
- Window: fullscreen, aspect-ratio behavior (letterbox)
- Stay-awake during an active cast (`powerSaveBlocker`)
- Regenerate pairing QR / cancel session

Read-only session info (e.g. "Receiving 720p · 30 fps · Balanced profile", sent by mobile via `session-info`) may be displayed but never edited.

## Technology

Electron + TypeScript (electron-vite scaffold). Rationale and alternatives: [ADR-001](../decisions/ADR-001-tech-stack.md). Chromium provides the WebRTC stack (receive H.264/VP8/Opus, hardware decode where available) and the Web Audio API used for the mixer.

## Process model

```text
┌─ Main process (Node.js) ────────────────────────────────┐
│  PairingServer: session generation, expiry, QR data     │
│  SignalingServer: WebSocket (ws), auth, message routing │
│  NetworkInfo: enumerate LAN IPs (multi-interface aware) │
│  WindowManager: window lifecycle, powerSaveBlocker      │
└───────────────┬─────────────────────────────────────────┘
                │ Electron IPC (thin, typed events only)
┌───────────────▼─────────────────────────────────────────┐
│  Renderer (Chromium)                                    │
│  PairingView: renders QR (from data URL), waiting state │
│  ReceiverSession: RTCPeerConnection ×2, ICE/SDP glue    │
│  VideoView: <video> element, letterboxed, dark bg       │
│  AudioMixer: MediaStreamAudioSourceNode → GainNode ×2   │
│             → AudioContext.destination                  │
│  StatusView: connection state, volume sliders           │
└─────────────────────────────────────────────────────────┘
```

Responsibility split: the main process owns **sockets and sessions**; the renderer owns **WebRTC and media**. IPC carries only typed session events (pairing-created, mobile-authenticated, signaling-message, cast-started/ended, error) — no business logic on both sides.

## Receiver session details

- The renderer creates the `RTCPeerConnection`s and answers the mobile's offers (mobile is always the offerer — it is the sender and configuration owner).
- Two peer connections, `media` (video + game audio) and `mic`, per [ADR-003](../decisions/ADR-003-two-audio-track-architecture.md).
- ICE: no STUN/TURN; host candidates only. mDNS candidate caveats: [webrtc.md](webrtc.md).
- Audio: each remote audio stream becomes a `MediaStreamAudioSourceNode` → its own `GainNode` → `AudioContext.destination`. No additional processing (no EQ, no echo cancellation on the receiver) unless a measured need appears.

## Window design for OBS / streaming (Phase 14 target)

The receiver window is the product's "output device" for streamers:

- Stable window (no re-layout during a cast), dark background (#000 or near-black).
- Video letterboxed with a predictable aspect ratio that follows the phone's rotation; no stretched rendering.
- Minimal UI: no overlays on top of the video while casting; controls fade out or sit outside the video rect.
- Smooth rendering: the `<video>` element is composited directly (no canvas copy) unless a measured reason appears.

## Lifecycle states

```text
Idle → ShowingQR (session + QR generated)
     → MobilePaired (auth succeeded)
     → Negotiating (SDP/ICE)
     → Receiving (video/audio playing)
     → (mobile disconnects | window closed) → ShowingQR (new session)
```

Disconnect behavior: on WebSocket loss or ICE failure, the desktop shows "Waiting for mobile…" and a fresh QR only after the old session expires or is explicitly cancelled, so a momentarily dropped Wi-Fi does not force re-pairing (reconnect rules: [webrtc.md](webrtc.md)).

## Constraints and known limitations

- **Firewall prompt on first run** (macOS/Windows): the OS asks to allow incoming connections for the signaling port. This is unavoidable; the UI and docs must set expectations. Binding a fixed default port is preferred so the user approves it once.
- **Chromium mDNS obfuscation**: host ICE candidates may be published as `*.local` names; some routers/APs block mDNS. Mitigation plan in [webrtc.md](webrtc.md) (validate in Phase 4).
- **Multi-interface machines** (VPN, VM bridges, dockings): the QR advertises *all* candidate LAN IPs (`hosts[]`), and the mobile tries each — see [pairing.md](pairing.md).
- No transcoding, no recording, no re-streaming (non-goals).
