# Feature: Internal / Game Audio

Implemented in: **Phase7** (see [roadmap](../development/roadmap.md)). Status: **implemented; JVM + desktop tests green; verified live on device (2026-09-20)** for pairing, honest opt-out detection and UI controls — the final *audible* listen of the debug test tone on the fixed desktop build is the one remaining acceptance item (see *Verification*).

## What is implemented

### Game audio rides the `media` PC (ADR-003)

`PlaybackCaptureAudioSource` (`audio/` module) builds the media factory's ADM and substitutes an `AudioRecord` configured with `AudioPlaybackCaptureConfiguration(mediaProjection)` — the same projection instance the screen capturer owns (one consent = one projection; `ScreenCapturerAndroid.getMediaProjection()`). The audio track joins the same PC and stream as the video (`zfc-media`), negotiated by the unchanged offer/answer flow; the desktop needs **no new logic** — the stream's audio plays through its `<video>` element (Phase 9 adds Web Audio per-stream volume).

### The ADM substitution (the spike's central finding)

ADR-003 assumed a "custom `AudioDeviceModule` implementation whose recorder wraps an AudioRecord configured with AudioPlaybackCaptureConfiguration". The spike (bytecode inspection of the pinned `stream-webrtc-android` **1.3.8** — latest published is 1.3.10) proved there is **no public PCM-injection API**:

- `WebRtcAudioRecord` (package-private) creates its mic record in a **private** method — no subclass/override point;
- the fork's `AudioRecordDataCallback` builder option is dead code — never wired anywhere;
- the only native ADM entry point still routes through `WebRtcAudioRecord`.

So the ADM is the stock `JavaAudioDeviceModule`, and its mic record is **substituted at recording start**: `onWebRtcAudioRecordStart()` runs on the ADM's recording thread *before its first read* — the swap is deterministic, not racy. The substitute mirrors the ADM-negotiated format (48 kHz stereo PCM16, `useStereoInput(true)`), sized to the ADM's own read buffer. The platform requires RECORD_AUDIO and a working mic record before any of this exists, so the mic record is stopped and released immediately at swap time — **no microphone sample is ever read, encoded or sent**. All reflection is lazy and defensive: if the library's internals ever change, the cast degrades to video-only with an honest `Failed` state (found live: the first build used `getField()` against a field that is private in 1.3.8 — `NoSuchFieldException` at class-init killed the cast; fixed with `getDeclaredField()` + lazy lookup).

Hardware/platform effects are refused on captured playback (they would only distort finished audio): `useHardwareAcousticEchoCanceler(false)`, `useHardwareNoiseSuppressor(false)`, plus `googEchoCancellation/AutoGainControl/NoiseSuppression=false` constraints on the audio source. Whether all of the APM actually stands down is an unmeasured quality question — flagged for Phase15's audio matrix.

### The platform's opt-out default — the big product finding

**Apps targeting Android 10+ (API29+) are NOT capturable by default.** Only apps that explicitly opt in with `android:allowAudioPlaybackCapture="true"` (or target API ≤28) can be captured. In practice on the test device:

- **YouTube, Netflix, Spotify, most games**: opted out → capture yields *digital silence, not an error* — exactly the case `audio.md` anticipated. The phone honestly shows **"This app's audio can't be captured."**
- **This app itself**: opted in via the manifest flag — which is what makes the debug test tone (below) a capturable source for verifying the pipeline.

Product consequence (worth a UI affordance in Phase 13): the app cannot promise "hear your game" for arbitrary games — it can only capture apps whose developers allowed it. The docs' earlier assumption ("many music/DRM/streaming apps and some games opt out") was backwards in emphasis: **most** modern apps opt out; the capturable ones are the exception.

### Silent-input detection + UI state

`GameAudioMonitor` (pure JVM, unit-tested) turns raw facts into the UI state, per [audio.md](../architecture/audio.md):

