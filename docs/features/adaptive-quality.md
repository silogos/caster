# Feature: Auto Quality / Adaptive Streaming

Implemented in: **Phase12** (see [roadmap](../development/roadmap.md)). Status: **implemented; mobile JVM 121/121 green, `assembleDebug` green, desktop 74/74 green (untouched)** — the on-device acceptance items (a real step-down observed end-to-end during a warm or throttled cast) need the phone in hand; see *Verification*.

## What is implemented

### The policy machine (`adaptive/` module)

New module `adaptive/` ([mobile.md](../architecture/mobile.md) module map): pure Kotlin, no Android and no WebRTC types — the policy is JVM-testable and the service only *feeds* it and *applies* it ([AdaptiveQualityController.kt](../../apps/mobile/app/src/main/java/com/zerofriction/localcast/adaptive/AdaptiveQualityController.kt), following `thermal.md` §How thermal data is used, which specifies the policy).

- **Inputs** (per ~1 Hz sender-stats tick, plus the latest thermal reading): dropped-frame fraction (per-tick deltas of the cumulative counters — the late encoder-stress signal), **encoder utilization** (since Phase18 — the leading encoder-stress signal, see below), packet loss (the desktop receiver's own `remote-inbound-rtp` `fractionLost`, newly extracted in `SenderStats`), RTT of the nominated pair, and the `PowerManager` thermal ladder from Phase11's monitor.
- **Steps down** one rung of the preset ladder (cool → balanced → performance → sharp, in that rung order; the ladder *order* — not a per-dimension comparison — is what makes descents and ascents symmetric, since e.g. performance 720p60 and sharp 1080p30 are not comparable) only after a *sustained* bad stretch: thermal `MODERATE`+ held ≥ 120 s (`THERMAL_HOLD_MS` — "minutes, not seconds"), or stream trouble held ≥ 30 s (`NET_HOLD_MS`). Any step also waits ≥ 90 s since the previous one (`DWELL_MS`): bad conditions cause a slow staircase, never a collapse. The floor is `cool`.
- **Steps up** only after ≥ 180 s of sustained clean stats (`HEALTHY_HOLD_MS` — deliberately much longer than the way down; that asymmetry is the oscillation killer), one comparable rung at a time, never above the user's settings at cast start (the **ceiling** is their explicit choice — thermal.md principle 2). A thermal step-down does **not** recover automatically: the device got hot under these exact settings, so only the user puts it back — the "Restore quality" button. Stream-health step-downs recover automatically, one rung per hold, and a descent can span several rungs (each recovery re-checks the sticky descent reason; it clears only back at the ceiling).
- **Counter resets are handled**: a pc rebuild zeroes the cumulative stats — negative deltas clamp to zero, so a rebuild can't fake a drop burst (pinned by a test). Phase18 extends the same guard to the encode-time counter (a reset reads as *unknown* utilization, never a burst).
- **Deliberately not an input:** measured fps below the target. Screen-content frame rate follows the game's own pacing (thermal.md's heat table) — a 30 fps game under a 60 fps profile is a quiet encoder, not a struggling one; acting on it would punish the wrong thing. This is the one place the roadmap's input list (dropped frames, RTT, packet loss, encoder stress, thermal status) is interpreted rather than copied.

### The encoder-utilization signal (Phase18)

Phase16's instrumentation made the per-frame encode cost visible (`encode ms/f` in the 1 Hz stats line), and the Phase16/17 sessions exposed the gap it fills: the rig **never dropped a frame**, so the dropped-fraction trigger cannot fire even when the encoder is clearly overloaded — the Phase16 fps-placebo era ran a 30 fps target at 61 frames/s and ~30 ms/f (~1.83 s of encode per wall second) with zero drops. Phase18 adds the *leading* version of that signal: per-tick **encoder utilization** = the `totalEncodeTime` delta over the wall-time delta, firing (with the same 30 s hold + dwell as the other stream-health inputs) at `ENCODER_UTILIZATION_BAD = 0.85` of wall time.

Deliberately **utilization, not a per-frame-cost-vs-fps-budget threshold**: a 30 fps game under a 60 fps profile encodes 20 ms frames with 33 ms of wall time each (≈0.6 utilization) — content pacing, not overload, and a budget comparison would punish exactly the common case. Recorded-data validation baked into the tests: the healthy rig's ceiling (encode p90 22.7 ms/f × 30 fps ≈ 0.68) never fires; the placebo era (1.83) fires; content-paced expensive frames (0.3) never fire. The 0.85 constant is a re-tunable starting hypothesis like the rest.

