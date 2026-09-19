# ADR-002: Pairing security model (QR bootstrap + HMAC challenge–response)

- **Status:** accepted (Phase 0, 2026-09-19)

## Context

The mobile app must connect to the desktop without the user typing IPs, on a LAN where plain `ws://` is sniffable. TLS with self-signed certificates adds a trust-store/TOFU flow that directly harms the "zero-friction" goal, and the product has no server infrastructure. The QR code displayed on the desktop is an available out-of-band channel: the phone's camera.

## Decision

1. The desktop generates a short-lived session containing a **256-bit random secret `k`** (plus session id `s`, host list, port, expiry) and renders it as a QR code ([pairing.md](../architecture/pairing.md)).
2. The mobile authenticates over the WebSocket with an **HMAC-SHA256 challenge–response**: the desktop issues a fresh 128-bit nonce; the phone answers `HMAC(k, s‖n)`. The secret never crosses the wire.
3. Sessions expire in 10 minutes, are single-device (`busy` for a second phone), allow reconnect within their validity, and are invalidated by `bye` or QR regeneration.
4. Payload and protocol are versioned; mismatches produce actionable errors.

## Considered alternatives

- **Send `k` directly on connect:** rejected — a passive LAN sniffer captures the secret and can impersonate the phone afterward.
- **TLS + self-signed cert, pin-on-first-use:** rejected — certificate generation, trust prompts, and TOFU states add real friction and code for a LAN hobby tool, while still leaving the "which desktop am I trusting?" moment to the user.
- **No auth (open signaling server):** rejected — any LAN device could inject offers/answers and hijack or DoS sessions; also enables camera-less desktop impersonation.

## Threat model (explicit)

| Threat | Covered? |
|---|---|
| Passive sniffing of pairing/traffic | ✅ HMAC; secret never transmitted |
| Replay of a captured handshake | ✅ fresh nonce per handshake, bound to `s` |
| LAN attacker connecting without the QR | ✅ cannot compute the HMAC |
| Someone photographing the desktop's QR | ❌ accepted — physical presence at the desktop is treated as authority |
| Active MITM *denying service* | ❌ unavoidable without additional infrastructure |
| Forward secrecy / strong mutual identity | ❌ out of scope for v1 |

## Consequences

- The desktop's signaling server stays tiny: random generation, one HMAC verify, expiry bookkeeping — no certificates, no accounts.
- Regenerating a QR (new `k`) is the recovery action for any suspected compromise; it is cheap and already part of the UX.
- If multi-user/remote-network use is ever added, this model must be revisited (it assumes same-LAN + physical line of sight).
- Implementation note: randomness uses platform CSPRNG (`SecureRandom` / `crypto.randomBytes`); the HMAC comparison on the desktop is constant-time.
