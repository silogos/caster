# Feature: Performance (Phase16 measurement campaign + profile-guided fixes)

Implemented in: **Phase16** (see [roadmap](../development/roadmap.md)). Status: **batch 1 shipped** — the profiling/analysis instrumentation, one measured optimization accepted (the profile/adaptive **fps cap**, live-verified before/after), one candidate measured and **rejected** with data (`jitterBufferTarget`), and two live-found reliability fixes that the measurement rig itself surfaced. Desktop 115/115 + typecheck green, mobile JVM153/153 + `assembleDebug` green at each commit.

Phase16's own rules shaped everything below: **profile-guided only** — no change ships without a before/after measurement, and rejected candidates are documented with their data rather than silently dropped.

## The instrumentation (what was shipped to be able to *measure*)

Nothing below existed before this phase; every optimization decision cites it.

**Receiver (desktop):**

- The ~1 Hz stats line now carries the decode/render fields Chromium reports but we ignored until now: reported fps, frames received, **per-frame jitter-buffer residence** (ms), freeze count and duration, keyframes decoded, NACK/PLI counts, frame size (`stats.ts` → `receiverSession`).
- **Audio reception is now visible on both pcs** (game audio on `media`, mic on `mic`): bytes/bitrate, packet loss, jitter, and **packet-loss concealment** (concealed-sample fraction — the audio-quality signal; A3-class questions are finally measurable). Until now, audio reception was invisible at the receiver end-to-end.
- **`measureRender`** on the existing `window.__castReceiver` CDP hook: a `requestVideoFrameCallback` probe that collects N presentation intervals (mean/median/p95/max, gaps measured against the median) — the receiver's first *direct* view of decode→render pacing, not just aggregate counters (`renderProbe.ts`, vitest-covered).

**Sender (mobile):**

- The ~1 Hz logcat stats line grows a **per-frame encode-cost tail** (`encode 24.3 ms/f`): the delta of cumulative `totalEncodeTime` over frames encoded — present in the pinned prebuilt (verified live), defensively null if absent.
- `SenderStats` also extracts `framesSent`/`retransmittedPacketsSent` (same defensive pattern).

**Analysis workbench:**

- `apps/desktop/scripts/analyze-session-stats.mjs` — turns a `capture-session-stats.mjs` JSONL into a per-run summary (bitrate/fps percentiles, decoded/dropped frames, loss, freezes, jitter-buffer ms, RTT, audio concealment) with the **session-start ramp excluded** from every steady-state metric (every cast ramps its capture format over the first ~12 s — session A finding 3; excluding it grades the run, not the start-up). Two files in → before/after table. The pure core (`scripts/lib/sessionStatsSummary.mjs`) is vitest-covered like product code (`scripts/**` joined the vitest include).
- `apps/mobile/tools/analyze-sender-stats.mjs` — the same summary treatment for a saved logcat capture of the sender's stats lines, so both ends of a run analyze identically. Whitespace-flexible parse: it tolerates whatever logcat hands over, and both the `encode … ms/f` tail and `rtt null ms`.

## The rig and the sessions

Lenovo TB321FU (Android 16) → macOS over the same Wi-Fi, both apps debug builds, sender logcat + receiver JSONL captured throughout. Three sessions on 2026-09-24:

- **Session A (baseline):** ~9 min cast, custom 1920px/60fps start. The phone began **thermally SEVERE** (pre-warmed by prior use; headroom ≈ 1.1, battery 39.5 °C) and the full adaptive staircase fired: `custom 1920/60 → performance → balanced → cool`, one rung per ~2 min — the policy's 120 s holds and 90 s dwell to the letter. **T7's acceptance behavior, observed live for the first time.**
- **Session B (fix verification):** same profile, same content, the fps-cap build. Same staircase, now with the steps actually *doing* their fps work.
- Analysis plus ad-hoc probes (render probe, receiver introspection) between sessions.

