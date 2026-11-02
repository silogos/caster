# Desktop Tray Lifecycle (close-to-tray)

Shipped 2026-11-03 ([ADR-010](../decisions/ADR-010-desktop-tray-lifecycle.md), issue #94) — the tray, not the window, owns the desktop app's lifetime. This doc records what shipped and the manual matrix that remains.

## What shipped

- **`tray/trayLifecycle.ts`** — the pure window/tray state machine (11 unit tests): `window-close` → hide (unconditional close-to-tray), `open-receiver` → show, `cast-started` while hidden → show (the one-tap Connect promise: a cast landing on a tray-only desktop opens the receiver with no desktop interaction), `quit-requested` → clean-`bye` walk. Visibility is an explicit intent field — never inferred from `isVisible()` (the bug that killed the first, reverted attempt of 2026-10-31).
- **`tray/trayMenu.ts`** (pure + tested) + **`tray/tray.ts`** (Electron glue): the tray menu — status line in the session-info vocabulary (`Waiting…` / `Connected to <phone>` + the cast summary while casting / `Waiting for <phone> to reconnect…` during the reconnect window), *Open window*, *Forget phone* (the `bye forget` revocation — the same code path as the no-input stage's Disconnect), *Virtual camera* checkbox (where the backend supports it), *Launch at login* (off by default; macOS/Windows only, `app.setLoginItemSettings` — the OS is the store), *Quit*.
- **Wiring** (`index.ts`): every close hides the receiver (macOS: Dock icon goes with it — a menu-bar app; a relaunch attempt/second-instance, Dock activation, or the tray brings it back); `window-all-closed` never quits; every quit path (tray Quit, Cmd+Q, OS logout/shutdown) funnels through `before-quit` → one bounded walk: plain `bye desktop-quit` (trust survives, ADR-006) → ≤300 ms settle → real quit through the normal `will-quit` teardown. A window recreate (frameless toggle) while hidden comes up hidden (`createMainWindow({ show: false })`).
- Desktop vitest **279/279** (21 new across the two pure modules), typecheck + production build green; 7 s smoke run of the built app (signaling binds, tray constructs, no errors).

## Manual matrix (extends the Phase 6 lifecycle matrix, desktop-side — pending the phone in hand)

| Case | Expectation | Result |
|---|---|---|
| Close-mid-cast (✕ with cast live) | Video decode, both audio tracks, and the vcam output stay alive with the window hidden; session-info overlay persists; tray status shows the cast summary | ☐ |
| One-tap Connect against a tray-only desktop | Window auto-opens, cast starts, zero desktop interaction | ☐ |
| Scan-to-pair against a tray-only desktop (un-trusted pair) | Same as above on the QR path | ☐ |
| Close while idle (no phone) | Window hides; tray status `Waiting…`; QR session keeps regenerating on its TTL | ☐ |
| Tray *Open window* / relaunch attempt / Dock click | Receiver re-shows (Dock icon restored on macOS) | ☐ |
| Tray *Forget phone* (connected) | Same as the no-input stage's Disconnect: `bye forget`, both sides back to QR | ☐ |
| Tray *Forget phone* (phone offline) | Silent revoke; fresh QR; tray status `Waiting…` | ☐ |
| Tray *Quit* mid-cast | Clean `bye` reaches the phone (cast ends honestly, trust survives), app exits, camera deleted | ☐ |
| Cmd+Q / OS logout / shutdown | Same walk; the OS killing the process first is covered by the phone's parked-session path (one-tap return) | ☐ |
| Launch at login on → reboot | App starts to the tray-visible receiver; saved phone connects with one tap | ☐ |
| macOS App Nap / hidden-window throttling | Hidden cast keeps decoding/painting (mitigations: `backgroundThrottling: false`, MSTP pump, keep-alive `<audio>`, `prevent-display-sleep` power assertion during casts) | ☐ |
| Frameless toggle while in tray mode | Window recreates **hidden** (stays tray-mode); no "app didn't open" regression | ☐ |

## Known limitations

- The app keeps running after its window closes by design; users expecting close-to-quit must use the tray's *Quit* (recorded in ADR-010 as the accepted tray-app trade).
- While windowless on macOS there is no Dock icon (deliberate, menu-bar-app shape); the tray, a relaunch, or the next cast start are the ways back.
- The quit walk holds shutdown ≤300 ms; an OS that kills the app faster skips the bye (phone parks, ADR-009).
- *Launch at login* hides on Linux (Electron cannot set login items there).
