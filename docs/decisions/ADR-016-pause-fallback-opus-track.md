# ADR-016: Silencing the game-audio Opus track while the DataChannel flows — rejected

- **Status:** rejected (2026-11-09) — tried and disproven on-device the same day (Lenovo TB321FU → macOS native receiver). The fallback track of [ADR-012](ADR-012-game-audio-pcm-over-datachannel.md) / [ADR-013](ADR-013-game-audio-aac-over-datachannel.md) stays encoded for the whole cast. Recorded so the idea is not retried without the missing piece below.

## Context

Since ADR-012 the phone encodes game audio twice. The `game-audio` DataChannel carries the stereo stream the desktop plays (AAC or PCM16). The libwebrtc Opus track carries a dual-mono copy (up to 192 kbps) that the desktop renders only after the channel has been quiet for 3 s (`FALLBACK_AFTER_DC_QUIET` in `apps/desktop-native/src-tauri/src/audio.rs`). The 2026-11-09 mobile review (group D, item 18) flagged the track as pure cost in a healthy cast, and the product owner chose to pause it while the channel flows.

## What was tried

A pure gate (`FallbackTrackGate`, now removed) silenced the track after 1 s of successful channel sends and restored it within 500 ms of a stall, which is far inside the desktop's 3 s window. Two ways of silencing it were tried on-device, the same day:

1. **Pause:** set the sender encoding `active = false`.
2. **Mute:** call `track.setEnabled(false)`. The hope was that a muted track keeps its send stream "sending", so the recording would run on.

## Why it cannot work

**The DataChannel's audio comes from the same WebRTC audio device module (ADM) recording that feeds the Opus track** (`PlaybackCaptureAudioSource` substitutes the playback-capture record inside `JavaAudioDeviceModule` and taps its samples callback). In the pinned prebuilt, libwebrtc stops ADM recording when the only audio send stream stops carrying audio, and **both** the pause and the mute triggered it. The mute log proves the ordering: `setEnabled` blocks while the ADM thread is stopped, so `WebRtcAudioRecordExternal: stopRecording` is logged *before* the call returns and the app logs "muted". The same holds for `startRecording` and the unmute.

The device log showed a self-sustaining loop roughly every 2 s:

1. The track was paused, then `WebRtcAudioRecordExternal: stopRecording`.
2. The channel starved, so the gate resumed the track.
3. `startRecording` and a fresh record substitution followed (`playback capture active`).
4. The channel flowed again, and after 1 s the track was paused again.

On the desktop this looked like a ~0.7 s hole every ~2 s: `underruns` climbed by about one per log line, and the channel delivered only ~55–60 chunks/s instead of ~100. The control cast from the same session, with the gate inert, ran at 100 chunks/s with zero underruns. The user heard the mute build as audio "dropping in and out".

A side finding from the same attempt: libwebrtc's Java `PeerConnection.getSenders()` **disposes every `RtpSender` it returned before**. A sender kept from `addTrack` is dead after any later `getSenders()` call (`applySenderParameters` makes one), so senders must be looked up per use.

## Considered alternatives (for a future attempt)

| Option | Status |
|---|---|
| Pause the encoding (`active = false`) | **Rejected** — stops the ADM recording, the channel's own source |
| Mute the track (`setEnabled(false)`) | **Rejected** — same: the recording stops too |
| Throttle the track's bitrate (e.g. 32 kbps) | Possible: keeps the recording alive, saves airtime only (the encoder still runs) |
| Feed the channel from a capture independent of the ADM | Would decouple the two; contradicts the single-record design of audio.md (the pre-open ordering that keeps capture stereo) |
| Keep encoding both (status quo) | **Chosen** — correct, and the cost is small on a LAN |

## Consequences

- The double encoding stays, as ADR-012/013 describe.
- **The track is mono anyway** (measured the same day, [features/game-audio.md](../features/game-audio.md)): the pinned prebuilt's Opus send path ignores the negotiated `stereo=1`. Replacing the channel with a single stereo Opus track is therefore not an option with this library.
- **Revisit if:** the channel gets a capture source independent of the ADM, or ADR-012's own revisit condition fires (a stereo Opus send path retires the bypass).
