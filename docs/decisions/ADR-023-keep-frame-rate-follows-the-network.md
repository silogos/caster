# ADR-023: "Keep frame rate" lets the video bitrate fall below the window

- **Status:** accepted (2026-11-11). Built and unit-tested; **not yet measured on-device** (see Consequences).

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
- **Not verified on-device.** The claim to test, with the gap probes: under "Keep frame rate", in the stretches where the uplink is short, the pacer wait stays low, the frame rate holds and the audio does not starve, while the sent bitrate and resolution fall.
- **Not addressed:** audio still shares the video's transport with no priority of its own. If the uplink is short for other reasons than the video's own volume, audio can still be late.
- **The presets are unchanged.** Sharp still defaults to "Keep resolution"; a user who wants this behaviour picks "Keep frame rate". Whether a preset should default to it is a separate product decision.
- **Revisit if:** the picture at the 1 Mbps floor is unusable (raise the floor), or casts start soft because the start bitrate is refused (the log line shows it).
