# Mobile App Development Guide

App: `/apps/mobile` · Id: `com.zerofriction.localcast` · Implemented in: Phase 1 (status: **complete**).

## Prerequisites

See [setup.md](setup.md). Concretely: JDK 17, Android SDK with platform 36 + build-tools, an Android 10+ device or AVD for run verification.

## Build & run

```bash
cd apps/mobile
./gradlew :app:assembleDebug          # → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest      # unit tests
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.zerofriction.localcast/.MainActivity
```

`local.properties` (gitignored) must point `sdk.dir` at your SDK. First build downloads dependencies; later builds are incremental.

Verified on 2026-09-19: assembleDebug + unit tests green; installs and launches on an API 36 emulator (AVD "ME"), home screen renders per spec.

## Pinned toolchain (gradle/libs.versions.toml)

| Component | Version | Notes |
|---|---|---|
| Gradle (wrapper) | 8.13 | Wrapper files committed; no system Gradle needed. |
| Android Gradle Plugin | 8.9.0 | First version line with compileSdk 36 support. |
| Kotlin | 2.1.20 | With the Compose compiler plugin (same version). |
| compileSdk / targetSdk | 36 | Android 16. |
| minSdk | 29 | Android 10 floor ([ADR-001](../decisions/ADR-001-tech-stack.md)). |
| Compose BOM | 2024.12.01 | ui, material3, tooling via the BOM. |
| activity-compose | 1.9.3 | Edge-to-edge + setContent. |
| core-ktx / lifecycle | 1.15.0 / 2.8.7 | runtime, runtime-compose, viewmodel-compose. |
| JUnit | 4.13.2 | Unit tests only so far. |

A known "Deprecated Gradle features … incompatible with Gradle 9.0" warning comes from AGP on Gradle 8.13; upgrade AGP+Gradle together when a phase requires it — not before.

## Project layout

```text
apps/mobile/
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── gradle/libs.versions.toml        # every pinned version lives here
└── app/
    └── src/main/java/com/zerofriction/localcast/
        ├── MainActivity.kt          # single activity, Compose-only UI
        └── ui/
            ├── home/                # HomeScreen + HomeViewModel + HomeUiState
            └── theme/               # dark-first Material 3 theme
```

The package skeleton follows [architecture/mobile.md](../architecture/mobile.md): more packages (`pairing/`, `signaling/`, `webrtc/`, …) appear as their phases land — do not create them early.

## What is implemented (Phase 1)

- Buildable/installable app, single-activity Compose UI, dark theme, placeholder launcher icon.
- Home screen exactly per spec: title, `Status: Not connected`, **Start Cast** button.
- Start Cast shows an honest snackbar ("Casting isn't wired up yet — it arrives in Phase 5."); no fake casting state.

## Not implemented yet (by design)

Pairing/QR (Phase 3), signaling (Phase 4), MediaProjection video (Phase 5–6), game audio (Phase 7), microphone (Phase 8), settings UI (Phase 10), thermal (Phase 11). No permissions are declared yet — each is added when its feature lands.

## Conventions

- UI state flows one way: ViewModel → `StateFlow<UiState>` → `collectAsStateWithLifecycle()` in composables.
- User-visible strings go in `res/values/strings.xml` (status enum labels are the current exception — they map 1:1 to enum values).
- Tests live in `app/src/test` (unit) — instrumented tests are added when there is real device behavior to assert (Phase 3+).
