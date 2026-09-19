# Architecture Overview

Status: Phase 0 (planned architecture; applications not yet implemented).

## What the system does

**Zero-Friction Local Cast** sends three streams from an Android device to a desktop over the same local Wi-Fi network:

```text
Android (sender, controls everything)
 ├── Screen          → MediaProjection → HW H.264 ─┐
 ├── Game audio      → AudioPlaybackCapture ───────┤→ WebRTC (LAN) → Desktop (receiver)
 └── Microphone      → mic APIs ───────────────────┘                     ├── Video renderer
                                                                          └── Web Audio mixer
```

The intended user experience:

```text
Desktop:  open app → QR code shown → waiting
Mobile:   open app → scan QR → connected → [Start Cast] → play
```

## Core principles

1. **Mobile controls. Desktop receives.** The Android app owns every cast decision. The desktop is a receiver plus environment controls only.
2. **Scan → Start → Play.** The normal flow must require the fewest possible actions and no technical understanding (no IP addresses, no codecs, no ICE).
3. **No USB, no ADB, no Developer Options** in the end-user workflow. Android platform APIs only.
4. **LAN only.** No internet relay, no STUN/TURN servers, no accounts.
5. **Simple user-facing errors.** Technical detail stays in logs.

## Component responsibilities

| Responsibility | Mobile (Android) | Desktop (Electron) |
|---|---|---|
| Cast configuration (resolution, FPS, bitrate, codec prefs, thermal profile) | **Owner** | Forbidden |
| Pairing data generation | — | **Owner** (QR display, signaling server) |
| QR scanning / initiating connection | **Owner** | — |
| Screen capture (`MediaProjection`) | **Owner** | — |
| Game audio capture (`AudioPlaybackCapture`) | **Owner** | — |
| Microphone capture | **Owner** | — |
| WebRTC media encoding/sending | **Owner** | — |
| WebRTC receiving, decoding, rendering | — | **Owner** |
| Per-stream playback volume | — | **Owner** (receiver/environment control) |
| Window behavior, stay-awake | — | **Owner** (receiver/environment control) |
| Thermal monitoring and profile application | **Owner** | — |

### Configuration ownership model

```text
                 MOBILE
                   │
          Configuration Owner
                   │
       ┌───────────┼───────────┐
       ▼           ▼           ▼
    Video        Audio       Thermal
    Config       Config       Config
       │           │           │
       └───────────┼───────────┘
                   ▼
                 WebRTC
                   ▼
                 DESKTOP  (receiver only; may adjust volume/output, never cast settings)
```

The desktop may display read-only session information (e.g. "receiving 720p · 30 fps") but must not expose controls that duplicate mobile cast settings.

## Runtime flow (three independent layers)

1. **Pairing** — desktop generates a short-lived session (IPs, port, credentials) and shows it as a QR code; the mobile app scans it. The QR is *bootstrap data only*, not a transport. Spec: [pairing.md](pairing.md).
2. **Signaling** — mobile opens a WebSocket to the desktop and exchanges versioned JSON messages (auth, SDP offer/answer, ICE). Spec: [webrtc.md](webrtc.md).
3. **Media transport** — WebRTC peer connections carry the three streams. Host ICE candidates only. Two peer connections are used to keep game audio and microphone as separate tracks (see [ADR-003](../decisions/ADR-003-two-audio-track-architecture.md)).

Each layer is a separate module on both sides; no layer depends on the internals of another beyond its documented protocol.

## Technology stack

| App | Stack | Key dependencies | Rationale |
|---|---|---|---|
| Mobile | Kotlin, Jetpack Compose, Coroutines/Flow, minSdk 29 | `io.getstream:stream-webrtc-android` (libwebrtc prebuilt), CameraX (QR scan) | Platform APIs are mandatory (`MediaProjection`, `AudioPlaybackCapture`, FGS, HW encoders) — cross-platform frameworks would need native modules for every critical path. [ADR-001](../decisions/ADR-001-tech-stack.md) |
| Desktop | Electron + TypeScript (electron-vite) | `ws`, `qrcode` | Chromium gives native WebRTC receive (H.264/VP8/Opus), the Web Audio API for the mixer, and an OBS-friendly stable window. [ADR-001](../decisions/ADR-001-tech-stack.md) |
| Transport | WebRTC, host candidates, no STUN/TURN | — | LAN peers can connect directly; relays add latency and infrastructure. |

## Non-goals (v1)

- No remote control of the desktop from the phone (view/audio only).
- No internet/LAN-crossing connectivity, no TURN fallback.
- No recording or persistence of streams.
- No simultaneous multi-desktop or multi-mobile sessions (one paired session at a time).
- No streaming-platform integration on the desktop; OBS captures the window externally (Phase 14 focuses on making that trivial).

## Known limitations (system level)

- Android shows a system consent dialog for `MediaProjection` **once per cast session**; this is a platform requirement and cannot be skipped (see [mobile.md](mobile.md)).
- Apps (including many games) can opt out of audio capture; for those, game audio arrives silent and the mobile app must indicate this honestly (see [audio.md](audio.md)).
- Latency target is "acceptable for gaming/streaming" (tens of milliseconds over LAN); it is measured, not promised, in Phase 5+ (see [risk-register](../development/risk-register.md)).
