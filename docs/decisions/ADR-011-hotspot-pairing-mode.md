# ADR-011: Hotspot pairing mode — cast with no shared Wi-Fi (LAN-only preserved)

- **Status:** accepted (2026-11-03) — implements issue #96; the user's go-ahead ("kerjakan sampai selesai", 2026-11-03) is taken as the decision on the gate question below, following the issue's own analysis.

## Context

The product's #1 user-facing error is *"Couldn't connect to the desktop. Make sure both devices are on the same Wi-Fi network."* — and it is the **only failure mode with no way out**: at a friend's house, a hotel, a classroom, there is no shared LAN, so caster cannot work at all, pairing included. Every other failure class has a recovery path (persistent sessions [ADR-006](ADR-006-persistent-trust-sessions.md), session parking + ICE restart [ADR-009](ADR-009-cast-resilience-ice-restart-wifilock.md)); this one is a dead end.

Three foundations already exist (why this is small here): the ADR-006 resume handshake (a session survives network changes without re-pairing), the QR payload's `h` = **all** candidate IPv4s tried in order (pairing.md), and `auth-ok.hosts` refreshing the phone's stored addresses.

## The gate decision — does a phone-hosted hotspot comply with "LAN only"?

**Yes.** The invariant's intent (overview.md: privacy — no relay, no STUN/TURN, no internet dependency, no accounts, no cloud) is about *where media and secrets flow*, not about who powers the router. A `LocalOnlyHotspot` is a **local AP that shares no internet** — no mobile data flows, the phone and desktop remain direct peers on a private subnet, all traffic stays on the radio between the two devices. This is the same posture as a $30 travel router, which nobody would call a cloud dependency. (Verified on-device that no mobile data is used: pending the manual matrix below — the acceptance gates on it.)

## Decision

1. **Phone hosts, desktop joins.** `WifiManager.startLocalOnlyHotspot()` — a local AP with **no internet sharing**. The app shows SSID + password on the phone screen (they exist only while the reservation is held).
2. **Guided-manual flow first** (the issue's recommendation): phone failure card → *Not on the same network? Start a hotspot* → credentials on the phone (Home card + a quiet line under the scanner, so they stay readable during the join) → the human joins on the desktop → scans the desktop's fresh QR → pair + cast exactly as on any LAN. **Zero protocol change**: `v` stays 1, `proto` untouched, no new messages — the phone (as AP) reaches its own clients directly.
3. **The QR must be fresh — the desktop's one change.** The desktop may join the hotspot *after* its QR was generated; a stale pending QR would advertise dead addresses. `PairingServer.ensureFreshSession` now also regenerates a **pending** session whose interface set changed (the 1 s sweep; snapshot per session, order-insensitive comparison). **Authorized sessions never regenerate for this reason** — a live cast or the ADR-006 reconnect window is exactly what the Phase15 deferral protects. The QR payload always carries the session's creation-time snapshot, never a later live reading.
4. **Pure lifecycle machine** (`hotspot/HotspotMachine`, JVM-tested): Idle → NeedPermission → Starting → Active(ssid, password) / Failed(reason) with honest plain-words reasons (permission declined, not supported, no channel, incompatible mode, **OS revoked**, unknown) and one-tap retry; explicit Stop is the only normal exit. The platform wrapper (`HotspotControl`, the ConnectedDesktop app-scoped precedent) feeds it OS callbacks: version-split permission (`NEARBY_WIFI_DEVICES` 13+, `ACCESS_FINE_LOCATION` below — the Wi-Fi API migration; `neverForLocation` asserted), the awkwardly-versioned `startLocalOnlyHotspot` (SDK 33+ exposes only the (callback, Handler) overload, absent from API 29–32 runtimes; below 33 the API-26 single-arg method is reached via reflection — it is hidden from new compile SDKs but alive on devices), credentials read from `SoftApConfiguration` (30+) / the deprecated `WifiConfiguration` (29), and `onStopped` → honest OS-revoked.
5. **The hotspot is explicit and user-owned**: nothing auto-stops it mid-session — the cast runs *over* it; stopping it kills the network the cast needs. OS revocation mid-cast degrades honestly through the ADR-009 paths (the failure parks the session; one tap returns). Process death releases the reservation (OS behavior).
6. **Failure-hint gate** (`hotspotHintFor`): the hotspot escape appears exactly for `CONNECT_UNREACHABLE` — the one error that means "no shared network". Every other failure keeps its existing escapes; no blanket nudge.

## Consequences

- **Positive:** the #1 error becomes a three-step solution instead of a dead end; works at any friend's/hotel/classroom; no protocol churn; ~1 new mobile module + one desktop sweep condition.
- **Costs / accepted:** the desktop is offline from its own network while joined (single-radio machines; ethernet desktops unaffected — stated in the join hint); hosting costs phone battery; SSID/password are typed by hand once (guided-manual; automatic join is a follow-up if the manual flow hurts); the phone's own Wi-Fi drops while hosting on single-radio devices (the intended case — there is no shared network).
- **Honest platform caveats (recorded in R11's OEM watch list):** `LocalOnlyHotspot` can be revoked by the OS at any moment; behavior varies per OEM/carrier (permission demands beyond the documented set surface as honest failures, never crashes); some versions need location *services* enabled, not just the permission.

## Rejected alternatives

- **Wi-Fi Direct / Wi-Fi Aware** — rejected: macOS cannot join WFD groups (the desktop platforms matter); the phone-hotspot path needs no new stack and every desktop already joins Wi-Fi. (TwinDesk reaches for the phone hotspot for Macs for exactly this reason.)
- **Desktop-hosted AP** — rejected: desktop Wi-Fi cards vary (many desktops have no Wi-Fi at all), and hosting from Ethernet-up machines needs ICS/router-grade bridging — heavier and less universal than the phone's built-in AP.
- **USB / ADB tethering** — forbidden by product invariant (no USB/ADB/Developer Options in the end-user workflow).
- **Automatic network migration (TwinDesk's full netlink machinery)** — rejected for v1: caster has no lower transport to escalate from; the guided-manual flow is the bootstrap. Automatic join/migration is a recorded follow-up if the manual flow proves painful.
- **Subnet scanning for saved desktops over the hotspot** — deferred: the acceptance flow pairs via the desktop's fresh QR (which now carries the hotspot address); sweeping the /24 to find a *saved* desktop's new address is a possible v2, decided by real usage.

## Verification

Mobile JVM **249/249** (12 new: the machine's acceptance matrix incl. stale-callback races and the hint gate); desktop vitest **262/262** (4 new sweep tests: pending regenerates on interface change, snapshot-not-live-reading, order-insensitivity, authorized never regenerates) + typecheck + build; `assembleDebug` green. **On-device matrix pending the phone in hand** — [features/pairing.md](../features/pairing.md) §Hotspot pairing mode: permission path → desktop join → QR pair over the hotspot subnet → full cast incl. both audio tracks → clean teardown; OS-revoked mid-cast degradation; **no mobile data used** during a hotspot cast.
