# Audio Architecture

Status: Phase 0 (planned; game audio lands in Phase 7, mic in Phase 8, desktop mixer in Phase 9).

## Product rule

> Game audio and microphone are **separate logical streams, end to end**. They are never mixed on Android.

The desktop must be able to control their volumes independently (Phase 9's two `GainNode`s), and muting one must not touch the other.

## Android side

### Game audio — `AudioPlaybackCapture` (API 29+)

```text
Game / other apps
   ↓ (audio played with capturable usage)
AudioRecord built with AudioPlaybackCaptureConfiguration(mediaProjection)
   ↓ 48 kHz stereo PCM16
custom AudioDeviceModule  ──▶ "media" PC audio track ──▶ desktop
```

Facts that shape the implementation:

- Requires a live `MediaProjection` token — game audio capture is only possible while screen capture is granted (the consent dialog covers both; this is one reason screen + game audio share the `media` PeerConnection).
- **Capture policy is per-app and set by the *source* app.** Apps may declare `ALLOW_CAPTURE_BY_NOBODY` (or DRM protection) — many music/DRM/streaming apps and some games do. Capturing an opted-out app produces **silence, not an error**.
- The capture config filters by audio usage: we accept `USAGE_MEDIA`, `USAGE_GAME`, `USAGE_UNKNOWN`. Silent-input detection (sustained zero samples while the desktop is connected) drives the UI state "This app's audio can't be captured", never a fabricated error.

### Microphone — standard capture

`JavaAudioDeviceModule` (libwebrtc default recorder, `VOICE_COMMUNICATION` audio source so the platform applies noise suppression / AGC as appropriate) ──▶ `"mic"` PC audio track.

### Why two PeerConnections (the libwebrtc constraint)

libwebrtc allows **one AudioDeviceModule per PeerConnectionFactory**, and every local audio track in that factory records through that single ADM — there is no second recording stream. Two independent sources therefore cannot share one PeerConnection, and mixing on Android is forbidden by the product rule.

Design (detailed in [ADR-003](../decisions/ADR-003-two-audio-track-architecture.md)):

- `"media"` PC on factory A with a **custom `AudioDeviceModule`** implementation that wraps an `AudioRecord` configured with `AudioPlaybackCaptureConfiguration` (game audio; video rides here too).
- `"mic"` PC on factory B with the standard `JavaAudioDeviceModule`.
- Both signaled over one WebSocket (`pc` discriminator, [webrtc.md](webrtc.md)).

**Validation spike (start of Phase 7, before committing):** prove on a real device that (a) the custom ADM produces audible game audio through a `media`-PC track, and (b) a second factory/PC streams mic simultaneously without ADM or audio-focus conflicts. Fallback options are listed in the ADR.

## Desktop side

```text
"media" PC ─▶ game-audio MediaStream ─▶ MediaStreamAudioSourceNode ─▶ GainNode ─┐
                                                                                ├─▶ AudioContext.destination
"mic"   PC ─▶ mic MediaStream     ─▶ MediaStreamAudioSourceNode ─▶ GainNode ─┘
```

- Independent volume per `GainNode`; persisted levels restored on launch.
- **No further processing** (no EQ, compression, echo cancellation on the receiver) unless a measured need appears. The desktop renders and plays; it does not re-mix into one track or re-encode.
- Chromium resamples each remote stream into the output clock; per-stream pull means there is no drift-accumulation problem between the two streams in practice (validated by ear + measurement in Phase 9).

## Known limitations

- **Opted-out apps are silent.** Honest UI on the phone, per above. There is no workaround — by platform design.
- **Acoustic echo when using the phone speaker:** if the phone plays game audio on its speaker *and* the mic is on, the mic picks up the game audio and the desktop hears it twice. v1 mitigation: recommend headphones in the app when both audio sources are enabled; a sender-side echo-control refinement (e.g., excluding known playback from the mic via APM constraints) is a measured, later decision.
- Capturing DRM-protected content is blocked by the platform.
- `AudioPlaybackCapture` needs Android 10+ — guaranteed by minSdk 29 ([ADR-001](../decisions/ADR-001-tech-stack.md)).
- Mic and game audio arrive as independent Opus streams; they are not sample-aligned at the receiver, which is acceptable — they are semantically different content, not a stereo pair.
