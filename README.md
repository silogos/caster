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

## Desktop receivers

There are two desktop apps in the tree. Only one is being developed.

| | `apps/desktop-native` | `apps/desktop` |
|---|---|---|
| **Status** | **Current.** All receiver work since 2026-11-09 lands here ([ADR-015](docs/decisions/ADR-015-native-receiver-tauri-libwebrtc.md)) | **Superseded.** No further work; removal is planned in [issue #152](https://github.com/silogos/caster/issues/152) |
| Stack | Tauri shell, Rust, libwebrtc, native video and audio | Electron, TypeScript, Chromium's WebRTC |
| Platforms | macOS only | macOS, Windows, Linux (there is no Windows test machine — [ADR-007](docs/decisions/ADR-007-virtual-camera.md)) |
| Video | Shown on arrival with a bounded 50 ms pacer ([ADR-017](docs/decisions/ADR-017-receiver-frame-pacing.md)) | Chromium's playout, ~91 ms floor ([ADR-014](docs/decisions/ADR-014-native-low-latency-audio-output.md)) |
| Game audio | Stereo PCM over the DataChannel, CoreAudio output ([ADR-020](docs/decisions/ADR-020-audio-playout-steers-the-trough.md)) | Stereo PCM through a Rust sidecar engine |
| Microphone playback | **Not yet** (the connection is answered, the track is not played) | Yes |
| Output to OBS | Syphon, switched on the receiver ([ADR-021](docs/decisions/ADR-021-native-syphon-output.md)) | Syphon / softcam / PipeWire, switched from the phone |
| Audio routing | **Not yet** (speaks protocol version 1) | Yes (protocol version 2) |
| Launch at login, frameless window | **Not yet** | Yes |
| Game audio after an ICE restart | Stays on the stereo channel ([ADR-019](docs/decisions/ADR-019-ice-restart-keeps-the-receivers-pc.md)) | Not checked; the fix was made in the native receiver only |
| Tests | 93 `cargo test` tests, **run locally only** | Vitest suite, run by the CI `desktop` job |

Things to know before touching either:

- **Use `apps/desktop-native` on a Mac.** It is the receiver the latency figures in [docs/development/latency-measurement.md](docs/development/latency-measurement.md) were measured on. Details: [docs/architecture/desktop-native.md](docs/architecture/desktop-native.md).
- **Both apps share the pairing store** (`trust.json`, `desktop-id`), so a phone moves between them without scanning a new QR code.
- **CI does not test the native receiver.** A green `desktop` check covers the Electron app only.
- **`apps/desktop` still holds things the native app needs later:** the source of the virtual audio driver (`resources/zfaudio/`) and the virtual camera's native pieces. They must be moved out before the directory is removed.
- **Windows and Linux** are served only by the Electron app today. The native receiver's video, audio output and power management are macOS code and would have to be ported.

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
- The end-user workflow **never requires** USB, ADB, or Developer Options. (Developers may use ADB for *installation during development*; the product itself must not depend on it.)
- Android minimum version: **10 (API 29)** — the floor for `AudioPlaybackCapture`. See [ADR-001](docs/decisions/ADR-001-tech-stack.md).
