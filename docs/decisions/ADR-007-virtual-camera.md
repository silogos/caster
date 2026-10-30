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
