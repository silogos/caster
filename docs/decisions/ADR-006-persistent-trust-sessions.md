# ADR-006: Persistent pairing sessions — reconnect without a QR (the WhatsApp model)

- **Status:** accepted (2026-10-30) — the user request: "aku mau connection tersimpan seperti whatsapp, jadi di desktop ada session di hp juga ada session. jadi tinggal reconnect tanpa harus scan qr lagi", refined as: *the session is lost only when Disconnect is pressed (mobile or desktop)*.

## Context

Until now every cast began with a camera scan: the QR session is ephemeral (10-minute TTL, ADR-002), and while a live connection could re-authenticate within its window, any restart, expiry, or drop past the TTL meant rescanning. ADR-005 deliberately deferred exactly this ("remembering devices across restarts (trust) and reconnecting without a QR is a different protocol phase — persistent pairing"); its identity half (`hello.deviceId`, retained when the display registry was removed on 2026-10-29) was kept precisely so this phase could build on it without a protocol bump.

The user wants WhatsApp-linked-device ergonomics on a LAN tool with no cloud, no accounts, no internet relay (product invariants).

Constraints:

- **LAN only** — no cloud, no push, no STUN/TURN; the phone must find the desktop itself.
- **ADR-002's handshake must stay the only auth** — a new secret must not cross the (sniffable, plain `ws://`) wire.
- **Zero friction** — the first pairing is already one scan + consent; remembering must add no step, or the feature defeats itself.
- **Honest state** — the removed registry taught that display-only state which can disagree with reality is worse than none ([ADR-005 removal addendum](ADR-005-device-identity-and-registry.md)); whatever persists must *grant* something, not just claim it.
- Versioning rules allow documented optional additive fields without bumping QR `v` or `proto` (the ADR-005 precedent).

## Decision

1. **One persistent session per side, established automatically by the first successful QR pairing.** Both sides derive — never transmit — a 256-bit resume key: `trustKey = HKDF-SHA256(ikm = k, salt = ASCII(s), info = "zfc-trust-v1")`. Both endpoints hold `k` and `s` at auth-ok, so no new message is needed to grant trust; a passive sniffer still cannot compute it. The desktop also gains a persistent `desktopId` UUID (`auth-ok.desktopId`), and both sides learn the desktop's current hosts on every connection (`auth-ok.h`).
2. **Resume handshake** ([pairing.md](../architecture/pairing.md) §Persistent sessions): `hello {resume: true}` with the envelope `sid` = the phone's `deviceId`; the challenge–response runs unchanged with the trustKey as the HMAC key. A new terminal error `unknown-device` tells a forgotten phone to rescan. An older desktop answers `unknown-session`, and the phone falls back to the QR flow — lockstep deployment is not required.
3. **Trust ends only via Disconnect.** The reserved `bye {reason: "forget"}` revokes on both sides — it is exactly what the mobile hero's and the desktop no-input stage's Disconnect buttons send. App kills, Wi-Fi drops, restarts, notification Stops, and ended casts keep the session: the phone's Home hero morphs Disconnect→**Connect** (one-tap resume), and a trusted desktop opens on the receiver's "No input video" stage (with its Disconnect) instead of the QR hero.
4. **Single session, last-pairing-wins per side.** The phone stores one desktop; the desktop stores one phone. A second phone can only become the trusted one while the desktop shows a QR (i.e. while nothing is trusted) — physical presence at the desktop remains the authority. Pairing with a different desktop replaces the phone's entry; the retired desktop's own Disconnect is its cleanup.
5. **Storage:** desktop — `trust.json` in userData (mode 0600, corrupt→empty, the CastSettingsStore convention) plus a `desktop-id` file; phone — one versioned JSON document in SharedPreferences with the trustKey **sealed by an Android Keystore AES-GCM vault** (`pairing/TrustVault`) because `allowBackup="true"` would otherwise let the raw key ride a backup. Both degrade to "nothing saved" + one re-scan on any corruption.
6. **Reconnects without an expiry** run the existing backoff ladder with a bounded attempt count (10 steps ≈ 3 min) and then fail `connect-unreachable` **keeping the entry** (the desktop may simply be off); the failure card offers "Scan QR code" and "Forget this desktop" as escapes.

## The threat-model trade, stated plainly