- **5 s of sustained digital zero**, while the `media` PC is *connected* and the user has *not* muted → `Silent` ("This app's audio can't be captured");
- one audible frame → back to `Active` — it recovers, never escalates to an error;
- user mute → `Muted`; capture could not start/died → `Failed` ("Game audio isn't available for this cast"); not part of this cast (setting off or RECORD_AUDIO denied) → `Off` ("Game audio is off for this cast.").

Both screens render the state + controls while casting (`GameAudioControls`), alongside `CastState` as in Phase 6.

### Mute / unmute

Sender-side, no renegotiation: `JavaAudioDeviceModule.setMicrophoneMute()` zeroes the ADM's frames, so the Opus stream keeps flowing as silence — the video track and the connection are untouched. The toggle lives on both screens (`CastService.requestToggleGameAudio` → `ACTION_TOGGLE_GAME_AUDIO` intent → session → ADM); the monitor knows mute is intentional and suppresses the opted-out hint while muted.

### Permission flow

The platform requires **RECORD_AUDIO** even though no microphone sample is ever captured (needed both to build the playback-capture record and because the ADM starts life as a mic recorder). It is requested at cast start (before the projection consent), non-fatally: on denial the cast runs video-only with an honest `Off` state — never a blocking error.

## Debug aids (debug builds only — this ROM needs them)

The test tablet's ROM (Lenovo/ZUI, Android 16) **silently discards all app-originated logcat output** (`persist.log.tag.aplog.mainlog=false`), which made the live failure undiagnosable. Debug builds therefore carry two aids (both `BuildConfig.DEBUG`-gated, aligned with the debug manual-payload precedent from Phase 3):

- **`[debug: …]` suffix on cast-failure messages** — the technical failure detail on screen, release builds keep the simple message;
- **"Debug: play test tone"** — a loud continuous DTMF tone (STREAM_MUSIC → USAGE_MEDIA, inside the capture filter) from this app itself, the one source we control that is guaranteed capturable.

## Verification (2026-09-20)

**Tests:** mobile JVM **55/55** (9 new: `GameAudioMonitor` silence/mute/streaming/failure transitions + `PcmSilence`). Desktop typecheck green, **48/48** (its only change is the ontrack log fix + the unmuted element).

**Live on device (Lenovo TB321FU / Android 16 → macOS, same Wi-Fi) — observed:**

- Full path to a cast **with the audio track in the offer**: camera scan → handshake → consent → session-info (gameAudio on) → desktop answered the media PC → cast streams (desktop log confirms offer/answer each run).
- **Honest opt-out detection**: with YouTube playing, the phone showed "This app's audio can't be captured" — verified correct against the platform's opt-out default, not a pipeline failure (the detector recovered state when frames were audible from other sources).
- **Three real bugs found live and fixed in this phase**: (1) the reflection `getField`/1.3.8-private-field crash above (surfaced via the new debug error detail — impossible to see otherwise, the ROM suppresses logcat); (2) the desktop's `<video>` element was hardcoded **`muted`** — a Phase 5 autoplay leftover that silently killed all cast audio; un-muted, with `videoEl.muted = false` belt-and-braces; (3) the scan screen was a navigation dead end (no back handler; `rememberSaveable` restored it forever) — back now returns to home through a stable `OnBackPressedCallback`, and both screens render the game-audio controls.
- **Mute/unmute wiring**: the toggle renders on both screens and routes to the ADM (its audible effect rides the same remaining listen as below).

**Remaining acceptance item — one listen:** the debug test tone end-to-end (phone speaker source → capture → Opus → Mac speakers) on the *fixed* desktop build. The pre-fix runs were silent on the Mac for the `muted`-element reason above; the final post-fix cast was running when review was requested, but no explicit "tone audible on the Mac" confirmation was recorded. Mute/unmute audibility likewise. Everything short of audible confirmation (capture active, track negotiated, stream connected, state machine correct) is verified.

Also pending (Phase 15 matrix): opted-out vs opted-in app catalogue on a real game, DRM app behavior, stereo/soundstage quality through the APM, behavior when RECORD_AUDIO is denied at runtime (currently reasoned, not device-observed).
