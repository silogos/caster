# Feature: Local Signaling

Implemented in: **Phase 4** (see [roadmap](../development/roadmap.md)). Status: **implemented and verified — loopback protocol tests + live check against the production desktop app (2026-09-20)**.

Implements the signaling half of [architecture/webrtc.md](../architecture/webrtc.md): the full envelope + message set (`sdp-offer`/`sdp-answer`, `ice`, `ping`/`pong`, `session-info`, `bye`, `error`), connection lifecycle (heartbeat, disconnect/reconnect rules, version negotiation). No WebRTC, no media — the protocol is exercised with *recorded* SDP/ICE blobs ("two endpoints exchanging recorded SDP blobs", roadmap). Pairing (Phase3) is unchanged apart from adopting the extended message set.

## What is implemented

### Desktop (`apps/desktop/src/main/signaling/`)

- **Envelope** (`envelope.ts`): the full v1 message set with typed payloads. SDP blobs are opaque strings; ICE candidates are **opaque JSON values** passed through verbatim (`candidate: null` = end-of-gathering per `pc`) — signaling never parses either. The `pc` discriminator is validated to `"media" | "mic"`; unknown types still parse as recoverable `bad-message`.
- **SignalingServer** (`signalingServer.ts`): after `auth-ok` the connection accepts the whole set:
  - `sdp-offer` → validated (`pc` + non-empty `sdp`) and forwarded to `onSdpOffer`. Signaling never answers offers — answering belongs to the ReceiverSession (Phase 5). Malformed offers get recoverable `bad-message`.
  - `ice` → forwarded verbatim to `onIceCandidate` (candidate untouched, including Chromium mDNS-obfuscated `*.local` host candidates — risk R4).
  - `session-info` → validated (6 typed fields) and forwarded to `onSessionInfo`; the coordinator pushes it to the renderer status line. Display-only by construction — nothing else consumes it.
  - `ping` → answered with `pong` carrying the same `t`.
  - `bye` (now with an optional `reason` for logs) → session invalidated, coordinator regenerates the QR.
  - **Heartbeat** (webrtc.md: ping every 5 s, peer silent > 15 s is gone): after authorization the server pings each interval; any inbound frame resets the silence clock; silence past the timeout closes the socket with `heartbeat-timeout`, which opens the reconnect window exactly like a TCP drop.
  - Desktop→mobile senders for Phase 5: `sendSdpAnswer(pc, sdp)`, `sendIceCandidate(pc, candidate)`, and desktop-initiated `sendBye(reason)` (invalidates the session + fires `onBye`, so the coordinator shows a fresh QR).
  - `sdp-answer` inbound from the mobile is rejected (`bad-message`) — the mobile is always the offerer (webrtc.md).
- **Coordinator/renderer** (`index.ts`, `renderer/src/main.ts`): `session-info` is pushed to the renderer and appended to the status line, e.g. `Connected to Pixel 8 — 1280×720 · 30 fps · balanced · game audio`. It is shown, never acted on (the desktop owns no cast settings — AGENTS.md invariant).

### Mobile (`apps/mobile`, package `signaling/`)

- **Envelope** (`Envelope.kt`): same message set, mirrored payloads; candidate round-trip preserves `JsonNull` (end-of-gathering) distinct from a missing key (malformed).
- **SignalingClient** (`SignalingClient.kt`): the full client lifecycle:
  - **Heartbeat**: ping every 5 s once authorized; a desktop silent > 15 s is treated as a drop (the client hangs up and reconnects). Inbound desktop `ping` is answered with `pong` (same `t`).
  - **Reconnect-with-backoff** (webrtc.md): an authorized drop (socket close/failure or heartbeat timeout) retries with 1 s → 2 s → 5 s → 10 s → 30 s (cap), re-running the full HMAC handshake against the same session id; the ladder resets after a successful re-auth. Retries continue while the QR session has not expired.
  - **Expiry stop**: a drop after `e` never retries — the user is sent back to scanning with the "QR expired" message (the desktop is authoritative; it has already regenerated its QR).
  - Terminal errors during reconnect (`expired`, `unknown-session`, …) stop the ladder; recoverable `bad-message` is logged and ignored.
  - `bye` from the desktop → `SessionEnded`, no retry (the session is invalidated there).
  - Senders for Phase 5: `sendSdpOffer`, `sendIceCandidate` (null = end-of-gathering), `sendSessionInfo`.
  - Timers live behind a `SignalingScheduler` seam (production: main-looper Handler; tests: virtual clock) — the client stays plain Kotlin, JVM-unit-testable.