### Baseline link/stream health (session A, steady windows)

| Metric (receiver) | Value |
|---|---|
| RTT p50 | 6 ms (p10 4, p90 9) |
| Packet loss | 0.17–0.18 % |
| Sender drops | 0 frames across both sessions |
| Decoder drops | ≈ 0.5–0.6 % of frames |
| Keyframes / PLIs / NACKs (≈ 7 min) | 11 /14 / 48 |
| Game-audio concealment | 0.24 % (inaudible) |
| Render presentation (probe, live cast) | median 24.8 ms/frame, **zero gaps**, max 33.4 ms |

The LAN link itself is not a bottleneck — every optimization must come from the encode/capture/render path, not the network. (Adaptive-quality's stream-health thresholds were never close to firing; its 1 Hz dropped-fraction input is fed by *encoder* stress on this rig, not the link.)

## Accepted change: the profile/adaptive fps is now real (encoder cap)

**Finding (live, session A):** a cast stepped down to a 30 fps target kept **encoding 61 fps** at ~30 ms/frame. Root cause: `MediaProjection` capture cannot throttle frame rate — the stock screencast capturer (our pinned-class port included) legitimately ignores the fps argument — and nothing else in the pipeline applied it. **Every profile's fps field was a placebo on device**, and the thermal staircase's fps rung (the biggest per-second workload lever it has) did nothing: after both steps to balanced and cool the encoder still ran 61 fps/30 ms/frame, and battery temperature kept climbing through the steps.

**Fix:** the one mechanism that reaches the actual encode rate is `RtpParameters.Encoding.maxFramerate` on the video sender. `applySenderParameters` now carries the *live* fps (profile at cast start, moved by adaptive steps) there, composed with the user's own Advanced "Encoder fps limit" via `effectiveEncoderFpsCap` — the user's limit can only tighten, never raise (`EncoderFpsCap.kt`, JVM-tested rule).

**Before/after (same rig, same staircase, same content family):**

| At the 30 fps step (960×600) | Before | After |
|---|---|---|
| Frames encoded per second | **61** | **30** |
| Per-frame encode cost | ~30 ms/f | ~13 ms/f |
| Bitrate | ~3.2 Mbps | ~2.4 Mbps |
| Encoder seconds per wall second | ~1.83 s | ~0.40 s (**≈4.6× lighter**) |
| Sender drops | 0 | 0 |

This is the thermal strategy's mitigation *actually mitigating*: the cool profile now costs about a quarter of the encoder work it did before the fix. The remaining heat delta per profile (the T1–T4 protocol runs) should be measured on this fixed build — every previous thermal number, including the hypothesis table in [thermal.md](../architecture/thermal.md), was gathered through a cast that ran double the intended frame rate.

## Rejected candidate: `jitterBufferTarget` on the receiver (with data)

**Hypothesis:** the receiver's jitter buffer was the dominant measured latency term (p50 ≈ 91 ms of buffer depth on a link with 6 ms RTT and ≤ 12 ms jitter — Chromium's adaptive buffer never shrinks on a quiet LAN). R6's "retune 16" listed the renderer path; `RTCRtpReceiver.jitterBufferTarget` looked like the reachable knob. **It is not.**

**What was measured (applied live on the video receiver, verified by CDP introspection that the property was set to 30):**

| Buffer depth (ms) | Without target (session A steady) | With target =30 (session B steady) |
|---|---|---|
| p10 | 10 | 114 |
| p50 | 91 | 137 |
| p90 | 111 | 159 |

The target is a **floor by spec, not a ceiling**: Chromium kept holding 90–160 ms of media regardless, and our floor moved *up* (the minimum is the only thing the field controls). No latency benefit, a measurably higher minimum, and transient spikes around resolution steps (913 ms during the 10:44 step) that the baseline run did not show at the same moments. **Reverted** (the code and its tests are gone, not commented out) — per the phase rule that a change must earn its place with data.

