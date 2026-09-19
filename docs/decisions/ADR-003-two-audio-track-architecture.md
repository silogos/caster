# ADR-003: Two-audio-track architecture (game audio + microphone as separate streams)

- **Status:** accepted (Phase 0, 2026-09-19) — **with a mandatory validation spike at the start of Phase 7**

## Context

The product requires game audio (`AudioPlaybackCapture`) and microphone as **separate WebRTC tracks**, never mixed on Android (independent desktop volume, independent muting). libwebrtc constrains the design:

- A `PeerConnectionFactory` owns exactly **one `AudioDeviceModule` (ADM)**.
- **All local audio tracks created by that factory record through that single ADM** — the APM/audio state has one recording stream; there is no per-track capture source in the Java API.
- `JavaAudioDeviceModule` (the standard Android ADM) captures the microphone via platform audio-source semantics; it cannot be fed arbitrary PCM from `AudioPlaybackCapture` while also capturing the mic.

So two independent capture sources cannot both live in one PeerConnection/factory.

## Decision

Use **two `PeerConnectionFactory` instances and two PeerConnections**, signaled over the single WebSocket with a `pc` discriminator ([webrtc.md](../architecture/webrtc.md)):

- **`media` PC (factory A):** screen video + game audio. Factory A gets a **custom `AudioDeviceModule`** implementation whose recorder wraps an `AudioRecord` configured with `AudioPlaybackCaptureConfiguration(mediaProjection)` (48 kHz stereo PCM16 → Opus stereo).
- **`mic` PC (factory B):** microphone only, using the stock `JavaAudioDeviceModule` (`VOICE_COMMUNICATION`) → Opus mono.

**Validation spike (beginning of Phase 7, on a real device, before building the full feature):**
1. A custom ADM streaming playback-capture audio audible at the desktop through a libwebrtc track.
2. Simultaneous operation of both factories/PCs with no ADM, audio-focus, or performance conflicts.
If either fails, fall back per the table below and record a superseding ADR.

## Considered alternatives

| Option | Verdict |
|---|---|
| Mix mic + game audio into one PCM stream on Android | **Rejected** — forbidden by the product rule (no independent volume/mute; spec §Phase 7/8) |
| One factory, two audio tracks | **Not possible** — both tracks share the single ADM recorder (above) |
| Custom native `AudioSourceInterface` fed via JNI for one of the sources | **Deferred** — elegant single-PC solution, but requires building/patching libwebrtc or maintaining JNI against it; revisit only if the spike fails or the second PC proves costly |
| Mic over a DataChannel with a custom codec | **Rejected** — reinvents transport (jitter, timing, codec management) that WebRTC already provides |

## Consequences

- **Positive:** keeps Android-side separation clean; uses only public `org.webrtc` Java APIs; each factory's ADM maps 1:1 to its capture source.
- **Costs / accepted:** a second ICE+DTLS session (negligible on LAN); slight offer/answer bookkeeping (`pc` discriminator — already in the signaling spec); two `AudioSession`s on Android that must not both claim audio focus (the custom ADM does not request focus; validated in the spike).
- **Explicitly revisit this ADR if:** the spike fails, or Phase 5 measurements show the second PC meaningfully impacting connection time or battery.
