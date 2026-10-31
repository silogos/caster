# Proposal: Virtual Camera Output (desktop app)

**Status: ACCEPTED 2026-10-30 — superseded by [ADR-007](../decisions/ADR-007-virtual-camera.md)** (tracking: issue #69), which records the user refinements that changed the draft: game-grade 60–120 fps output with **no cap**, **no preview while active** (MSTP + WebGL frame path, replacing the draft's rVFC+sharp sketch), the platform map (**Windows softcam · Linux PipeWire kernel-free · macOS Syphon**), and the WebSocket (not DataChannel) transport. Kept below as the original analysis.

*Original draft (2026-10-28, pre-acceptance):* Shared context and the full evidence list are in [proposals/README.md](README.md); the optional phone-camera source that would make this a full "phone as webcam" story is a separate draft — [camera-source.md](camera-source.md).

## 1. What it is

The desktop app re-publishes the incoming cast as a **virtual webcam device** usable in Zoom/Teams/Discord/OBS. Toggled **from the phone**; enabled only when the connected desktop supports it (capability negotiation, §4). Windows first; macOS behind it.

This feature works with the existing screen cast as-is (screen-as-camera). If [camera-source.md](camera-source.md) is also accepted, the "phone as webcam" story completes end-to-end — that scope decision is one of the blocking questions below.

## 2. Why it is worth doing

- **It is the competitor's flagship feature** — the single biggest use case it leads with (calls, presentations, OBS), and the only reason its Windows desktop app exists.
- **Caster's version is strictly more capable by construction.** With caster's *separate mic + game audio tracks*, a caster virtual camera feed comes with independently switchable audio: e.g. game audio into OBS, mic into the call — a single mixed-audio feed (the competitor's only option) cannot do that.
- **The biggest technical unknown is already retired.** The competitor's desktop app is **Electron — same framework as caster** — and ships this feature today using only open-source components (§3). No custom native code is required on Windows.

## 3. Per-OS reality + the proven Windows recipe

| OS | Mechanism | Effort / risk |
|---|---|---|
| Windows | **[softcam](https://github.com/tshino/softcam) DirectShow filter (MIT)** — validated end-to-end by the competitor's shipping app (§3.1) | lowest risk; ship first |
| macOS | CMIO Camera Extension (System Extension) for macOS 14+; the old DAL plugin path is dead | **entitlement must be requested from Apple manually** — the schedule is not in our control; plan for rejection-delay |
| Linux | `v4l2loopcap`/v4l2loopback | easy, niche audience |

### 3.1 The proven Windows recipe (from the competitor teardown)

The competitor's Windows desktop app is an Electron app (electron-builder `win-unpacked`), and its entire virtual camera stack is:

- **`softcam.dll`** (open-source, MIT, tshino/softcam): a DirectShow virtual-camera filter with a tiny C API — `scCreateCamera(width, height, fps)` → `scSendFrame(camera, BGR pixels)` → `scDeleteCamera(camera)`. User-mode DLL, **no kernel driver, no WHQL signing**; one camera instance; width/height must be multiples of 4.
- **[koffi](https://koffi.dev/) (FFI for Node)**: the main process declares those three C functions and calls the DLL directly — **no native Node addon to build or sign**. The DLL sits in `app.asar.unpacked` (a DLL cannot load from inside an asar).
- **One-time registration with UAC**: `softcam_installer.exe register <dll>` elevated via `Start-Process -Verb RunAs` (a generated temp `.bat` + PowerShell). Registration is detected by querying the filter's CLSID in the registry. The UX is a three-state model: *available* / *needsRegistration* ("Administrator permission required for first-time setup") / *active*.
- **Frame path (renderer → main → DLL)**: renderer draws the `<video>` into a canvas and ships frames to the main process over IPC; main decodes/resizes with **sharp** (libvips), converts to BGR, and calls `scSendFrame` at ~30 fps.

Caster can adopt this architecture wholesale. The one part to **not** copy is their frame transport (§5 R1).

## 4. Control model — capability negotiation over the DataChannel

The *phone* shows the toggle; the *desktop* announces whether it can. Both of the competitor's shipping endpoints speak it, end to end:

```
desktop → phone : {"type":"vc-status", "available":true|false, "active":true|false}
phone → desktop : {"type":"vc-start"} / {"type":"vc-stop"} / {"type":"vc-query"}   // vc-query = re-send status (used on (re)connect)
```

This keeps the "mobile controls, desktop receives" invariant intact: no cast settings appear on the desktop, and the phone renders no UI for a capability the connected desktop lacks. It becomes a **versioned protocol addition** (webrtc.md bump) — docs first per the repo rules.

## 5. What to adopt / not adopt from the competitor

| # | Decision | Item | Reason |
|---|---|---|---|
| VC-A1 | **adopt** | The Windows stack: softcam.dll (MIT) + koffi FFI + one-time UAC registration, DLL loaded from `app.asar.unpacked` | validated shipping by an Electron app — kills the "can Electron do a virtual camera?" risk with **zero custom native code**; MIT license is compatible |
| VC-A2 | **adopt** | Registration-state UX: *available / needsRegistration / active* with the honest "administrator permission required for first-time setup" message | the elevation moment is the one scary step for users; naming it plainly (and only requiring it once) is the right shape |
| VC-A3 | **adopt** | `vc-query` (phone asks the desktop to re-send `vc-status` on (re)connect) + dimension handling (cap 1920×1080, round to multiples of 4) | keeps the phone's toggle truthful across reconnects; softcam's multiple-of-4 constraint handled at the boundary |
| VC-R1 | **reject** | The base64-JPEG frame path: canvas → `toDataURL('image/jpeg')` → IPC as a base64 string → sharp decode → per-pixel JS RGB→BGR loop → `scSendFrame`, driven by `setInterval` | it works, but it pays JPEG encode + 33% base64 overhead + a full decode + a JS swizzle per frame, 30×/s — measurable CPU burn caster doesn't need. The same stack with `requestVideoFrameCallback` (frame-accurate, no duplicate sends), raw-pixel IPC transfer (structured-clone transferables), and `sharp.toColourspace('bgr')` avoids all four; measure before/after per the repo's perf discipline |

## 6. Design constraints

- **Mobile controls.** The toggle lives on the phone, gated by desktop-announced capability. The desktop may show *state* (e.g. "Virtual camera active") but not cast settings.
- **Two separate audio tracks.** The virtual camera input choice (game audio / mic / silent) is a *receiver-environment* control on the desktop side — same class as the mixer, not a cast setting.
- **Lifecycle:** virtual camera stops when the cast stops (`vc-stop` on teardown), and survives renderer hiccups without leaking the DLL camera.

## 7. Risks & open questions

- **Frame-path efficiency** — the *existence* of a working path is de-risked (§3.1); what remains is doing it without the JPEG round-trip (VC-R1) and measuring the cost. Do first.
- **macOS CMIO entitlement timeline** — start the Apple request early or ship Windows-first as the competitor did. Note softcam is Windows-only; macOS needs the CMIO extension regardless.
- **Blocking scope decision:** screen-as-camera only (no Android change) vs also accepting [camera-source.md](camera-source.md).
- Sequencing vs the remaining roadmap — the user's call.

## 8. Non-binding phase sketch (illustrative only — not added to the roadmap)

- **Phase V1 — Virtual camera (Windows)**: frame-path spike (softcam + koffi, raw-pixel transport per VC-R1) → registration/UAC flow + capability-negotiation messages → phone toggle. Accept: cast appears as a camera in OBS/Zoom on Windows.
- **Phase V2 — Virtual camera (macOS)**: CMIO extension behind the entitlement.
