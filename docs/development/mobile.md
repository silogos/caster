# Mobile App Development Guide

App: `/apps/mobile` · Id: `com.zerofriction.localcast` · Implemented in: Phases 1–13 (status: **Phase13 implemented — the polished scan flow's real-device walk remains; see [features/pairing.md](../features/pairing.md)**).

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

Verified 2026-09-20 (Phase13): **126 unit tests** green (4 new: ScanViewModel behaviors — instant "Desktop found" `Connecting` after a scan, in-flight/connected scans ignored, failed pairing accepts a fresh scan, handover/disconnect semantics). `assembleDebug` green; the polished scan-flow walk on the real device is the remaining acceptance item ([features/pairing.md](../features/pairing.md)).
Verified 2026-09-20 (Phase11): **94 unit tests** green (11 new: thermal ladder state machine + the `PowerManager` constant-mirror guard, preset relabels + v1→v2 settings migration, encoder-target distinctness). `assembleDebug` green; the thermal wiring (ladder listener, headroom, battery samples, home-screen read-out) is exercised on device in the remaining acceptance items ([features/thermal.md](../features/thermal.md)).
Verified 2026-09-20 (Phase 10): **83 unit tests** green (21 new: quality presets/labels, settings codec, settings ViewModel, frame-size stats extraction). `assembleDebug` green; the settings UI (home page) and its persistence exercised on an API 34 emulator (screen render, preset pick, manual-bitrate floor derivation, restore after force-stop, toggle persistence) — the on-device live-cast check remains ([features/cast-settings.md](../features/cast-settings.md)).
Verified 2026-09-20 (Phase 6): `assembleDebug` + **46 unit tests** green (3 new: pairing handover semantics). Live on device (Lenovo TB321FU / Android 16): camera-scan pairing → foreground service with the new notification; projection-revoked ends handled cleanly. The full lifecycle matrix is tabulated in [features/cast-session.md](../features/cast-session.md) and is the remaining hands-on item.
Verified on 2026-09-20 (Phase 5): 43 unit tests green (14 new: SDP codec ordering, capture sizing, sender-stats extraction). Live on-device cast verified end to end — details in [features/screen-capture.md](../features/screen-capture.md).
Previously (Phase 4): 29 unit tests green — signaling lifecycle (heartbeat, backoff ladder, expiry stop, desktop bye) with a virtual-clock scheduler; details in [features/signaling.md](../features/signaling.md).
Previously (Phase 3): real-device QR scan verified — details in [features/pairing.md](../features/pairing.md).

## Pinned toolchain (gradle/libs.versions.toml)

| Component | Version | Notes |
|---|---|---|
| Gradle (wrapper) | 8.13 | Wrapper files committed; no system Gradle needed. |
| Android Gradle Plugin | 8.13.2 | First version line with compileSdk 36 support. |
| Kotlin | 2.1.20 | With the Compose compiler + serialization plugins (same version). |
| compileSdk / targetSdk | 36 | Android 16. |
| minSdk | 29 | Android 10 floor ([ADR-001](../decisions/ADR-001-tech-stack.md)). |
| Compose BOM | 2024.12.01 | ui, material3, icons-core, tooling via the BOM. |
| material-icons-core | (BOM) | The small core icon set (CheckCircle, Warning, Info) — pairing-screen states (Phase13). |
| activity-compose | 1.9.3 | Edge-to-edge + setContent + permission result launcher. |
| core-ktx / lifecycle | 1.15.0 / 2.8.7 | runtime, runtime-compose, viewmodel-compose. |
| CameraX | 1.4.2 | core, camera2, lifecycle, view — QR scan preview/analysis (Phase 3). |
| ML Kit barcode-scanning | 17.3.0 | Bundled model: fully on-device, no network dependency; +~4 MB. |
| okhttp | 4.12.0 | WebSocket signaling client (Phase 3). |
| stream-webrtc-android | 1.3.8 | Prebuilt Google libwebrtc (`org.webrtc` API, no native build step) — the media PC, capture, ICE. [ADR-001](../decisions/ADR-001-tech-stack.md). ~+58 MB APK native libs. |
| kotlinx-serialization-json | 1.8.0 | QR payload + envelope codec. |
| JUnit / coroutines-test | 4.13.2 / 1.10.1 | Plain-JVM unit tests. |

A known "Deprecated Gradle features … incompatible with Gradle 9.0" warning comes from AGP on Gradle 8.13; upgrade AGP+Gradle together when a phase requires it — not before.

## Project layout

```text
apps/mobile/
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── gradle/libs.versions.toml        # every pinned version lives here
└── app/src/main/java/com/zerofriction/localcast/
    ├── MainActivity.kt              # single activity; Home ⇄ Scan navigation (state-based, no nav lib). Phase10: the home page carries the cast settings.
    ├── pairing/                     # QrPayload + parser, PairingClient state machine (Phase 3)
    ├── signaling/                   # Envelope codec, Handshake HMAC, SignalingClient + OkHttp transport (Phase 3)
    ├── config/                      # CastConfig + CastSettings: presets, JSON codec, persistence (Phases 5/10; thermal vocabulary in Phase11)
    ├── capture/                     # CaptureSize/DisplaySize math; projection via ScreenCapturerAndroid (Phase 5)
    ├── audio/                        # PlaybackCaptureAudioSource (game audio, Phase 7), mic capture (Phase 8), GameAudioMonitor + GameAudioState/MicState
    ├── thermal/                      # ThermalMonitor (ladder state machine) + ThermalSource (platform listener, headroom, battery) — read-only (Phase 11)
    ├── webrtc/                      # MediaCastSession (media PC), SdpCodecOrderer, IceCandidateJson, SenderStats (Phase 5)
    ├── service/                     # CastService — mediaProjection FGS owning the whole session (Phase 6)
    ├── debug/                        # DebugTestTone launcher glue (debug builds only)
    └── ui/
        ├── home/                    # HomeScreen (header: cast state + button; content: settings + live controls) + HomeViewModel
        ├── settings/                # CastSettingsPanel (the home page's settings sections) + SettingsViewModel (Phase 10)
        ├── pairing/                 # ScanScreen + ScanViewModel + QrCamera (CameraX + ML Kit)
        └── theme/                   # dark-first Material 3 theme
```

The package skeleton follows [architecture/mobile.md](../architecture/mobile.md): more packages (`webrtc/`, `service/`, …) appear as their phases land — do not create them early.

## What is implemented

- **Phase 1**: buildable/installable app, single-activity Compose UI, dark theme, home screen (title, `Status: Not connected`, **Start Cast**).
- **Phase 3 — pairing**: Start Cast opens the scan screen; camera scans the desktop's QR (payload v1), connects over WebSocket, runs the HMAC handshake, and shows "Connected to \<desktop name\>" (or the mapped, non-technical failure message). **Disconnect** sends `bye` — the desktop returns to a fresh QR. Runtime CAMERA permission with rationale; debug builds add a manual "paste payload JSON" input for emulator verification. Details: [features/pairing.md](../features/pairing.md).
- **Phase 10 — cast settings**: the configuration-owner UI — quality presets (balanced/sharp/smooth/light), resolution/fps tweaks, auto-or-manual bitrate, audio defaults for the next cast. Every change persists immediately (versioned JSON in SharedPreferences; corrupt → defaults); the cast-start path reads the store fresh, so changes take effect on the next cast. Details: [features/cast-settings.md](../features/cast-settings.md).

## Permissions

- `INTERNET` (signaling WebSocket), `CAMERA` (QR scanning only, requested at the scan screen), `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PROJECTION` (cast service), `POST_NOTIFICATIONS` (cast notification visibility, requested before the first cast; not fatal). **Cleartext is allowed via `network_security_config.xml`**: the signaling channel is plain `ws://` on the LAN by design ([ADR-002](../decisions/ADR-002-pairing-and-signaling-security.md)); Android blocks cleartext by default. Do not remove this without changing the transport.

## Not implemented yet (by design)

Thermal profiles (Phase 11) and adaptive quality (Phase 12). Casting (Phases 5–10) is implemented — see [features/screen-capture.md](../features/screen-capture.md), [features/cast-session.md](../features/cast-session.md), and [features/cast-settings.md](../features/cast-settings.md).

## Conventions

- UI state flows one way: ViewModel → `StateFlow<UiState>` → `collectAsStateWithLifecycle()` in composables.
- User-visible strings go in `res/values/strings.xml` (status enum labels are the current exception — they map 1:1 to enum values). Error strings follow [pairing.md](../architecture/pairing.md) §Failure modes verbatim — simple and non-technical; codes stay in logs.
- `pairing/` and `signaling/` know nothing about UI or Android widgets; the UI drives them ([architecture/mobile.md](../architecture/mobile.md)).
- Tests live in `app/src/test` (unit, plain JVM — `android.util.Log` is a stub, no-op'd via `unitTests.isReturnDefaultValues`). Instrumented tests are added when there is real device behavior to assert.
- Debug-only UI affordances (manual payload input) are gated on `BuildConfig.DEBUG` and must never ship in release builds.
