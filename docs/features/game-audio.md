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

**Stereo found not to reach the desktop (2026-11-03, user-reported "echoey vs the phone's own output"):** the capture was stereo but the *negotiated* Opus was mono ~32 kbps — `setUseStereoInput` shapes only the record, and without `stereo=1` in the fmtp the encoder downmixes L+R (comb filtering on widened game mixes) and 32 kbps pre-echoes. Fixed by fmtp munging (`SdpOpusFmtp` — `stereo=1; sprop-stereo=1; maxaveragebitrate=192000`) on **both** descriptions: the offer asks, and — because the first on-device run showed Chromium's answer carries only its own default fmtp (`minptime=10;useinbandfec=1`, no mirroring) — the answer is relaxed to the same request before `setRemoteDescription` (details in [architecture/audio.md](../architecture/audio.md)). The same listen that settles the pending item above also settles this: cast a stereo-rich source and compare against the phone's speakers — the `answer opus fmtp` / `negotiated opus fmtp` log pair is the non-audible half of the check.

**Follow-up found live the same day — the test ROM's tap itself is one-channel:** even with stereo negotiated and the encoder listening, per-second PCM aggregates (`capture stereo content`) showed the platform delivering L-only, R-only, or L=R — occasionally flipping to true stereo minutes in, with the dead side shuffling on every capture-record rebuild (bounded self-heal shipped: `MonoTapRecovery`). Index-mask records are rejected by the ROM, and the privileged `AudioPolicy` sink scrcpy uses is hidden-API-blocked for normal apps (evidenced in logcat). Full record in [architecture/audio.md](../architecture/audio.md) §Known limitations: on this ROM cast audio is mono at the tap; a device with a healthy tap gets true stereo end to end.

**AAC payload on the `game-audio` DataChannel (2026-11-04, ADR-013):** the channel's default payload is now **ADTS-framed AAC-LC 192 kbps CBR stereo 48 kHz** — the codec + framing + production config imported from TikTok's cast ([audio-importable.md](../../../tiktok-cast-teardown/audio-importable.md); the transport stays WebRTC, per the ADR). The wire opens as ADR-012's raw PCM and switches to AAC only after the desktop acks the in-band announce — an old desktop (ignores text messages) or a missing WebCodecs `AudioDecoder` degrades to PCM exactly as before, and the 3 s quiet fallback to the dual-mono track is untouched. An encoder failure reverts to PCM mid-cast (announced, so the desktop follows immediately). The desktop synthesizes the ASC (`0x11 0x90`) receiver-side and timestamps decoded frames from the sample clock (the teardown's `getJitterFreePTS` insight). The stats line gained a `gameChannel` field — the SCTP channel is getStats-invisible, so its own counters (wire bitrate, format, decode health) are the only channel visibility.

**Verification (unit level, this change):** mobile JVM **304/304** (17 new: `AdtsFramerTest` — the ADTS header byte-identical to the teardown's `addADTStoPacket` including the 13-bit length across boundary values — and `GameAudioFormatNegotiatorTest` — ack switch, old-desktop timeout, late ack, encoder-failure revert, reopen). Desktop typecheck green, **309/309** (17 new: `gameAdtsAac` — parser fixtures, handshake compat matrix, sample-clock timestamps, backpressure drop; `dataChannelStats` — interval bitrate, format-switch baseline re-arm, counter-reset null; mixer `pushFrames` — same worklet message shape, fallback liveness). Also fixed here: a re-auth `negotiate()` used to overwrite `gameAudioChannel` without closing the previous instance — the abandoned sender (now sender + encoder) thread kept spinning on the dead pc until teardown.

**Remaining acceptance item — one PUBG listen:** AAC end-to-end audibility vs the PCM build (true stereo positionality intact, the ~40–50 ms codec latency unnoticeable for gameplay), plus the stats line showing the game channel at ~0.2 Mbps (down from ~1.5). The fallback chain exercises (kill the channel → dual-mono; deny the ack → PCM) are reasoned and unit-covered but not yet device-observed.

**Found live on the first AAC cast (2026-11-04, Lenovo TB321FU → macOS): the desktop heard dual-mono.** The forensic ladder settled it cleanly: the `localcast_capture_dump` WAV proved the **encoder input was true stereo** (113/120 s classified `true-stereo`, L/R rms clearly apart — confirmed by ear through the Mac speakers), so the loss was downstream. An isolated Electron decode harness (real `afconvert` AAC-LC ADTS from the dump → the real `gameAdtsAac` path → real WebCodecs `AudioDecoder`) reproduced it exactly: **every frame rejected** — `AudioDecoder.decode()` requires a real `EncodedAudioChunk` instance and threw `TypeError` on the plain init object; the unit fakes had accepted the init object, which is exactly the fake-tests-mirror-the-implementation trap AGENTS.md warns about. Fixed (`makeChunk` in `gameAdtsAac.ts`): Chromium constructs a real `EncodedAudioChunk`; Node tests pass a `{type, timestamp, byteLength}` record their fakes consume. Harness post-fix: **1876/1876 frames rendered, 0 decode errors, plane correlation 0.68** (stereo through the full decode+conversion path). The dual-mono the user heard was the automatic 3 s quiet fallback doing its job over a starved worklet — the degradation ladder worked as designed, hiding a decode bug the stats counters then exposed.

**Verified live after the fix (2026-11-04, same device/LAN): true stereo end to end over AAC.** User-confirmed ear test through the desktop app: left/right separation present on the stereo test source with the AAC path engaged — the first ADR-013 acceptance listen. Still pending: the PUBG gameplay listen (positional audio + the ~40–50 ms codec latency under real game load), the fallback exercises on hardware, and a second device's AAC encoder sanity.

**Low-latency toggle — raw PCM16 on the channel (2026-11-07, Feature 2026-11d):** the Advanced settings card gains **"Low-latency game audio"** — on keeps the channel on ADR-012's raw PCM16 instead of negotiating the AAC path, mechanically the *proven* forced-PCM state (`selfSupportsAac=false`: the announce says `pcm16`, `onAck` can never switch, `sendsAac` stays false — the same state an old desktop already forces), plus `sender.start()` skipped so the encoder is never built (`onSamples` is null-safe on it and keeps feeding raw PCM). Rationale (the measured ear-path budget, performance.md): AAC's ~40–50 ms codec latency leaves the path (~26–41 ms vs ~76–91 ms), paid in wire bitrate (~1.5 vs ~0.2 Mbps — trivial on a LAN). Off stays the default (ADR-013's fidelity stands); the switch is not preset-owned, like the other audio choices; settings codec v5 → v6 with the off default (the `autoQuality` v2→v3 precedent). The mobile is the configuration owner — the desktop needs nothing.

**Verification (unit level, this change):** mobile JVM **307/307** (3 new: the low-latency choice round-trips; a v5 document decodes with the low-latency default off — the ladder's newest rung; the viewmodel toggle persists and survives preset picks). `assembleDebug` green.

**Remaining acceptance item — one measured cast:** toggle on → the stats line shows `gameChannel mode pcm16` at ~1.5 Mbps (announce/ack logs confirm the forced-PCM path on hardware); toggle off → `mode adts-aac-192` at ~0.2. The ear-latency delta (the whole point) reads from the desktop's new `ctxBaseLatencyMs`/`ctxOutputLatencyMs` + ring-depth telemetry per the [performance.md](performance.md) instrumentation — PCM vs AAC before/after is the number the next latency decision cites.
