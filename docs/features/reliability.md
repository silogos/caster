# Feature: Reliability Testing (Phase15)

Implemented in: **Phase 15** (see [roadmap](../development/roadmap.md), issue #16). Status: **in progress — matrices and measurement protocol defined; results recorded below as cases run.** This doc consolidates every pending case scattered across the feature docs into one execution matrix, plus the thermal measurement protocol that [thermal.md](../architecture/thermal.md:57) assigns to this phase. Per-case results live here (pass/fail + date + evidence); the feature docs' "pending Phase 15" lists link to these results once run.

**Rules of execution** (from the roadmap's acceptance criteria): results recorded per case — no silent failures, no skipped cases without a recorded reason. A case that cannot be reproduced in this environment (e.g., an AP that blocks mDNS) is recorded as *not reproducible here* with what was attempted — never silently dropped. Bugs found mid-matrix are fixed in-phase (commit per fix) and the case re-run.

## Evidence channels

- **Phone logcat** (via `adb logcat -v time -s MediaCastSession:* MicCastSession:* CastService:* ThermalSource:* PairingClient:* SignalingClient:* ConnectedDesktop:* PlaybackCaptureAudio:* MicRecordSubstituter:*`): the ~1 Hz sender stats line (`stats: <kbps> kbps, … encoded, … dropped, … fps, WxH, rtt … ms, encoder …`), thermal ladder transitions (`thermal: NONE → MODERATE, headroom 2.1, battery39.5°C`) +10 s fact samples (`sample: status …, headroom …, battery …`), adaptive transitions (`adaptive quality: DOWN (THERMAL) — balanced → cool`), pairing/signaling state.
  **Device caveat** ([screen-capture.md](screen-capture.md)): the Lenovo TB321FU (ZUI) ships with app-level main logging disabled (`persist.log.tag.aplog.mainlog=false`). First session step: enable aplog in the device's engineering settings and verify the stats line is visible. Until then, desktop-side logs + dumpsys + CDP are the fallback channels.
- **`dumpsys media_projection`** — the leak check after every lifecycle case (empty = no leaked projection). `dumpsys thermalservice` — the platform's own thermal ladder, cross-check for ours. `dumpsys battery` — battery level/temperature for the thermal protocol.
- **Desktop receiver stats over CDP** — `apps/desktop/scripts/capture-session-stats.mjs` (new this phase): samples `window.__castReceiver.statsSnapshot()` ~1 Hz and writes JSONL (receive bitrate, RTT, loss, jitter, frames decoded/dropped, decoder — the receiver's own view). Long-session evidence channel for the OBS, thermal, and drift runs. `measure-mic-level.mjs` remains the ear-free audio audibility check.
- **Desktop main-process log** (`[webrtc]`/`[pairing]` lines) — desktop-side pairing/signaling events (`bye reason: …`, fresh QR after session end).
- **The user's ears and eyes** — audio quality, echo, UI states, and anything a photo of the two screens proves (e.g., latency).

## L — Android lifecycle (the Phase6 acceptance matrix, [cast-session.md](cast-session.md))

All cases that end a cast must funnel through the one idempotent `endCast()`; after every case, **L11's leak check** is part of the case.

| ID | Case | Expected | Result |
|---|---|---|---|
| L1 | Cast continues while a game/other app is foreground | FGS keeps streaming; no state loss when returning to the app | — |
| L2 | Screen locked mid-cast | FGS continues; capture shows lock-screen content per OS policy (document what actually appears) | — |
| L3 | Physical rotation mid-cast | Stream flips via `ScreenCapturer` in-place resize (Phase14 fix); desktop letterbox follows; no crash (the Android-14 `createVirtualDisplay` trap stays closed) | — |
| L4 | Stop via the app (home Stop button) | Clean stop; `bye` sent; desktop shows fresh QR; notification gone | — |
| L5 | Stop via the notification action | Same as L4 (primary stop path during fullscreen games) | — |
| L6 | Projection revoked (status-bar chip) | First-class `onStop` → `Failed(ProjectionRevoked)`, "The screen cast was stopped."; clean teardown | — |
| L7 | Network loss mid-cast (brief) | Socket ladder reconnects while P2P keeps flowing; cast resumes without rescan (cross-ref D6, N2) | — |
| L8 | Desktop closed mid-cast | Terminal `unknown-session` after the ladder → clean `Failed(ConnectionLost)` stop; desktop log shows the drop | — |
| L9 | Task removed from recents | FGS survives; cast continues until stopped or the process dies | — |
| L10 | Process death (`adb shell am force-stop`) | Cast ends, notification gone, no leaked projection (no restart — consent is single-use, by design) | — |
| L11 | Leak check after every case | `dumpsys media_projection` empty; `media session torn down` in logcat | — |

## D — Desktop & pairing

Failure-mode messages come from [pairing.md](../architecture/pairing.md) §Failure modes; session rules from §Session lifecycle.

| ID | Case | Expected | Result |
|---|---|---|---|
| D1 | Desktop app quit mid-cast | Mobile ends cleanly (cross-ref L8); relaunch shows a fresh QR (old session dead) | — |
| D2 | Scan a stale QR (desktop restarted) | `unknown-session` → "Scan the QR code shown on the desktop." message path | — |
| D3 | QR regenerated (no cast) | Previous pending session invalidated; the new QR scans and pairs; the old one gets `unknown-session` | — |
| D4 | Second pairing attempt while authorized | Second device/scan gets `busy` → "The desktop is already connected to another device." | — |
| D5 | Expired QR | `expired` → "This QR code has expired. Generate a new one on the desktop." | — |
| D6 | Reconnect-within-TTL (Wi-Fi blip) | Same session id re-authenticates; media resumes without a re-scan (Phase4 live evidence exists; re-verify end-to-end with media) | — |
| D7 | Wrong-app / malformed QR | Parse `t`/`v` mismatch → "This isn't a Zero-Friction Cast QR code." | — |
| D8 | Unreachable host (QR from another desktop/network) | `connect-unreachable` → "Couldn't reach the desktop…" message; no hang | — |

## A — Audio

Pending items from [microphone.md](microphone.md), [game-audio.md](game-audio.md), [audio-mixer.md](audio-mixer.md); the mic coexistence contract is [ADR-004](../decisions/ADR-004-mic-capture-coexistence.md).

| ID | Case | Expected | Result |
|---|---|---|---|
| A1 | Opted-out app (capture policy) on a real game/app | Honest UI state "This app's audio can't be captured"; never fabricated audio; catalogue ≥2 opted-out + ≥2 opted-in apps recorded | — |
| A2 | DRM app (e.g. Netflix) | Same honest silence path as A1 — no error, no fake audio | — |
| A3 | Stereo/soundstage through the APM | Distinct L/R content stays distinct at the receiver (the APM's hardware/platform effects stood down — game-audio.md's unmeasured quality question) | — |
| A4 | RECORD_AUDIO denied at runtime (settings revoke mid-flow) | Game audio off **and** mic `NeedsPermission`; cast otherwise unaffected | — |
| A5 | Mute paths | Sender-side game-audio mute and desktop mixer mute each silence only their own stream; unmute restores | — |
| A6 | Reverse-direction platform silence (ADR-004): cast mic ON first, then a privacy-sensitive capture starts (e.g. game voice chat with the pre-fix source) | `MicState.Silenced` surfaces in plain words and recovers when the winner stops | — |
| A7 | Mic while app backgrounded (fixed `microphone` FGS type) | Mic stays clean with the app in the background (the silencing was found live; the fixed behavior needs this formal listen) | — |
| A8 | Mic pc through a Wi-Fi flap (mic ON) | Mic pc follows the reconnect ladder cleanly; no stuck states (Phase8 observed the ladder re-authing ×3 — formalize with mic on) | — |
| A9 | Echo sanity: speaker vs headset | Speaker + mic on → desktop hears the echo (documented R10; recommend headphones); headset → clean | — |
| A10 | Mic quality through the APM (noise suppression strength) | Subjective listen recorded; no chipmunk/delay (the 2026-09-23 verification holds on this build) | — |
| A11 | Mixer: independence + clipping + drift | Independent volume/mute per stream; unity gain defaults don't clip; no drift over a long session (rides T5) | — |

## N — Network

The reconnect ladder is 1→2→5→10→30 s (Phase4, unit-pinned); ICE rules per [webrtc.md](../architecture/webrtc.md) §Disconnect.

| ID | Case | Expected | Result |
|---|---|---|---|
| N1 | Weak Wi-Fi (device far from the AP / degraded signal) | Stream survives with visible degradation; adaptive quality steps down (announced, no oscillation) and recovers when signal returns | — |
| N2 | Sustained network loss (> ladder + TTL) | ICE `FAILED` → clean `Failed(ConnectionLost)` stop with the friendly message; rescan works immediately | — |
| N3 | Phone Wi-Fi toggled off→on mid-cast | Within the reconnect window: cast resumes without rescan (D6); past it: clean stop (N2) | — |
| N4 | Phone switches networks (different Wi-Fi) | Old session unreachable → clean failure path; fresh scan pairs on the new network | — |
| N5 | Desktop IP change mid-cast (DHCP renew / interface change) | Document actual behavior: established P2P may survive or drop; either way no hang, and the next QR advertises the new `hosts[]` | — |
| N6 | mDNS hostile-AP validation (R4) | An AP/blocking config that fails `*.local` resolution: verify the mitigation ladder's verdict. If no hostile AP is available at home, record *not reproducible here* with what was attempted | — |
| N7 | Adaptive-quality under induced loss/RTT | Step-downs announce (log + notification + session-info); dwell respected; recovery only after clean stretch; constants retuned if the data disagrees (→ [adaptive-quality.md](adaptive-quality.md)) | — |

## T — Thermal & long sessions

### Thermal measurement protocol (defined by this phase, per [thermal.md](../architecture/thermal.md:57))

One fixed-duration cast per profile on the real device, same content across runs, conditions controlled as far as a home allows:

1. **Per profile** (`cool`, `balanced`, `performance`, `sharp` — the settings presets): one **20-minute** cast, adaptive quality **off** (the profile must run exactly at its settings), mic off, screen brightness fixed, volume low.
2. **Content:** the same gameplay video or game replay across all runs — comparable load, comparable motion.
3. **Power:** the device runs on **battery** (charging adds heat), with adb over Wi-Fi (`adb tcpip`) so the USB cable isn't a heat/lifeline variable. Log capture is wireless; nobody touches the device during the run.
4. **Recorded per run:** battery level % and battery temperature at start/end (`dumpsys battery`), ambient conditions (room temp estimate, case on/off), the full thermal trail from logcat (ladder transitions + 10 s samples), the ~1 Hz sender stats (FPS stability, dropped frames, actual vs. target bitrate, encoder implementation), and the receiver-side JSONL from `capture-session-stats.mjs` (decode drops, jitter, RTT).
5. **Cooldown between runs:** until battery temperature returns to the previous run's baseline (±1 °C) with ≥10 min idle.
6. **Output:** the measured table below replaces the hypothesis table in [thermal.md](../architecture/thermal.md) §Initial profiles; method + samples recorded (one phone does not generalize — thermal.md §Constraints).

### Cases

| ID | Case | Expected | Result |
|---|---|---|---|
| T1 | 20-min cast, `cool` | The run's measured numbers (ladder, temp delta, battery drain, FPS/drops) recorded below | — |
| T2 | 20-min cast, `balanced` | Same | — |
| T3 | 20-min cast, `performance` | Same; expected the measurably hottest run (thermal.md's own prediction) | — |
| T4 | 20-min cast, `sharp` | Same; outside the adaptive ladder by design | — |
| T5 | 30+ min OBS Window Capture session | Clean, correctly-proportioned, smooth feed; window stays where placed; rotation mid-cast re-fits the bars without moving the window (Phase14's remaining acceptance, [obs-streaming.md](obs-streaming.md)) | — |
| T6 | Profile switch visible in the stats line | The ~1 Hz line shows the new WxH/bitrate window immediately after a settings change takes effect on the next cast ([thermal.md](thermal.md) feature doc's remaining item) | — |
| T7 | Thermal-driven step-down (if any run reaches `MODERATE`+ for120 s) | `adaptive quality: DOWN (THERMAL)` announced; one rung only; "Restore quality" returns it (if no run reaches it in 20 min, record that fact — itself a thermal result) | — |
| T8 | Glass-to-glass latency measurement | The on-screen clock method (R6): a ms clock displayed on the phone, both screens photographed — measured number replaces the Phase5 "eyeballed" gap | — |

## Results record

Nothing below is filled in until the case actually runs on hardware — no result is assumed (risk-register rule: evidence only). Each entry: date, case IDs, verdicts, evidence excerpts, and any fixes.

### Session A — 2026-09-24 (Lenovo TB321FU / Android 16 → macOS, same Wi-Fi)

**Instrumentation first — a live-found and live-fixed bug:** the ~1 Hz sender-stats line never appeared (Phase5 blamed the device's disabled aplog; aplog is enabled now and other app logs appear, so the excuse died). Probe: the getStats callback fired with a 14–15-entry report whose every `members["type"]` was null — `RTCStats` exposes `type` as a **getter on the object, not a key in `members`** (verified against the pinned1.3.8 classes.jar). The sampler read a key that never exists → always null → **no stats line ever, and Phase12's adaptive quality was starved of every sample on real hardware**. Fixed in `MediaCastSession`/`MicCastSession` (merge `stats.type` into the entry map before sampling); mobile JVM 147/147. Device-verified on the fixed build: the line now logs complete (`stats: 10016 kbps, 866 encoded, 0 dropped, 55.0 fps, 1280x800, rtt9 ms, encoder c2.qti.avc.encoder` — HW encoder, RTT 4–12 ms, 0 drops during game content). The fixed lines are now the measurement channel the thermal protocol and T-cases need.

| Case | Verdict | Evidence |
|---|---|---|
| L1 | **PASS** | Cast continued through a foreground game and back; user-confirmed + stats show 30–55 fps @ 1280x800, ~6–10 Mbps while the game ran |
| L2 | **PASS (behavior documented — OS ends the cast)** | Locking the screen **stops the media projection** on this device/OS (`media projection stopped — ending the cast`, `cast ended: projection revoked`) → clean `Failed(ProjectionRevoked)`, `media session torn down`, desktop back to a fresh QR, re-cast works. There is no "no input video" phase to show: the OS revokes the projection itself, and ending the cast is the correct honest response. The matrix expectation ("capture shows lock-screen content per OS policy") is corrected to this. |
| L3 | **PASS** | Physical rotation landscape→portrait→landscape: `capture → 800x1280 (display1600x2560)` and back, stats show the frame flips (600x960, 800x1280), no crash (the Android-14 second-createVirtualDisplay trap stays closed), stream continued; user-confirmed "aman" |
| L8 | **PASS** | Desktop process killed mid-cast: socket abort → ladder 1→2→5→10→30→30… s (each attempt a 10 s connect timeout) → ~3.5 min later `session expired — no auto-retry, user must re-scan` → clean Failed state, connected state cleared; leak check clean (`dumpsys media_projection` null, no service). The terminal path was session-expiry (not `unknown-session` — the desktop never came back to answer) — same clean family |
| L10 | **PASS** | `am force-stop` mid-cast: cast ended, no active notification, no service, `dumpsys media_projection` null; desktop detected the drop (`authorized mobile disconnected — reconnect window open until expiry`) and returned to the QR hero |
| L11 | partial — clean after L8, L10 and the session's app-initiated stops | `media session torn down` logged; `dumpsys media_projection` empty after each checked case |

**Open findings from session A (under investigation):**
1. **One `malformed candidate from desktop — ignoring` per cast** — a desktop ICE candidate fails the mobile's decode every session; connectivity survives via ICE peer-reflexive discovery (the mobile never learns the desktop's host candidates properly). R4's "byte-for-byte verbatim" is being violated somewhere between the desktop renderer's `RTCIceCandidate` and the wire. A desktop-side probe (log the outbound candidate at the IPC→socket relay) is running on the next cast.
2. **A reconnect attempt that didn't time out at its 10 s deadline** (07:38:44 → 07:40:31, ~107 s hung in "connecting" before failing with a different message shape: `Failed to connect` vs the usual `failed to connect … after 10000ms`) — the connect-timeout appears to not cover some failure path in `SignalingTransport`. Rare but real; needs a look when the network matrix (N) starts.
3. Session-start capture ramp: `640x400 →960x600 → 1280x800` in the first ~12 s of each cast (the capturer's start format then `followDisplay` resize) — not a defect; noted because the thermal protocol's FPS-stability window should exclude it.

## Findings & fixes (as they surface)

- **2026-09-24 — sender stats never sampled (L-baseline instrumentation):** `RTCStats.type` is an object getter, not a `members` key; the sampler read `members["type"]` and always got null → no 1 Hz stats line and **no adaptive-quality input ever reached `AdaptiveQualityController` on real hardware** (the JVM tests faked the member shape — a fake test mirroring the implementation, caught only by the device). Fixed by merging `stats.type` into the entry map at both call sites (`MediaCastSession`, `MicCastSession`); 147/147 JVM; device-verified (see Results, session A).
