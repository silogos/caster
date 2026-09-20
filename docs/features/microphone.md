# Feature: Microphone

Implemented in: **Phase8** (see [roadmap](../development/roadmap.md)). Status: **implemented; JVM 58/58 + desktop53/53 green; verified live on device (2026-09-20)** — mic offers answered on their own pc, mic stream rendered at the receiver, on/off toggle cycles exercised, simultaneous operation with game audio observed. The remaining acceptance item is the user's **audible listen** (see *Verification*).

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

`MicState` (`audio/` module): `Off` (not in this cast), `Active`, `NeedsPermission` (RECORD_AUDIO denied), `Failed`. No silence detection — a quiet microphone is normal, unlike opted-out game audio. `MicControls` renders next to `GameAudioControls` on both screens while casting: the state as a plain fact, the on/off button, and the headphones tip when **both** audio sources are in this cast (the documented echo trap: the phone speaker + live mic [audio.md](../architecture/audio.md) — the mic picks the game audio up acoustically; AEC has no reference signal for playback capture, so the honest v1 mitigation is the hint).

`NeedsPermission`'s button asks for the grant from the activity (the service cannot show dialogs); granting turns the mic on immediately — denial keeps the honest fact, never an error.

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
