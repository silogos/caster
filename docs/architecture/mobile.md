# Mobile Application Architecture (Android)

Status: Phases 1–12 (implemented; the cast session — `webrtc`/`capture`/`config`/`service` — is service-owned since Phase6, [features/cast-session.md](../features/cast-session.md); game audio on the `media` PC since Phase 7, [features/game-audio.md](../features/game-audio.md); the mic on its own `mic` PC since Phase 8, [features/microphone.md](../features/microphone.md); the configuration-owner settings UI (home page) + persistence since Phase 10, [features/cast-settings.md](../features/cast-settings.md); thermal profiles + read-only monitoring since Phase 11, [features/thermal.md](../features/thermal.md); stats/thermal-driven auto quality since Phase 12, [features/adaptive-quality.md](../features/adaptive-quality.md); the pairing screens polished in Phase 13, [features/pairing.md](../features/pairing.md)).

## Role

The Android app is the **sender and the configuration owner**. It captures screen, game audio, and microphone; encodes and sends them via WebRTC; and owns every cast setting.

## Technology choices

| Choice | Value | Why |
|---|---|---|
| Language | Kotlin | Platform APIs (`MediaProjection`, `AudioPlaybackCapture`, FGS, HW codecs) are the critical path; anything cross-platform would wrap them in native modules anyway. |
| UI | Jetpack Compose + Material 3 | Small surface (pairing, status, settings); modern default. |
| Async | Coroutines + Flow | Natural fit for session state and service ↔ UI event streams. |
| minSdk | **29 (Android 10)** | `AudioPlaybackCapture` requires API 29; making it the floor keeps one clean audio architecture. |
| WebRTC | `io.getstream:stream-webrtc-android` | Actively published prebuilt of Google's libwebrtc; same `org.webrtc` API; no native build step. [ADR-001](../decisions/ADR-001-tech-stack.md) |
| QR scanning | CameraX + ML Kit barcode (bundled) | Decided in Phase 3: fully on-device (bundled model, no network dependency), best scanning reliability; +~4 MB APK. |

Exact dependency versions are pinned when the project is created in Phase 1.

## Planned module layout

Small, explicit modules — no giant `CastManager` (see AGENTS.md):

```text
com.zerofriction.localcast/
├── ui/            # Compose screens: Home (header: connection state + cast button; content: cast settings, Phase10), Scan (pairing)
├── pairing/       # QR scan, payload parsing, pairing state machine
├── signaling/     # WebSocket client, protocol envelope, message types
├── webrtc/        # PeerConnectionFactory setup, the two PeerConnections, track wiring
├── capture/       # MediaProjection virtual display + video frame source
├── audio/         # PlaybackCapture ADM (game audio), mic capture
├── service/       # CastService: foreground service owning the whole cast session
├── config/        # Cast settings (presets, persistence, the one CastConfig conversion)
├── thermal/       # Thermal status monitoring (read-only diagnostics; Phase11)
├── adaptive/      # Auto-quality policy machine: stats + thermal in, announced level changes out (Phase12)
└── diagnostics/   # Structured logging facade
```

Dependency direction: `ui` and `service` drive the session; `pairing`, `signaling`, `webrtc`, `capture`, `audio`, `thermal`, `adaptive`, `config` are independent of UI. `adaptive` is pure policy (no Android, no WebRTC types) — `service` feeds it samples and applies its announcements to `webrtc`. `signaling` knows nothing about media; `webrtc` knows nothing about sockets.

## Session architecture

