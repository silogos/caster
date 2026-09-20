# Feature: Pairing (QR + WebSocket handshake)

Implemented in: **Phase 3** (see [roadmap](../development/roadmap.md)). Status: **implemented and verified on a real device (2026-09-20)**.

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

## Known limitations (by design in this phase)

- The mobile session lives and dies with the scan screen (ViewModel-scoped); a persistent session arrives with the cast service (Phase 6).
- No heartbeat, backoff reconnect or mDNS/host-candidate validation — Phase 4 (roadmap).
- On a Wi-Fi drop after auth, the mobile shows "Connection lost" rather than auto-reconnecting — the desktop's reconnect window exists server-side; the mobile reconnect logic is Phase 4.
- Desktop status for "connection lost while authorized" is just "Waiting for mobile device…" (the reconnect window stays open until expiry, but the desktop does not yet visualize it distinctly).
