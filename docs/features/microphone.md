# Feature: Microphone

Implemented in: **Phase8** (see [roadmap](../development/roadmap.md)). Status: **implemented; JVM 138/138 green; verified live on device (2026-09-20)** — mic offers answered on their own pc, mic stream rendered at the receiver, on/off toggle cycles exercised, simultaneous operation with game audio observed. **2026-09-23 fix ([ADR-004](../decisions/ADR-004-mic-capture-coexistence.md)):** the cast mic no longer silences the casted game's voice chat (PUBG) — see *Concurrent-capture coexistence*; the fix's device matrix below is **pending**. The remaining Phase 8 acceptance item is the user's **audible listen** (see *Verification*).

## What is implemented

### The `mic` PC (factory B, ADR-003 as written)

`MicCastSession` (`webrtc/` module) owns the second PeerConnection: microphone only, on its own `PeerConnectionFactory` with the **stock `JavaAudioDeviceModule`** — voice defaults (`VOICE_COMMUNICATION`, mono, platform AEC/NS/AGC on) → Opus mono ([audio.md](../architecture/audio.md)). Negotiated over the same WebSocket with the `pc: "mic"` discriminator; no protocol changes — signaling already carried the discriminator since Phase 4.

The mic is **off by default** and turned on/off by a **live toggle during the cast**:

- **On** = the service builds the mic session on demand (`CastService.toggleMic` → new `MicCastSession`), which negotiates the mic pc from scratch. The `media` pc — screen + game audio — is never renegotiated, so the cast is untouched by mic churn. This independence is the point of the two-pc design.
- **Off** = the mic session tears down (pc, track, source, factory, thread — all disposed; toggle spam safe).
- A new `session-info` (display-only) follows every toggle so the desktop status line stays truthful; the media session's own summary is corrected at first `STREAMING`, when the config-plus-permission mic decision is settled.

### Failures are mic-local

Every mic failure (record init/start error, `createPeerConnection` null, ICE `FAILED`, signaling `bye`/terminal) ends **only the mic session**: the UI shows `MicState.Failed` ("Microphone isn't available for this cast.") and the cast keeps running. The reverse is also true — the mic session never makes cast-level decisions; cast-fatal events belong to `MediaCastSession` ([mobile.md](../architecture/mobile.md) lifecycle).

### State + UI

`MicState` (`audio/` module): `Off` (not in this cast), `Active`, `Silenced` (the platform muted our capture under the concurrent-capture policy — [ADR-004](../decisions/ADR-004-mic-capture-coexistence.md); recovers by itself), `NeedsPermission` (RECORD_AUDIO denied), `Failed`. A quiet microphone is still normal and never surfaced — `Silenced` is about the platform's arbitration, not the room's volume. `MicControls` renders next to `GameAudioControls` on both screens while casting: the state as a plain fact, the on/off button, and the headphones tip when **both** audio sources are in this cast (the documented echo trap: the phone speaker + live mic [audio.md](../architecture/audio.md) — the mic picks the game audio up acoustically; AEC has no reference signal for playback capture, so the honest v1 mitigation is the hint).

`NeedsPermission`'s button asks for the grant from the activity (the service cannot show dialogs); granting turns the mic on immediately — denial keeps the honest fact, never an error.

### Concurrent-capture coexistence (ADR-004, fix of 2026-09-23)

Found live: with a cast running, turning the cast mic on **muted the microphone of the game being cast** (PUBG voice chat); the game's mic returned the moment the cast mic went off. Streaming apps on the same device did not cause it (TikTok + PUBG coexist). Root cause (platform rule, "Sharing audio input"): the stock ADM's `VOICE_COMMUNICATION` source is **privacy-sensitive by default**, and a privacy-sensitive capture wins the concurrent-capture arbitration while every other recording is silenced.

