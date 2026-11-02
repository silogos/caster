# ADR-004: Mic capture coexistence — the cast mic must not win the arbitration

- **Status:** accepted (fix after Phase 8 review period, 2026-09-23) — verified on device (Y700/Android 16: PUBG voice chat + cast mic coexist cleanly after round 3; see the addenda)

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

## Addendum (device round 2, 2026-09-23): the arbitration was fixed, then the shared HAL path broke the audio — rate + routing

The privacy-insensitive twin worked (the casted game's voice chat receives the mic again), but two new symptoms appeared when the game's voice chat captured **concurrently** with the cast mic:

1. The desktop mic stream got **delayed**, then
2. turned into **chipmunk audio** — the classic pitch-up of a rate mismatch. dumpsys (live) showed why: the game's capture is `MIC`, **16 kHz** stereo on `AUDIO_DEVICE_IN_BUILTIN_MIC`, while our record was `VOICE_COMMUNICATION`, **48 kHz** mono that the platform routed to `AUDIO_DEVICE_IN_BACK_MIC` — a different device path that fed our 48 kHz record raw 16 kHz data (48/16 = exactly the 3× chipmunk). Retoggling the mic only helped once the game's mic was already off: the re-created record opened into the same mis-matched shared path.

**What shipped:** the cast mic's capture contract is now aligned with the shared path from the start — factory B runs at **`SHARED_VOICE_INPUT_RATE_HZ = 16 kHz`** (`MicCastSession`, matching the on-device voice-chat rate), and the twin is explicitly routed to **`AUDIO_DEVICE_IN_BUILTIN_MIC`** via `setPreferredDevice` (`MicRecordSubstituter.routeToBuiltinMic`) so it reads the same physical mic the user speaks into, in the same device+rate configuration the game's capture already runs.

**Accepted cost:** the cast mic is 16 kHz mono (wideband voice) rather than the ADM's default 48 kHz — slightly duller for viewers, but consistent with the voice-chat path it must coexist with. Revisit only if viewer quality complains. Routing may still be overridden by the platform (the preferred device is a request; the fallback is the default routing, logged).

## Addendum (device round 3, 2026-09-23, user-directed): the capture source itself moves to `MIC`

Round 2's rate alignment shipped, but the split routing did not move: dumpsys still showed our `VOICE_COMMUNICATION` record on `AUDIO_DEVICE_IN_BACK_MIC` while the game's `MIC` capture held `AUDIO_DEVICE_IN_BUILTIN_MIC` — and the twin's `setPreferredDevice(builtin-mic)` request did not override it (the audio policy routes per audio source; the voice-comm source owns a routing profile of its own on this ROM).

**Revision:** the whole capture contract now runs on **`MediaRecorder.AudioSource.MIC`** (`MicRecordSubstituter.CAPTURE_SOURCE` — the ADM builder, the twin record, and the silence monitor's "ours" match all share it). `MIC` is the exact source the game's own voice chat uses, so the audio policy hosts both captures identically — same source, same 16 kHz rate (`SHARED_VOICE_INPUT_RATE_HZ`), same builtin-mic device.

**Cost accepted (revises round 2's rationale):** the platform AEC/NS/AGC tied to the voice source is gone. The documented acoustic-echo limitation (phone speaker + live mic) therefore stands unmitigated by the platform — the headphones tip in the UI is the mitigation. `setPrivacySensitive(false)` remains explicit on the twin (redundant for `MIC`, which is non-sensitive by default, but it keeps the ADR-004 invariant stated in code). Revisit back to `VOICE_COMMUNICATION` only if echo/noise quality becomes the top complaint and a device shows the voice source coexisting cleanly.

**Clarification (2026-09-24):** the lost processing is narrower than the round-3 note reads. The mic track still runs libwebrtc's **software** APM — the mic PC's audio source is created with default constraints, so AEC/NS/AGC are on (only the media PC disables them, because playback capture is finished audio). What was dropped is only the HAL chain tied to the voice source, and the acoustic-echo case was never covered by that chain either: the mic PC's ADM never plays out the game audio, so neither platform nor software AEC has a far-end reference for the speaker. `MIC` is also the natural reference input — the game's own voice chat records the same source/rate/mic and applies its own software processing, so the desktop now hears exactly the input the player's teammates hear.

## Addendum (2026-10-08): the routing target becomes a user setting — the built-in mic stays default and fallback

Round 2's `setPreferredDevice` hook existed only to fix the back-mic split; the built-in mic was hardcoded. The routing hook is now the user's microphone setting: a `MicDeviceSource` *kind* (built-in, wired headset, USB, Bluetooth SCO — stored as a label in the settings document, v4 → v5) resolved at mic-session start by `MicDeviceResolver` against the currently connected input devices, then applied by `MicRecordSubstituter` via the same `setPreferredDevice` call. Rationale: an external mic is a real use case (headset/USB mic for streamers), and the resolution rules that made the built-in fix work extend naturally to other kinds.

**The built-in mic remains the default and the fallback**, and the round-2/3 findings stand unchanged: the source is still `MIC`, the rate still `SHARED_VOICE_INPUT_RATE_HZ`, and the preferred-device request is still a request — the platform may override routing (the fallback is the default routing, logged). A chosen kind that isn't connected resolves to the built-in mic, logged, never a failed session. Wired headsets (`TYPE_WIRED_HEADSET`), USB inputs (`USB_HEADSET`/`USB_DEVICE`/`USB_ACCESSORY`) and Bluetooth SCO map by type; output-only twins (wired headphones, A2DP) are deliberately not mic options.

**Costs / accepted:** the explicit built-in-only assumption of round 2 is generalized, so the coexistence behavior of the *external* kinds is unproven until each kind is verified on device with a concurrent game voice chat (the round-3 history shows routing surprises are real). Bluetooth SCO additionally records at phone-call quality and may affect audio routing while connected — offered in the UI with that caveat. The built-in path is unchanged by this addendum and remains verified.

## Addendum (2026-11-02): round 4 — the capture rate becomes per-device; the external kinds had left the builtin contract

The warning above came true. #59 made the mic device a setting and #66 made it live-switchable, but both kept round 2's **single** input rate — every kind recorded at `SHARED_VOICE_INPUT_RATE_HZ` (16 kHz), the rate that is the *builtin* path's contract with the game's voice chat. Found live (user report; same TB321FU rig, PUBG unchanged): with the cast mic routed to a **wired/USB** input and PUBG's voice chat capturing concurrently, **both** captures turned chipmunk — the cast mic at the desktop *and* the game's own voice chat. Wired/USB input paths are natively 44.1/48 kHz; asking one for 16 kHz while the game holds the builtin path forces the audio policy to reconfigure the shared input profile, and on this ROM that mis-feeds every client on it (the same raw-feed behavior round 2 proved, triggered by a *device* mismatch instead of a *source* mismatch). #66's commit message had the transport matrix as "on-device check pending" — it never ran.

**What shipped:** the rate contract is per-kind and lives with the resolver (`MicDeviceResolver.captureRateHz`, JVM-tested): builtin keeps **16 kHz** (`SHARED_VOICE_INPUT_RATE_HZ`, moved there from `MicCastSession`); wired + USB record at their native **48 kHz** (`USB_ANALOG_INPUT_RATE_HZ`); Bluetooth SCO at the **HFP wideband ceiling, 16 kHz** (`HFP_VOICE_INPUT_RATE_HZ` — still unverified on device). The ADM's input rate is chosen at mic-session build from the **resolved** kind, so a selected-but-unconnected kind keeps the builtin contract. A live device switch is an in-place `setPreferredDevice` only when the rates already match (`MicDeviceResolver.isLiveReroutable` — e.g. wired ↔ USB, both 48 kHz); a cross-rate pick (builtin ↔ wired/USB) **rebuilds the mic session**, because an `AudioRecord`'s rate is fixed at creation — a ~1–2 s mic-local gap, the cast untouched. The cast's live config follows a mid-cast pick (`config.copy(micDevice = …)`, the live-quality pattern), so a pick made with the mic off applies at the next mic-on; the unplug watcher heals cross-rate removals through the same rebuild.

**Costs / accepted:** the cross-rate switch costs the brief mic gap (honest and mic-local, versus corrupting two audio streams); the external kinds' rates rest on device-class standards (USB Audio Class 48 kHz, HFP 16 kHz) rather than per-device probing — `AudioDeviceInfo` exposes no rate list — so a rogue HAL profile remains possible and the dumpsys check stays in the acceptance matrix (issue #92). SCO is still unverified on device.

**Revisit if:** the on-device matrix shows a transport whose real profile differs from its contracted rate — the map is one table entry in `MicDeviceResolver`, not an architecture change.

**Hardening (2026-11-03, live-found in the first on-device session):** the rate contract itself verified on the rig (48 kHz mono `MIC` on the USB headset input while it was alive; concurrent game capture `not silenced` both ways; no chipmunk). The session exposed switching-machinery races (a dying session's late `Off` publish clobbering its replacement's `Active`; needless rebuilds for picks arriving before the record was live; one rebuild per input flap — now identity-guarded publishes, a three-way pick outcome, and a 2 s device-settle timer that follows the *selection* once per storm), a transport gap (a connection reset kept the cast but parked the mic as Failed — the mic pc now renegotiates on its own ladder, `MicReconnectPolicy`, reliability A8), and the dead-but-listed input case (a capture-side death on an external kind now degrades to the built-in mic with the fallback fact instead of parking). The external test headset itself proved unstable on the rig (input flapping; +9 output discontinuities in 45 s live) — recorded as environmental, matrix ② needs a stable headset.
