# Virtual Camera (Windows + macOS) — verification record

Feature: [ADR-007](../decisions/ADR-007-virtual-camera.md) · protocol: [webrtc.md](../architecture/webrtc.md) §Virtual camera control (proto 2) · architecture: [desktop.md](../architecture/desktop.md) §Virtual camera · issue #69. The desktop re-publishes the cast for other apps: **Windows** = system webcam (softcam via koffi), **macOS** = Syphon server (native helper — OBS/VJ reach). Toggled from the phone; output follows the cast's fps 1:1 (no cap); **no preview while active** — the receiver shows a placeholder and may be minimized.

## Native receiver, macOS: OBS Virtual Camera (ADR-039, 2026-11-22)

[ADR-039](../decisions/ADR-039-feeding-the-obs-virtual-camera.md) · design frames 25–26 (`designs/preview/desktop-25-obs-virtual-camera.png`, `desktop-26-obs-camera-states.png`) · architecture: [desktop-native.md](../architecture/desktop-native.md) §OBS Virtual Camera output. The native receiver feeds OBS Studio's own camera extension, so every camera app on the Mac sees the cast as "OBS Virtual Camera" while OBS is closed. It is a switch of its own under Syphon, and Syphon is unchanged. The Electron-era records below are kept as history; they don't count for the native receiver (verification-pass.md).

**Automated (MacBook Pro M1 Pro, macOS 26.2, OBS 32.1.1 installed with its camera allowed):**
- `cargo test --lib`: 133 passed (+5).
  - `obs_camera`: the UID is found in a fake OBS.app's extension bundle; an OBS without the key and a UID that is not a UUID are refused; the bridge's answers map to the switch's vocabulary.
  - **End to end through the bridge and OBS's real extension:** 30 frames at 60 fps, none refused, and the queue drained by the extension (skipped with a reason where OBS is missing).
  - `publisher` (moved from `syphon.rs`, 4 tests unchanged).
  - `audio`: the camera alone makes the sound play.
  - `settings`: an old file leaves the camera off.
- `node --test ui/window_state.test.mjs`: 20 passed (+6). Covered: the row in every state of frame 26 except "taken by OBS"; the Audio line; Syphon and the camera independent.
- **The window, in a browser with a stand-in for the Rust side:** off → on (casting / no cast) → failed event → a start that fails → not set up and no OBS (greyed out) → back on after a focus re-check. Each line matches frame 26.

**Measured while building it (the spike probe):**
- CMIO reports the device as running as soon as our sink alone is, so "is anyone watching" cannot be known (ADR-039 §5). Frames go in whenever the switch is on.
- With OBS running (started with `--startvirtualcam`), our frames kept draining from the queue: a full queue is **not** a sign that OBS took the camera. Frame 26's state 6 is not built. What a camera app shows when both write is a manual check below.

**On the device, 2026-11-22 (TB321FU → M1 Pro, cable, release build of `2895905`, OBS closed):**
- The product owner reported "All checks passed".
- The receiver's log shows one cast at 1920×1200 @ 60 fps with game audio on the speakers. The camera was turned on 4 s in and fed for about 95 s: 3600+ frames sent, **0 dropped**, send avg **0.37 ms** per frame.
- The same run went on: four casts in all with the camera left on. Each cast end logged "cast ended — OBS Virtual Camera shows OBS's own picture" and each next cast "cast live — feeding OBS Virtual Camera". What the camera app showed between casts isn't in the log.
- The last cast ran **2 h 31 min** (02:04:11 → 04:35:10) at 1920×1200, 54–63 fps: **499,732 frames sent, 0 dropped**, send avg 0.38 → 0.386 ms over the whole run, with no growth worth naming.
- Then the camera was switched off (04:35:32) and the receiver exited cleanly (exit 0), after the cast had ended. So this isn't a quit mid-cast.
- Still no trace of: a quit mid-cast, 120 fps (the log never shows more than 63 fps), a portrait cast (every cast was 1920×1200), and OBS starting its own camera.
- Seen in the same log and not attributed to the camera: the phone's frame rate dipped to 16–44 fps about every 5 s, in step with the link falling to ~2 Mbit/s. That is likely a still screen, and it should be compared with a run with the camera off.

