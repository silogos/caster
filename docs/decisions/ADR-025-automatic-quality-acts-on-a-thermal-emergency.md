# ADR-025: Automatic quality acts on a thermal emergency only; the network stays libwebrtc's

- **Status:** accepted (2026-11-11, product owner), in its second form of the day (see *History*). Built and unit-tested; **the emergency itself has never occurred on the measured device**, so the rule has not run in a cast (see Consequences). Supersedes the auto-quality policy of [thermal.md](../architecture/thermal.md) ("How thermal data is used"). Issue #165.

## Context

A profile has two behaviour settings:

- **Under network pressure** — libwebrtc's own adaptation (`degradationPreference`): what it gives up when the link is short.
- **Automatic quality** — the app's own rule (Phase12), which lowered the picture along the preset ladder.

Automatic quality as built did not work for its owner:

- It stepped on a *temperature reading*: thermal MODERATE or more for three minutes, then a rung every five, down to the lowest preset; a thermal step never came back by itself. From 1920 px 60 fps its first step was to 30 fps.
- The product owner's experience: it went to the lowest quality while the device was still fine, so they turned it off. Its thresholds had already been relaxed twice.
- A game keeps the measured tablet (TB321FU) at thermal SEVERE for a whole session, and the cast runs at 59–60 fps there.

Its other inputs were checked against every cast recorded on 2026-11-10/11 (about 14,000 seconds, eleven logs):

| Signal | What the logs show |
|---|---|
| Dropped frames | The counter never left 0 — also with the encoder deliberately stalled, and while the frame rate sat at 20–40 fps. |
| Encoder utilization (encode time × frame rate) | 0.95–1.57 at a healthy 57–60 fps; 0.55–0.77 while the picture crawled at 20–45 fps on 1920 px. It follows the frame rate, not the load: the hardware encoder takes about the same time per frame whatever happens. The Phase18 record that it "fired on real overload at 1.59–1.90" was most likely a healthy cast misread. |
| Thermal CRITICAL | Never reached in two days. |

## Decision

**The network is libwebrtc's. The app lowers the picture only in a thermal emergency.**

- **Under network pressure** stays as it was: libwebrtc decides, second by second, inside the profile's bitrate range and never under its least (ADR-023, revised).
- **Automatic quality** (a switch in each profile) acts on one thing, thermal status CRITICAL and above:

| | |
|---|---|
| The emergency begins | one step down, at once |
| Still there 60 s later | one more step; no further, however long it lasts |
| The order | the profile's pressure strategy: Keep frame rate gives up resolution twice; Keep resolution gives up 60 → 30 fps, then resolution; Balanced one of each |
| Out of the emergency for 30 s | one step back, then the next |
| "Restore now" | straight back to the profile's values; during an emergency it holds for a minute |

Resolution steps are the app's choices (1920 → 1280 → 960 px). The bitrate range is never part of a step. Dropped frames, encoder utilization, packet loss and RTT are no longer inputs.

Home's STATUS says what happened: `Lowered to 1280 px: the phone is too hot. Comes back once it cools.`

## History: one field for a day

The first form of this decision (built and installed the same day) merged the two settings into one choice, "When the cast struggles", and had the app step the picture on four symptoms held for 10 s — encoder utilization, dropped frames, the pacer queue, the bitrate at the profile's least — with libwebrtc's own adaptation disabled.

One game cast with it (8 minutes, 1920 px 60 fps, 8–12 Mbps):

- It did bound the fall: 1920 → 1280 (bitrate at the least) → 960 (pacer queue), 33 s apart, and no further; the frame rate went from 32 to 60 fps with no frame dropped.
- It never came back: the encoder-utilization symptom was "on" in 337 of the next 360 seconds, at a healthy 60 fps.

That is what sent the signals to the logs (the table above). With two of the four symptoms disproved and the other two being the network's, the product owner split the settings again: the network to libwebrtc, the app's rule to the one trigger that is unambiguous.

### Stored settings: v9

v9 has v7's shape (`degradationStrategy` and `autoQuality` per profile); v7 reads as is. v8, the one-day format with a single `struggle`, maps back: smooth → Keep frame rate, sharp → Keep resolution, balanced → Balanced, each with Automatic quality on; fixed → Balanced with it off.

## Consequences

- **In ordinary use Automatic quality does nothing**, on or off: game heat does not reach CRITICAL on the measured device. It is a brake for the case where the platform has run out of other options.
- **Not exercised.** CRITICAL has not occurred, so the rule is proven only by its tests (a one-second clock, the thermal ladder). Whether one step, or two, relieves a device at CRITICAL is not known.
- **libwebrtc can still take the picture far down** under network pressure; what bounds it is the profile's bitrate least. It is not announced on screen: the app does not know when libwebrtc scaled the frame.
- **Nothing reacts to an overloaded encoder** short of the stall recovery of ADR-022. A signal that holds up would have to be found first (candidate, not measured: the frame rate the encoder is given against the one it delivers).
- **The built-in profiles** have Automatic quality on, as before.
- **Revisit if:** a device reaches CRITICAL in a cast (measure what the steps did), or a load signal is found that separates a struggling encoder from a healthy one.
