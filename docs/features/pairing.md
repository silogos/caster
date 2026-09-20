# Feature: Pairing (QR + WebSocket handshake)

Implemented in: **Phase 3** (see [roadmap](../development/roadmap.md)); **screens polished in Phase 13**. Status: **implemented and verified on a real device (2026-09-20)**.

Implements [architecture/pairing.md](../architecture/pairing.md) and the pairing subset of [architecture/webrtc.md](../architecture/webrtc.md) ([ADR-002](../decisions/ADR-002-pairing-and-signaling-security.md)). No media — the handshake is the whole feature ("test handshake only").

## What is implemented

### Desktop (`apps/desktop/src/main/`)

- **PairingServer** (`pairing/pairingServer.ts`): real session generation — 16-byte base64url session id, 32-byte base64url secret (`crypto.randomBytes`), expiry `now + 600 s`, one session at a time, regeneration invalidates the previous one, `busy` for a second concurrent device, reconnect window for the same session id after a drop, `bye` invalidates. Expiry is enforced on the desktop clock; a slow sweep in `index.ts` regenerates the QR automatically once a session expires.
- **NetworkInfo** (`pairing/networkInfo.ts`): all non-loopback LAN IPv4s go into `h` — the mobile tries them in order.
- **SignalingServer** (`signaling/signalingServer.ts`): `ws` server on port 52341 (ephemeral fallback if occupied; the *actual* port goes into the QR) at `/zfc/v1`. Implements the full handshake: `hello` → `challenge` (fresh 16-byte nonce) → `auth` (HMAC-SHA256 over `s‖n`, constant-time compare) → `auth-ok` (desktop name, negotiated proto). 10 s handshake deadline; failed auths are rate-limited per address (1 s doubling, cap 30 s); terminal errors (`unknown-session`, `expired`, `bad-auth`, `busy`, `bad-version`) close the socket after send; pre-auth frames other than `hello`/`auth` get `bad-message` (recoverable). Envelope rules per webrtc.md: v=1, seq from 1 (regressions logged, not enforced), sid required, 256 KiB max.
- **Renderer** (still framework-free): shows the QR + "Waiting for mobile device…", switches to "Connected to \<phone model\>" (parsed from `hello.ua`) on auth-ok, has a **Regenerate** button — the one desktop control the spec allows. New typed IPC: `pairing:regenerate` (invoke) and pushes `pairing:session-updated` / `pairing:mobile-state`.
- Fixed a latent Phase 2 gap found during this phase: `preload/index.d.ts` imported `DesktopApi` from the implementation module where it was never exported, so `window.desktopApi` was silently `any` in the renderer (masked by `skipLibCheck`). It now imports from `shared/ipc.ts`.

### Mobile (`apps/mobile`, packages `pairing/` + `signaling/`)

- **QR parsing** (`pairing/QrPayloadParser.kt`): payload v1 validation (version, `t` tag, hosts/port/session/secret sanity); expiry deliberately not re-checked here — the desktop is authoritative and answers `expired`.
- **Signaling** (`signaling/`): OkHttp WebSocket client behind a `SignalingTransport` seam (unit-testable in plain JVM), envelope codec (kotlinx-serialization), HMAC handshake, host-list iteration, error-code mapping per the pairing.md failure table. Reconnect-with-backoff, heartbeat and SDP/ICE are Phase4.
- **Pairing state machine** (`pairing/PairingClient.kt`): Idle → Connecting → Authenticating → Connected(desktop name) / Failed(error) / Disconnected; drives 1:1 what the scan screen shows.
- **Scan screen** (`ui/pairing/`): CameraX preview + ML Kit bundled barcode model (QR format only) — on-device, no network dependency (the Phase 3 scanner decision, recorded in [architecture/mobile.md](../architecture/mobile.md)). Runtime CAMERA permission flow with rationale. Debug builds additionally offer a manual "paste payload JSON" input so the WebSocket+handshake path can be exercised on an emulator (which cannot scan a real desktop QR). Home's Start Cast now opens the scanner; the placeholder "arrives in Phase 5" notice is gone.

## Verification

- **Desktop unit tests** (vitest, plain Node — the modules are Electron-free): session lifecycle (expiry regeneration, busy, reconnect window, invalidation, no-LAN-IP failure + recovery), HMAC vector, and **loopback protocol tests** — a real `ws` server + a scripted phone client that scans the QR from the rendered PNG (jsQR), then exercises: successful handshake, `unknown-session`, `bad-auth` + rate limiting, `busy` + reconnect, `bad-version` (payload range and envelope version), recoverable `bad-message`, `expired`, `bye` → fresh QR. **20/20 green**, typecheck green, production build green.
- **Mobile unit tests** (JVM JUnit): payload parse/validate (wrong app, unknown version, malformed, blank hosts), a **cross-platform HMAC vector identical to the desktop's** (both implementations must agree byte-for-byte), envelope codec round-trip, and PairingClient behavior with a scripted fake transport (exact wire frames, host iteration, error mapping). **15/15 green**, `assembleDebug` green.
- **Live check** (2026-09-20): the production desktop build running on macOS shows a QR whose decoded payload carries the machine's real LAN IP and a fresh session; payload extracted via CDP (`apps/desktop/scripts/read-qr-payload.mjs`).
- **Real-device camera scan (2026-09-20, pass)**: desktop (macOS, production build) generates QR → phone (Lenovo TB321FU, Android 16) scans it in the app → connects over Wi-Fi → handshake completes → phone shows "Connected to \<desktop name\>" and the desktop shows "Connected to TB321FU". Two findings from this test:
  - **Android cleartext policy blocks plain `ws://` by default** (`CLEARTEXT communication not permitted by network security policy`). The signaling channel is deliberately plain `ws://` on the LAN (ADR-002), so the app ships a `network_security_config.xml` allowing cleartext (see `apps/mobile/app/src/main/res/xml/`).
  - **Scan retrigger loop**: without a guard, every analyzed frame (~30/s) redelivers the QR payload, so an instantly-failing connect attempt made the screen blink between camera and status. The scanner now delivers a detected payload exactly once, and the ViewModel ignores scans while a connection is in flight.