- **PairingClient** (`pairing/PairingClient.kt`): maps the new lifecycle — `Reconnecting(desktopName)` on a drop, `Ended` on desktop `bye`, `Failed(EXPIRED)` when retries are exhausted by expiry. The scan screen shows a spinner + "Reconnecting to \<name\>…" / a scan-again prompt respectively. The old `Disconnected` state is gone: drops auto-reconnect now.
- **Version negotiation**: unchanged from Phase 3 (`protoMin`/`protoMax` in `hello`, negotiated `proto` in `auth-ok`); both sides still speak version 1.

## Verification

- **Desktop loopback protocol tests** (`signalingProtocol.test.ts`, vitest, plain Node — new file, 11 tests): a real `ws` server + a scripted phone that scans the QR from the rendered PNG (jsQR) and exercises: sdp-offer relay + sdp-answer delivery (recorded blobs), ICE passthrough verbatim — **including a Chromium mDNS `*.local` host candidate** (R4 plumbing evidence) and `candidate: null`, ping→pong echo, session-info forwarding + malformed-but-recoverable, malformed sdp-offer recoverable, heartbeat keeps a ponging phone connected and **closes a silent one after the timeout** (disconnect reported → reconnect window), mobile bye with reason, desktop-initiated bye, **reconnect within TTL** (same sid re-auths and carries media again), and **refusal past expiry** (`expired`). **31/31 green desktop-wide** (20 prior + 11 new), typecheck green, production build green.
- **Mobile unit tests** (JVM JUnit, 29 total: 14 new): envelope codec round-trips for every new payload (incl. end-of-gathering and bye reason), and `SignalingClientTest` with a scripted transport + virtual-clock scheduler — heartbeat pings on the interval, pong echo, **silence-past-timeout → drop → reconnect scheduled at 1 s**, **the full backoff ladder** (1→2→5→10→30 s) on repeated failures with reset after successful re-auth, **expiry stops all retries**, terminal error during reconnect stops the ladder, desktop bye ends without retry, sdp-answer/ice events forwarded, media senders produce the exact wire shape. `assembleDebug` + `testDebugUnitTest` green.
- **Live check against the production desktop app** (2026-09-20, macOS, production build, CDP): a scripted phone connected over the real LAN WebSocket → `auth-ok` → sdp-offer/ice (mDNS + host + end-of-gathering)/session-info accepted → `ping` answered with `pong {t}` → **one real server heartbeat ping observed** (answered; connection stayed) → renderer status line showed `Connected to Pixel 8 — 1280×720 · 30 fps · balanced · game audio` → `bye` → clean close → desktop returned to "Waiting for mobile device…" with a **fresh QR** (new session id).
- **Not re-verified on a physical Android device in this phase**: the mobile lifecycle is protocol-only and fully covered by the JVM tests; the phone-side runtime behavior that a real device adds (OkHttp reconnect behavior over Wi-Fi drops) gets its first on-device exercise together with Phase 5's real media sessions.

## Risk R4 (LAN ICE / mDNS obfuscation) — Phase4 verdict

Signaling is proven **form-agnostic for candidates**: a Chromium mDNS-obfuscated host candidate traverses the envelope byte-for-byte (unit + loopback + live). What Phase 4 *cannot* validate is mDNS **resolution** on a real LAN — that requires real libwebrtc ICE, which does not exist until the Phase 5 media connection. R4 therefore moves its "on-LAN resolution" validation to Phase 5 (first real media session), where the mitigation ladder in webrtc.md (resolve normally → disable obfuscation → candidate filtering with QR `hosts[]` as ground truth) will be applied. See [risk-register](../development/risk-register.md).

## Known limitations (by design in this phase)

- `sdp-offer`/`ice`/`session-info` arrive but nobody answers or consumes them yet — the ReceiverSession (desktop) and the `webrtc` module (mobile) are Phase 5. The desktop logs offers with an explicit "answering is Phase 5" marker.
- The reconnecting mobile rebuilds PeerConnections on re-auth — a Phase 5 concern (there are no PCs to rebuild yet).
- The desktop heartbeat starts only after `auth-ok`; there is no heartbeat during the 10 s handshake window (the handshake deadline covers that phase).
- The mobile session still lives and dies with the scan screen (ViewModel-scoped, Phase 3 decision); the cast service (Phase 6) takes ownership later.
