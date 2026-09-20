# Feature: OBS / Streaming Workflow (receiver-window hardening)

Implemented in: **Phase14** (see [roadmap](../development/roadmap.md)). Status: **implemented; desktop 83/83 green; verified live via CDP against the production build (2026-09-20)** — the acceptance item itself (a clean, correctly-proportioned, smooth OBS Window Capture over a **30+ min session**) needs real hardware, OBS, and the user's eyes; see *Verification*.

## What is implemented

The receiver window is the streamer's "output device": OBS Window Capture records exactly one window, so that window must contain nothing but video, for the whole session — and *its shape is the user's* (§3: letterboxing is the default; an earlier window-follows-stream default was replaced after the live session below). Phase14 closed the three items left open in [architecture/desktop.md](../architecture/desktop.md) §Window design (the full-window video surface and hover overlay landed earlier, review-time after Phase9).

### 1. Stay-awake for long sessions (`powerSaveBlocker`)

`startCastKeepAwake()`/`stopCastKeepAwake()` (`src/main/window.ts`) start Electron's `powerSaveBlocker` with `prevent-display-sleep` when a cast goes live and stop it when the cast ends — display sleep or a screensaver firing 20 minutes into a session would stall or pollute the captured feed. The blocker is id-guarded (double starts are no-ops) and is also released if the receiver window itself is closed mid-cast, together with the session-info window. This is the piece of the "30+ min OBS-session hardening" that code can do; the session itself is the acceptance test.

### 2. The off-cast session-info window

The cast summary line ("Connected to Pixel 8 — 1280×720 · 30 fps · balanced · game audio · mic") used to exist only in the receiver's hover overlay — *inside* the captured rect. Phase14 adds an optional **second BrowserWindow** that lives outside it:

- Small (520×64), frameless, always-on-top, draggable via a CSS app-region, closed via its ✕. The text ellipsizes; while no phone event has arrived yet it shows a muted "Cast info" placeholder.
- It exists exactly while **a cast is live AND the user wants it**. Created at cast start (if enabled), destroyed at cast end; a mid-cast toggle creates/destroys it live, replaying the cached mobile state so the fresh window immediately shows the current line.
- The preference defaults **off** (a second window must never surprise anyone) and persists in renderer `localStorage` (`zfc.session-info-overlay.v1`) like the mixer levels — per-receiver-machine trivia that never crosses the IPC boundary. The main process owns the effective state at runtime because it owns the windows; the Settings checkbox and the window's ✕ both funnel through one IPC call (`window:session-info-overlay`), and the main process pushes the state back so the checkbox can never lie about the window's existence.
- The line is formatted by one pure module, `sessionInfoLine.ts` (moved out of `main.ts`), rendered both in the receiver's hover overlay and in this window — one copy, two windows, unit-tested.

### 3. Letterboxing is the default — "Match window to video" on demand

**The window's shape is the user's.** By default the video letterboxes into it: `contain` scales it to full width or full height (whichever the aspects allow) with black bars for the rest — never stretch, never crop — and follows rotation automatically. Nothing about a stream moves the window. The fill is **re-asserted imperatively** (a live-session follow-up): an explicit window-resize listener, plus cast start, pins the video element to the window's exact pixel size with inline styles (`fillVideoWindow()` in `main.ts`), so the full-window fill never depends on viewport-unit (100vw/100vh) resolution — and even survives a stylesheet whose state has been disturbed. The inline styles are released at cast end, restoring the waiting layout untouched.

This is the second default for this behavior: an earlier build auto-reshaped the window to the stream's aspect at cast start and rotation (a review-time decision after Phase9, kept through Phase14's first build). **Replaced after a live Phase14 session on the real device (2026-09-20):** a portrait 800×1280 phone stream landing in a landscape window sized for an OBS canvas made the window jump shapes on its own, and the old "Fit window to video" button then re-fought every manual resize (the session log showed it clicked ~20× in 20 s). For the OBS workflow — where the receiver window is placed and sized to match the stream canvas — a window that moves itself is the enemy; letterboxing is the honest default.

