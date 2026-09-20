# Feature: WebRTC Video Proof of Concept

Implemented in: **Phase 5** (see [roadmap](../development/roadmap.md)). Status: **implemented and verified on device (2026-09-20, Lenovo TB321FU / Android 16 → macOS desktop, same Wi-Fi)** — the smallest media pipeline end to end: MediaProjection → video source → `media` PC → desktop `<video>`. No audio yet (Phases 7–8), no settings UI yet (Phase 10).

## What is implemented

### Mobile (`apps/mobile`)

- **`config/CastConfig`**: the single home of cast settings (AGENTS.md invariant). Phase5 ships the fixed conservative profile from [webrtc.md](../architecture/webrtc.md): 720p long edge (1280), 30 fps, bitrate window 4–6 Mbps, degradation preference BALANCED.
- **`capture/`**: `CaptureSize` (pure: scale the physical screen to the profile's long edge, portrait keeps orientation, smaller screens not upscaled, even dimensions for HW encoders) and `DisplaySize` (physical px via window metrics, API29 fallback). The projection itself is libwebrtc's `ScreenCapturerAndroid`, driven by `MediaCastSession`.
- **`webrtc/MediaCastSession`**: factory + the `media` PC (the `mic` PC arrives in Phase 8 per [ADR-003](../decisions/ADR-003-two-audio-track-architecture.md)). All libwebrtc work runs on one private HandlerThread (libwebrtc requires a single thread); signaling events arrive on transport threads and are posted over.
  - **The mobile is always the offerer** (webrtc.md): the offer is munged by `SdpCodecOrderer` (pure, unit-tested) to list **H.264 first, VP8 second**; the desktop answers with the first codec it supports.
  - Sender parameters from the config module only: bitrate min/max on the `RtpSender` encoding + BALANCED degradation, applied once the answer exists.
  - ICE: `iceServers: []`, host candidates only; candidates serialized in the RTCIceCandidateInit shape (`{candidate, sdpMid, sdpMLineIndex}`, `IceCandidateJson`) — the exact shape Chromium produces and consumes, so signaling stays a byte-transparent pipe. End-of-gathering → `candidate: null`. Candidates arriving before the answer's remote description are queued.
  - Re-auth (reconnect within TTL) rebuilds the pc and re-offers — the Phase4 rule "pcs are rebuilt on re-auth", now real.
  - **getStats ~1 Hz** (`SenderStats`, pure, unit-tested): sent bitrate, frames encoded/dropped, fps, RTT, encoder implementation. No quality automation until Phase 12.
- **`service/CastService`** — minimal foreground service (type `mediaProjection`), and the one deliberate deviation from the roadmap's phase order: **Android 14+ requires a FGS of this type running before the virtual display is created**, so a Phase 5 cast cannot run on the target device without it. The ordering is exactly [mobile.md](../architecture/mobile.md): consent → service in foreground → projection. What is deliberately *not* here yet: the full lifecycle matrix (rotation during gameplay, permission revoked, process death restart policy, notification design) — that is Phase 6 as planned.
- UI (scan screen, while paired): `Start casting` → POST_NOTIFICATIONS (13+, non-fatal) → system consent dialog → service start; `Casting to <name>` + `Stop casting` while live; simple failure messages per AGENTS.md (codes in logs).
- `SignalingClient` gained a listener list (pairing state machine + cast session consume events); `PairingClient` exposes the live client for the service.

### Desktop (`apps/desktop`)

- **Renderer `ReceiverSession`** (`renderer/src/webrtc/`): answers the mobile's offer (never offers, never renegotiates on its own), ICE glue both ways (queueing candidates that beat the answer), renders the remote track to the `<video>` VideoView (dark letterboxed stage; full OBS-grade window treatment is Phase 14), tears down cleanly when the mobile is gone or a new offer arrives (a returning mobile always re-offers). The `mic` pc is honestly ignored until Phase 8.
- **getStats ~1 Hz** (`stats.ts`, pure, unit-tested): receive bitrate (byte-delta), RTT, packet loss, jitter, frames decoded/dropped, decoder implementation. Note: Chromium's `RTCStatsReport` is **maplike — iteration yields `[id, stats]` tuples** (this bit us during live verification; fixed and covered by a tuple-shape test).
- IPC additions (`shared/ipc.ts`): pushes `signaling:sdp-offer` / `signaling:ice-candidate`, invokes `send-sdp-answer` / `send-ice-candidate` — validated at the process boundary (`pc` ∈ media|mic, non-empty sdp). The main process remains dumb plumbing; WebRTC lives only in the renderer (desktop.md process split).

## Verification (on device, 2026-09-20)

Lenovo TB321FU (Android 16) → MacBook Pro (macOS, Electron 44) over the LAN Wi-Fi (same subnet). The phone paired by **scanning the real QR with its camera** — the actual product path — twice (the second time against a fresh session after a desktop restart; the old session was correctly refused as expired/unknown-session → rescan).

- **Connection succeeds.** Full exchange live in logs: hello → auth → `session-info` → sdp-offer → sdp-answer → ICE → `connection connected` → track rendered. Desktop status line: `Connected to TB321FU — 800×1280 · 30 fps · balanced` (portrait screen scaled exactly per `CaptureSize`).
- **Quality acceptable.** Steady state under a real game (PUBG) cast:
  - receive bitrate **3.8–4.6 Mbps** (inside the 4–6 Mbps target; ramping peaked at the 6 Mbps ceiling), **RTT 5–10 ms**, jitter 4–12 ms, packets lost ~310 cumulative over ~21,200 decoded frames, **decoder-level dropped frames 61–76 over 21,213 (≈0.3%)**.
  - Sender side: hardware encoding confirmed by codec logs (`c2.qti.avc.encoder`, Qualcomm AVC) — H.264 preferred over VP8 as specified.
  - Static screen content correctly suppresses frames (screencast mode): the receive frame counter freezes on a static home screen and bursts on change.
- **Survives backgrounding.** The cast ran through YouTube and PUBG in the foreground (app + activity in background) with the FGS alive and ~30–60 fps continuing to decode on the desktop. Deliberate HOME test as well.
- **Desktop restart mid-cast** (twice): phone drops → backoff ladder → terminal `unknown-session` → pairing Failed with the friendly message → camera rescans the fresh QR → reconnect → full re-negotiation. The documented reconnect/expiry paths behave as specced.
- **Tests:** desktop vitest **48/48** (17 new: ReceiverSession behaviors — answer flow, ICE queueing/relay/end-of-gathering, mic-offer refusal, re-offer closes stale pc, teardown on failure/mobile-gone, tuple-shaped stats; plus stats sampling/bitrate math). Mobile JVM **43/43** (14 new: SDP codec ordering ×6, capture sizing ×5, sender-stats extraction ×3). `assembleDebug` green; desktop typecheck + production build green.
- **Risk R4 (mDNS on LAN) — first verdict:** the desktop's ICE candidates (Chromium publishes host candidates as obfuscated `*.local` mDNS names by default) were **resolved successfully by the phone on this network** — the connection succeeded with the default configuration, so mitigation 1 (resolve normally) sufficed here. Not yet validated across hostile APs — that stays in Phase 15's network matrix.

### Not (fully) verified this phase — honest gaps

- **Glass-to-glass latency: not formally measured.** Two attempts (screenshot-hash around a tap; frame-counter burst on a static screen) were confounded by live video content and a failed tap command respectively. The measured signals that bound it: network RTT 5–10 ms, jitter ≤12 ms, zero decoder backlog (real-time). A proper measurement pass (synced clock overlay or high-rate video comparison) belongs with Phase 6/15 verification. Informally the mirror tracked interactions in real time.
- **Rotation: partially validated — a known gap.** A settings-forced display rotation (no physical movement) does **not** reconfigure the capture: the stream kept 800×1280 while the display turned to 2560×1600. `ScreenCapturerAndroid` reconfigures on the device's **orientation sensor**, which forced rotation does not move; physical rotation was not exercised in this session. Rotation handling is explicitly Phase 6 scope (lifecycle matrix), so this does not block Phase 5, but the doc records the behavior and the gap. **Resolved in the Phase14 device session (2026-09-21): the stock `ScreenCapturerAndroid` does NOT follow rotation at all — not even physical — and a portrait-start cast kept its portrait frames forever (Android squeezing the rotated screen into them). The fix: `MediaCastSession` now listens to the default display's change events and re-applies the capture format live from the display's current bounds (`CaptureSize.followDisplay`, JVM-tested); a 180° turn or an unrelated display event is a no-op.
- **Stop-cast while streaming** was not exercised end-to-end on device in this session (the device was in active use); the path is covered by unit tests plus the socket-death teardowns above, and needs one manual confirmation in Phase 6.
- `decoderImplementation` is null in Chromium's inbound-rtp stats for hardware decode (the field isn't exposed there) — the encoder-side identification (mobile stats) is where HW/SW is visible.
- The device (ZUI/Android 16) ships with **app-level main logging disabled** (`persist.log.tag.aplog.mainlog=false`), so the phone's 1 Hz stats lines did not appear in logcat during the live session. The logging code ran (evidenced by pipeline behavior); native codec logs still exposed the encoder. To read app logs on this device, aplog must be enabled in its engineering settings — worth knowing for Phase15.

## Known limitations (by design in this phase)

- Video only — no audio of any kind; a `mic` offer is logged and unanswered (Phases 7–8).
- Cast settings are the fixed conservative profile; no UI, no live changes (Phase 10–11).
- ~~The pairing/cast session still lives with the scan screen~~ **Resolved in Phase 6** — `CastService` owns the whole session ([cast-session.md](cast-session.md)).
- ~~`CastService`'s notification uses a placeholder icon; no restart after process death~~ **Resolved/deliberate in Phase 6** — real icon + Stop action; still `START_NOT_STICKY` by design (consent is single-use) ([cast-session.md](cast-session.md)).
- One cast at a time (v1 non-goal: multi-desktop).
