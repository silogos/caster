# Implementation Roadmap

Development is incremental: each phase = **plan → implement → test → document → user review**. A phase starts only after the previous one is approved. Every phase lists its acceptance criteria; a feature is not "done" because it compiles (see AGENTS.md and the Definition of Done below).

Current status: **Phase 2 complete — awaiting review.**

> **Phase tracking on GitHub:** each phase has a corresponding issue (labeled `phase`) at `silogos/caster` — issue #1 = Phase 0 through #17 = Phase 16. Open issues are remaining work; a phase's issue is closed with a completion comment (commit hash + verification summary) when it passes review. This file remains the source of truth; the issues mirror it.

---

## Phase 0 — Project Architecture ✅
**Scope:** repo skeleton; architecture docs ([architecture/overview.md](../architecture/overview.md) and siblings); ADRs 001–003 ([decisions/](../decisions)); this roadmap; risk register. No application code.
**Accept:** structure matches the spec (`apps/`, `docs/`, root files); docs are consistent and cover why/what/constraints/limitations; risks identified with mitigations and validation phases.

## Phase 1 — Initialize Mobile Application ✅
**Scope:** `/apps/mobile`: Gradle (Kotlin DSL) project, application id `com.zerofriction.localcast`, minSdk 29, Compose UI with the minimal home screen (title, `[Start Cast]`, status "Not connected"), pinned dependency versions, `development/mobile.md`.
**Not in scope:** WebRTC, MediaProjection, any audio, pairing, desktop communication.
**Accept:** build succeeds; installs and launches on emulator/device; commit `chore: initialize mobile app`. *(Verified 2026-09-19: build + 3 unit tests green; installed and launched on API 36 emulator, home screen renders per spec — [mobile.md](mobile.md).)*