The old behavior survives as the explicit **"Match window to video"** action in the settings modal: one click snaps the window to the stream's aspect (same geometry math as before: current content area, stream aspect, work-area clamps — `windowGeometry.ts`). The main process caches the stream size from the renderer's size reports (first metadata, every rotation, quality steps); a match request with no live stream is a logged warning, not an error.

### 4. A markup defect fixed (found during the phase)

`index.html` had **duplicate `id="hint"` and `id="regenerate"` elements** — a leftover of the Phase13 restructure: a second hint line and a second "New QR code" button that `getElementById`-based rendering never hid (it only ever found the first of each). Removed; the pairing stage now has exactly one of each, verified live.

## Verification (2026-09-20)

**Tests:** desktop typecheck green; vitest **83/83** (6 new `sessionInfoLine.test.ts`: empty-while-unpaired, name-only, full summary, off-source omission both partial and whole, profile variation). Production build green — the multi-page renderer now emits both `index.html` and `overlay.html`.

**Live on the real device (TB321FU / Android 16 → macOS, first Phase14 session):**

- A real portrait cast (800×1280) ran end to end; the session-info window was enabled from its Settings checkbox and opened at cast start; the cast ended cleanly via the phone (`bye` `user-ended` → session regenerated).
- **Found live — the default was wrong:** the window auto-reshaped itself to the portrait stream at cast start and on every rotation, fighting the user's own window sizing for the OBS canvas (the session log shows the old "Fit window to video" clicked ~20× in 20 s trying to keep up). Outcome: §3's design change — letterboxing is the default, the window never moves itself, and the button became the explicit on-demand **"Match window to video"**.
- **Found live — the rotation symptom was mobile-side all along:** a cast that starts portrait and then rotates to landscape showed the stream "small, centered, with blank space" (start landscape worked). An instrumented session (CDP monitor on the video element, track settings, and resize events) proved the desktop received **zero** dimension changes for the whole cast — the stream stayed 800×1280: the stock `ScreenCapturerAndroid` does not follow device rotation, so Android squeezed the rotated screen into the stale-orientation frames. The desktop's letterbox handles a dimension flip perfectly (verified synthetically both orientations); it just never got one. Fix shipped on the mobile: live display-change listener re-applying the capture format (`CaptureSize.followDisplay` — [features/screen-capture.md](../features/screen-capture.md) closes its Phase5 rotation gap).

**Live via CDP against the production build (14/14 checks):**

- Markup fix: exactly one `#hint`, one `#regenerate`.
- Settings modal: both Phase14 controls present; checkbox defaults off with fresh storage; toggling persists `zfc.session-info-overlay.v1=true`.
- A simulated cast start (`setCastActive(true)`, the same IPC the ReceiverSession sink fires) opened the session-info window; its line showed the styled placeholder pre-pairing and the ✕ was present.
- **✕ close**: window closed *and* the Settings checkbox synced off (the pushed state) — one shared state, verified.
- **Mid-cast toggle re-opened** the window; cast end closed it.
- "Match window to video" with no live stream size was handled as a warn (main log: `match-to-stream requested with no live stream size — ignoring`), no crash.
- **Keep-awake at the OS level**: `pmset -g assertions` showed `PreventUserIdleDisplaySleep 1` while the simulated cast was live and `0` after it ended.

**Remaining acceptance items (need OBS + a real device):**

- The **30+ min OBS Window Capture session**: a clean, correctly-proportioned, smooth feed — the window stays where the user placed it (letterboxing by default), a portrait→landscape rotation mid-cast re-fits the bars without moving the window (with the mobile rotation fix: the stream itself now flips), and "Match window to video" works as the on-demand snap. The receiver's side is verified; the session is the user's eyes-on acceptance (roadmap Phase15's matrices will formally record it).
- The session-info window's line under a *real* cast was not read during the live session (the window opened; its content with a real `session-info` event still wants an eyes-on check).
