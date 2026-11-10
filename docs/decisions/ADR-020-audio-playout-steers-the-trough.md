# ADR-020: The receiver's audio playout steers the buffer's trough, with a small IO buffer

- **Status:** accepted (2026-11-10) — measured on-device the same day (Lenovo TB321FU → MacBook Pro M1 Pro); the product owner's listening check in a game: "Bersih" (clean). Replaces the fixed 40 ms target of `audio.rs` (ADR-014 Fase A's engine).

## Context

Game audio reaches the Mac ~150 ms after the phone's own speaker. About 120 ms of that is inside Android and out of reach ([latency-measurement.md](../development/latency-measurement.md)). The product owner asked for whatever the Mac side could still give.

The old playout held the *mean* ring depth at 40 ms. Two facts made that a poor handle:
- The phone delivers audio in bursts, one per capture period (~21 ms, often two or three together), so the depth is a sawtooth. Arriving audio waited only ~28–30 ms, not 40. The mean says nothing about how close the low point comes to empty, and a phone with a different burst size would get a different safety margin from the same number.
- Every underrun rebuilt the whole 40 ms cushion in silence, however short the lateness that caused it.

The device IO buffer was the system default (512 frames, 10.7 ms). It adds its length to the output delay, and each callback needs that much audio at once.

## Decision

1. **Steer the trough.** The callback tracks the least audio left after a callback (down at once, up at 10 ms per second) and plays up to ±1 % faster or slower to hold it at a **15 ms margin**. Nothing is discarded; the 200 ms emergency cut stays.
2. **A starve is a short gap.** The last frame decays over 2 ms, playback resumes as soon as 5 ms is back (the gap has already moved the timeline later by the lateness), and the resumed audio fades in. Only a gap over 50 ms rebuilds the 40 ms start cushion, because the arrival phase is unknown again.
3. **IO buffer of 128 frames** (2.7 ms), clamped to what the device allows; a device that refuses it gets its default.
4. **Diagnostics:** the 1 Hz `dc:` line adds the silence played while starved, the tracked trough, and the callback size.

| Option | Verdict |
|---|---|
| Lower the fixed target (40 → 30 / 20 ms) | Rejected: measured earlier — 2 underruns/min at 30 ms, 84–120 at 20 ms, each a full re-prime |
| Margin 10 ms | Rejected as the default: ~8 ms less delay than 15 ms for 2–3× the gaps |
| **Margin 15 ms** | **Chosen**: gaps as rare as the 40 ms target it replaces |
| Time-stretch concealment (NetEq-style) | Not now: far more code than a 2 ms fade for gaps this rare |

## Consequences

Measured by alternating the old and new binaries on the same cast (midday Wi-Fi, ~118 s each):

| Playout | Arriving audio waits in the ring | Underruns | Silence while starved |
|---|---|---|---|
| Old (mean 40 ms, 512-frame IO) | 28 / 30 ms | 3 / 2 | not counted; each rebuilt a 40 ms cushion |
| Margin 10 ms | 20–21 ms | 6–10 | 42–76 ms |
| Margin 12 ms | 25 ms | 5 | 67 ms |
| **Margin 15 ms** | **27–28 ms** | **2–4** | **0–86 ms** |
| Margin 20 ms | 32 ms | 2 | 19 ms |

- **The ring was not where the delay was.** At equal robustness the wait is the same ~28 ms. The gain is the IO buffer: 512 → 128 frames is 8 ms less output delay (from the buffer sizes; not measured acoustically).
- **Gaps are shorter.** A late burst now costs its own lateness, not a 40 ms rebuild. Network stalls of 20–60 ms still produce a gap of that length; no margin this size hides them.
- The margin now means the same thing on any phone, whatever its capture period.
- **Revisit if:** gaps are audible in real play (raise the margin), or a 128-frame IO buffer glitches on a loaded Mac (raise `OUTPUT_BUFFER_FRAMES`).