- **`CastService`** is a **foreground service** (type `mediaProjection`, plus `microphone` while the mic session is on — `CastForegroundTypes`) that owns the whole cast session: MediaProjection, the peer connections, **and the pairing signaling connection** (handed over by the pairing machine at cast start — Phase6). The Activity is only UI; the cast survives the app being backgrounded while a game runs, the scan screen being left, and the task being removed. The mic type is added/removed at the mic toggle because Android11+ silences a backgrounded app's microphone unless its FGS declares the type (found live on Android16 — [features/microphone.md](../features/microphone.md)).
- **Ordering constraint (Android 14+):** the service enters the foreground *before* `createVirtualDisplay`/projection starts, and the media-projection consent result is obtained *before* the service starts. Implemented: consent → handover → foreground → projection.
- **Stop policy (Phase6 decision):** ending a cast ends the pairing session too (`bye` → the desktop shows a fresh QR; a new cast means a new scan). The service must stop for clean resource release, and no background process can be trusted to hold the socket. Every lifecycle edge (user stop via app or notification, projection revoked, desktop gone, process death) funnels through one idempotent teardown — no leaked projections/displays (the acceptance matrix lives in [features/cast-session.md](../features/cast-session.md)).
- Session state is exposed to the UI as a `StateFlow` (`Idle` / `Starting` / `Casting` / `Failed`), rendered by both the home and scan screens — not via callbacks scattered across classes.
- **Consent mode pitfall (Android 14+):** the projection dialog defaults to *Share one app*; a single-app share ends the moment that app leaves the foreground. Users must choose *Share full screen*. Handled cleanly (it is a normal projection-revoked stop), and since Phase13 the scan screen carries a UX affordance: a hint under the cast trigger — "When your phone asks what to share, choose 'Share full screen'."

## Permissions

| Permission | Purpose | When requested |
|---|---|---|
| `INTERNET` | WebSocket + WebRTC | Install time |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PROJECTION` + `FOREGROUND_SERVICE_MICROPHONE` | Cast keeps running while a game is foreground; the microphone type is required on Android11+ for a backgrounded app's mic (added/removed at the mic toggle) | Install time (manifest) |
| `POST_NOTIFICATIONS` | Cast-in-progress notification (required for FGS visibility) | Runtime, before first cast |
| `RECORD_AUDIO` | Playback-capture AudioRecord + the WebRTC ADM's initial mic record (Phase7), and microphone capture itself on the `mic` PC (Phase 8 — off by default, toggled live during the cast) | Runtime, before first cast (non-fatal: denial = video-only cast + mic needs-permission state) |
| `CAMERA` | QR code scanning only | Runtime, at scan screen |

`MediaProjection` consent is a system dialog per session, not a manifest permission.

## Android constraints future implementers must know

- **Consent dialog per session.** Every `MediaProjection` requires the system dialog; Android 14+ additionally can revoke projection when the app is backgrounded in specific ways, and each consent grants a single session (no "remember"). The UX must present this as normal ("Allow casting to start"), never as an error.
- **Projection callbacks.** The app must handle `MediaProjection.Callback#onStop` (user revoked from status bar / system timeout) as a first-class stop path.
- **Rotation.** Screen rotation changes the captured surface dimensions mid-stream; the capture pipeline must reconfigure the video source without dropping the session. The stock `ScreenCapturerAndroid` does **not** do this by itself (found live in the Phase14 device session) — `MediaCastSession` listens to the default display's change events and re-applies the capture format live from the display's current bounds (`CaptureSize.followDisplay`), no-opping on unrelated display events and 180° turns.
- **AudioPlaybackCapture policy** is per-app on the device — and **opt-out is the platform default for apps targeting API 29+**: only apps that set `allowAudioPlaybackCapture="true"` (or target ≤28) can be captured; opted-out apps produce silence, not errors. See [audio.md](audio.md) and the Phase7 verification record — the honest "This app's audio can't be captured" UI state covers this.
- **Hardware encoders** vary by SoC; H.264 is preferred with VP8 fallback negotiated in the offer (see [webrtc.md](webrtc.md)).

## Known limitations

- One cast session at a time (v1 non-goal: multi-desktop).
- Game audio cannot be captured on Android < 10 — impossible by design given minSdk 29 makes the whole app 10+.
- The consent dialog cannot be pre-granted or skipped, even for repeated casts.
- Casting while the phone is in extreme battery-saver/thermal states may be throttled by the OS; surfaced via the thermal strategy ([thermal.md](thermal.md)).

## Verification approach (per phase)

- Phase 1: builds, installs, launches (emulator + device).
- Phase 3+: protocol-level unit tests for pairing payload parsing; instrumentation tests for scanner.
- Phase 5/6/7/8: real-device manual matrix defined in the roadmap; emulators cannot validate HW encoding, real game audio, or thermals.
