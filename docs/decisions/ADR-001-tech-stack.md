# ADR-001: Technology stack for mobile and desktop

- **Status:** accepted (Phase 0, 2026-09-19)
- **Deciders:** project owner + agent proposal

## Context

The product requires, on Android: `MediaProjection` screen capture, `AudioPlaybackCapture` game-audio capture, microphone capture, hardware H.264 encoding, a foreground service surviving gameplay, and WebRTC as media transport. On desktop: WebRTC receiving (H.264/VP8/Opus decode), a Web Audio `GainNode` mixer (the product spec itself sketches this model), a stable window that OBS can capture, and near-zero configuration. The end-user workflow may not involve USB, ADB, or Developer Options.

## Decision

| Layer | Choice |
|---|---|
| Mobile | **Native Android, Kotlin, Jetpack Compose, Coroutines/Flow** |
| Mobile minSdk | **29 (Android 10)** |
| Mobile WebRTC | **`io.getstream:stream-webrtc-android`** (prebuilt Google libwebrtc on Maven Central, `org.webrtc` namespace) |
| Desktop | **Electron + TypeScript** (electron-vite scaffold; main = Node, renderer = Chromium) |
| Transport | WebRTC, host ICE candidates, **no STUN/TURN**; H.264 preferred with VP8 fallback; Opus audio |

## Considered alternatives

**Mobile framework.** Flutter/React Native were rejected because every critical path (projection, playback capture, foreground service, HW encoders, libwebrtc integration) is a native platform API — cross-platform frameworks would reduce UI code while adding native modules, bridging layers, and build complexity exactly where latency and reliability matter most. Native Kotlin is the smallest-risk path for a media-pipeline app.

**minSdk.** 26 (wider reach) was rejected because `AudioPlaybackCapture` requires API 29 — older devices would need a permanently degraded audio path and extra branching. 33+ was rejected as excluding a meaningful share of Android 10–12 gamers for marginal code simplification. minSdk 29 keeps one clean audio architecture.

**Mobile WebRTC artifact.** Google's `org.webrtc:google-webrtc` is frozen (~2020) with known old ICE/codec behavior — rejected. Building libwebrtc from source gives maximum control (e.g., custom audio sources) at the cost of a multi-day, multi-gigabyte build repeated for every security update — rejected for now; revisit only if ADR-003's spike fails and a native patch becomes necessary. Stream's prebuilt is the same libwebrtc API, published and updated on Maven Central, swappable later without code changes.

**Desktop.** Tauri 2 is far lighter but uses each OS's system WebView; WebRTC and codec support in WKWebView/WebView2 vary by platform and version — an unacceptable risk for the app's entire purpose. A native receiver (Qt/Swift + libwebrtc) offers the lowest latency but a heavy, ongoing native build burden for a receiver whose LAN WebRTC latency budget is comfortably met by Chromium. Electron additionally ships proprietary codecs (H.264) in its Chromium, provides Web Audio for the spec's GainNode mixer, and yields a stable, OBS-friendly window.

## Consequences

- **Positive:** platform APIs used directly; the spec's Web Audio mixer model maps 1:1; both apps are TypeScript/Kotlin with no bridging layers; libwebrtc updates are a version bump.
- **Negative / accepted costs:** Electron RAM footprint (tens of MB–few hundred) is acceptable for a receiver; trust in Stream's artifact provenance (mitigated: it is a repackaging of Google's own source; can be swapped); Android 9-and-below users are excluded by design.
- Versions (Kotlin/Compose/Electron/etc.) are pinned when each app is initialized (Phases 1–2) and recorded in the app-level docs.
