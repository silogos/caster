# ADR-004: Mic capture coexistence — the cast mic must not win the arbitration

- **Status:** accepted (fix after Phase 8 review period, 2026-09-23) — device verification pending

## Context

The cast mic is captured by the stock `JavaAudioDeviceModule` (factory B, [ADR-003](ADR-003-two-audio-track-architecture.md)) with its default audio source `VOICE_COMMUNICATION` — chosen in Phase 8 because the platform's AEC/NS/AGC ride on that source ([audio.md](../architecture/audio.md)).

The live-found bug: with a cast running, turning the **cast mic on muted the microphone of the app being cast (PUBG voice chat)**; turning the mic off restored it instantly. Streaming apps on the same device did **not** cause this (TikTok + PUBG voice chat coexist).

The platform rule (developer.android.com "Sharing audio input"): under Android's concurrent-capture policy, `VOICE_COMMUNICATION` and `CAMCORDER` are **privacy-sensitive by default**, and *"if one of the apps is privacy-sensitive, it receives audio and the other app gets silence"* — regardless of who started capturing or which app is foreground. So the cast mic's voice source won the arbitration and silenced the game's capture, while a streaming app using a non-sensitive source (`MIC`/`UNPROCESSED`) coexists with the game.

An earlier claim (from a side discussion) that Android 16 introduced *new* compatibility rules for this could not be found in the official Android 16 behavior-change pages — the arbitration rule above predates Android 16 and is sufficient to explain the symptom; the Android-16-specific claim is recorded here as **unverified**.

## Decision

Factory B keeps the `VOICE_COMMUNICATION` source — the HAL voice processing (AEC/NS/AGC) is tied to the source and is wanted for the viewer experience — but the record it captures with is made **explicitly privacy-insensitive**:

- At the ADM's recording start (deterministic, before the first read), its mic `AudioRecord` is swapped via reflection for a twin built with `AudioRecord.Builder().setAudioSource(VOICE_COMMUNICATION)...setPrivacySensitive(false)` (`audio/MicRecordSubstituter`) — the same substitution pattern factory A has used since the Phase 7 spike.
- The flag can only be set at record creation; the fork's `JavaAudioDeviceModule` builder exposes `setAudioSource(int)` but **no** privacy-sensitive API (verified against the pinned 1.3.10 sources), which is why substitution rather than configuration.
- **Degradation, not failure:** if substitution cannot run (below API 30 where `setPrivacySensitive` exists, reflection lookup fails, twin record rejected), the stock record stays — the mic works exactly as before, with the documented coexistence cost, logged not surfaced.
- **Silence is never a silent failure:** `audio/MicSilenceMonitor` consumes the session's `AudioManager.AudioRecordingCallback` and, when a recording on the mic's source reports `isClientSilenced`, the UI moves to `MicState.Silenced` with a plain-words fact; it recovers automatically when the winner stops recording. Public APIs cannot identify *which* recording is ours (uid accessors are system APIs), so "ours" is matched by audio source — a deliberate approximation, with every config snapshot logged as source/silenced metadata for the Phase 15 matrix.

## Considered alternatives

| Option | Verdict |
|---|---|
| Switch factory B's source to `UNPROCESSED` (`builder.setAudioSource`) | **Rejected as primary** — no CPU/battery benefit (the effects it skips mostly run on the audio DSP, and WebRTC's own Opus/APM pipeline is unchanged), while dropping all platform processing worsens noise/echo for viewers; also the UNPROCESSED path can be unavailable on some HALs |
| Switch to `MIC` | **Kept as fallback** — simplest fix (one builder call, non-sensitive by default) but loses the voice tuning; the fallback is one line if the device test shows the substituted record still silencing the game |
| Capture mic only when the game is not voice-chatting | **Rejected** — the app cannot know; mic is a live user toggle |
| Drop the cast mic feature during gameplay | **Rejected** — the product requires screen + game audio + mic as separate tracks |

## Consequences

- **Positive:** two non-sensitive captures are allowed to coexist (the same shape of traffic as a streaming app + game voice chat, which the device evidence already shows working); viewer-facing voice quality unchanged; degradation is the exact pre-fix behavior.
- **Costs / accepted:** factory B now carries the second reflection site (pinned to `audioInput`/`audioRecord`/`byteBuffer` — same accepted cost as factory A's substitution, ADR-003 addendum); the "ours" match in the silence monitor is by source, so another capture on `VOICE_COMMUNICATION` being silenced would show the fact on our UI (rare, self-clearing, and the log disambiguates).
- **Explicitly revisit this ADR if:** the device test shows the substituted record still silencing the game's capture (→ the `MIC` fallback, then re-evaluate the substitution), or a libwebrtc update ships a public privacy-sensitive configuration option (→ replace the reflection with the public API).
