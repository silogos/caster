# ADR-010: Desktop tray lifecycle — close-to-tray keeps the receiver listening

- **Status:** accepted (2026-11-03) — implements issue #94; finishes the promise ADR-006 made ("one-tap Connect to a saved desktop") by making the desktop reachable without an open window. Supersedes the ADR-007 addendum paragraph that claimed a windowless tray mode had shipped (it was reverted hours later — see Context).

## Context

ADR-006 gave the phone a one-tap **Connect** hero for a saved desktop — but the promise only materializes while the desktop app is running, and the desktop *was* its window: closing it killed the signaling WebSocket server, so the saved entry could never reach it. The virtual camera (ADR-007) already worked with the window minimized (`MediaStreamTrackProcessor` is decode-side), making "minimize, don't close" hidden knowledge a user had to possess.

A first attempt shipped 2026-10-31 (ADR-007 addendum, "Windowless mode") and was **reverted the same day** as bug-prone — the live finding: the frameless-toggle window recreate inferred "hidden" from `BrowserWindow.isVisible()`, which is also false mid-startup before `ready-to-show`, so a recreated window sometimes came up invisible ("the app didn't open"). The docs were never corrected (desktop.md and the ADR-007 addendum kept describing the tray as shipped) — this ADR records both the revert and the re-acceptation, and fixes that mismatch.

Issue #94 (2026-11-03, informed by the TwinDesk teardown — GPL-3.0, ideas only, no code) re-accepted the pattern with a fuller design and an explicit testability requirement.

## Decision

1. **Close → tray, unconditionally** (the issue's recommendation, TwinDesk's shape — no preference): closing the receiver window hides it; the app, the signaling server, and the trust store stay alive. The tray (macOS menu bar / Windows system tray, embedded template icon) owns the app's lifetime — `window-all-closed` never quits on any platform. Quit is the only exit.
2. **The lifecycle is a pure, JVM-style unit-tested state machine** (`tray/trayLifecycle.ts`, the `heroView` precedent): events (`window-close`, `open-receiver`, `cast-started`, `quit-requested`) in → state + effects (`hide-receiver`, `show-receiver`, `bye-then-quit`) out; the Electron shell only applies effects. **Visibility is an explicit intent field, never inferred from `isVisible()`** — the exact class of bug that killed the first attempt. A recreated window (frameless toggle) comes up hidden only when the intent says hidden (`createMainWindow({ show: false })`).
3. **Close-mid-cast keeps the cast** — video decode, the mixer, and the virtual camera keep running with the window hidden (`backgroundThrottling: false` since Phase14; MSTP is decode-side since ADR-007): the vcam workflow becomes explicit — *close the window, OBS keeps receiving.* macOS hides the Dock icon while windowless (a menu-bar app); the tray's **Open window**, a Dock/relaunch attempt (second-instance), or the next cast start bring it back.
4. **A cast starting while the window is closed auto-opens it** — the phone's one-tap Connect or scan flow lands on a visible receiver with zero desktop interaction. An already-visible window is never focus-stolen mid-show.
5. **Tray menu** (`tray/trayMenu.ts`, pure + tested; `tray.ts` maps to Electron): a status line in the session-info line's one vocabulary (`Waiting…` / `Connected to <phone>` + cast summary / `Waiting for <phone> to reconnect…`), **Open window**, **Forget phone** (the existing `bye forget` revocation — one code path with the no-input stage's Disconnect), **Virtual camera** checkbox (where supported, rides the same controller), **Launch at login** (off by default, macOS/Windows only — `app.setLoginItemSettings`; the OS is the store, no second persistence layer), **Quit**.
6. **Every quit path walks the clean `bye`**: tray Quit, Cmd+Q, and OS logout/shutdown all land in `before-quit`, which (once) sends a plain `bye desktop-quit` to a connected phone — deliberately **not** `forget`, so the trust survives plain quits per ADR-006 — waits a bounded 300 ms for the socket to flush, then quits through the normal `will-quit` teardown (camera delete, platform audio). An OS that kills the process before the walk finishes is covered by the existing honesty paths: the phone parks the session (ADR-009/#85) and reconnects in one tap.

## Consequences

- **Positive:** ADR-006's promise is real — the saved phone's Connect always finds the desktop (as long as the machine is on); the vcam workflow is explicit; the receiver behaves like an appliance (a capture card with a window you may close).
- **Costs / accepted:** the app now keeps running after its window closes — a user expecting close-to-quit must use the tray's Quit (the conventional trade for tray apps; TwinDesk idles at small footprint, and our idle cost is the ws server + one hidden renderer). On macOS the Dock icon disappears while windowless — deliberate (menu-bar app), with the tray, a relaunch attempt, and cast-start as the ways back. The quit walk adds ≤300 ms to shutdown.
- **App Nap / hidden-window throttling:** the shipped mitigations cover the hidden receiver — `backgroundThrottling: false` (Phase14), MSTP for the vcam pump (ADR-007), the muted keep-alive `<audio>` for the mixer (Phase9), and `prevent-display-sleep` during a cast (a power assertion disables App Nap anyway). On-macOS verification stays on the manual matrix ([features/tray.md](../features/tray.md)) — pending the phone in hand.
- **Windows note:** close-to-tray follows the OS convention for tray apps (✕ hides to tray); this is the same choice on all platforms, made once in the pure machine rather than per-platform branches.

## Rejected alternatives

- **Preference for close behavior** (close = quit vs. tray) — rejected: one less setting; Quit in the tray is the escape (the issue's own recommendation, mirroring TwinDesk).
- **Keep the first attempt's shape** (module-level `isQuitting` globals, visibility inferred from `isVisible()`) — rejected: the revert taught exactly why; the state machine + explicit intent is the redo.
- **Quit without bye** — rejected: the issue's acceptance requires the clean bye on every quit path, and the phone's parked-session UX (one-tap return) depends on it.
