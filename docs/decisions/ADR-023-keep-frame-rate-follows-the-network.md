# ADR-023: "Keep frame rate" lets the video bitrate fall below the window

- **Status:** **revised the same day** (see *Revision* at the end): the hidden 1 Mbps floor is gone; a profile's own least is the floor. Originally: accepted (2026-11-11). Built, unit-tested and measured in one cast on-device: the bitrate and resolution follow the network and the frame rate holds; **the audio benefit is not shown** (see Consequences).

## Context

The product owner's priority, stated after the audio investigation of 2026-11-11: smooth audio and a smooth frame rate first; picture quality may drop for a while when it has to ("kualitas boleh menurun di saat-saat tertentu").

What the cast did instead ([latency-measurement.md](../development/latency-measurement.md), "Audio starves in a long game cast"):

- A bitrate window's floor is applied as the video sender's `minBitrateBps`, a hard floor: libwebrtc may not send less, whatever the uplink carries. The Sharp window holds 8 Mbps.
- In the measured game cast the uplink carried 3–8 Mbps for two minutes. The video queued up in the sender (pacer wait up to 590 ms per packet), the frame rate fell to 16–37 fps, and the game audio, which shares the transport, starved on the desktop (128 underruns in 6.4 minutes; none in the stretches without a backlog).
- The tablet's strategy was "Keep resolution" (Sharp's default), which gives up frames first.
- The app's *Bitrate mode: VBR* does nothing yet (libwebrtc hardcodes CBR; [cast-settings.md](../features/cast-settings.md)), so choosing it changed nothing.

The app already has the right promise on screen: *Under network pressure → Keep frame rate*, "stays smooth and drops detail". With an 8 Mbps floor it could not drop detail.

## Decision

Under **Keep frame rate** the sender's floor is `KEEP_FRAME_RATE_FLOOR_BPS` = 1 Mbps instead of the window's floor (`webrtc/SenderBitrateFloor.kt`, applied in `MediaCastSession.applySenderParameters`). The window's floor becomes the bitrate the cast **starts** at (`PeerConnection.setBitrate`, once per window), so a healthy link looks the same from the first second. The ceiling is unchanged.

**Balanced** and **Keep resolution** keep the window's floor: "Keep resolution" is the user's choice of detail over smoothness.

| Option | Result |
|---|---|
| Lower every preset's floor | Rejected: changes what Sharp means for users who chose detail, and the preset labels (`8–12 Mbps`) with it |
| A separate "bitrate floor" control | Rejected: a second knob for what "Keep frame rate" already promises |
| Make *Bitrate mode: VBR* real (codec rate control) | Not this decision: VBR spends bits by frame complexity and can burst on busy scenes; it does not follow the network |
| **Tie the floor to "Keep frame rate"** | **Chosen**: no new control, the existing text becomes true |

1 Mbps: the uplink carried 1.6–3.4 Mbps at its worst in the measured cast, and under this strategy libwebrtc scales the frame down as the bitrate falls.

## Consequences

- **Diagnostics:** `sender targets: <floor>–<ceiling> kbps, start <n> kbps, under pressure: <strategy>` on every apply, and the 1 Hz stats line carries libwebrtc's current target (`(target N)`), so a cast that backed off is visible.
- **Measured in one cast (2026-11-11, 14 minutes, a streamed video on a cool tablet;** [latency-measurement.md](../development/latency-measurement.md), "With 'Keep frame rate' below the window"**).** The claim was: where the uplink is short, the sent bitrate and resolution fall, the frame rate holds, the pacer wait stays low and the audio does not starve.
  - *Bitrate and resolution fall:* yes. The target went down to 2.3 Mbps and the frame to 1280×800, and both came back.
  - *The frame rate holds:* yes for 60 fps content: 59–60 fps in the 111 seconds under the old 8 Mbps floor. A 4K stream ran at 38–51 fps whatever the bitrate; that is not explained.
  - *The pacer wait stays low:* partly. It still reached 60 ms or more in 110 of 797 seconds.
  - *The audio does not starve:* **no.** 5.7 underruns a minute, at the same rate with the video queue empty as with it backed up. The video backlog is not the only thing that delays the audio.
- **Not compared:** no cast of the same content under "Keep resolution", no real game, no hot tablet.
- **Not addressed:** audio still shares the video's transport with no priority of its own. If the uplink is short for other reasons than the video's own volume, audio can still be late.
- **The presets are unchanged.** Sharp still defaults to "Keep resolution"; a user who wants this behaviour picks "Keep frame rate". Whether a preset should default to it is a separate product decision.
- **Revisit if:** the picture at the 1 Mbps floor is unusable (raise the floor), or casts start soft because the start bitrate is refused (the log line shows it).

## Revision (2026-11-11, later): the floor is the profile's own

**What happened.** In a game cast on the user's profile (1920 px, 60 fps, 8–12 Mbps, Keep frame rate, tablet at thermal SEVERE) libwebrtc's target fell from 8.8 to 2.2 Mbps in three minutes and the frame went 1920 → 1280 → 960 → 640×400, while the send queue was empty and the RTT 6–10 ms. The frame rate did hold (20 → 59 fps as the frame shrank). The product owner's verdict: too extreme. The profile's row still read "8–12 Mbps".

**What was wrong with the decision above.** A fixed 1 Mbps floor, hidden behind a strategy name, let an estimate that looks too cautious drag the picture four steps down, and made the range on screen untrue.

**Decision.** The sender's floor is the profile's least, whatever its pressure strategy. `KEEP_FRAME_RATE_FLOOR_BPS`, `SenderBitrateFloor.kt` and the start-bitrate call are removed. In the user's own profile the bitrate is one slider with two thumbs, the least and the most (1–20 Mbps in 1 Mbps steps); the rule "the floor is half the ceiling" is gone. The built-in profiles keep their ranges, so Performance (Keep frame rate, 6–10 Mbps) holds 6 Mbps again.

**Consequences.**
- How far the picture may fall is the user's choice, and the range on the profile's row is the range the sender uses.
- A least set above what the uplink carries queues the video again, and the audio with it: the failure the original decision was about. The range is the user's trade.
- Still libwebrtc's own: when it scales the frame or drops frames inside that range. Taking that over (steps of our own, one at a time, bounded, coming back by themselves) is the next decision; the design draft states the rule.
- The diagnostics stay: `sender targets: <least>–<most> kbps, under pressure: <strategy>` and `(target N)` on the stats line.
- **Not measured on-device** in this form.