## Phase 2 — Initialize Desktop Application
**Scope:** `/apps/desktop`: electron-vite + TypeScript scaffold; dark UI showing app title, "Waiting for mobile device…", a QR rendered from **static test data**; main/renderer split per [desktop.md](../architecture/desktop.md); `development/desktop.md`; root package scripts wired.
**Not in scope:** real pairing data, WebSocket server, WebRTC.
**Accept:** builds, launches, QR renders and is scannable with any QR reader; window behaves correctly; commit `chore: initialize desktop app`. *(Verified 2026-09-20: typecheck + 3 unit tests green; production build launches, dark pairing screen renders; QR decoded both from the generated data URL and from the running window's screenshot — [desktop.md](desktop.md).)*

## Phase 3 — Pairing Protocol
**Scope:** QR payload v1 generation + display on desktop ([pairing.md](../architecture/pairing.md)); camera scanning + payload parsing on mobile; WebSocket connect; `hello/challenge/auth/auth-ok` handshake (HMAC); session expiry/regeneration. Test handshake only — no media.
**Accept:** desktop generates QR → mobile scans → mobile extracts payload → connects → desktop recognizes the phone (shows its name/state). Unit tests for payload parse/validate + HMAC handshake (loopback). Commit `feat: add qr pairing`. *(Implemented 2026-09-20: desktop vitest 20/20 (incl. loopback ws handshake), mobile unit tests 15/15, cross-platform HMAC vector; live QR verified via CDP — see [features/pairing.md](../features/pairing.md). Real-device camera scan verified 2026-09-20 on a Lenovo TB321FU / Android 16 — see [features/pairing.md](../features/pairing.md).)*

## Phase 4 — Local Signaling
**Scope:** full envelope + message set (`sdp-*`, `ice`, `ping/pong`, `bye`, `error`) per [webrtc.md](../architecture/webrtc.md); connection lifecycle, heartbeat, disconnect/reconnect rules; version negotiation. Verified with **loopback protocol tests** — two endpoints exchanging recorded SDP blobs, no real media.
**Accept:** protocol tests pass; reconnect-within-TTL and expiry paths exercised; mDNS/host-candidate behavior on LAN validated (risk R4). Commit `feat: add local signaling`.

## Phase 5 — WebRTC Video Proof of Concept
**Scope:** smallest media pipeline: MediaProjection → video source → `media` PC → desktop `<video>`. Conservative config: 720p/30fps/4–6 Mbps, H.264 HW with VP8 fallback. Includes basic `getStats` logging.
**Accept (measured, on device):** connection succeeds; latency eyeballed/measured acceptable; quality acceptable; survives rotation; documents behavior when backgrounded. Findings written into `features/screen-capture.md`. Commit `feat: add webrtc video`.

## Phase 6 — Foreground Service + Stable Screen Capture
**Scope:** move capture into `CastService` (FGS type `mediaProjection`); Android 14+ ordering; notification; full lifecycle matrix — screen locked, app backgrounded, rotation, service stopped, permission revoked (`onStop` callback), network loss, desktop closed; resource release.
**Accept:** cast continues during gameplay; every lifecycle edge ends in a clean state (no leaked projections/displays); documented in `architecture/mobile.md` + `features/cast-session.md`. Commit `feat: add android screen capture` (service rework).

## Phase 7 — Internal/Game Audio
**Scope:** **validation spike first** (ADR-003): custom playback-capture ADM over the `media` PC. Then the feature: `AudioPlaybackCaptureConfiguration`, usage filter, silent-input detection + UI state, mute/unmute.
**Accept:** game audio audible on desktop; opted-out apps detected and surfaced honestly; mute works; behavior on disconnect clean; `features/game-audio.md` with findings. Commit `feat: add playback audio capture`.

## Phase 8 — Microphone Audio
**Scope:** `mic` PC (factory B) with `JavaAudioDeviceModule`; permission flow; mic toggle; simultaneous operation with game audio.
**Accept:** mic audible as an independent stream; independent of game-audio state; permission-denied path graceful. Commit `feat: add microphone capture`.

## Phase 9 — Desktop Audio Mixer
**Scope:** `MediaStreamAudioSourceNode → GainNode ×2 → destination`; independent, persisted volume sliders; no extra processing.
**Accept:** independent volume/mute per stream; levels persist across launches; no distortion/clipping at sensible defaults; drift check over a long session. Commit `feat: add desktop audio mixer`.

## Phase 10 — Mobile Cast Configuration
**Scope:** settings screen on mobile (quality profile, resolution, FPS, bitrate/auto, game-audio & mic toggles) per [overview.md](../architecture/overview.md) ownership rules; sender-side `RTCRtpSender` parameter application; changes take effect on next cast (or live if cheap).
**Accept:** settings demonstrably change the sent stream (bitrate/resolution visible in stats); desktop exposes **no** cast settings; settings persist. Commit `feat: add mobile cast settings`.

## Phase 11 — Thermal Profiles
**Scope:** profile definitions ([thermal.md](../architecture/thermal.md)); profile → sender parameters; thermal status monitoring + diagnostics logging; **read-only** thermal state (no auto-degradation yet).
**Accept:** profiles switch measurably distinct encoder configs; thermal ladder visible in diagnostics; docs updated with any tuned values. Commit `feat: add thermal profiles`.

## Phase 12 — Auto Quality / Adaptive Streaming
**Scope (only after the system is stable):** stats-driven adaptation with hysteresis and user notification; inputs: dropped frames, RTT, packet loss, encoder stress, thermal status.
**Accept:** controlled step-downs/ups without oscillation; user can disable; every transition logged and user-visible. Commit `feat: add adaptive quality`.

## Phase 13 — Production Pairing UX
**Scope:** polished pairing screens on both sides per the spec mockups (desktop QR hero + "Scan with your Android device"; mobile scan → "Desktop found / Connected → START CAST"); minimal-step happy path; friendly error states.
**Accept:** first-time user flow completes in ≤ 3 actions; all failure modes from `pairing.md` show their mapped messages. Commit UX phase.

## Phase 14 — OBS / Streaming Workflow
**Scope:** receiver window hardening: stable size/aspect, letterboxing that follows rotation, controls out of the video rect, dark background, smooth rendering; optional `session-info` overlay off-cast.
**Accept:** OBS Window Capture produces a clean, correctly-proportioned, smooth feed over a 30+ min session. Commit receiver-window phase.

## Phase 15 — Reliability Testing
**Scope:** full manual+instrumented matrices: network (weak Wi-Fi, loss, reconnects, IP changes), Android lifecycle (all Phase 6 edges + app/service killed), audio (blocked capture, permissions, mute), desktop (closed/restarted, QR regenerated, multiple pairing attempts), thermal measurement protocol per profile on real hardware.
**Accept:** results recorded (pass/fail per case) in docs; no silent failures; thermal numbers replace hypotheses in `thermal.md`. Commits: fixes as they surface.

## Phase 16 — Performance Optimization
**Scope:** profile-guided optimization only (capture, encode, network, decode, render, audio, service overhead). Each optimization tied to a measurement before/after.
**Accept:** documented before/after for each accepted change; no speculative rewrites.

---

## Definition of Done (every phase)

```text
Implementation + Tests + Documentation + Manual verification (where appropriate)
+ Clear report (what/files/tests/decisions/limitations/next) + User review
```

## Documentation produced along the way

- Phase 1/2: `development/mobile.md`, `development/desktop.md`
- Phase 3+: `features/pairing.md`, `features/screen-capture.md`, `features/game-audio.md`, `features/microphone.md`, `features/cast-session.md`
- Any architecture change → update `architecture/*` and add/supersede an ADR in the same phase.
