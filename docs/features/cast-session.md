# Feature: Foreground Service + Stable Screen Capture

Implemented in: **Phase 6** (see [roadmap](../development/roadmap.md)). Status: **implemented; JVM tests green; partially verified live on device (2026-09-20)** — the full lifecycle matrix below needs a short hands-on session with the device and is the remaining acceptance item (see *Verification* and *Matrix* sections for exactly what is and is not confirmed).

## What is implemented

### The service owns the whole cast session

Phase5's cast died with the scan screen (the pairing session was ViewModel-scoped). Phase 6 moves ownership to `CastService` (FGS type `mediaProjection`), exactly as [mobile.md](../architecture/mobile.md) specifies — the Activity is only UI:

- **Handover** (`PairingClient.releaseSignaling()`): when a cast starts, the pairing machine detaches its live `SignalingClient` *without ending it* and hands it to the service; its own state goes back to `Idle` and its event handler is unregistered, so a later `reset()` (leaving the scan screen, `ViewModel.onCleared`) owns nothing and is harmless. From the handover on, the connection's lifecycle is the cast's lifecycle.
- **Stop policy**: ending the cast (user stop, revoked projection, desktop gone) **ends the pairing session too** — `CastService.endCast()` sends `bye`, the desktop shows a fresh QR, and starting a new cast means scanning again. Rationale: the service must stop for clean resource release, and a non-foreground process cannot be trusted to hold the socket; keeping a "stopped cast but live pairing" state in a dying service would leak the session. Cost: one rescan per cast — acceptable for v1 (the projection consent is per-cast anyway).
- **Start validation**: `CastService.start()` refuses a handed-over client that is not `AUTHORIZED`/`RECONNECTING` and surfaces `Failed("Couldn't start the cast…")` instead. Found live: a consent dialog can easily outlive a pairing drop (the user taps *Start casting*, the connection dies, they tap *Start now*) — with a dead client the cast would hang in `Starting` forever, because no `Authorized`/`Failed` event ever arrives on a closed socket.
- **Every exit converges on one idempotent `endCast()`**: session stop, signaling `bye` + close, foreground notification removed, service stopped, state → `Idle` (or `Failed` with the simple message). A user stop racing a desktop `bye` is safe.

### User-facing state

`CastState` is now `Idle` / `Starting(desktop)` / `Casting(desktop)` / `Failed(message)` and both screens render it:

- **Home screen** is session-aware: a live cast shows `Casting to <desktop>` with a Stop button, a failed one shows the message plus *Start Cast*. The cast is visible/controllable without entering the scan flow.
- **Scan screen**: a running cast takes precedence over the pairing machine (there is nothing to scan while casting); a `Failed` cast falls through to the scan UI (ending the cast ended the pairing — rescan).
- **Notification**: real cast icon (`ic_cast`), title `Casting to <desktop>`, content intent reopens the app, and a **Stop** action (`PendingIntent` → `ACTION_STOP`) — the primary stop path while a game is fullscreen.

### Failure taxonomy (`MediaCastSession.Failure`)

| Failure | Trigger | User message |
|---|---|---|
| `ProjectionRevoked` | `onStop` callback — status-bar chip, system revoke, **single-app share ended by leaving the app** | "The screen cast was stopped." |
| `DesktopEnded` | `bye` from the desktop (window closed) — previously tore down silently and left the FGS stuck | "The desktop ended the session. Scan again to connect." |
| `ConnectionLost` | ICE `FAILED`, or terminal signaling failure (ladder exhausted / session expired) | "The connection to the desktop was lost. Scan the QR code to reconnect." |
| `Error` | pipeline build failures | "Couldn't start the cast. Please try again." |

Mid-cast signaling drops (Wi-Fi blip) remain non-fatal: the socket reconnects on its Phase4 backoff ladder while the P2P stream keeps flowing; the session re-offers on re-auth.

### Android 14+ ordering and restart policy

Unchanged from Phase 5 and still enforced: consent → service foreground → projection start. The service is `START_NOT_STICKY`: projection consent cannot be reused after process death, so a killed cast does not try to resurrect itself — the user starts a fresh cast (new scan + new consent). `onDestroy` tears the session down; the system reclaims the projection token when the process dies.

## Verification (2026-09-20)

