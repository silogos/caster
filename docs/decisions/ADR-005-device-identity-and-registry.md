# ADR-005: Device identity and the desktop's device registry

- **Status:** accepted (2026-10-10) — Phase A of the "registered devices" request (user: "aku mau seperti adb, device yang sudah connect itu terdaftar")

## Context

The desktop knows almost nothing about a paired phone. The handshake's `hello` carries only the user-agent string and the protocol range (`pairing.md`), so after pairing the desktop can display "Connected to *" parsed from the UA — and nothing else. There is no notion of *which* device connected, no memory of devices seen before, and the session model is deliberately ephemeral (one QR session, in-memory, expired in 10 minutes).

The user wants an `adb devices`-like experience: devices that connected are *registered* — listed with identity and state, not just a transient "connected" line.

Constraints that shape this:

- **LAN only, no cloud, no accounts** (product invariants) — identity must come from the device itself.
- **The pairing secret must stay the only auth** (ADR-002) — an identity field that could authenticate anything would weaken the handshake model.
- **The QR session model (ephemeral, single-device, `busy` on a second phone) stays** — remembering devices across restarts ("trust") and reconnecting without a QR is a different protocol phase (persistent pairing), explicitly out of scope here.
- The repo's versioning rules allow **additive optional fields without a protocol bump** when the doc defines them as optional first (`pairing.md` §Versioning rules).

## Decision

1. **Identity = a random UUID generated once per app install** and persisted in the app's private storage (`pairing/DeviceIdentity` on the mobile). It rides `hello` as the **optional `deviceId`** field (documented in `pairing.md`/`webrtc.md` first; `proto` stays 1). The desktop validates it loosely (non-empty string ≤ 128 chars when present) and must accept handshakes without it — older clients simply register as unidentified.
2. **The desktop keeps an in-memory device registry** (`devices/DeviceRegistry`): one entry per identity — `{deviceId, name (from UA), ua, remote (IP), state: online|offline, connectedAt, lastSeen}`. `onMobileConnected` moves an entry online; the existing disconnect paths (bye, socket failure, heartbeat drop) mark it offline. The registry's lifetime is the desktop process — persistence to disk is the next phase, deliberately deferred.
3. **The registry is display plumbing, not security.** It never grants, denies, or remembers authorization; deleting it changes nothing about who can pair (a pairing still requires scanning a fresh QR and completing the HMAC handshake).
4. The pairing window renders the registry as a compact device list (state + name + short id) under the QR hero — the `adb devices` surface.

## Consequences

- **Positive:** the desktop can honestly show *which* device is/was connected (`adb devices` shape) with no new transport, no new port, and no change to the trust model; older mobiles still pair cleanly (optional field, honored degradation).
- **Costs / accepted:** the identity is install-scoped — reinstalling the app or clearing storage makes the phone a "new" device (accepted: matches adb's "new serial on reflash" spirit; revisit only if users re-pair often). The registry is in-memory, so a desktop restart clears it (accepted for Phase A; disk persistence is the announced next step). One device is `online` at a time — the single-session rule stands.
- **Explicitly revisit if:** persistent pairing without QR lands (then the identity graduates into the trust decision — a new ADR), or multi-device sessions are ever wanted (the receiver's single-cast model is the bigger change).

## Rejected alternatives

| Option | Verdict |
|---|---|
| Derive identity from hardware (MAC / ANDROID_ID / serial) | **Rejected** — privacy-sensitive, changes with hardware resets/SIM swaps, and MAC randomization per Wi-Fi network makes it unstable; a random UUID is stable and carries nothing else. |
| Google account / FCM identity | **Rejected** — requires internet/cloud, violates the LAN-only invariant. |
| Device model string as identity | **Rejected** — two identical phones would be indistinguishable; the registry would lie. |
| Protocol version bump (proto 2) for the field | **Rejected as unnecessary** — the versioning rules explicitly allow documented optional additive fields; a bump would force pointless bad-version failures between lockstep-shipped apps. |
| Persisting the registry to disk now | **Deferred** — part of the announced next phase (registry across restarts), not needed for the adb-like *list*; keeps this phase protocol-and-UI only. |