## Phase 13 — Production pairing UX

The Phase 3 machinery was correct but the screens were bare. Both sides now render the roadmap's spec mockups.

### Desktop — the QR hero

- The waiting screen leads with the QR (288 px, white card, soft shadow) under a small muted brand line; the heading **"Scan with your Android device"** and the "valid for 10 minutes / refreshes automatically" hint sit below it. The "New QR code" control stays (the one allowed desktop action).
- **Paired** (auth-ok, not yet casting): a green check icon, **"Connected to \<phone\>"**, and **"Start casting from your phone."** — the desktop hands the stage to the phone (it exposes no cast controls). The regenerate control is hidden in this state: regenerating would invalidate the live session ([pairing.md](../architecture/pairing.md) — one session at a time), and the old UI left that trap armed.
- **Session error** (no LAN IP / generation failed): warning icon plus the friendly message, with the button relabeled **"Try again"**.
- All of this is one pure view model — `renderer/src/pairingHero.ts` (`heroView`) — applied to the DOM by `main.ts`; the strings never contain codes (AGENTS.md).

### Mobile — scan → "Desktop found" → Connected → Start Cast

- **Immediate post-scan feedback**: `PairingClient.startFromQrText` now enters `Connecting` right after a payload parses — the machine previously stayed `Idle` until a socket opened, so the QR-decode moment gave no response. The screen shows **"Desktop found"** with a spinner ("Pairing with the desktop…") for both `Connecting` and `Authenticating`.
- **Connected**: check icon, "Connected to \<desktop\>", a prominent **Start Cast** trigger (the same vocabulary as the home button), the disconnect demoted to a text button, and — the [mobile.md](../architecture/mobile.md) consent-pitfall affordance — a hint under the trigger: *"When your phone asks what to share, choose 'Share full screen'."* The Android 14+ dialog defaults to "Share one app", which ends the moment that app leaves the foreground; the hint steers the user to the choice that keeps the cast alive.
- **Error states**: every [pairing.md](../architecture/pairing.md) failure mode renders as icon + mapped plain-words message above the still-live scanner (rescanning is inherent — the camera never stops in a failed state); cast failures get the same treatment. New `StatusMessage` composable; icons from `material-icons-core` (dependency pinned, the core set only).
- Camera preview clipped to a rounded card with a subtle border; the debug paste-payload input is unchanged.

**Happy path ≤3 actions** (roadmap acceptance): tap **Start Cast** (home) → scan → tap **Start Cast** (scan screen). The camera grant and the system consent/permission dialogs are OS-imposed prompts, not app actions — they are counted here as part of "scan" and "start", not as separate steps. No app-side step was removed from the flow because each remaining one is an explicit user intent (the screen-share consent cannot legally be pre-granted).

### Verification (Phase 13)

- **Desktop**: typecheck green; vitest 77/77 (3 new `pairingHero` tests: waiting/paired/error hero contents); production build green.
- **Live CDP check against the production build** (2026-09-20): waiting hero rendered (QR decoded from the screen itself — scannable; heading/hint/button correct); a scripted phone then performed the *real* HMAC handshake over the real WebSocket → the paired hero appeared exactly as specced (check icon, "Connected to Pixel Test", regenerate hidden); `bye` returned the app to a fresh QR hero. Full sequence in the main-process logs (`hello` → `mobile authorized` → `bye` → reconnect window).
- **Mobile**: JVM tests 126/126 — 4 new `ScanViewModelTest` behaviors (instant `Connecting` after a scan, in-flight/connected scans ignored, failed pairing accepts a fresh scan, handover releases the live connection, disconnect returns to the scanner); `assembleDebug` green.
- **Remaining (needs the phone in hand)**: the live rescan paths — scan → "Desktop found" → Connected → Start Cast on the real device, and each failure mode seen for itself (expired QR after 10 min, wrong Wi-Fi, busy desktop). The strings are the already-verified Phase3 mapping; only the presentation changed.

## Known limitations (by design in this phase)

- The mobile session lives with the scan screen until a cast starts — the connection is then handed to the cast service (Phase 6, [cast-session.md](cast-session.md)).
- Heartbeat, backoff reconnect and mDNS/host-candidate plumbing landed in **Phase 4** — see [signaling.md](signaling.md).
- On a Wi-Fi drop after auth, the mobile now auto-reconnects with backoff (Phase 4); the desktop's reconnect window had existed server-side since this phase.
- Desktop status for "connection lost while authorized" is just "Waiting for mobile device…" (the reconnect window stays open until expiry, but the desktop does not yet visualize it distinctly).