**On-device record (2026-09-27, ~25 min of a demanding 3D game, custom 1920/60 ceiling):** both Phase18 acceptance items measured live. The signal fired on real overload — witnessed at utilization 1.59–1.90 (61 fps content × 26–32 ms/f, `custom → sharp`) and 1.18 (60 fps × 19.7 ms/f, `performance → balanced`), with the other two rung changes of the session attributable by elimination (sender drops 0, RTT 4–11 ms, loss ≈0.35 % — the other stream-health inputs never crossed thresholds) — and stayed silent through minutes of 30 fps content under the 60 fps rung at 0.44–0.48 (live-validating the utilization choice over the budget-based alternative, which would have misfired exactly there). The descents also measurably restored smoothness: the receiver's jitter buffer crept 110 → 189 ms during a 1920/60 overload window and fell back to a stable ~118 ms after settling at balanced 1280/30 — the "laggy" report was the overload window, not the bitrate mode (the CBR window moved 12 → 6 → 4 Mbps across the descents with no smoothness correlation).

Named constants carry the tuning (`THERMAL_HOLD_MS`, `NET_HOLD_MS`, `HEALTHY_HOLD_MS`, `DWELL_MS`, `DROPPED_FRACTION_BAD = 0.05`, `PACKET_LOSS_FRACTION_BAD = 0.05`, `RTT_BAD_MS = 100` — Phase5 measured 5–10 ms RTT on a healthy LAN; ≈0.3% drops on a healthy cast; `ENCODER_UTILIZATION_BAD = 0.85` — Phase18). All are starting hypotheses, re-tuned from measurements.

### How a step is applied and announced

A step must never renegotiate: `MediaCastSession.changeQuality(longEdge, fps, min, max)` applies the new resolution and moves the video sender's bitrate window (`setParameters`) live on the session thread. **Phase16:** the step's fps reaches the actual encode rate through the encoder cap (`encodings.maxFramerate`, [performance.md](performance.md)) — measured live, a 30 fps step previously left the encoder running at 61 fps. **Phase17:** the step's resolution no longer reconfigures the capture pipeline at all — the VirtualDisplay stays at the cast-start *ceiling* size and the step downscales inside libwebrtc (`VideoSource.adaptOutputFormat`, a GL scale before the encoder, plus the same fps target at the source adapter). The old resize path ran serialized on the capture thread and reallocated the surface buffer every step, which the Phase16 rig measured as freeze bursts and a jitter-buffer spike (913 ms) around resolution changes. Rotation is the one thing that still resizes the display (an aspect flip cannot be downscaled) — the pure decision between "adapt the source" and "resize the display" lives in the JVM-tested `capture/StepFormat` rule. The session's *live* window (not the frozen cast config) is what `applySenderParameters` applies, so re-auths keep the stepped values.

**Phase17 on-device record (2026-09-27, rig TB321FU/Android 16 → macOS, ~21 min cast, SEVERE pre-warm so the full staircase fired):** four adaptive transitions measured through the new mechanism (three thermal step-downs 1920→1280→960 plus a user restore back up), sender logcat + receiver JSONL captured throughout (same rig method as Phase16).

| At an adaptive resolution step | Old mechanism (Phase16 resize path) | New mechanism (this session) |
|---|---|---|
| Jitter buffer around the step | **913 ms spike** during a resolution step | **none** — p50 flat or falling across all three measured step windows (252→246→214, 168→164→158, 140→140→140 ms) |
| Keyframe cost | part of a changeCaptureFormat reconfig | exactly **+1 keyframe** per clean step (the unavoidable encoder input-size reconfig); no PLI, no NACK, no extra dropped frames |
| Capture pipeline per step | `VirtualDisplay.resize` + `setTextureSize`, serialized on the capture thread | **untouched** — logged live as `adaptive step → source 960x600 (capture untouched)`; only rotation produced `capture → …` lines |
| fps-only step (performance→balanced) | touched the capture format anyway (the old `force` re-apply) | **zero capture interaction** (`StepFormat` resolves to None; only the sender window moved) |
| Sender drops | 0 | 0 across the whole session (~38 k encoded frames, 4 frame sizes incl. a mid-cast rotation) |

Honest notes: one rig, one session; the biggest descent (1920→1280, right after a start-up orientation flap) carried a bump of +3 keyframes/+4 PLI/+13 NACK not attributable to the step alone — the two later, orientation-stable steps were surgically clean.

**MAINTAIN_RESOLUTION check — closed (same day, second session, Sharp ceiling):** a ~15 min cast started at Sharp (1920×1200/30, degradation MAINTAIN_RESOLUTION) ran the full staircase through the same mechanism: sharp → performance logged live as `adaptive step → source 1280x800 (capture untouched)` with the receiver confirming the frame size actually dropped (1920×1200 → 1280×800 → 960×600), each resolution step costing exactly +1 keyframe with no PLI/drop growth, and the buffer flat-or-falling across the steps (p50 124 → 118 → 85 → 84 ms). **The `adaptOutputFormat` pixel cap is honored under MAINTAIN_RESOLUTION on the pinned libwebrtc 1.3.8 build — the resize fallback for MR configs is not needed.** All Phase 17 acceptance items are measured.

