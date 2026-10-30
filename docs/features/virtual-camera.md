# Virtual Camera (Windows) — verification record

Feature: [ADR-007](../decisions/ADR-007-virtual-camera.md) · protocol: [webrtc.md](../architecture/webrtc.md) §Virtual camera control (proto 2) · architecture: [desktop.md](../architecture/desktop.md) §Virtual camera · issue #69. The desktop re-publishes the cast as a system webcam (softcam via koffi), toggled from the phone; output follows the cast's fps 1:1 (no cap); **no preview while active** — the receiver shows a placeholder and may be minimized.

## Automated (2026-10-30, macOS dev machine)

**Desktop — vitest 170/170 green, typecheck (node+web) green, `dist:win` cross-build green:**

- `signalingVc.test.ts` (6 loopback tests over real sockets): proto-2 negotiation (and proto-1 ↔ old-phone), `vc-query/start/stop` relayed to the coordinator, `vc-status` pushed as query reply and on change, **proto gating** (a proto-1 phone's vc frame rejected recoverably; no vc-status ever sent to it), pre-auth rejection, phone→desktop `vc-status` rejected (desktop is the sender).
- `vcDims.test.ts`: 1080p cap, portrait cap-by-height, multiples of 4 (round-to-nearest), min 4, non-positive refused.
- `registration.test.ts` (injected runner): CLSID query shape, elevated-register command shape (`Start-Process -Verb RunAs`), UAC-declined (1223) = `declined`, installer-failure and registry-disagrees = `failed`, spawn-throw = `failed`.
- `virtualCamera.test.ts` (fakes for softcam + registration): unavailable never registers; needsRegistration→UAC→active path; declined stays honest; dims capped at creation; no-stream-size refusal; frames forwarded / mismatched dropped / pre-start ignored; vc-stop idempotent; cast-gone and dispose both delete the camera; re-entrant starts during registration collapse to one.
- `framePump.test.ts` (fakes for source/canvas): 1:1 frame forwarding with close() discipline, scratch-buffer reuse, stop drops in-flight frames (still closed), restart switches pairs, bounded context-loss recovery then honest `pump-failed`.
- `vcActiveView.test.ts`: placeholder copy states what is happening + minimize OK.
- Packaging verified in the built artifacts: `win-unpacked/resources/softcam/{softcam.dll,softcam_installer.exe,LICENSE}` present (x64 **and** arm64 builds), koffi unpacked at `app.asar.unpacked/node_modules/koffi`, NSIS installer produced. `extraResources` scoped to `win` so macOS builds stay clean.

**Mobile — JVM 214/214 green, `assembleDebug` green:**

- `SignalingClientTest` (+2): `vc-status` parses into the event (malformed missing-field frame ignored), `sendVcQuery/Start/Stop` encode the right envelope types with empty payloads.
- `VcUiMappingTest` (new, 4): `vc-status` → UI state mapping (unavailable hides the card — even a lying `active`; needsRegistration → NeedsSetup; available → Ready+active) and the honest subline per state.
- `Handshake`: `PROTOCOL_MAX = 2` (old desktops negotiate 1 and the phone never shows the card).

## Manual on Windows x64 — PENDING (needs the rig)

The DLL-dependent paths (koffi load, UAC registration, OBS/Zoom visibility, real frame path) cannot run on the macOS dev machine. The matrix, per ADR-007's acceptance:

- [ ] First use: toggle on the phone → UAC prompt on the desktop → accept → camera appears; declined → honest needs-setup state; retry works.
- [ ] OBS (and one call app, e.g. Zoom/Teams) list the camera ("Softcam") and show the feed; aspect correct.
- [ ] 60 fps game cast → smooth camera output (and 120 if the rig supports it); numbers recorded (pump fps, ms/frame readback+IPC, CPU % in Task Manager).
- [ ] Rotation portrait↔landscape mid-VC → letterboxed correctly inside the fixed camera dims, no camera re-creation glitch.
- [ ] Window minimized while VC active → feed keeps publishing (MSTP is decode-side); placeholder shows when restored.
- [ ] Adaptive quality steps mid-VC → feed keeps flowing (fixed dims absorb it).
- [ ] Stop cast / phone Disconnect / desktop quit while active → camera disappears cleanly every time (no leak, OBS doesn't hang).
- [ ] Mid-cast reconnect (Wi-Fi blip) → camera stops; phone re-query shows inactive; re-toggle works on the rebuilt stream.

## Known limitations (recorded in ADR-007)

Device name is the DLL's built-in **"Softcam"**; Windows ARM64 reports unavailable (no arm64 softcam build); audio routing for call consumers is v1-out-of-scope (video-only); closing the receiver window still ends the app (tray mode = follow-up).
