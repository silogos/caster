# Zero-Friction Local Cast

Cast your **Android screen**, **game audio**, and **microphone** to a desktop over the local Wi-Fi network — no USB cable, no ADB, no Developer Options.

Built for mobile gamers and streamers: the phone runs the game and controls the cast; the desktop is a receiver that is trivially easy to capture in OBS.

The product should feel like:

> **Scan → Start → Play.**

## Core principle

> **Mobile controls. Desktop receives.**

The Android app is the source of truth for every cast setting (resolution, FPS, bitrate, audio sources, thermal profile). The desktop exposes only receiver/environment controls such as playback volume — it never duplicates cast configuration. See [docs/architecture/overview.md](docs/architecture/overview.md).

## What is sent

| Stream | Android capture mechanism | Transport | Desktop output |
|---|---|---|---|
| Screen | `MediaProjection` + hardware H.264 | WebRTC (LAN) | Renderer in a stable dark window |
| Game audio | `AudioPlaybackCapture` (separate track) | WebRTC (LAN) | Web Audio `GainNode` → speakers |
| Microphone | Android mic APIs (separate track) | WebRTC (LAN) | Web Audio `GainNode` → speakers |

Game audio and microphone are **never mixed on Android** — they travel as separate logical tracks so the desktop can control their volumes independently. See [docs/architecture/audio.md](docs/architecture/audio.md).

## Repository structure

```text
/
├── apps/
│   ├── mobile/          # Android app (Kotlin, Jetpack Compose) — Phase 1
│   └── desktop/         # Desktop receiver (Electron + TypeScript) — Phase 2
├── docs/                # Shared knowledge base for humans and agents
└── package.json         # Root metadata (desktop scripts arrive in Phase 2)
```

## Documentation

Start at [docs/README.md](docs/README.md). Highlights:

- [Architecture overview](docs/architecture/overview.md) — system boundaries and configuration ownership
- [Pairing protocol](docs/architecture/pairing.md) — QR payload and handshake spec
- [WebRTC & signaling](docs/architecture/webrtc.md) — message taxonomy and connection lifecycle
- [Implementation roadmap](docs/development/roadmap.md) — phases, scope, acceptance criteria
- [Risk register](docs/development/risk-register.md) — known technical risks and mitigations

## Project status

**Phase 0 — Project Architecture: complete.** The applications are not implemented yet; `/apps/*` currently contains placeholders. Development proceeds one phase at a time with user review between phases — see the [roadmap](docs/development/roadmap.md) for what comes next.

## Development

Prerequisites and environment setup are described in [docs/development/setup.md](docs/development/setup.md). The project is developed incrementally: each phase produces implementation + tests + documentation + a coherent commit, then stops for review.

## Constraints

- Everything runs on the **same local Wi-Fi network**. No internet relay, no STUN/TURN.
- The end-user workflow **never requires** USB, ADB, or Developer Options. (Developers may use ADB for *installation during development*; the product itself must not depend on it.)
- Android minimum version: **10 (API 29)** — the floor for `AudioPlaybackCapture`. See [ADR-001](docs/decisions/ADR-001-tech-stack.md).
