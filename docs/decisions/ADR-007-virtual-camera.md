# ADR-007: Virtual camera output — re-publishing the cast as a webcam (Windows first)

- **Status:** accepted (2026-10-30) — accepts the draft [proposal](../proposals/virtual-camera.md) with user refinements recorded here. Tracking: issue #69.

## Context

The competitor's flagship feature is the virtual camera: its desktop app re-publishes the incoming phone cast as a webcam usable in OBS/Zoom/Teams ([proposals/README.md](../proposals/README.md) teardown). The draft proposal concluded the biggest technical unknown was already retired — the competitor ships this from Electron using only open-source components — and recommended adopting its Windows recipe while replacing its wasteful frame path.

The user accepted the feature with three refinements that shape this ADR:

1. *"buat game yang mana 60fps–120fps itu batas nyaman"* — the virtual camera is a **game feed**, not a 30 fps webcam toy. The output must be comfortable at 60–120 fps.
2. *"tidak usah ada preview ketika mode virtual camera"* — while the virtual camera is active the desktop shows **no video preview** (a placeholder instead), and the window may be minimized freely.
3. Platform choices, asked and answered: **macOS → Syphon**, **Linux → without v4l2loopback**, Windows implicit.

Constraints:

- **Mobile controls, desktop receives** (AGENTS.md): the toggle lives on the phone; the desktop may show state, never cast settings. The virtual camera's fps is therefore *the cast's fps* — no separate knob anywhere.
- **LAN only, no admin ceremony beyond the unavoidable**: whatever mechanism is used must work without STUN/TURN, cloud, or per-launch elevation.
- **The signaling channel stays dumb data plumbing** ([webrtc.md](../architecture/webrtc.md)) — testable with WebRTC entirely absent.
- Cross-build reality: dev machines are macOS; Windows builds are cross-compiled (`dist:win`). Every native dependency multiplies packaging risk.

## Decision

### Platform map (per user decision)

