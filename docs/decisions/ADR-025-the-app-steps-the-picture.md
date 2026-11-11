# ADR-025: The app steps the picture; libwebrtc only moves the bitrate

- **Status:** accepted (2026-11-11, product owner). Built and unit-tested; **not measured in a cast** (see Consequences). Supersedes the auto-quality policy of [thermal.md](../architecture/thermal.md) ("How thermal data is used") and the degradation strategy of [ADR-023](ADR-023-keep-frame-rate-follows-the-network.md). Issue #165.

## Context

Two mechanisms lowered a struggling cast's picture, and neither behaved:

- **libwebrtc's own adaptation** ("Under network pressure"). In a game cast on the user's profile (1920 px, 60 fps, Keep frame rate, tablet at thermal SEVERE) it took the frame 1920 → 1280 → 960 → 640×400 in three minutes, with an empty send queue and an RTT of 6–10 ms, and stayed there. The app cannot see why it steps, bound how far, or say so on screen.
- **The app's Automatic quality** (Phase12). It stepped on a *temperature reading*: thermal MODERATE or more for three minutes, then a rung every five, down to the lowest preset, and a thermal step never came back by itself. From 1920 px 60 fps its first step was to 30 fps. The product owner's experience: it went to the lowest quality while the device was still fine, so they turned it off. Its thresholds had been relaxed twice already.

The two also asked the user the same question twice ("what goes first?") under different names, and only one of them said anything on screen.

## Decision

**One rule, in the app, for a slow network, a hot phone and an overloaded encoder alike.**

- **libwebrtc moves the bitrate**, by itself and within seconds, inside the profile's range. Its `degradationPreference` is `DISABLED`: it no longer changes the resolution or the frame rate.
- **The app moves the resolution and the frame rate** (`adaptive/AdaptiveQualityController`), through the same live path as before (`MediaCastSession.changeQuality`).
- **One setting in a profile, "When the cast struggles"** (`StrugglePolicy`), replaces "Under network pressure" and "Automatic quality":

| Choice | First step | Second step |
|---|---|---|
| Keep it smooth | Resolution down | Resolution down again |
| Keep it sharp | 60 → 30 fps | Resolution down |
| Balanced | Resolution down | 60 → 30 fps |
| Don't adjust | none | none |

Resolution steps are the app's choices (1920 → 1280 → 960 px). When what a choice would give up is used up, the other thing goes instead. The bitrate range is never part of a step.

**The rule.** Every number is a starting guess to be measured in a real game cast:

| | Value |
|---|---|
| Down one step | one of these holds for 10 s: the encoder spends ≥ 95% of the time it has; ≥ 10% of frames dropped; video packets wait ≥ 60 ms to be sent; the bitrate sits at the profile's least |
| Up one step | 30 s with none of those and the bitrate at least a quarter of the range above the least |
| Between two steps | 20 s, either way |
| How far | at most two steps below the profile |
| Temperature | nothing until CRITICAL; then one step down, and no step up while it lasts |
| The user | "Restore now" and a profile pick go back to the profile's values and start the rule over |
| Not a symptom | a frame rate below the target (a 30 fps game under a 60 fps profile is a quiet encoder) |

Packet loss and RTT are no longer inputs: they are the bitrate's business, and libwebrtc answers them faster than a ten-second hold.

| Option | Result |
|---|---|
| Keep libwebrtc's adaptation, only raise the bitrate floor | Rejected: bounds how far, not how fast or why; nothing to tell the user |
| Keep both mechanisms and retune Automatic quality again | Rejected: two answers to one question, and a third retune of a rule that acts on temperature |
| Let the app move the bitrate too | Rejected: libwebrtc's estimator reacts in seconds and sees what the app does not |
| **App steps the picture, libwebrtc the bitrate** | **Chosen** |

### Stored settings: v8

A profile's `degradationStrategy` and `autoQuality` become one `struggle`. v7 documents (and v1–v6 through them) migrate by the pressure strategy: Keep frame rate → Keep it smooth, Keep resolution → Keep it sharp, Balanced → Balanced. **"Automatic quality: off" does not become "Don't adjust"**: it was off because the old rule went to the lowest quality on a temperature reading, which this rule does not do; "Don't adjust" is only ever the user's own pick.

## Consequences

- **The picture cannot fall more than two steps, or more than one step every twenty seconds, and it comes back.** Home's STATUS says what was lowered, why, and that it returns: `Lowered to 1280 px: the encoder couldn't keep up. Comes back by itself.`
- **Slower than libwebrtc.** For the ten seconds of the hold a short network is answered only by the bitrate. If the bitrate is already at the profile's least, the video queues and the audio can starve until the step. A lower "least" on the profile's slider is the user's lever.
- **Coarser than libwebrtc.** Four symptoms, read once a second.
- **With "Don't adjust" and a short network** the picture stutters and the sound falls behind; the choice says so.
- **Every step costs one key frame** (the encoder's input size changes); the pause between steps bounds it.
- **The built-in profiles** map as their old pressure strategy did: Cool and Balanced → Balanced, Performance → Keep it smooth, Sharp → Keep it sharp.
- **Not measured.** No cast has run with it. What decides the numbers: a real game cast, read with the gap probes — how often the picture steps, whether ten seconds is short enough to keep the audio from starving, whether thirty is long enough not to flap.
- **Revisit if:** the picture flaps, the hold lets the audio starve, or CRITICAL turns out to be too late to help a throttling device.
