# Mobile App Development Guide

App: `/apps/mobile` · Id: `com.zerofriction.localcast` · Implemented in: Phases 1 and 3 (status: **Phase 3 complete — verified on device**).

## Prerequisites

See [setup.md](setup.md). Concretely: JDK 17, Android SDK with platform 36 + build-tools, an Android 10+ device or AVD for run verification. **Camera QR scanning requires a real device** — an emulator has no camera pointed at your monitor; emulator verification uses the debug manual payload input instead (below).

## Build & run

```bash
cd apps/mobile
./gradlew :app:assembleDebug          # → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest      # unit tests
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.zerofriction.localcast/.MainActivity
```

`local.properties` (gitignored) must point `sdk.dir` at your SDK. First build downloads dependencies; later builds are incremental.

Verified on 2026-09-20 (Phase 3): `assembleDebug` + 15 unit tests green (details: [features/pairing.md](../features/pairing.md)); live QR scan on a real device — pending.

## Pinned toolchain (gradle/libs.versions.toml)

| Component | Version | Notes |
|---|---|---|
| Gradle (wrapper) | 8.13 | Wrapper files committed; no system Gradle needed. |
| Android Gradle Plugin | 8.13.2 | First version line with compileSdk 36 support. |
| Kotlin | 2.1.20 | With the Compose compiler + serialization plugins (same version). |
| compileSdk / targetSdk | 36 | Android 16. |
| minSdk | 29 | Android 10 floor ([ADR-001](../decisions/ADR-001-tech-stack.md)). |
| Compose BOM | 2024.12.01 | ui, material3, tooling via the BOM. |
| activity-compose | 1.9.3 | Edge-to-edge + setContent + permission result launcher. |
| core-ktx / lifecycle | 1.15.0 / 2.8.7 | runtime, runtime-compose, viewmodel-compose. |
| CameraX | 1.4.2 | core, camera2, lifecycle, view — QR scan preview/analysis (Phase 3). |
| ML Kit barcode-scanning | 17.3.0 | Bundled model: fully on-device, no network dependency; +~4 MB. |
| okhttp | 4.12.0 | WebSocket signaling client (Phase 3). |
| kotlinx-serialization-json | 1.8.0 | QR payload + envelope codec. |
| JUnit / coroutines-test | 4.13.2 / 1.10.1 | Plain-JVM unit tests. |

A known "Deprecated Gradle features … incompatible with Gradle 9.0" warning comes from AGP on Gradle 8.13; upgrade AGP+Gradle together when a phase requires it — not before.

## Project layout

```text
apps/mobile/
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── gradle/libs.versions.toml        # every pinned version lives here
└── app/src/main/java/com/zerofriction/localcast/
    ├── MainActivity.kt              # single activity; Home ⇄ Scan navigation (state-based, no nav lib yet)
    ├── pairing/                     # QrPayload + parser, PairingClient state machine (Phase 3)
    ├── signaling/                   # Envelope codec, Handshake HMAC, SignalingClient + OkHttp transport (Phase 3)
    └── ui/
        ├── home/                    # HomeScreen + HomeViewModel + HomeUiState
        ├── pairing/                 # ScanScreen + ScanViewModel + QrCamera (CameraX + ML Kit)
        └── theme/                   # dark-first Material 3 theme
```

The package skeleton follows [architecture/mobile.md](../architecture/mobile.md): more packages (`webrtc/`, `service/`, …) appear as their phases land — do not create them early.

## What is implemented

- **Phase 1**: buildable/installable app, single-activity Compose UI, dark theme, home screen (title, `Status: Not connected`, **Start Cast**).
- **Phase 3 — pairing**: Start Cast opens the scan screen; camera scans the desktop's QR (payload v1), connects over WebSocket, runs the HMAC handshake, and shows "Connected to \<desktop name\>" (or the mapped, non-technical failure message). **Disconnect** sends `bye` — the desktop returns to a fresh QR. Runtime CAMERA permission with rationale; debug builds add a manual "paste payload JSON" input for emulator verification. Details: [features/pairing.md](../features/pairing.md).

## Permissions

- `INTERNET` (signaling WebSocket), `CAMERA` (QR scanning only, requested at the scan screen). Nothing else — later phases add theirs with their feature.
- **Cleartext is allowed via `network_security_config.xml`**: the signaling channel is plain `ws://` on the LAN by design ([ADR-002](../decisions/ADR-002-pairing-and-signaling-security.md)); Android blocks cleartext by default. Do not remove this without changing the transport.

## Not implemented yet (by design)

WebRTC/casting (Phases 5–6), game audio (Phase 7), microphone (Phase 8), settings UI (Phase 10), thermal (Phase 11). Heartbeat/reconnect/backoff and SDP/ICE signaling arrive in Phase 4.

## Conventions

- UI state flows one way: ViewModel → `StateFlow<UiState>` → `collectAsStateWithLifecycle()` in composables.
- User-visible strings go in `res/values/strings.xml` (status enum labels are the current exception — they map 1:1 to enum values). Error strings follow [pairing.md](../architecture/pairing.md) §Failure modes verbatim — simple and non-technical; codes stay in logs.
- `pairing/` and `signaling/` know nothing about UI or Android widgets; the UI drives them ([architecture/mobile.md](../architecture/mobile.md)).
- Tests live in `app/src/test` (unit, plain JVM — `android.util.Log` is a stub, no-op'd via `unitTests.isReturnDefaultValues`). Instrumented tests are added when there is real device behavior to assert.
- Debug-only UI affordances (manual payload input) are gated on `BuildConfig.DEBUG` and must never ship in release builds.
