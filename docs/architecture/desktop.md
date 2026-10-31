# Desktop Application Architecture (Electron)

Status: Phases 2–9 + 13–14 implemented (pairing, signaling, the receiver's `ReceiverSession` + video, the Phase9 audio mixer, the Phase13 pairing-hero screens, and the Phase14 receiver-window hardening — [features/screen-capture.md](../features/screen-capture.md), [features/audio-mixer.md](../features/audio-mixer.md), [features/pairing.md](../features/pairing.md), [features/obs-streaming.md](../features/obs-streaming.md)). The **virtual camera** (ADR-007, 2026-10-30) adds the `vc/` modules and a fourth receiver stage — see §Virtual camera.

## Role

The desktop app is a **receiver**. It pairs, receives, renders, and plays. It must be almost zero-configuration: open it, show a QR code, receive the cast.

It must **not** expose cast configuration. Forbidden on the desktop: resolution, FPS, bitrate, codec, audio-capture, microphone, or thermal settings (see [overview.md](overview.md) — *Mobile controls. Desktop receives.*).

Allowed desktop controls are **receiver/environment** controls only:

- Game-audio volume, microphone volume (independent `GainNode`s — Phase9), reached through the hover-triggered settings modal
- Window: fullscreen, size — **the window's shape is the user's**: by default the video letterboxes into it (`contain` — full width or full height, black bars; follows rotation automatically because the CSS re-fits); the one-click **"Match window to video"** action (settings modal) snaps the window to the stream's aspect on demand (Phase14)
- Stay-awake during an active cast (`powerSaveBlocker`, `prevent-display-sleep` — Phase14): the display is held awake for exactly the cast's lifetime; Chromium window throttling is off (`backgroundThrottling: false`), so the video keeps painting while the window is unfocused or occluded
- The optional **session-info window** (Phase14): the cast summary line in its own small frameless window, *outside* the receiver window, so window capture records a pure video feed
- Regenerate pairing QR / cancel session

Read-only session info (e.g. "Receiving 720p · 30 fps · Balanced profile", sent by mobile via `session-info`) may be displayed but never edited.

## Technology

Electron + TypeScript (electron-vite scaffold). Rationale and alternatives: [ADR-001](../decisions/ADR-001-tech-stack.md). Chromium provides the WebRTC stack (receive H.264/VP8/Opus, hardware decode where available) and the Web Audio API used for the mixer.

## Process model

```text
┌─ Main process (Node.js) ────────────────────────────────┐
│  PairingServer: session generation, expiry, QR data     │
│  TrustStore: the persistent session (ADR-006) — one     │
│  trusted phone, resume hellos, `bye forget` revocation  │
│  SignalingServer: WebSocket (ws), auth, message routing │
│  NetworkInfo: enumerate LAN IPs (multi-interface aware) │
│  WindowManager: window lifecycle, powerSaveBlocker      │
│  vc/: VirtualCameraController (state machine, ADR-007)  │
│      + softcamLibrary (koffi FFI), registration (UAC)   │
└───────────────┬─────────────────────────────────────────┘
                │ Electron IPC (thin, typed events only)
┌───────────────▼─────────────────────────────────────────┐
│  Renderer (Chromium)                                    │
│  PairingView: QR hero (no session); "No input video"   │
│             stage (paired, no video — noInputView.ts);  │
│             friendly session-error (pairingHero.ts);    │
│             VC-active placeholder (vcActiveView.ts)     │
│  ReceiverSession: RTCPeerConnection ×2, ICE/SDP glue    │
│  VideoView: <video> letterboxes into the user-shaped    │
│             window (`contain`; Match-to-video on ask)   │
│  vc/FramePump: MediaStreamTrackProcessor → WebGL BGR    │
│             canvas → raw IPC frames (ADR-007)           │
│  AudioMixer: MediaStreamAudioSourceNode → GainNode ×2   │
│             → AudioContext.destination                  │
│  CastOverlay: hover-revealed status + settings trigger; │
│  SettingsModal: the mixer (volume/mute per stream)      │
│  SessionInfoWindow: optional off-cast cast summary      │
│             (own BrowserWindow — Phase14)               │
└─────────────────────────────────────────────────────────┘
```

Responsibility split: the main process owns **sockets, sessions, and the window** (including the on-demand "Match window to video" reshape — the geometry is pure and unit-tested in `windowGeometry.ts`; the window's default shape is the user's and the video letterboxes via CSS); the renderer owns **WebRTC and media**. IPC carries only typed session events (pairing-created, mobile-authenticated, signaling-message, cast-started/ended, stream-size, window-shape-on-demand, error) — no business logic on both sides.

**Receiver stage states (Phase15; startup behavior since ADR-006):** the desktop behaves like a monitor. While a video stream flows, the video owns the window. While the *pairing session* is alive but no video flows — paired but not yet casting, a cast that ended with the session retained (e.g. the OS stopping the projection on screen lock), or the socket gap inside the reconnect window — the stage shows **"No input video"** with the device name (pure copy: `noInputView.ts`); a dropped-but-alive socket arrives as the `disconnected` mobile state and only says *"Waiting for \<device\> to reconnect…"*. The QR hero renders **only** when the session itself is gone (bye, expiry, regenerate, restart) — `waiting` is pushed exactly at those moments, because pairing.md's reconnect window means missing video is never a lost pairing. **Since ADR-006 the no-input stage is also the startup screen whenever a phone is trusted** (the renderer asks once at load; the QR hero is the un-trusted desktop's screen), and it carries the stage's **Disconnect** button — the revocation that ends the persistent session (`bye forget` to a connected phone, a silent revoke when offline) and returns to the QR hero. A phone-side `bye forget` arrives as a normal bye that cleared the trust first; a plain `bye` with trust intact keeps the no-input stage ("Waiting for X to reconnect…" — one tap on the phone's Connect). The same split applies to ICE candidates crossing the renderer→main IPC hop: `RTCIceCandidate` is not structured-cloneable, so candidates are serialized via `toJSON()` (the `RTCIceCandidateInit` wire shape) before relay — found live in Phase15 (the raw wrapper arrived at the socket relay as `{}`).

