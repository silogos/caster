# Desktop Application Architecture (Electron)

Status: Phases 2–9 + 13–14 implemented (pairing, signaling, the receiver's `ReceiverSession` + video, the Phase9 audio mixer, the Phase13 pairing-hero screens, and the Phase14 receiver-window hardening — [features/screen-capture.md](../features/screen-capture.md), [features/audio-mixer.md](../features/audio-mixer.md), [features/pairing.md](../features/pairing.md), [features/obs-streaming.md](../features/obs-streaming.md)).

## Role

The desktop app is a **receiver**. It pairs, receives, renders, and plays. It must be almost zero-configuration: open it, show a QR code, receive the cast.

It must **not** expose cast configuration. Forbidden on the desktop: resolution, FPS, bitrate, codec, audio-capture, microphone, or thermal settings (see [overview.md](overview.md) — *Mobile controls. Desktop receives.*).

Allowed desktop controls are **receiver/environment** controls only:

- Game-audio volume, microphone volume (independent `GainNode`s — Phase9), reached through the hover-triggered settings modal
- Window: fullscreen, size — **the window's shape is the user's**: by default the video letterboxes into it (`contain` — full width or full height, black bars; follows rotation automatically because the CSS re-fits); the one-click **"Match window to video"** action (settings modal) snaps the window to the stream's aspect on demand (Phase14)
- Stay-awake during an active cast (`powerSaveBlocker`, `prevent-display-sleep` — Phase14): the display is held awake for exactly the cast's lifetime
- The optional **session-info window** (Phase14): the cast summary line in its own small frameless window, *outside* the receiver window, so window capture records a pure video feed
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
│  PairingView: QR hero (waiting); paired check card;     │
│             friendly session-error (pairingHero.ts)     │
│  ReceiverSession: RTCPeerConnection ×2, ICE/SDP glue    │
│  VideoView: <video> letterboxes into the user-shaped    │
│             window (`contain`; Match-to-video on ask)   │
│  AudioMixer: MediaStreamAudioSourceNode → GainNode ×2   │
│             → AudioContext.destination                  │
│  CastOverlay: hover-revealed status + settings trigger; │
│  SettingsModal: the mixer (volume/mute per stream)      │
│  SessionInfoWindow: optional off-cast cast summary      │
│             (own BrowserWindow — Phase14)               │
└─────────────────────────────────────────────────────────┘
```

Responsibility split: the main process owns **sockets, sessions, and the window** (including the on-demand "Match window to video" reshape — the geometry is pure and unit-tested in `windowGeometry.ts`; the window's default shape is the user's and the video letterboxes via CSS); the renderer owns **WebRTC and media**. IPC carries only typed session events (pairing-created, mobile-authenticated, signaling-message, cast-started/ended, stream-size, window-shape-on-demand, error) — no business logic on both sides.

## Receiver session details

- The renderer creates the `RTCPeerConnection`s and answers the mobile's offers (mobile is always the offerer — it is the sender and configuration owner).
- Two peer connections, `media` (video + game audio) and `mic`, per [ADR-003](../decisions/ADR-003-two-audio-track-architecture.md).
- ICE: no STUN/TURN; host candidates only. mDNS candidate caveats: [webrtc.md](webrtc.md).
- Audio: each remote audio stream becomes a `MediaStreamAudioSourceNode` → its own `GainNode` → `AudioContext.destination`. No additional processing (no EQ, no echo cancellation on the receiver) unless a measured need appears.

## Window design for OBS / streaming (Phase 14)

The receiver window is the product's "output device" for streamers: OBS Window Capture records one window, so that window must contain **nothing but video**. The pieces, in landing order:

- Stable, dark window (#000/near-black). **The window's shape is the user's** — by default the video letterboxes into it: `contain` scales it to full width or full height (whichever the aspects allow) with black bars for the rest, never stretch or crop, and rotation re-fits automatically. The fill is **re-asserted imperatively**: an explicit window-resize listener (plus cast start) pins the video element to the window's exact pixel size with inline styles, so the fill never depends on viewport-unit (100vw/100vh) resolution or on any stylesheet rule surviving intact. An earlier build auto-reshaped the window to the stream's aspect at cast start and rotation; that was replaced after a live Phase14 session — the window moving itself fought the user's own sizing (worst for a portrait phone stream landing in a landscape window sized for an OBS canvas). The old behavior survives as the explicit **"Match window to video"** action (settings modal): one click snaps the window to the stream's aspect — same geometry math as before (current content area, stream aspect, work-area clamps, `windowGeometry.ts`).
- Minimal UI: **no visible UI over the video** while casting — a hover-revealed overlay carries the status line and the settings trigger; the mixer and the window controls live in the modal it opens.
- Smooth rendering: the `<video>` element is composited directly (no canvas copy) unless a measured reason appears.
- **Session-info lives off-cast** (Phase14): the optional session-info window is a *second* BrowserWindow — small, frameless, always-on-top, draggable, closable — outside the captured window. It exists only while a cast is live and the user wants it (settings toggle, off by default; the overlay's ✕ and the checkbox share one state pushed from the main process). The status line is formatted by one pure module (`sessionInfoLine.ts`) rendered in both places.
- **Stay-awake** (Phase14): `powerSaveBlocker` (`prevent-display-sleep`) is started when a cast goes live and stopped when it ends — a 30-minute session must not stall on display sleep or a screensaver firing into the captured feed. Closing the receiver window mid-cast releases the blocker and the session-info window with it.

Remaining: the acceptance itself — the 30+ min OBS Window Capture session on real hardware ([features/obs-streaming.md](../features/obs-streaming.md)).

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
