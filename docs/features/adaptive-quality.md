# Feature: Auto Quality / Adaptive Streaming

Implemented in: **Phase12** (see [roadmap](../development/roadmap.md)). Status: **implemented; mobile JVM 121/121 green, `assembleDebug` green, desktop 74/74 green (untouched)** — the on-device acceptance items (a real step-down observed end-to-end during a warm or throttled cast) need the phone in hand; see *Verification*.

## What is implemented

### The policy machine (`adaptive/` module)

New module `adaptive/` ([mobile.md](../architecture/mobile.md) module map): pure Kotlin, no Android and no WebRTC types — the policy is JVM-testable and the service only *feeds* it and *applies* it ([AdaptiveQualityController.kt](../../apps/mobile/app/src/main/java/com/zerofriction/localcast/adaptive/AdaptiveQualityController.kt), following `thermal.md` §How thermal data is used, which specifies the policy).

- **Inputs** (per ~1 Hz sender-stats tick, plus the latest thermal reading): dropped-frame fraction (per-tick deltas of the cumulative counters — the encoder-stress signal), packet loss (the desktop receiver's own `remote-inbound-rtp` `fractionLost`, newly extracted in `SenderStats`), RTT of the nominated pair, and the `PowerManager` thermal ladder from Phase11's monitor.
- **Steps down** one rung of the preset ladder (cool → balanced → performance → sharp, in that rung order; the ladder *order* — not a per-dimension comparison — is what makes descents and ascents symmetric, since e.g. performance 720p60 and sharp 1080p30 are not comparable) only after a *sustained* bad stretch: thermal `MODERATE`+ held ≥ 120 s (`THERMAL_HOLD_MS` — "minutes, not seconds"), or stream trouble held ≥ 30 s (`NET_HOLD_MS`). Any step also waits ≥ 90 s since the previous one (`DWELL_MS`): bad conditions cause a slow staircase, never a collapse. The floor is `cool`.
- **Steps up** only after ≥ 180 s of sustained clean stats (`HEALTHY_HOLD_MS` — deliberately much longer than the way down; that asymmetry is the oscillation killer), one comparable rung at a time, never above the user's settings at cast start (the **ceiling** is their explicit choice — thermal.md principle 2). A thermal step-down does **not** recover automatically: the device got hot under these exact settings, so only the user puts it back — the "Restore quality" button. Stream-health step-downs recover automatically, one rung per hold, and a descent can span several rungs (each recovery re-checks the sticky descent reason; it clears only back at the ceiling).
- **Counter resets are handled**: a pc rebuild zeroes the cumulative stats — negative deltas clamp to zero, so a rebuild can't fake a drop burst (pinned by a test).
- **Deliberately not an input:** measured fps below the target. Screen-content frame rate follows the game's own pacing (thermal.md's heat table) — a 30 fps game under a 60 fps profile is a quiet encoder, not a struggling one; acting on it would punish the wrong thing. Dropped frames is the stress signal instead. This is the one place the roadmap's input list (dropped frames, RTT, packet loss, encoder stress, thermal status) is interpreted rather than copied: encoder stress *is* the dropped-frame signal.

Named constants carry the tuning (`THERMAL_HOLD_MS`, `NET_HOLD_MS`, `HEALTHY_HOLD_MS`, `DWELL_MS`, `DROPPED_FRACTION_BAD = 0.05`, `PACKET_LOSS_FRACTION_BAD = 0.05`, `RTT_BAD_MS = 100` — Phase5 measured 5–10 ms RTT on a healthy LAN; ≈0.3% drops on a healthy cast). All are starting hypotheses, re-tuned from Phase 15 measurements.

### How a step is applied and announced

A step must never renegotiate: `MediaCastSession.changeQuality(longEdge, fps, min, max)` reconfigures the capture pipeline (`changeCaptureFormat`) and moves the video sender's bitrate window (`setParameters`) live on the session thread. The session's *live* window (not the frozen cast config) is what `applySenderParameters` applies, so re-auths keep the stepped values.

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
- No fps-target enforcement and no content-adaptive bitrate tricks beyond libwebrtc's own — the sender windows are set, the encoder adapts inside them (BALANCED degradation, unchanged since Phase5).
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
- `changeCaptureFormat` reconfigures `ScreenCapturerAndroid` mid-stream; if a device's encoder misbehaves on a live format change, the honest fallback is the next cast (the step is logged with the capture line either way) — that path is part of the on-device check above.
- Packet loss rides `remote-inbound-rtp`, which arrives only once the desktop's receiver is reporting — early-stream ticks may have `null` loss; RTT and drops cover the rest.
- The adaptation is per-cast state: the ceiling is the user's settings *at cast start*; changing settings mid-cast applies to the next cast (unchanged Phase10 semantics).
