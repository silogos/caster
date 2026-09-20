# Feature: OBS / Streaming Workflow (receiver-window hardening)

Implemented in: **Phase14** (see [roadmap](../development/roadmap.md)). Status: **implemented; desktop 83/83 green; verified live via CDP against the production build (2026-09-20)** — the acceptance item itself (a clean, correctly-proportioned, smooth OBS Window Capture over a **30+ min session**) needs real hardware, OBS, and the user's eyes; see *Verification*.

## What is implemented

The receiver window is the streamer's "output device": OBS Window Capture records exactly one window, so that window must contain nothing but video, for the whole session. Phase14 closed the three items left open in [architecture/desktop.md](../architecture/desktop.md) §Window design (the full-window video, hover overlay, and window-follows-stream landed earlier, review-time after Phase9).

### 1. Stay-awake for long sessions (`powerSaveBlocker`)

`startCastKeepAwake()`/`stopCastKeepAwake()` (`src/main/window.ts`) start Electron's `powerSaveBlocker` with `prevent-display-sleep` when a cast goes live and stop it when the cast ends — display sleep or a screensaver firing 20 minutes into a session would stall or pollute the captured feed. The blocker is id-guarded (double starts are no-ops) and is also released if the receiver window itself is closed mid-cast, together with the session-info window. This is the piece of the "30+ min OBS-session hardening" that code can do; the session itself is the acceptance test.

### 2. The off-cast session-info window

The cast summary line ("Connected to Pixel 8 — 1280×720 · 30 fps · balanced · game audio · mic") used to exist only in the receiver's hover overlay — *inside* the captured rect. Phase14 adds an optional **second BrowserWindow** that lives outside it:

- Small (520×64), frameless, always-on-top, draggable via a CSS app-region, closed via its ✕. The text ellipsizes; while no phone event has arrived yet it shows a muted "Cast info" placeholder.
- It exists exactly while **a cast is live AND the user wants it**. Created at cast start (if enabled), destroyed at cast end; a mid-cast toggle creates/destroys it live, replaying the cached mobile state so the fresh window immediately shows the current line.
- The preference defaults **off** (a second window must never surprise anyone) and persists in renderer `localStorage` (`zfc.session-info-overlay.v1`) like the mixer levels — per-receiver-machine trivia that never crosses the IPC boundary. The main process owns the effective state at runtime because it owns the windows; the Settings checkbox and the window's ✕ both funnel through one IPC call (`window:session-info-overlay`), and the main process pushes the state back so the checkbox can never lie about the window's existence.
- The line is formatted by one pure module, `sessionInfoLine.ts` (moved out of `main.ts`), rendered both in the receiver's hover overlay and in this window — one copy, two windows, unit-tested.

### 3. Manual-resize letterbox polish ("Fit window to video")

The window follows the stream's aspect automatically at cast start and on rotation; a **manual** resize deliberately letterboxes (`contain`, never stretch/crop). The settings modal gains a **"Fit window to video"** button that re-applies the automatic reshape (current content area → stream aspect, work-area clamps, same `windowGeometry.ts` math) in one click — the operator's escape hatch after an accidental window drag. The main process caches the stream size from the automatic-reshape path; a fit request with no live stream is a logged warning, not an error.

### 4. A markup defect fixed (found during the phase)

`index.html` had **duplicate `id="hint"` and `id="regenerate"` elements** — a leftover of the Phase13 restructure: a second hint line and a second "New QR code" button that `getElementById`-based rendering never hid (it only ever found the first of each). Removed; the pairing stage now has exactly one of each, verified live.

## Verification (2026-09-20)

**Tests:** desktop typecheck green; vitest **83/83** (6 new `sessionInfoLine.test.ts`: empty-while-unpaired, name-only, full summary, off-source omission both partial and whole, profile variation). Production build green — the multi-page renderer now emits both `index.html` and `overlay.html`.

**Live via CDP against the production build (14/14 checks):**

- Markup fix: exactly one `#hint`, one `#regenerate`.
- Settings modal: both Phase14 controls present; checkbox defaults off with fresh storage; toggling persists `zfc.session-info-overlay.v1=true`.
- A simulated cast start (`setCastActive(true)`, the same IPC the ReceiverSession sink fires) opened the session-info window; its line showed the styled placeholder pre-pairing and the ✕ was present.
- **✕ close**: window closed *and* the Settings checkbox synced off (the pushed state) — one shared state, verified.
- **Mid-cast toggle re-opened** the window; cast end closed it.
- "Fit window to video" with no live stream size was handled as a warn (main log: `fit-to-stream requested with no live stream size — ignoring`), no crash.
- **Keep-awake at the OS level**: `pmset -g assertions` showed `PreventUserIdleDisplaySleep 1` while the simulated cast was live and `0` after it ended.

**Remaining acceptance items (need OBS + a real device):**

- The **30+ min OBS Window Capture session**: a clean, correctly-proportioned, smooth feed, ideally through a couple of rotations and a manual resize + re-fit. The receiver's side is verified; the session is the user's eyes-on acceptance (roadmap Phase15's matrices will formally record it).
- The session-info window's behavior with a *real* cast (the live checks simulated the cast-active IPC; no real `session-info` event rode the overlay during this window's checks).