**Manual (TB321FU → M1 Pro, OBS closed unless the line says otherwise):**
*Accepted 2026-11-22 by the product owner after the 2 h 31 min live run. The boxes left open below were waived for V1: they were not run, and they are not claimed.*
- [x] A live cast in camera apps, picking "OBS Virtual Camera" (product owner, 2026-11-22). Which apps were tried isn't recorded.
- [ ] 1920×1200 and a portrait cast are shown whole, not cropped to the 1920×1080 OBS's camera advertises.
- [ ] 60 and 120 fps. Receiver CPU with the camera on versus Syphon on, at the same settings (`camera` log line: send avg).
- [ ] The cast ends → OBS's own picture, not a frozen frame; the next cast → the picture comes back. *Log side done (sink stopped and restarted across four casts); what the camera app shows is still to be seen.*
- [ ] Video window closed, camera on: the call app still gets the picture, and the sound plays (Caster Audio as the call app's mic).
- [ ] Quit Caster mid-cast → camera apps show OBS's picture.
- [ ] OBS opened and its own Start Virtual Camera clicked while Caster feeds it: record what the camera app shows.
- [ ] OBS uninstalled (or renamed away), and a Mac where OBS's camera was never started: the switch is greyed out with the right line, and becomes clickable after the fix once the window comes to the front.
- [x] 30+ min session with the camera on: 2 h 31 min, 499,732 frames, 0 dropped, send time flat (receiver log, 2026-11-22).

## Automated (2026-10-30/31, macOS dev machine)

**Desktop — vitest 178/178 green, typecheck (node+web) green, `dist:win` + mac `.app` builds green:**

- `signalingVc.test.ts` (6 loopback tests over real sockets): proto-2 negotiation (and proto-1 ↔ old-phone), `vc-query/start/stop` relayed to the coordinator, `vc-status` pushed as query reply and on change, **proto gating** (a proto-1 phone's vc frame rejected recoverably; no vc-status ever sent to it), pre-auth rejection, phone→desktop `vc-status` rejected (desktop is the sender).
- `vcDims.test.ts`: 1080p cap, portrait cap-by-height, multiples of 4 (round-to-nearest), min 4, non-positive refused.
- `registration.test.ts` (injected runner): CLSID query shape, elevated-register command shape (`Start-Process -Verb RunAs`), UAC-declined (1223) = `declined`, installer-failure and registry-disagrees = `failed`, spawn-throw = `failed`.
- `virtualCamera.test.ts` (fakes for backend + registration): unavailable never registers; needsRegistration→UAC→active path; declined stays honest; dims capped at creation; no-stream-size refusal; frames forwarded / mismatched dropped / pre-start ignored; **bgra backend validates 4-byte frames** (the macOS path); **a dead backend tears down honestly** (the Syphon-helper case); vc-stop idempotent; cast-gone and dispose both delete the camera; re-entrant starts during registration collapse to one.
- `syphonPublisher.test.ts` (5, fake child): header+frame piping per sendFrame (`ZFRV` magic + dims), intentional close kills without a dead report, unexpected helper exit reports dead exactly once, off-darwin unsupported, no-op registration shape. The vendored artifacts' presence (`resources/syphon`) is pinned by the tests using the real directory.
- `framePump.test.ts` (fakes for source/canvas): 1:1 frame forwarding with close() discipline, scratch-buffer reuse (both 3- and 4-byte formats), stop drops in-flight frames (still closed), restart switches pairs, bounded context-loss recovery then honest `pump-failed`.
- `vcActiveView.test.ts`: placeholder copy states what is happening + minimize OK.
- Packaging verified in the built artifacts: Windows — `resources/softcam/{softcam.dll,softcam_installer.exe,LICENSE}` in both win builds, koffi unpacked, NSIS installer produced; macOS — `Resources/syphon/{syphon-helper,Syphon.framework,LICENSE}` inside the `.app`, and the packaged helper runs with its `@executable_path` rpath resolving the framework.

**Mobile — JVM 214/214 green, `assembleDebug` green:**

- `SignalingClientTest` (+2): `vc-status` parses into the event (malformed missing-field frame ignored), `sendVcQuery/Start/Stop` encode the right envelope types with empty payloads.
- `VcUiMappingTest` (new, 4): `vc-status` → UI state mapping (unavailable hides the card — even a lying `active`; needsRegistration → NeedsSetup; available → Ready+active) and the honest subline per state.
- `Handshake`: `PROTOCOL_MAX = 2` (old desktops negotiate 1 and the phone never shows the card).

## Live on macOS (2026-10-31, this machine)

- **The native half end-to-end, standalone**: `syphon-helper` fed a synthetic 64×32 30 fps stream publishes cleanly (clean EOF exit), and — after the **discovery-runloop fix** (ADR-007 addendum; the first cut read stdin in a blocking loop and the server was invisible to later-starting clients) — a `SyphonServerDirectory` probe lists **"Caster"** continuously while it publishes. The helper is universal (arm64+x86_64), built from source by `resources/syphon/build.sh` together with the Syphon framework.
- **Packaged app**: the `.app` carries the helper + framework; the packaged binary runs.

## Manual — PENDING

**macOS (needs the phone in hand — everything else is ready on this machine):**

- [ ] Pair + cast → the phone shows the "Virtual camera" card (Mac reports available, no setup state) → toggle on → receiver swaps to the placeholder → **OBS (Syphon Client source)** lists "Caster" and shows the feed; orientation correct (flip check), letterbox on rotation.
- [ ] **No-signal stage (ADR-007 addendum)**: disconnect from the phone mid-VC → OBS shows the **"No input video" still** (server stays listed, no frozen frame); cast again → frames resume; toggle off (desktop Settings row, the placeholder's Turn-off, or the phone) → the server disappears from OBS instantly.
- [ ] **Desktop toggle**: Settings (also reachable from the No-input stage) → "Virtual camera" row toggles the same state as the phone's card.
- [ ] **Always-on (batch B)**: Settings → "Start with every cast" → each new cast brings the camera up by itself (no toggle); between casts it idles on the no-input still — capture-card behavior. Windows too (softcam flag flipped; verify the still in the OS camera list on the rig).
- [ ] Minimize the receiver mid-VC — OBS keeps receiving.
- [ ] Numbers: pump fps + ms/frame + CPU % at 60 fps (and 120 if the phone's display allows).
- [ ] If OBS 32.x shows a blank source: re-check with Syphon Recorder (upstream OBS 32.0 Syphon regression — see ADR-007 addendum) before blaming the server.

**Windows x64 (needs a rig — VM Windows 11 ARM with the x64 build is the workable path from this Mac; cloud/RDP cannot work, the product is LAN-only):**

- [ ] First use: toggle on the phone → UAC prompt on the desktop → accept → camera appears; declined → honest needs-setup state; retry works.
- [ ] OBS (and one call app, e.g. Zoom/Teams) list the camera ("Softcam") and show the feed; aspect correct.
- [ ] 60 fps game cast → smooth camera output (and 120 if the rig supports it); numbers recorded.
- [ ] Rotation portrait↔landscape mid-VC → letterboxed correctly inside the fixed camera dims.
- [ ] Adaptive quality steps mid-VC → feed keeps flowing.
- [ ] Every teardown path removes the camera cleanly (stop cast / Disconnect / quit; OBS doesn't hang).
- [ ] Mid-cast reconnect → camera stops; phone re-query shows inactive; re-toggle works.

**Linux (adapter shipped — issue #73; needs a Linux rig with PipeWire + GStreamer):**

- [ ] `gst-launch-1.0` present → the phone's card shows available; absent → honestly unavailable (install guidance in the log).
- [ ] Firefox (camera portal) sees the Electron receiver's camera and shows the feed; aspect correct. OBS: check its current PipeWire-camera transition state and record honestly.
- [ ] 60 fps cast → smooth node output; the declared-caps 60/1 vs real-rate interplay is the knob if pacing looks wrong (`DECLARED_FRAMERATE`, `vc/pipewirePublisher.ts`).
- [ ] No-signal idle, minimize, rotation letterbox, every teardown path — the same matrix as above.
- [ ] Raw `/dev/video*` consumers: confirm they do **not** see it (the recorded reach limitation).

## Known limitations (recorded in ADR-007)

Windows device name is the DLL's built-in **"Softcam"**; Windows ARM64 reports unavailable (no arm64 softcam build); macOS reaches **OBS/VJ apps only** (call apps wait on the CMIO entitlement door); audio routing for call consumers is v1-out-of-scope (video-only); closing the receiver window hides it since [ADR-010](../decisions/ADR-010-desktop-tray-lifecycle.md) — the camera (like the cast) keeps running from the tray.