The fix (factory B): keep the voice source — its platform AEC/NS/AGC is tied to it and wanted for viewers — but swap the ADM's mic `AudioRecord` at recording start for a twin with `setPrivacySensitive(false)` (`audio/MicRecordSubstituter`; the fork's builder exposes no privacy-sensitive API, hence the same reflection substitution factory A uses). Any substitution failure degrades to the pre-fix stock record — logged, never a mic failure. The reverse case (another app's recording wins and the *platform* silences ours) is surfaced by `MicSilenceMonitor` + `AudioRecordingCallback` as the plain-words `MicState.Silenced` (new state + string) and recovers automatically; "ours" is matched by audio source because the uid-level accessors are system APIs.

A side claim that Android 16 introduced *new* compatibility rules here could not be found in the official behavior-change pages (recorded as unverified in ADR-004) — the arbitration rule predates Android 16.

**Pending device matrix (this fix's acceptance):**

1. Cast + game audio + mic ON while PUBG voice chat is active → the game's mic reaches its teammates **and** the cast mic is audible at the desktop (the real acceptance).
2. Regression: cast + mic OFF → the game's mic unaffected.
3. Regression: mic toggle cycle mid-cast + backgrounded with the `microphone` FGS type — unchanged behavior.
4. Reverse direction: cast mic ON first, the game's voice chat started after — if the platform silences our capture, the UI shows `mic_silenced` (plain words) and recovers when the game stops recording.
5. Echo sanity: speaker vs headset (the known limitation below stands).

Fallback if 1 fails on device: one builder call — `setAudioSource(MIC)` (non-sensitive by default) — re-test, and ADR-004 records the outcome. Note: this ROM discards app logcat (Phase 7 constraint), so the monitor's diagnostics are for other devices/emulator; on-device verification of this fix rides on the game's own mic indicator and the desktop listen.

**Device round 2 (same day, live-found):** the arbitration fix worked — the game's voice chat receives the mic — but concurrent capture surfaced a second platform behavior: the platform routed our record to `AUDIO_DEVICE_IN_BACK_MIC` (the game kept `BUILTIN_MIC`) and the shared-path rate churn turned the desktop stream into delay → **chipmunk** (our 48 kHz record receiving the game's 16 kHz path data, 3× pitch-up; a mic retoggle only helped once the game's mic was off). Fix: factory B input rate **16 kHz** (`SHARED_VOICE_INPUT_RATE_HZ`) + the twin explicitly routed to the built-in mic (`routeToBuiltinMic`, `setPreferredDevice`) — the same device+rate configuration the game's capture runs. Tradeoff: mic = 16 kHz mono wideband voice instead of 48 kHz (duller for viewers, accepted). Full evidence + rationale: [ADR-004 addendum](../decisions/ADR-004-mic-capture-coexistence.md). Re-test matrix: items 1–4 above, plus **no chipmunk/delay while the game's voice chat is on** and **cast mic reads the built-in mic** (verify via dumpsys `Input device: AUDIO_DEVICE_IN_BUILTIN_MIC`).



### Backgrounded operation — the `microphone` FGS type (found live in the Phase9 listen)

The cast FGS started as type `mediaProjection` only (Phase6), which covers screen and game-audio capture while the app is backgrounded — but **Android 11+ allows a backgrounded app's microphone only through a `microphone`-typed foreground service**: with the mic on, backgrounding the app (running a game, say) silenced the mic on the desktop while screen + game audio kept streaming. Fix: `CastForegroundTypes` computes the service's type set — `mediaProjection` always, `microphone` added at mic-on and removed at mic-off (a re-`startForeground` with the same notification); the bit joins only with RECORD_AUDIO granted (Android14+ refuses a microphone-typed start without it) and only from API30, where the type exists. Manifest: `foregroundServiceType="mediaProjection|microphone"` + the `FOREGROUND_SERVICE_MICROPHONE` permission. JVM tests cover the type-set computation (62 total).

### Permission flow

RECORD_AUDIO was already requested non-fatally before every cast (Phase7 needs it for playback capture). Phase 8 reuses it: granted → both audio paths available; denied → game audio off **and** mic `NeedsPermission`, cast otherwise unaffected. Denial observed live in Phase 7's reasoning; the runtime-denial device case is listed for Phase 15's matrix.

### Desktop

`ReceiverSession` now keeps one answerer per pc id (`Map<PcId, ActivePc>`): media offers and mic offers each replace only their own pc; ICE routes per pc; the media stream (screen + game audio) renders to the `<video>`, the mic stream to a new hidden unmuted `<audio>` element — **not** through the video element, keeping the two audio tracks separate products at the receiver (the Web Audio mixer with per-stream volume is Phase 9). A mic pc `closed`/`failed` clears only the audio sink — that is exactly what "mic toggled off mid-cast" looks like from the desktop; `handleMobileGone` still tears everything down. Video stats polling stays on the media pc; the mic pc logs connection transitions only.

## Debug aid

`scripts/measure-mic-level.mjs` (desktop, CDP like `read-qr-payload.mjs`): measures the peak sample level of the mic stream at the receiver — an ear-free audibility check for Phase 15's audio matrix. Run against `--remote-debugging-port=9222`.

## Verification (2026-09-20)

**Tests:** mobile JVM **58/58** (3 new: `SenderStats.sampleAudioSend` extraction/degradation — the mic pc's ~1 Hz bytes-sent log proves the stream is flowing). Desktop typecheck green, vitest **53/53** (per-pc answering, mic ICE routing, per-pc sink routing, mic-off clears only the audio sink, fresh mic offer leaves the media pc untouched, no video-stats on the mic pc). `assembleDebug` and the desktop production build both green.

**Live on device (Lenovo TB321FU / Android16 → macOS, same Wi-Fi),** observed in the desktop's logs (this ROM discards app logcat — Phase 7's constraint) and via CDP:

- **Mic offers answered on the new build** — twice, i.e. a full off→on toggle cycle mid-cast (`sdp answer sent (pc=mic)` ×2; the Phase 7 build would have logged "ignoring sdp-offer … audio arrives in a later phase").
- **Mic stream rendered to the audio sink**: `audio track arrived (pc=mic) — rendering`, and the receiver's `<audio>` element measured a live `MediaStream` (`srcObject` non-null via CDP).
- **Mic pc connected independently** (`connection connected (pc=mic)`) while the media pc's video stats kept flowing — simultaneous game audio + mic on two pcs, the acceptance item for coexistence.
- **Game audio loud on the media pc at the same time** (receiver peak ≈ 0.46 measured via Web Audio on the video element) — both audio paths alive simultaneously.
- **The new UI rendered on the phone during the cast** (uiautomator dump): "Microphone is off for this cast." + "Turn on microphone" next to the game-audio controls, on the scan screen while casting.
- **Mic toggle-off reached the receiver**: the mic stream cleared at the desktop when the mic was turned off.

**Remaining acceptance item — the audible listen:** the room was quiet during the measurement window, so the mic stream's peak at the receiver was ≈ 0.00003 (a live but silent stream — nobody spoke). A planned loud-sample test (macOS `say` through the Mac's speakers into the phone's mic) raced the mic being toggled off. So: everything short of *heard* audio is verified; the user's listen of a spoken word on the desktop speakers closes the acceptance, exactly like Phase 7's test tone.

**Also pending (Phase 15 matrix):** runtime RECORD_AUDIO denial on device, mic quality through the APM (noise suppression strength), behavior of a mic pc when Wi-Fi flaps mid-toggle (the reconnect ladder itself was observed re-authing cleanly three times when the phone's Wi-Fi grew unstable at the end of the session), and the formal backgrounded-mic verification on the fixed build (the `microphone` FGS type above — the silencing was found live; the fixed behavior still needs a backgrounded listen).

## Verification (2026-09-23 — the ADR-004 coexistence fix, code-level)

**Tests:** mobile JVM **138/138** (6 new: `MicSilenceMonitorTest` — another app's loss doesn't touch us, our source silenced → `Silenced` with the metadata log line, automatic recovery, the playback-capture record excluded from the decision, idempotent config repeats). `assembleDebug` green.

**Device matrix:** pending — see the checklist under *Concurrent-capture coexistence* above; needs the phone in hand (PUBG voice chat + the desktop listen), exactly like Phase 14's OBS acceptance.