## Receiver session details

- The renderer creates the `RTCPeerConnection`s and answers the mobile's offers (mobile is always the offerer — it is the sender and configuration owner).
- Two peer connections, `media` (video + game audio) and `mic`, per [ADR-003](../decisions/ADR-003-two-audio-track-architecture.md).
- ICE: no STUN/TURN; host candidates only. mDNS candidate caveats: [webrtc.md](webrtc.md).
- Audio: each remote audio stream becomes a `MediaStreamAudioSourceNode` → its own `GainNode` → `AudioContext.destination`. No additional processing (no EQ, no echo cancellation on the receiver) unless a measured need appears.

## Window design for OBS / streaming (Phase 14)

The receiver window is the product's "output device" for streamers: OBS Window Capture records one window, so that window must contain **nothing but video**. The pieces, in landing order:

- **Frameless, as a Settings toggle** (ADR-007 addendum, default framed): no title bar, no traffic lights, square corners — a pure video surface for capture; the whole window drags (controls opt out), edges resize, the tray owns navigation/quit. Toggling recreates the window (bounds kept; live pushes re-asserted). Stable, dark window (#000/near-black). **The window's shape is the user's** — by default the video letterboxes into it: `contain` scales it to full width or full height (whichever the aspects allow) with black bars for the rest, never stretch or crop, and rotation re-fits automatically. The fill is **re-asserted imperatively**: an explicit window-resize listener (plus cast start) pins the video element to the window's exact pixel size with inline styles, so the fill never depends on viewport-unit (100vw/100vh) resolution or on any stylesheet rule surviving intact. An earlier build auto-reshaped the window to the stream's aspect at cast start and rotation; that was replaced after a live Phase14 session — the window moving itself fought the user's own sizing (worst for a portrait phone stream landing in a landscape window sized for an OBS canvas). The old behavior survives as the explicit **"Match window to video"** action (settings modal): one click snaps the window to the stream's aspect — same geometry math as before (current content area, stream aspect, work-area clamps, `windowGeometry.ts`).
- Minimal UI: **no visible UI over the video** while casting — a hover-revealed overlay carries the status line and the settings trigger; the mixer and the window controls live in the modal it opens.
- Smooth rendering: the `<video>` element is composited directly (no canvas copy) unless a measured reason appears. **Chromium's window throttling is disabled** (`webPreferences.backgroundThrottling: false`, receiver and session-info windows alike): with the default on, the moment another app takes focus and the receiver window is occluded/backgrounded on macOS, Chromium stops presenting frames — decode keeps running (the getStats fps still reads), but nothing is painted and `requestVideoFrameCallback` stalls, so the video visibly freezes while unfocused. The receiver is the cast's output device; it must keep painting regardless of focus.
- **Session-info lives off-cast** (Phase14): the optional session-info window is a *second* BrowserWindow — small, frameless, always-on-top, draggable, closable — outside the captured window. It exists only while a cast is live and the user wants it (settings toggle, off by default; the overlay's ✕ and the checkbox share one state pushed from the main process). The status line is formatted by one pure module (`sessionInfoLine.ts`) rendered in both places.
- **Stay-awake** (Phase14): `powerSaveBlocker` (`prevent-display-sleep`) is started when a cast goes live and stopped when it ends — a 30-minute session must not stall on display sleep or a screensaver firing into the captured feed. Closing the receiver window mid-cast releases the blocker and the session-info window with it.

Remaining: the acceptance itself — the 30+ min OBS Window Capture session on real hardware ([features/obs-streaming.md](../features/obs-streaming.md)).

## Virtual camera (ADR-007, 2026-10-30)

The desktop can re-publish the incoming cast for other apps to consume — per-platform backends behind one controller seam: **Windows = softcam system webcam** (DirectShow via koffi FFI), **macOS = Syphon** (a vendored native helper process, OBS/VJ-only reach — call apps wait on the CMIO door; see the ADR addendum). Linux (kernel-free PipeWire) stays the recorded follow-up. Full decision record: [ADR-007](../decisions/ADR-007-virtual-camera.md); protocol: [webrtc.md](webrtc.md) proto 2.

- **Control**: the phone's toggle (`vc-start`/`vc-stop`), the desktop's state (`vc-status {available, needsRegistration, active}`); the desktop displays state only — never a cast setting. Capability is per-platform honest: `available:false` (unsupported platform or missing files) hides the phone's card entirely. Windows needs a one-time UAC registration (declined = honest retry state); macOS needs none.
- **Frame path (no preview while active)**: the renderer consumes **decoded frames directly** via `MediaStreamTrackProcessor` (works hidden/minimized — `requestVideoFrameCallback` would not, being presentation-driven), draws each frame into a **WebGL canvas whose shader letterboxes to the camera's fixed dims and produces the backend's pixel format on the GPU** — BGR24 packed for Windows, BGRA for macOS — and ships the raw buffer over IPC (`vc:frame`); the main process does zero per-pixel work (Windows: `scSendFrame`; macOS: header+bytes to the helper's stdin → Metal texture → Syphon). Output follows the cast 1:1 (30/60/120 fps — no cap; the phone's quality console remains the only fps control). softcam runs **unpaced** (`framerate 0`) so it never sleeps inside `scSendFrame`.
- **Camera dims** are fixed at creation from the stream size (`vcDims`: ≤1920×1080, multiples of 4); rotation/quality steps afterwards letterbox inside them.
- **Receiver stage**: while the camera publishes, the video preview is replaced by the **VC-active placeholder** (`vcActiveView.ts` — the no-input stage's language; mixer still reachable via the hover overlay). Stage map: QR hero → no-input → live video ⇄ VC-active placeholder.
- **Lifecycle (ADR-007 addendum)**: the toggle is **both-sided** — the phone's card and a desktop-side control (settings-modal row + a Turn-off button on the placeholder) drive the same controller. On macOS the Syphon server **outlives casts**: when the cast drops, the renderer publishes one **"No input video" still** through the same GPU path and the server keeps serving it (OBS never freezes on a stale frame); a fresh cast resumes frames automatically; the toggle or app quit kills the server. Both backends idle on the no-input still between casts (batch B — the capture-card shape; Windows included, reversing the earlier dies-with-cast choice). An always-on preference (default off) starts the camera with every cast by itself. Every terminal path (vc-stop, backend death, app quit) deletes the camera. Closing the receiver window **hides it** — the windowless/tray mode (ADR-007 addendum): cast, mixer, and the virtual camera keep running from the menu bar (Show receiver / Virtual camera / Quit); only the tray's Quit ends the app. The no-input stage carries a **Settings** button — environment controls (mixer, window) never needed a live stream.
- Packaging: win-only `extraResources` for `resources/softcam/`; mac-only `extraResources` for `resources/syphon/` (helper + framework + license, built from source by `build.sh`); koffi `asarUnpack`ed.

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