Every transition is announced four ways (acceptance: "every transition logged and user-visible"):

1. one INFO log line (`CastService`: `adaptive quality: DOWN (THERMAL) — balanced → cool`),
2. the ongoing cast notification's text updates (`IMPORTANCE_LOW` — it tells, it never buzzes),
3. a fresh display-only `session-info`, so the desktop's status line follows what is actually being sent (the desktop remains untouched — the summary is display text in an existing field, webrtc.md),
4. a plain-words line in the "This cast" section (home *and* scan screens, wherever Phase11's thermal read-out rides): *"The phone got hot, so the quality was lowered to Cool."* / *"The stream struggled, so the quality was lowered to Cool."* / *"The stream stayed healthy, so the quality went back up to Balanced."* — with a **Restore quality** button whenever the cast sits below the ceiling.

### The switch and the persistence

- **"Automatic quality"** in the settings (Share screen section; persisted with everything else) is the acceptance's "user can disable": off means the cast runs exactly at the user's settings, nothing watches the stats. It is a cast setting like any other — next-cast semantics.
- Settings document is now **v3**; v1/v2 documents decode through the existing ladder (`autoQuality` defaults to on). Found during this phase and fixed: the store's key embeds the codec version, so the Phase11 v2 bump would have silently reset every stored setting (the codec's own legacy decode was unreachable); `CastSettingsStore.load` now migrates from the newest legacy key that still decodes and writes it forward.

## What is deliberately not here

- No desktop involvement anywhere (configuration ownership, overview.md) — the desktop shows the label it is given and nothing else.
- No content-adaptive bitrate tricks beyond libwebrtc's own — the sender windows are set, the encoder adapts inside them (BALANCED degradation, unchanged since Phase5). Fps-target enforcement *was* added in Phase16, via the sender-side encoder cap (not the capturer, which cannot throttle) — [performance.md](performance.md).
- No thermal *step-up* ever (thermal.md: step back up only manually for thermal descents).
- No per-step "try and measure" loop: one step per hold window; Phase 15's measurements may retune the constants, the shape stays.

## Verification

**Tests (mobile JVM 121/121):** `AdaptiveQualityControllerTest` (20 cases) pins the acceptance matrix — healthy casts never change; sustained `MODERATE` steps down after the 120 s hold (and `LIGHT` never does); the sustained-heat staircase lands one rung per hold and stops at the floor; heat ending does **not** recover (manual only, even after 8 clean minutes); drops/loss/RTT each step down after the 30 s hold while single bad ticks don't; a clean stretch recovers a stream-health descent one rung at a time (and the ceiling-bound step when no comparable preset sits between); the ceiling is never exceeded; the dwell blocks a fresh bad stretch right after a recovery (the no-oscillation acceptance); `restore` returns to the ceiling and restarts the hysteresis; counter resets don't fake a burst. `CastSettingsCodecTest` covers v3 round-trips, v2 documents decoding with the switch's default, and an off switch persisting; `QualityProfileTest` pins `matchingProfile` (the inverse mapping used to name a level); `SettingsViewModelTest` covers the new toggle. `SenderStatsTest` covers `fractionLost` extraction. `assembleDebug` green; desktop vitest 74/74 (no desktop change).

**On-device acceptance items (need the phone in hand):**

1. Squeeze the link (walk away from the AP with the desktop on a far room's Wi-Fi) until the stats line shows sustained drops/RTT — the "quality lowered" line + notification should appear and the stats line should show the smaller capture/window; then walk back and watch the automatic recovery after ~3 clean minutes.
2. On a warm device, hold a `performance` cast until `MODERATE` — the thermal step-down after ~2 minutes, and confirmation that **only** "Restore quality" (never time) brings the ceiling back.
3. Toggle "Automatic quality" off and verify nothing changes under the same conditions.

## Known limitations

- The holds/dwell are tuned for a 1 Hz stats cadence — the policy's clock *is* the caller's cadence (documented on the controller); a much slower feed would stretch the holds proportionally.
- A step itself no longer touches the capture pipeline (Phase17) — but it still changes the encoder's input size (the GL-scaled frame), which reconfigures MediaCodec and costs a keyframe; the on-device record above measured that cost as exactly one keyframe per clean step, with the capture-side stall and buffer reallocation gone. If a device's encoder misbehaves on a live size change, the honest fallback is the next cast (the step is logged with the capture line either way).
- Packet loss rides `remote-inbound-rtp`, which arrives only once the desktop's receiver is reporting — early-stream ticks may have `null` loss; RTT and drops cover the rest.
- The adaptation is per-cast state: the ceiling is the user's settings *at cast start*; changing settings mid-cast applies to the next cast (unchanged Phase10 semantics).
