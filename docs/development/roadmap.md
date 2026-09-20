# Implementation Roadmap

Development is incremental: each phase = **plan → implement → test → document → user review**. A phase starts only after the previous one is approved. Every phase lists its acceptance criteria; a feature is not "done" because it compiles (see AGENTS.md and the Definition of Done below).

Current status: **Phase 11 implemented — awaiting review (the on-device items — profile switches visible in sender stats, and a live thermal-ladder read — remain; see [features/thermal.md](../features/thermal.md) and [features/cast-settings.md](../features/cast-settings.md)).**

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
**Accept:** protocol tests pass; reconnect-within-TTL and expiry paths exercised; mDNS/host-candidate behavior on LAN validated (risk R4). Commit `feat: add local signaling`. *(Implemented 2026-09-20: desktop vitest 31/31 incl. 11 loopback protocol tests, mobile 29/29, live check against the production desktop app — heartbeat, backoff ladder, reconnect-within-TTL, expiry stop, bye both ways; R4 plumbing validated, on-LAN mDNS resolution deferred to Phase 5 with rationale — see [features/signaling.md](../features/signaling.md).)*

## Phase 5 — WebRTC Video Proof of Concept ✅
**Scope:** smallest media pipeline: MediaProjection → video source → `media` PC → desktop `<video>`. Conservative config: 720p/30fps/4–6 Mbps, H.264 HW with VP8 fallback. Includes basic `getStats` logging.
**Accept (measured, on device):** connection succeeds; latency eyeballed/measured acceptable; quality acceptable; survives rotation; documents behavior when backgrounded. Findings written into `features/screen-capture.md`. Commit `feat: add webrtc video`. *(Implemented 2026-09-20: desktop vitest 48/48, mobile 43/43, and verified live on device (Lenovo TB321FU / Android 16 → macOS) — camera-scanned QR → real cast of YouTube/PUBG at ~4–6 Mbps, RTT 5–10 ms, ≈0.3% decoder drops; backgrounding via FGS verified. Honest gaps: glass-to-glass latency not formally measured; rotation validated only partially (settings-forced rotation doesn't move the sensor libwebrtc keys off) — both flagged for Phase 6/15. A minimal CastService FGS shipped *early* because Android 14+ requires it before projection — rationale in [features/screen-capture.md](../features/screen-capture.md).)*

## Phase 6 — Foreground Service + Stable Screen Capture
**Scope:** move capture into `CastService` (FGS type `mediaProjection`); Android14+ ordering; notification; full lifecycle matrix — screen locked, app backgrounded, rotation, service stopped, permission revoked (`onStop` callback), network loss, desktop closed; resource release.
**Accept:** cast continues during gameplay; every lifecycle edge ends in a clean state (no leaked projections/displays); documented in `architecture/mobile.md` + `features/cast-session.md`. Commit `feat: add android screen capture` (service rework). *(Implemented 2026-09-20: the service now owns the whole session (signaling handed over at cast start, cast survives leaving the scan screen/app), richer `CastState` on home+scan, notification with Stop action, first-class failure taxonomy (revoked/desktop-ended/connection-lost), start-validation against dead handovers (bug found live). JVM tests 46/46. Live-verified on device: camera-scan pairing → running FGS with the new notification; projection-revoked path ended casts cleanly (`bye` twice). The remaining lifecycle matrix needs the device in hand — cases + expectations tabulated in [features/cast-session.md](../features/cast-session.md); the device was in active personal use during the verification window and remote driving was deliberately halted.)*

## Phase 7 — Internal/Game Audio
**Scope:** **validation spike first** (ADR-003): custom playback-capture ADM over the `media` PC. Then the feature: `AudioPlaybackCaptureConfiguration`, usage filter, silent-input detection + UI state, mute/unmute.
**Accept:** game audio audible on desktop; opted-out apps detected and surfaced honestly; mute works; behavior on disconnect clean; `features/game-audio.md` with findings. Commit `feat: add playback audio capture`. *(Implemented 2026-09-20: the spike disproved the "custom ADM" wording — no public PCM-injection API in the pinned prebuilt (ADR-003 addendum) — so the stock ADM's mic record is substituted at recording start. Live on device: audio track in the offer, honest "This app's audio can't be captured" verified against the platform's **opt-out default** (YouTube, most games — the big product finding), mute wiring, three live-found bugs fixed (reflection class-init crash, the desktop's muted `<video>`, the scan-screen navigation dead end). Remaining: one audible test-tone listen on the fixed desktop build + the Phase15 audio matrix. JVM tests 55/55, desktop 48/48.)*

## Phase 8 — Microphone Audio
**Scope:** `mic` PC (factory B) with `JavaAudioDeviceModule`; permission flow; mic toggle; simultaneous operation with game audio.
**Accept:** mic audible as an independent stream; independent of game-audio state; permission-denied path graceful. Commit `feat: add microphone capture`. *(Implemented 2026-09-20: `MicCastSession` built/torn down on demand by a live on/off toggle (mic off by default — privacy, and the media PC is never renegotiated); every mic failure is mic-local; the desktop answers the mic pc on its own answerer and renders it to a separate `<audio>` element (the Phase9 mixer is next). Live on device: mic offers answered through a full off→on toggle cycle, mic stream rendered at the receiver, mic pc connected independently while game audio streamed loud on the media pc, new mic UI observed on both screens. Remaining: the audible mic listen (the room was quiet during measurement — stream live but silent) + runtime permission-denial on device goes to the Phase15 matrix. JVM tests 58/58, desktop 53/53.)*

## Phase 9 — Desktop Audio Mixer
**Scope:** `MediaStreamAudioSourceNode → GainNode ×2 → destination`; independent, persisted volume sliders; no extra processing.
**Accept:** independent volume/mute per stream; levels persist across launches; no distortion/clipping at sensible defaults; drift check over a long session. Commit `feat: add desktop audio mixer`. *(Implemented 2026-09-20: `AudioMixer` is the receiver's only audible path — the `<video>` element is muted (it renders video only; the old element playback would double the game audio next to the graph); the mic stream is held by a muted keep-alive `<audio>` element because Chromium stops pulling an element-less MediaStream while the window is hidden (found live: minimized → mic silent). Two live-found mic bugs fixed during the listen: the desktop keep-alive above, and the mobile `microphone` FGS type — Android11+ silences a backgrounded app's mic without it (mic went silent when the app was backgrounded; `CastForegroundTypes` adds/removes the type at the mic toggle, mobile JVM 62/62). Unity-gain defaults; levels persisted in renderer `localStorage`, restored on launch; corrupt storage degrades to defaults. Desktop 67/67 (14 new mixer tests), build green; live CDP check drove the sliders/mute and verified persistence across a relaunch. Remaining: the audible live-cast listen (independence heard, clipping, long-session drift) — [features/audio-mixer.md](../features/audio-mixer.md).)*

## Phase 10 — Mobile Cast Configuration
**Scope:** settings screen on mobile (quality profile, resolution, FPS, bitrate/auto, game-audio & mic toggles) per [overview.md](../architecture/overview.md) ownership rules; sender-side `RTCRtpSender` parameter application; changes take effect on next cast (or live if cheap).
**Accept:** settings demonstrably change the sent stream (bitrate/resolution visible in stats); desktop exposes **no** cast settings; settings persist. Commit `feat: add mobile cast settings`. *(Implemented 2026-09-20: the home page is the settings hub — header = connection state + Start/Stop button, content = "This cast" live controls (while casting) + "Share screen" & "Audio" settings (a review-time restructure; no separate settings screen). Every change persists immediately (SharedPreferences, versioned JSON, corrupt → defaults) and the cast-start path reads the store fresh — "next cast" semantics; four named presets (balanced/sharp/smooth/light) plus tweakable resolution/fps, auto-or-manual bitrate (manual floor = half the ceiling); the derived profile label rides the display-only `session-info` ("custom" when tweaked past a preset). Desktop untouched — no cast settings exist there (Phase9's mixer is receiver/environment control). Sender stats now log frame size next to bitrate, so a settings change is visible in the ~1 Hz line. JVM tests 83/83, build green, UI + persistence verified on an emulator; remaining: the live-cast proof on the real device — [features/cast-settings.md](../features/cast-settings.md).)*

## Phase 11 — Thermal Profiles
**Scope:** profile definitions ([thermal.md](../architecture/thermal.md)); profile → sender parameters; thermal status monitoring + diagnostics logging; **read-only** thermal state (no auto-degradation yet).
**Accept:** profiles switch measurably distinct encoder configs; thermal ladder visible in diagnostics; docs updated with any tuned values. Commit `feat: add thermal profiles`. *(Implemented 2026-09-20: the Phase10 presets became the thermal vocabulary — `light`→`cool`, `smooth`→`performance`, values unchanged (thermal.md's hypotheses, tuned to the shipped windows); the profile→sender-parameters chain is the existing preset→CastConfig→capturer/sender path, now covered by a distinctness test. New `thermal` module: pure `ThermalMonitor` (ladder state machine, JVM-tested incl. a mirror guard against `PowerManager`'s own constants) + `ThermalSource` (platform listener API29, headroom API30+, battery temperature, 10 s samples). Read-only everywhere: the "This cast" section shows a plain-words thermal line, transitions + headroom + battery go to logcat (Phase15's trail); nothing changes cast parameters. Stored settings v1 documents migrate via the codec (v2, legacy label translation). Mobile JVM 94/94, `assembleDebug` green. Remaining: on-device profile-switch stats check + a live thermal-ladder read — [features/thermal.md](../features/thermal.md).)*

## Phase 12 — Auto Quality / Adaptive Streaming
**Scope (only after the system is stable):** stats-driven adaptation with hysteresis and user notification; inputs: dropped frames, RTT, packet loss, encoder stress, thermal status.
**Accept:** controlled step-downs/ups without oscillation; user can disable; every transition logged and user-visible. Commit `feat: add adaptive quality`.

## Phase 13 — Production Pairing UX
**Scope:** polished pairing screens on both sides per the spec mockups (desktop QR hero + "Scan with your Android device"; mobile scan → "Desktop found / Connected → START CAST"); minimal-step happy path; friendly error states.
**Accept:** first-time user flow completes in ≤ 3 actions; all failure modes from `pairing.md` show their mapped messages. Commit UX phase.

## Phase 14 — OBS / Streaming Workflow
**Scope:** receiver window hardening: stable size/aspect, letterboxing that follows rotation, controls out of the video rect, dark background, smooth rendering; optional `session-info` overlay off-cast. *(Partially landed early, review-time after Phase9: full-window video, hover-revealed overlay + settings modal, and the window following the stream's aspect on cast start/rotation — see [architecture/desktop.md](../architecture/desktop.md) §Window design. Remaining: the 30+ min OBS session hardening, manual-resize letterbox polish, the off-cast overlay.)*
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
- Phase 3+: `features/pairing.md`, `features/signaling.md`, `features/screen-capture.md`, `features/game-audio.md`, `features/microphone.md`, `features/cast-session.md`, `features/audio-mixer.md`, `features/cast-settings.md`
- Any architecture change → update `architecture/*` and add/supersede an ADR in the same phase.
