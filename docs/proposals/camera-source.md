# Proposal (DRAFT — not decided): Camera Capture Source (phone camera)

**Status: draft for user review.** The most optional of the three drafts. Not an ADR, not a roadmap phase. Shared context and the full evidence list are in [proposals/README.md](README.md); the desktop-side output this feeds into is [virtual-camera.md](virtual-camera.md).

## 1. What it is

The phone's **front/back camera as an alternative capture source** to screen capture — chosen on the phone (source picker), cast through the same session machinery. It is the Android half of the "phone as webcam" story; without it, the virtual camera can only ever show the screen.

## 2. Why it might be worth doing

- It is the competitor's **primary** use case (phone as webcam, camera-first product), validated by shipping.
- It unlocks non-gaming audiences (calls, content creation) without touching the gaming core: a new, isolated capture module per the architecture rules ("keep capture concerns in separate, small modules") — the screen-cast pipeline is untouched.
- Front camera + virtual camera + caster's separate mic track = a webcam whose microphone and "system audio" are independent — something the competitor's single mixed track cannot offer.

Why *optional*: caster's identity is the gaming cast; the screen cast + virtual camera already covers "show the phone in a call". The camera source is an audience expansion, not a gap in the existing story.

## 3. What to adopt from the competitor (patterns, not code)

| # | Pattern | Reason |
|---|---|---|
| C-A1 | Camera capture module around `Camera2Enumerator` + WebRTC `CameraVideoCapturer`: front/back selection with fallback to the other side, live `switchCamera` with a double-tap guard | a small isolated capture module per the architecture rules; their switch flow (with success/error callbacks updating state) is proven shipping |
| C-A2 | Freeze auto-recovery: `onCameraFreezed` → `stopCapture()` → 200 ms → `startCapture()` on a worker thread | a proven resilience pattern for camera pipelines (camera service hiccups are common on real devices) |
| C-A3 | Their camera defaults as a starting reference: 1280×720@30, 2–4 Mbps | sanity-checked numbers, not decisions — caster's presets/adaptive machinery applies the real values |

## 4. Design constraints

- **Mobile controls.** The source picker (screen / front camera / back camera) is a phone control — receivers never see a source switch.
- **Separate from the screen cast.** A camera cast is a different session source, not a renegotiation of a running screen cast; switching sources mid-cast is an explicit user action on the phone.
- **Foreground service typing**: the FGS gains the `camera` type while a camera cast runs (the competitor declares `mediaProjection|camera` and swaps per source).
- **Orientation is a new problem**: camera sensors are portrait-native and the capturer delivers sensor-oriented frames — different from the owned `ScreenCapturer.kt` display-bounds logic. Receiver-side letterboxing already tolerates aspect changes, but rotation behavior for a camera source needs its own spec and device verification.

## 5. Risks & open questions

- Whether this is in scope at all — the blocking decision recorded in [virtual-camera.md §7](virtual-camera.md) (screen-as-camera only vs camera source too).
- Thermal: the thermal ladder was tuned for screen-cast encode windows; camera capture + encode has a different thermal profile — needs measurement before the profiles claim to cover it.
- Camera lifecycle edges: another app taking the camera mid-cast, permission revoked mid-cast, freeze loops (recovery must give up after N tries, not spin).
- Sequencing vs the remaining roadmap — the user's call.

## 6. Non-binding phase sketch (illustrative only — not added to the roadmap)

- **Phase C1 — Camera source**: new capture module + source picker on the phone, FGS typing, switch-camera, freeze recovery; thermal check on device. Accept: a camera cast is viewable on the desktop app with the mic track independent, survives an in-cast camera switch, and its thermal behavior is measured.
