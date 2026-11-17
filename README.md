# Caster

Cast your **Android screen**, **game audio**, and **microphone** to a desktop over the local Wi-Fi network or a USB cable — no ADB, no Developer Options.

Built for mobile gamers and streamers: the phone runs the game and controls the cast; the desktop is a receiver that is trivially easy to capture in OBS.

The product should feel like:

> **Scan → Start → Play.**

## Core principle

> **Mobile controls. Desktop receives.**

The Android app is the source of truth for every cast setting (resolution, FPS, bitrate, audio sources, thermal profile). The desktop exposes only receiver/environment controls such as playback volume — it never duplicates cast configuration. See [docs/architecture/overview.md](docs/architecture/overview.md).

## What is sent

| Stream | Android capture mechanism | Transport | Desktop output |
|---|---|---|---|
| Screen | `MediaProjection` + hardware H.264 | WebRTC (LAN) | Native video window |
| Game audio | `AudioPlaybackCapture` (separate track) | WebRTC (LAN) | Speakers, with its own volume |
| Microphone | Android mic APIs (separate track) | WebRTC (LAN) | Speakers, with its own volume — not played by the native receiver yet (see [Desktop receivers](#desktop-receivers)) |

Game audio and microphone are **never mixed on Android** — they travel as separate logical tracks so the desktop can control their volumes independently. See [docs/architecture/audio.md](docs/architecture/audio.md).

## Repository structure

```text
/
├── apps/
│   ├── mobile/          # Android app (Kotlin, Jetpack Compose)
│   ├── desktop-native/  # Desktop receiver (Tauri + Rust + libwebrtc) — the current one, macOS
│   └── desktop/         # Desktop receiver (Electron + TypeScript) — superseded, to be removed
├── docs/                # Shared knowledge base for humans and agents
├── tools/               # Development tools (latency measurement)
└── package.json         # Root metadata
```

## Desktop receiver

The receiver is `apps/desktop-native` ([ADR-015](docs/decisions/ADR-015-native-receiver-tauri-libwebrtc.md), [docs/architecture/desktop-native.md](docs/architecture/desktop-native.md)): a Tauri shell with Rust, libwebrtc, and native video and audio. **macOS only.**

- The Electron receiver (`apps/desktop`, superseded since 2026-11-09) was removed on 2026-11-18 ([ADR-035](docs/decisions/ADR-035-machine-identifiers-carry-caster.md), [issue #152](https://github.com/silogos/caster/issues/152)). Git history keeps it, including its Windows and Linux outputs (softcam, PipeWire) and its virtual audio driver.
- **There is no Windows or Linux receiver today.** The native receiver's video, audio output and power management are macOS code and would have to be ported.
- **CI does not test the receiver**: its `cargo test --lib` suite runs locally only (it needs macOS frameworks).

## Documentation

Start at [docs/README.md](docs/README.md). Highlights:

- [Architecture overview](docs/architecture/overview.md) — system boundaries and configuration ownership
- [Pairing protocol](docs/architecture/pairing.md) — QR payload and handshake spec
- [WebRTC & signaling](docs/architecture/webrtc.md) — message taxonomy and connection lifecycle
- [Implementation roadmap](docs/development/roadmap.md) — phases, scope, acceptance criteria
- [Risk register](docs/development/risk-register.md) — known technical risks and mitigations

## Project status

Both the Android app and the macOS receiver are implemented and cast end to end; see [Desktop receivers](#desktop-receivers) for which desktop app is current. Development proceeds one phase at a time with user review between phases — see the [roadmap](docs/development/roadmap.md) for what comes next.

## Development

Prerequisites and environment setup are described in [docs/development/setup.md](docs/development/setup.md). The project is developed incrementally: each phase produces implementation + tests + documentation + a coherent commit, then stops for review.

## Troubleshooting

- **Cast dies mid-game on some phones:** while a cast runs, the app holds itself exempt from standard battery saving (a foreground service + a Wi-Fi radio hold), so no setup should be needed. A few aggressive manufacturer ROMs still restrict background apps their own way — if that happens, set the app to **Unrestricted** in the phone's battery settings (see [R11](docs/development/risk-register.md)).

## Constraints

- Everything runs on the **same local Wi-Fi network**. No internet relay, no STUN/TURN.
- The end-user workflow **never requires** ADB or Developer Options; a USB cable is optional and goes through Android Open Accessory only (ADR-034). (Developers may use ADB for *installation during development*; the product itself must not depend on it.)
- Android minimum version: **10 (API 29)** — the floor for `AudioPlaybackCapture`. See [ADR-001](docs/decisions/ADR-001-tech-stack.md).