**What this leaves open (honest):** the receiver-side latency term is measured at ~90–140 ms and **not reachable through the public WebRTC API** — R6's renderer-path tuning hits a documented wall. Glass-to-glass itself is still to be photo-measured (T8's on-screen clock method); with a ~100 ms buffer inside it, the 40–150 ms LAN budget is at risk, and closing the gap would need Chromium-side work (flags we cannot set per-app) or a native receiver — out of scope until a user-visible latency problem is reported.

## Other live findings from the measurement rig

- **Receiver freeze bursts are content-shaped, not defect-shaped.** Freeze counts grew only around resolution steps and while the cast showed static content (the settings screen — the encoder sends on change, so inter-frame gaps exceed Chromium's freeze threshold on a quiet screen). Steady windows show zero freeze growth. `freezeCount` is a content-sensitive metric and is documented as such here.
- **The platform's thermal signals contradict each other on this device:** status reads SEVERE continuously (from a 39.5 °C battery, pre-warmed) while the same OS reports headroom ≈ 1.1–1.2 (comfortable) in the same samples. The adaptive policy follows the ladder as designed, but T-runs should record both signals and note this — a device that cries SEVERE with 1.2 headroom makes `headroom` the more trustworthy signal for tuning.
- **Battery temperature kept rising even under the fixed cool profile** (39.5 →41.5 °C over ~10 min, SEVERE throughout, ambient warm) — heat on this rig is dominated by the display/game/SOC baseline as much as by the cast; the controlled T1–T4 runs (idle-baseline cooldown, same content, adaptive off) are the only way to separate the two.
- **Session-start ramp confirmed and quantified** (960×600 → 1280×800 → 1920×1200 in the first ~40 s of session A): the analyzer's `START_RAMP_EXCLUDE_SEC = 15` window is calibrated to it.
- **Two receiver copies raced** (both bind attempts logged): a second instance grabbed an ephemeral signaling port and displayed a QR nobody could use. Fixed with the Electron single-instance lock (the duplicate logs and quits; relaunch focuses the live window) — committed separately after being found in the working tree.

## Verification

- **Tests:** desktop 115/115 (13 files — the stats sampler's new fields, the render probe's interval math, the analysis core's percentiles/deltas/ramp exclusion), mobile JVM 153/153 (the encode-time extraction; the fps-cap composition rule). Both suites ran green at every commit; `assembleDebug` green.
- **On-device (live, 2026-09-24):** both sessions' raw trails are the evidence — sender logcat (stats lines with the encode tail, the adaptive staircase, ThermalSource samples) and receiver JSONL (the full stats snapshots the analyzers summarize). The fps cap's before/after is measured across the two sessions' identical staircase transitions; the jitter-buffer candidate was applied-and-verified live before being rejected on its own numbers.
- **Not yet measured (the remaining Phase16 items):**
  1. **T8 glass-to-glass** (on-screen clock photo method) — the one number that turns the buffer/encode findings into the end-to-end latency verdict R6 asks for.
  2. **T1–T4 thermal protocol runs** (20 min per profile, battery-only, wireless adb, cooldown rules) on the *fixed* build — the thermal.md hypothesis table can only be replaced by numbers from a cast whose fps rung actually applies.
  3. **N7/adaptive-constants retune** from real distributions — the stream-health thresholds never fired on this healthy rig; the thermal holds are confirmed exact by the observed staircase.

## Known limitations

- All measurements are one rig (one phone SoC, one AP, one day, warm ambient) — thermal.md's "one phone does not generalize" rule applies to every number above.
- The jitter-buffer comparison is across two casts (content differs minute to minute); the floor semantics, not the exact percentiles, are the finding — the spec-level behavior was confirmed by introspection of the live receiver.
- The encode-cost tail is encoder-reported time (queueing included) — treat `ms/f` as workload evidence, not a pure silicon measurement.
