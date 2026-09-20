# Audio Architecture

Status: game audio **implemented in Phase7** and mic **in Phase 8** (findings: [features/game-audio.md](../features/game-audio.md), [features/microphone.md](../features/microphone.md)); desktop mixer lands in Phase 9.

## Product rule

> Game audio and microphone are **separate logical streams, end to end**. They are never mixed on Android.

The desktop must be able to control their volumes independently (Phase 9's two `GainNode`s), and muting one must not touch the other.

## Android side

### Game audio — `AudioPlaybackCapture` (API 29+)

```text
Game / other apps
   ↓ (audio played with capturable usage by an app that allows capture)
AudioRecord built with AudioPlaybackCaptureConfiguration(mediaProjection)
   ↓ 48 kHz stereo PCM16
media factory ADM (stock JavaAudioDeviceModule, mic record substituted at start) ──▶ "media" PC audio track ──▶ desktop
```

Facts that shape the implementation (all verified live in Phase 7 — [features/game-audio.md](../features/game-audio.md)):

- Requires a live `MediaProjection` token — audio reuses the screen capturer's projection instance (one consent = one projection; this is one reason screen + game audio share the `media` PeerConnection).
- **Capture policy is per-app and opt-out is the default.** Apps targeting Android 10+ (API 29+) are **not capturable unless they explicitly set `android:allowAudioPlaybackCapture="true"`** (only apps targeting API ≤28 are capturable by default). YouTube, Netflix, Spotify and — critically — most modern *games* are opted out. Capturing an opted-out app produces **silence, not an error**. The product can honestly capture only apps that allow it; this deserves a UX affordance in Phase 13.
- The capture config filters by audio usage: we accept `USAGE_MEDIA`, `USAGE_GAME`, `USAGE_UNKNOWN`. Silent-input detection (sustained zero samples while the desktop is connected) drives the UI state "This app's audio can't be captured", never a fabricated error.
- The app must hold **RECORD_AUDIO** even though no microphone sample is ever captured — the platform requires it to build the playback-capture record, and the WebRTC ADM cannot start without a mic record (see the substitution below). Requested non-fatally before a cast; denial → video-only cast.
- **ADM substitution (Phase 7 implementation reality):** libwebrtc's public Java API has no PCM-injection point (`WebRtcAudioRecord` is package-private with a private record factory; the fork's `AudioRecordDataCallback` is dead code). The media factory therefore uses the stock `JavaAudioDeviceModule`, whose microphone `AudioRecord` is **substituted at recording start** with the playback-capture record — deterministic, because the ADM's start callback runs on its recording thread before the first read. The replaced mic record is stopped and released immediately. Reflection is lazy and defensive: a library that changes internals degrades the cast to video-only, never crashes it. Full rationale: [ADR-003 addendum](../decisions/ADR-003-two-audio-track-architecture.md).

### Microphone — standard capture

`JavaAudioDeviceModule` (libwebrtc default recorder, `VOICE_COMMUNICATION` audio source so the platform applies noise suppression / AGC as appropriate) ──▶ `"mic"` PC audio track.

Implemented in Phase 8: the mic is **off by default** and turned on/off by a live toggle during the cast (`MicCastSession`, built and torn down on demand — the `media` PC is never renegotiated because of it; every mic failure is mic-local, the cast keeps running). Verification record: [features/microphone.md](../features/microphone.md).

### Why two PeerConnections (the libwebrtc constraint)

libwebrtc allows **one AudioDeviceModule per PeerConnectionFactory**, and every local audio track in that factory records through that single ADM — there is no second recording stream. Two independent sources therefore cannot share one PeerConnection, and mixing on Android is forbidden by the product rule.

Design (detailed in [ADR-003](../decisions/ADR-003-two-audio-track-architecture.md)):

- `"media"` PC on factory A: screen video + game audio — factory A's ADM is the stock `JavaAudioDeviceModule` with its mic record substituted for playback capture at recording start (Phase 7 implementation, addendum in the ADR).
- `"mic"` PC on factory B with the standard `JavaAudioDeviceModule` (Phase 8).
- Both signaled over one WebSocket (`pc` discriminator, [webrtc.md](webrtc.md)).

**Validation spike (Phase 7 result):** the spike proved audible-capture *possible* on a real device with a capturable source, and simultaneously disproved the original "custom ADM" wording — there is no public PCM-injection API in the pinned prebuilt (see the substitution above). Fallback options were not needed beyond the substitution; the ADR records the addendum.

## Desktop side

```text
"media" PC ─▶ game-audio MediaStream ─▶ MediaStreamAudioSourceNode ─▶ GainNode ─┐
                                                                                ├─▶ AudioContext.destination
"mic"   PC ─▶ mic MediaStream     ─▶ MediaStreamAudioSourceNode ─▶ GainNode ─┘
```

Until Phase9 wires this graph, the game-audio track plays directly through the receiver `<video>` element (Phase7 verified its markup must **not** be muted — a Phase5 autoplay leftover silently ate all cast audio until found live), and the mic track plays through its own separate `<audio>` element (Phase8) — separate elements on purpose, so the two streams stay independent at the receiver too.

- Independent volume per `GainNode`; persisted levels restored on launch.
- **No further processing** (no EQ, compression, echo cancellation on the receiver) unless a measured need appears. The desktop renders and plays; it does not re-mix into one track or re-encode.
- Chromium resamples each remote stream into the output clock; per-stream pull means there is no drift-accumulation problem between the two streams in practice (validated by ear + measurement in Phase 9).

## Known limitations

- **Opted-out apps are silent.** Honest UI on the phone, per above. There is no workaround — by platform design.
- **Acoustic echo when using the phone speaker:** if the phone plays game audio on its speaker *and* the mic is on, the mic picks up the game audio and the desktop hears it twice. v1 mitigation: recommend headphones in the app when both audio sources are enabled; a sender-side echo-control refinement (e.g., excluding known playback from the mic via APM constraints) is a measured, later decision.
- Capturing DRM-protected content is blocked by the platform.
- `AudioPlaybackCapture` needs Android 10+ — guaranteed by minSdk 29 ([ADR-001](../decisions/ADR-001-tech-stack.md)).
- Mic and game audio arrive as independent Opus streams; they are not sample-aligned at the receiver, which is acceptable — they are semantically different content, not a stereo pair.