**Tests:** mobile JVM **46/46** (3 new: pairing handover — release sends nothing on the wire and survives a later `reset`; a released connection's events no longer drive the machine; release with nothing owned returns null. The home ViewModel test was rewritten for the cast-state contract). `assembleDebug` green. Desktop typecheck/build green (its only change is the dev-mode fix below).

**Live on device (Lenovo TB321FU / Android16 → macOS, same Wi-Fi), on the new build — observed:**

- The **real product path to a running foreground service**: camera-scanned QR → handshake → *Start casting* → consent → `CastService` in the foreground with type `mediaProjection` and the new notification (verified via `dumpsys activity services`: `isForeground=true types=0x00000020`, channel `cast`, ongoing + 1 action).
- **Projection-revoked handling, twice**: the user consented with the dialog's default **"Share one app"** mode and then switched apps; the system stopped the single-app projection, the service ended the cast cleanly (desktop log: `bye reason:"user-ended"`), i.e. the first-class `onStop` path works end to end. This also surfaced the *consent-mode pitfall*: on Android 14+ the consent dialog offers single-app vs full-screen sharing and defaults to a single app — a single-app cast ends the moment that app leaves the foreground. Users must choose **Share full screen**; a UX affordance for this belongs to Phase 13.
- **The stuck-start bug**: a cast started with an already-closed pairing (consent outlived the connection) hung in `Starting` with the FGS alive and nothing ever failing. Found live, fixed (start validation above), and the phone's stuck service was cleared by reinstalling.
- **Desktop dev-mode bug found on the way**: `window.ts` opened DevTools but never `loadURL`'d the vite renderer in dev mode — the dev window was blank (production builds were unaffected). Fixed with the missing `loadURL` call.

**What is *not* yet confirmed on the new build — honest gaps:** video actually streaming (the receiver window was never observed with live frames; the renderer's stats don't reach the terminal log, so the user's casts can't be retroactively confirmed), screen-locked behavior, rotation, task-removed, network-loss and desktop-closed paths, and the leak checks (`dumpsys media_projection` after each end). The attempt to drive these remotely was halted deliberately: the test device was in active personal use, and remote taps collided with live usage once — the matrix requires the phone in hand.

## Lifecycle matrix (the Phase 6 acceptance list)

| # | Case | Status |
|---|---|---|
| 1 | Cast continues while a game/other app is foreground | **pending hands-on** (Phase 5 evidence carries over: FGS kept streaming through PUBG/YouTube on the Phase 5 build; the service mechanism is unchanged, but it must be re-confirmed on the new build) |
| 2 | Screen locked mid-cast | **pending hands-on** — expect: FGS continues, capture shows the lock screen content per OS policy (document what happens) |
| 3 | Rotation during cast | **pending hands-on, needs the device physically rotated** — `ScreenCapturerAndroid` reconfigures on the orientation *sensor*; a settings-forced rotation does not trigger it (Phase 5 finding) |
| 4 | Stop via app (home/scan button) | pending hands-on (unit-covered: handover + endCast wiring) |
| 5 | Stop via notification action | pending hands-on (action verified present via dumpsys) |
| 6 | Projection revoked (status-bar chip) | **verified live** (via single-app consent ending; same `onStop` callback) |
| 7 | Network loss mid-cast | pending hands-on — expect: socket ladder reconnects while P2P survives; sustained loss → ICE `FAILED` → clean `Failed(ConnectionLost)` stop |
| 8 | Desktop closed mid-cast | pending hands-on — expect: `unknown-session` after the ladder → clean `Failed(ConnectionLost)` stop |
| 9 | Service stopped by the system / task removed | pending hands-on — expect: FGS survives task removal; cast continues until stopped or the process dies |
| 10 | Process death (`am force-stop`) | pending hands-on — expect: cast ends, notification gone, no leaked projection (`dumpsys media_projection` empty) |
| 11 | Resource release after every edge | pending hands-on: `dumpsys media_projection` shows nothing after each case; teardown log line `media session torn down` |

All cases that end a cast funnel through the same `endCast()`; the matrix exists to prove no edge *bypasses* it.

## Known limitations

- **Stopping the cast ends the pairing** (see stop-policy rationale) — one rescan per cast.
- **Consent mode is user-chosen** (single-app vs full screen); a single-app share stops when that app leaves the foreground — handled cleanly, but it surprises users who miss the spinner.
- Rotation reconfiguration keys off the physical orientation sensor; forced (settings) rotation does not reconfigure the capture.
- No restart after process death (by design — consent is single-use).
