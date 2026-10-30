# Virtual Camera (Windows + macOS) — verification record

Feature: [ADR-007](../decisions/ADR-007-virtual-camera.md) · protocol: [webrtc.md](../architecture/webrtc.md) §Virtual camera control (proto 2) · architecture: [desktop.md](../architecture/desktop.md) §Virtual camera · issue #69. The desktop re-publishes the cast for other apps: **Windows** = system webcam (softcam via koffi), **macOS** = Syphon server (native helper — OBS/VJ reach). Toggled from the phone; output follows the cast's fps 1:1 (no cap); **no preview while active** — the receiver shows a placeholder and may be minimized.

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

- **The native half end-to-end, standalone**: `syphon-helper` fed a synthetic 64×32 30 fps stream publishes cleanly (clean EOF exit), and — after the **discovery-runloop fix** (ADR-007 addendum; the first cut read stdin in a blocking loop and the server was invisible to later-starting clients) — a `SyphonServerDirectory` probe lists **"Zero-Friction Local Cast"** continuously while it publishes. The helper is universal (arm64+x86_64), built from source by `resources/syphon/build.sh` together with the Syphon framework.
- **Packaged app**: the `.app` carries the helper + framework; the packaged binary runs.

## Manual — PENDING

**macOS (needs the phone in hand — everything else is ready on this machine):**

- [ ] Pair + cast → the phone shows the "Virtual camera" card (Mac reports available, no setup state) → toggle on → receiver swaps to the placeholder → **OBS (Syphon Client source)** lists "Zero-Friction Local Cast" and shows the feed; orientation correct (flip check), letterbox on rotation.
- [ ] Minimize the receiver mid-VC — OBS keeps receiving; stop cast → the server disappears from OBS instantly.
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

## Known limitations (recorded in ADR-007)

Windows device name is the DLL's built-in **"Softcam"**; Windows ARM64 reports unavailable (no arm64 softcam build); macOS reaches **OBS/VJ apps only** (call apps wait on the CMIO entitlement door); audio routing for call consumers is v1-out-of-scope (video-only); closing the receiver window still ends the app (tray mode = follow-up).