ADR-002 accepted "anyone who can photograph the desktop's QR obtains `k`" because physical presence at the desktop is authority — and the QR's TTL capped that authority at ≤ 10 minutes. Persistent sessions extend it: a photographed QR now mints a trust that survives until revoked. Accepted because (a) it is the exact WhatsApp-like behavior requested; (b) revocation is one visible button on the desktop's own screen (the no-input stage's Disconnect) and one on the phone; (c) **re-pairing rotates the key** — regenerate the QR and pair the real phone once to evict any trust minted from an older code; (d) the store is single-entry, so a new pairing always replaces whatever an attacker derived. Sniffing, replay, and unauthenticated LAN connections remain covered exactly as before (the trustKey is derived, never sent).

## Consequences

- **Positive:** the day-two experience is one tap on each side (Connect on the phone, video on the desktop); no camera, no QR, no re-entry of anything. The phone survives desktop restarts and its own restarts; the desktop opens straight to the receiver stage.
- **Costs / accepted:** QR photographs grant persistence until Disconnect or re-pair (above). A desktop IP change while unreachable costs one rescan (mDNS discovery stays deferred, Phase 5 precedent). A desktop Disconnect while the phone is offline is learned lazily (`unknown-device` on the next Connect). Reinstalling the phone app orphans the desktop's entry until its Disconnect is pressed (install-scoped identity, ADR-005). One phone, one desktop — multi-desktop lists are a non-goal v1.
- **Registry lesson honored:** the persisted state grants resume; it displays nothing it cannot enforce. The desktop's no-input stage claims no online/offline knowledge — it shows the trust it holds and the button that revokes it.

## Rejected alternatives

| Option | Verdict |
|---|---|
| Send a persistent secret over the (plain `ws://`) channel after pairing | **Rejected** — a passive sniffer captures it and owns the desktop forever; derivation keeps ADR-002's core property. |
| mTLS certificates / self-signed pin-on-first-use | **Rejected** (again, ADR-002) — trust-store and TOFU flows for a LAN hobby tool; HKDF keeps the server tiny. |
| A "Remember this desktop?" prompt after pairing | **Rejected** — adds a step to the zero-friction path and a protocol round-trip; the user asked for WhatsApp-like, which remembers. |
| Multi-desktop list on the phone / multi-phone registry on the desktop | **Rejected for v1** — the request is one session ("di desktop ada session di hp juga ada session"); lists resurrect the removed registry's stale-display problem. Single entry, last-wins. |
| mDNS/Bonjour discovery for desktop IP changes | **Deferred** (Phase 5 precedent) — last-known hosts + a one-scan fallback is honest; discovery adds router-dependent failure modes. |
| Trust with expiry / periodic renewal | **Rejected** — "hilang hanya ketika pencet tombol disconnect" is the requirement; silent expiry would betray it. |
| Bumping QR `v` or `proto` | **Rejected as unnecessary** — everything rides documented optional fields (`hello.resume`, `auth-ok.desktopId`/`hosts`, error `unknown-device`, reserved `bye` reason), the ADR-005 precedent; degradation across versions is defined. |

## Addendum (2026-10-30, live review): Disconnect/Terminate split on the mobile

The first cut made the mobile's single **Disconnect** the revocation (`bye forget`). The user refined it immediately: **Connect/Disconnect are connection-level only** (Disconnect = plain `bye user-ended`; the session survives, the desktop stays on its no-input stage, the next Connect is one tap), and a separate **Terminate** is the session-level exit (`bye forget` + both stores cleared → QR flow). The desktop keeps its single Disconnect (the revocation) — one deliberate exit per surface, and the phone's hero now carries the connection action plus a quiet Terminate. Decision points 3 and 6 above read with this refinement; nothing else changes.

The same review surfaced a real bug, fixed with it: handshake-phase error frames went out with an **empty envelope `sid`** (`conn.sid` was only owned after a successful hello), and the mobile rejects sid-less frames as malformed — so an `unknown-device` answer was silently ignored and the phone sat on "Connecting…" until the desktop's handshake timeout. Replies now name the session from the moment hello validates, `failHandshake` clears its own timer, and the mobile closes its socket immediately on any terminal error instead of waiting for the peer's close.

## Verification pointers

Cross-platform HKDF vectors mirrored in both test suites; the resume handshake, `busy`, `unknown-device`, `bye forget`, re-pair key rotation, and the bounded ladder are exercised by loopback/JVM tests (`signalingResume.test.ts`, `SignalingClientResumeTest`, `PairingClientTest`, `trustStore.test.ts`). The on-device matrix lives in [features/pairing.md](../features/pairing.md).