| Platform | Mechanism | Reach | Gate | Phase |
|---|---|---|---|---|
| **Windows** | **softcam** DirectShow filter (MIT, [tshino/softcam](https://github.com/tshino/softcam)) — user-mode DLL, no kernel driver, prebuilt; called via **koffi** FFI (3 C functions: `scCreateCamera`/`scSendFrame`/`scDeleteCamera`) | every camera consumer (OBS, Zoom, Teams, browsers) | one-time UAC registration (`softcam_installer.exe`, registry CLSID) | **this feature** |
| **Linux** | **PipeWire virtual camera** — user-space source node with `media.class=Video/Source` + `media.role=Camera`; published from Electron by spawning a GStreamer pipeline (`pipewiresink mode=provide`), frames over stdin — zero custom native code | portal-aware apps (Firefox/Chrome via the camera portal; OBS in transition); raw `/dev/video*` readers no | none (no root, no kernel module) | follow-up |
| **macOS** | **Syphon** (BSD-3-clause) — IOSurface GPU frame sharing via a small native Obj-C helper | **OBS/VJ apps only** — call apps (Zoom/Meet/Discord) cannot see it; it is not a system camera | none (no Apple entitlement) | follow-up |

All three sit behind the same main-process seam (`vc/virtualCamera.ts` state machine + the `vc-*` protocol below): a new platform is a new adapter module, not a redesign. `vc-status.available` honestly reports what the running desktop can do.

**macOS, stated plainly:** Syphon is the user's choice, bought with a known limitation — call apps will never see caster on a Mac until the CMIO Camera Extension path is added (restricted Apple entitlement, requestable any time, no commitment made now). The old DAL plugin path is dead; there is no healthy entitlement-free system camera on modern macOS. OBS users on macOS are covered *today* by Phase 14's window capture regardless.

### Control protocol — proto 2 over the existing signaling WebSocket

Four new message types (envelope `v` stays 1; the negotiated protocol version — `hello.protoMin/Max` → `auth-ok.proto` — moves to **2**; unknown envelope types were already recoverable, so old endpoints degrade gracefully):

- `m→d vc-query {}` — re-send status (the phone sends it at cast start and after every re-auth, keeping the toggle truthful across reconnects).
- `m→d vc-start {}` / `m→d vc-stop {}` — the toggle. `vc-start` while unregistered first triggers the elevated one-time registration, then creates the camera.
- `d→m vc-status {available, needsRegistration, active}` — the desktop's honest state, sent as the query reply *and* on every change. `available:false` (non-Windows-x64, DLL missing) hides the phone UI entirely; `needsRegistration:true` shows the one-time-setup copy.

Capability gating rides the version negotiation: a proto-1 peer (old phone or old desktop) never sees or sends `vc-*`; the desktop stores the negotiated proto per connection and ignores `vc-*` below 2.

### Frame path — presentation-independent, GPU-swizzled, no fps cap

```
media pc track ─▶ MediaStreamTrackProcessor (renderer)   per decoded frame, no rendering needed
                    │ draw → WebGL canvas (shader swaps R/B during the scale draw — GPU, free)
                    │ gl.readPixels → bytes already BGRA
                    ▼ raw IPC (vc:frame) → main → scSendFrame(softcam.dll) → camera consumers
```

- **`MediaStreamTrackProcessor`**, not `requestVideoFrameCallback`: rVFC is driven by *presentation* — hidden or minimized video stops firing it. MSTP yields each decoded frame directly, which is what the no-preview design requires, is frame-exact (no `setInterval` duplicates), and keeps working minimized. It is standard in the Electron/Chromium we ship.
- **WebGL R/B swap in the shader** — the conversion RGBA→BGRA that DirectShow needs happens on the GPU during the draw that scaling requires anyway; the main process does **zero per-pixel work**. This is why the design scales to 120 fps where a main-process JS loop (the competitor's path, fine at 30 fps) would burn 36–96 % of a core at 60. Context loss → recreate; repeated failure stops the virtual camera with an honest status.
- **No fps cap** — output follows the cast 1:1 (30/60/120 per the phone's quality console). The cast window's own rendering is unaffected.
- **Dimensions** are fixed at camera creation from the stream size (≤1920×1080, aspect preserved, multiples of 4 — softcam's constraint). Rotation afterwards letterboxes inside the fixed canvas rather than re-creating the camera (a dimension change would glitch consumers mid-use).

### Lifecycle

The camera is deleted on **every** exit path: `vc-stop`, cast end, mobile gone/bye/waiting, app quit — the DLL camera can never leak. While active, the receiver window swaps its live video for a **placeholder stage** in the "No input video" visual language (`vcActiveView.ts`); the audio mixer stays reachable from it (game audio and mic keep flowing to the desktop speakers regardless of the camera). Closing the receiver window still ends the app and session, exactly as today — a tray/windowless mode is a recorded follow-up, not v1.

## Consequences

- **Positive:** the "phone as webcam" story on Windows with caster's structural edge — the virtual camera feed arrives alongside independent game-audio and mic tracks (a single-mixed-audio competitor feed cannot offer that); game-grade fps; zero custom native code to build or sign on Windows; the phone stays the only control surface.
- **Costs / accepted:** one-time UAC elevation on first use (named plainly in the phone UI; declined UAC is an honest retry state, not an error storm); the camera device is named **"Softcam"** (the DLL's built-in name — renaming means building the filter ourselves); koffi joins the dependency list (packaged with `asarUnpack`, DLL via `extraResources`); Windows ARM64 reports `available:false` (no softcam arm64 build exists); no audio-input control for call consumers in v1 (video-only; the desktop's mixer and the OS remain the audio story).
- **Protocol:** proto 2 is additive — desktop↔phone pairs across versions degrade to "no virtual camera UI", nothing else.

## Rejected alternatives

| Option | Verdict |
|---|---|
| Competitor's frame path (canvas → JPEG base64 → IPC → sharp decode → JS R/B loop, `setInterval`) | **Rejected** — JPEG encode + 33 % base64 + full decode + a per-frame JS swizzle, 30×/s; pays four costs MSTP+WebGL+raw-IPC pay zero of. |
| `requestVideoFrameCallback` as the frame source | **Rejected** — presentation-driven; stops firing for hidden/minimized video, which the no-preview design requires. (It remains the right tool for measurement — `renderProbe.ts`.) |
| Main-process JS swizzle (competitor-proven at 30 fps) / **sharp** `toColourspace('bgra')` | **Rejected for the 60–120 fps target** — the JS loop doubles into main-thread saturation; sharp fixes the CPU but adds a second native dependency (cross-build risk, installed even on platforms that never use it). WebGL does the swap for free during a draw we need anyway. |
| 30 fps cap on the virtual camera output | **Rejected by the user** — "webcam standard" reasoning assumed call consumers; the target is game feeds. No cap: fps follows the cast (a phone-owned setting — the invariant holds). |
| DataChannel for `vc-*` (the competitor's transport) | **Rejected** — its signaling rides a cloud server, so it needs a peer channel; caster's signaling *is* the direct LAN link, and webrtc.md requires signaling to stay testable without WebRTC. |
| **akvirtualcamera** (one GPL library, three platforms) | **Rejected** — GPL-3.0 forces license obligations on the app; it bypasses no platform gate anyway (macOS still CMIO+entitlement, Linux still a DKMS kernel module — heavier than v4l2loopback); and it moves a 3-toolchain native build onto us, killing the zero-native-code property. |
| Linux v4l2loopback / akvcam-DKMS | **Not chosen** — kernel modules mean root, headers, DKMS breakage per kernel update, secure-boot friction; PipeWire's user-space virtual camera (Feb 2026) is the kernel-free path the user asked for. |
| macOS CMIO extension *now* | **Deferred by the user's Syphon choice** — CMIO stays the open door for call-app reach (entitlement request can be filed whenever the priority changes). |
| macOS DAL plugins / DYLD-injection into call apps | **Rejected** — DAL is dead on modern macOS (why OBS et al. moved to CMIO); injection is fragile, Gatekeeper-hostile, and malware-adjacent. Not something this project will ship. |
| Tray/windowless mode while the camera runs | **Follow-up** — closing the receiver window ends the app today; changing that lifecycle is its own decision, recorded for later. |

## Verification pointers

Protocol and state machine are exercised by desktop loopback vitest (`vc` cases in `signalingServer`/`virtualCamera`) and mobile JVM tests; the DLL-dependent path requires the Windows rig — the manual matrix (UAC-once, OBS visibility, 60/120 fps feed, minimized window, rotation letterbox, teardown on every exit, reconnect reset, frame-path numbers) is recorded in [features/virtual-camera.md](../features/virtual-camera.md).

## Addendum (2026-10-31): macOS shipped via Syphon — earlier than planned

The user greenlit the macOS phase the same day ("langsung saja macos syphon"), largely because the Mac is the only test rig available today (no Windows machine). Shipped shape:

- **A native helper, not an addon**: `resources/syphon/syphon-helper` — ~100 lines of Objective-C (vendored source + `build.sh`) that reads framed BGRA bytes from stdin, uploads them to a `MTLTexture`, and publishes via `SyphonMetalServer`. Built universal (arm64+x86_64) by compiling the BSD-3 Syphon framework from source (the last prebuilt SDK is 2019/x86_64-only); ad-hoc codesigned like the app's dev builds. Spawned per `vc-start` by `syphonPublisher.ts`, killed on every teardown — the same `VcCameraBackend` seam as softcam, with **no registration** (a no-op registration makes `vc-status.needsRegistration` permanently false on macOS).
- **Pixel format per platform** rides the pump command: Windows packs BGR24 on the GPU (softcam wants 24-bit), macOS keeps BGRA (a texture upload). The renderer canvas grew a `bgra` mode — same pass 1, pass 2 becomes flip+R/B-swap — so the main process still never touches a pixel.
- **Live finding — the discovery runloop (found on the Mac, would have hit OBS too)**: the first helper read stdin in a blocking loop, so its `NSDistributedNotificationCenter` observer never fired — the server announced once at creation and never answered clients' discovery requests, leaving it invisible to Syphon clients that started later (exactly OBS's case). The frame loop now runs on a dispatch queue with the main runloop alive. Verified with a `SyphonServerDirectory` probe: the server "Zero-Friction Local Cast" stays discoverable while publishing.
- Known limitations carried over: OBS/VJ-only reach on macOS (call apps need the still-open CMIO door); OBS 32.0 had a Syphon-client regression for some servers ([upstream issue](https://github.com/obsproject/obs-studio/issues/12684)) — if the OBS source shows blank, verify with Syphon Recorder/OBS 31.x before blaming the server.

### The no-signal still + the desktop toggle (2026-10-31, first live use)

Live use exposed a UX gap and a product refinement, both from the user:

- **The frozen-last-frame problem**: on disconnect the camera (and with it the Syphon server) died, leaving OBS frozen on the last frame — the user read it as a bug ("tampilan syphon tidak berubah jadi No input video"). The receiver-shaped expectation is right: an output device has stages. Backends now declare `idleNoSignal`: **Syphon keeps the server up between casts and the renderer publishes one "No input video" still** through the same GPU path (a text-rendered 2D canvas → WebGL → one frame); a fresh cast resumes frames (`onCastActive`); only the toggle/quit kills the server. Windows/softcam keeps the dies-with-cast behavior (a stale camera in the OS device list is worse there) — adopting idle-no-signal there is a possible follow-up, decided by Windows-rig observation.
- **The desktop toggle**: the toggle now also lives on the desktop (settings modal row + a "Turn off" button on the VC placeholder) — the user's call, and invariant-compatible: the virtual camera configures *what the receiver does with the stream it already has*, putting it in the receiver-environment class next to the mixer, not a cast setting. The phone's toggle stays (it drives the same controller; both sides stay truthful through the same pushes).
- **Settings in the no-input stage**: environment controls (mixer, window preferences) no longer need a live stream — the no-input stage carries a Settings button opening the same modal.
- **Audio routing mirrored on the phone**: the same both-sided pattern — `audio-routing-query/set/status` (proto 2) let the phone's Audio card pick the desktop's speakers/cable/both route; the desktop's mixer remains the single owner of the truth (a refused cable-without-device surfaces as the next status, never as an error).

### The frameless receiver window (2026-10-31, same live round)

The clarification that followed: not windowless-but-framed, but a window **without window chrome at all** — no title bar, no traffic lights, **square corners** (`roundedCorners: false` — macOS rounds frameless windows by default, found live); the receiver becomes a pure video surface, so a capture never sees a title bar to crop. Refined again in the same round into a **Settings toggle** (default framed): frame is fixed at window creation, so the main process recreates the window on toggle — but **never while a cast is live** (found live: the renderer owns the WebRTC pcs, so a mid-cast recreate is a guaranteed media drop — the phone declared the connection lost instantly). The pref applies immediately when idle and otherwise waits for the cast to end (the product's existing "next cast" semantics; the Settings row says so). The recreate keeps the user's bounds and windowless state, and re-asserts the mobile-state and virtual-camera pump pushes into the fresh renderer (`reassertPump`); the drag surface CSS is scoped under `body.frameless` (grab anywhere to move, edges resize, controls opt out). Combined with the tray, a frameless receiver has no on-window navigation by design — move by dragging, resize by edges, quit from the tray.

### Windowless mode: the menu-bar tray (2026-10-31, the recorded follow-up shipped)

The user's framing: with Window Capture they had to crop the letterboxed window — the cast should flow with **no window at all**. Closing the receiver window now **hides** it instead of ending the app: the hidden renderer keeps decoding (throttling already off since Phase 14), the mixer keeps playing, the virtual camera keeps publishing — Syphon consumers never notice. The app lives in a menu-bar tray (embedded template icon): *Show receiver*, *Virtual camera* (checkbox, rides the same controller), *Quit* — the tray owns the app's lifetime now (`window-all-closed` never quits). macOS hides the Dock icon while windowless; activating the Dock or the tray's Show brings the window back. First-run QR pairing still needs the visible window (physical presence at the desktop is the authority, ADR-002) — the tray's Show is that path.

### The recovered phase-19 branch (2026-10-31): prior art found and its audio half ported

The user remembered an audio switch that "disappeared" — investigation found the first virtual-camera effort (local branch `phase-19/virtual-camera`, Oct 2026, never merged; its ADR-005 slot was later renumbered on main). That branch had already investigated the macOS camera paths independently (DAL plugins rejected on macOS 26, free CMIO path closed — corroborating this ADR's platform map) and shipped **audio output routing** (speakers / cable / both via `AudioContext.setSinkId` → BlackHole, refusing a cable route with no device — issue #54). Nothing was ever removed from a released app; the work was stranded. The routing feature is ported onto this branch (commit message names the original); its per-track-routing follow-up (game audio and mic as separate cables) is exactly the "virtual mic/sound" direction discussed for a future batch.
