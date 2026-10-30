# Pairing Protocol

Status: Phase 0 spec (implemented in Phase 3, reviewed again before implementation).

## Two concepts, deliberately separate

1. **QR pairing payload** — *bootstrap data* rendered by the desktop and scanned by the mobile app. It tells the phone where to connect and proves authorization. It is **not** a transport and carries no media or negotiation.
2. **Signaling channel** — the WebSocket the mobile opens *after* scanning. All further communication (auth, SDP, ICE, lifecycle) happens here ([webrtc.md](webrtc.md)).

The QR code is generated fresh each time the desktop waits for a device, and expires quickly.

## QR payload v1

Compact JSON encoded as QR (type 2/byte, error correction M). Field names are deliberately short to keep QR density low for fast scanning:

```json
{
  "v": 1,
  "t": "zfc",
  "h": ["192.168.1.42", "192.168.137.1"],
  "p": 52341,
  "s": "6Xk…b64url…",
  "k": "Qm9…b64url…",
  "e": 1758300000
}
```

| Field | Type | Meaning |
|---|---|---|
| `v` | int | Payload schema version. This doc specifies `1`. Unknown `v` → mobile shows "Desktop app is newer/older — please update." |
| `t` | string | Literal `"zfc"` (zero-friction cast). Guards against scanning unrelated QR codes. |
| `h` | string[] | **All** candidate IPv4 addresses of the desktop on non-loopback interfaces (Wi-Fi, Ethernet). The mobile tries them in order — this survives multi-interface/VPN ambiguity that breaks single-IP designs. |
| `p` | int | WebSocket signaling port. Default `52341`; the desktop falls back to an ephemeral port if occupied and always writes the actual port here. |
| `s` | string | Session ID — 16 random bytes, base64url. |
| `k` | string | Pairing secret — 32 random bytes, base64url. Never transmitted again; used only as an HMAC key. |
| `e` | int | Expiry — Unix seconds. Default `now + 600` (10 minutes). |

Wire endpoint: `ws://<host>:<port>/zfc/v1`.

Approximate encoded size: 130–170 bytes → comfortably scannable QR.

## Session lifecycle (desktop side)

```text
generated ──▶ pending ──(successful auth)──▶ authorized ──▶ closed
   │            │                                │
   └─ regenerate ┴── expiry (e) ──────────────────┘
```

- **One session at a time.** Generating a new QR invalidates any previous pending session.
- **pending → authorized** happens exactly once per session (first successful `auth`). A second phone attempting auth gets error `busy`.
- **Reconnect window:** once authorized, the same `s` may re-authenticate (Wi-Fi blip, app restart) until expiry or `bye`. This avoids forcing a re-scan for transient drops.
- **Expiry is enforced authoritatively by the desktop** (its clock). The mobile treats `e` with ±120 s tolerance for its own UX countdown only. **One exception (Phase15 live finding): an authorized session whose socket is still connected defers expiry** — the 1-second sweep skips regeneration while the authorized mobile is live, because the TTL governs the pending QR and the reconnect window, never a live cast (the sweep used to kill casts at exactly TTL; no cast could outlive 10 minutes). The moment the socket drops, the already-expired session regenerates — the reconnect window still ends at expiry, so a drop past TTL means a re-scan.
- `bye` (graceful end from either side) invalidates the session immediately; the desktop returns to the QR screen with a fresh session.

## Authentication handshake

Runs over the WebSocket immediately after connect (envelope and message definitions: [webrtc.md](webrtc.md)):

```text
Mobile                                   Desktop
  │ ── hello {ua, deviceId?, protoMin, protoMax} ──▶ │   (envelope carries sid)
  │ ◀─ challenge {n: nonce16} ───────────── │   or error: unknown-session / expired
  │ ── auth    {mac} ─────────────────────▶ │
  │ ◀─ auth-ok {name, proto} ────────────── │   or error: bad-auth / busy / bad-version
```

`mac = base64url( HMAC-SHA256( key = k, msg = ASCII(s) || ASCII(n) ) )`

- The nonce is 16 fresh random bytes per handshake; the binding to `s` prevents nonce reuse across sessions sharing a secret (defense in depth).
- The desktop allows the full handshake 10 seconds; otherwise it closes the socket.
- Failed `auth` closes the connection; repeated failures from one address are rate-limited (1 s, doubling, capped 30 s).
- **`deviceId` (optional, defined 2026-10-10 — additive per the versioning rules below, `proto` stays 1):** a random UUID the mobile generates once per install and never changes. It exists so the desktop can keep an `adb devices`-style registry of the devices it has seen (name + state + last seen) — it is **not** an authentication secret (auth remains the HMAC above, ADR-002) and carries no user identity beyond what the install itself chose. Absent field = an older client; the desktop must accept the handshake and treat the device as unidentified. Validation: when present it must be a non-empty string (≤ 128 chars) — a malformed one is `bad-message`. Rationale and rejected alternatives: [ADR-005](../decisions/ADR-005-device-identity-and-registry.md).

## Persistent sessions (trusted reconnect without a QR)

Since 2026-10-30 ([ADR-006](../decisions/ADR-006-persistent-trust-sessions.md)): one QR pairing establishes a **persistent session** between this phone and this desktop. Both sides remember it — the phone reconnects with one tap (`hello {resume}`) and no camera — until either side presses **Disconnect** (`bye` with the reserved reason `forget`). Everything else (app kills, Wi-Fi drops, restarts, a stopped cast) keeps it. The QR payload and `proto` stay at `1`; everything below is additive-optional per the versioning rules.

### Trust establishment — derived, never transmitted

After a successful QR handshake, **both sides locally derive** the resume key from that session's own material:

```text
trustKey = HKDF-SHA256(ikm = k, salt = ASCII(s), info = "zfc-trust-v1", L = 32)
```

No protocol message carries it; a passive sniffer sees only HMAC outputs and cannot compute the key (the same property that protects the handshake itself, ADR-002). A **re-pair rotates the key** (fresh `k` per QR session) — regenerating the QR and re-pairing evicts any trust derived from a photographed older code.

- **Desktop** persists `{deviceId, name, trustKey, createdAt, lastConnectedAt}` (single entry — the last phone that paired while nothing was trusted) in `trust.json` under the Electron userData dir, plus its own persistent `desktopId` UUID.
- **Phone** persists `{desktopId, name, hosts, port, trustKey}` (single entry) in SharedPreferences, the key sealed with an Android Keystore AES-GCM vault (`pairing/TrustVault`) — the app allows OS backups, and the sealed document alone cannot yield the key.
- Both stores degrade to empty on anything unreadable (logged) — the honest fallback is one QR re-scan, never a session on guessed values.

### Resume handshake

The phone reconnects to `ws://<host>:<port>/zfc/v1` using its saved hosts/port (no QR):

```text
Mobile                                   Desktop
  │ ── hello {ua, deviceId, resume: true, proto…} ──▶ │   (envelope sid = the phone's deviceId)
  │ ◀─ challenge {n} ─────────────────────────────── │   or error: unknown-device
  │ ── auth {mac = HMAC(trustKey, sid‖n)} ─────────▶ │
  │ ◀─ auth-ok {name, proto, desktopId?, h?} ──────── │
```

- `hello.resume: true` + `sid = deviceId` selects the trust store instead of the QR session; the MAC is the same construction with the trustKey as key. An older desktop (no resume support) answers `unknown-session` for the unknown sid — the phone falls back to the QR flow automatically.
- **`unknown-device`** (new error code, terminal): the desktop no longer trusts this phone (Disconnected there) — the phone drops its saved entry and prompts a re-scan.
- A successful resume closes the bypassed QR session and stages a fresh pending one underneath — a photographed old QR can never piggyback on a trusted reconnect.
- **`auth-ok` gains `desktopId`** (the desktop's persistent UUID — the phone keys its saved entry on it) **and `h`** (the desktop's current LAN candidates) so the phone refreshes its entry on *every* successful connection, QR or resume.

### `bye forget` — the only way trust ends

`bye {reason: "forget"}` (reserved value) ends the session **and revokes the persistent trust on both sides**; that is what the **desktop's Disconnect** and the **mobile's Terminate** send. Everything else is connection-level: the mobile's **Disconnect** sends a plain `bye user-ended` — the session survives, the phone's next Connect is one tap, and the desktop stays on its no-input stage — as do stopped casts and dead connections. A **revocation while the other side is offline** revokes silently — the phone learns it on its next Connect attempt (`unknown-device`) and falls back to the QR flow.

### Reconnect rules for a trusted session

The phone's reconnects are **not bounded by the QR's expiry** (there is none to read). They run the same backoff ladder with a bounded attempt count (`TRUSTED_RECONNECT_MAX_ATTEMPTS`, 10 steps ≈ 3 min), then fail `connect-unreachable` with the saved entry **kept** (the desktop may simply be off) — retry with one tap, or scan a QR / forget from the failure card.

### Threat model, honestly revised

The ADR-002 row "someone photographing the desktop's QR" changes: physical presence at the desktop used to buy ≤ 10 minutes of authority; with persistent sessions it buys **trust until revoked** (Disconnect or a re-pair). This is the accepted WhatsApp-like trade the feature exists for ([ADR-006](../decisions/ADR-006-persistent-trust-sessions.md)); the desktop's no-input stage carries the Disconnect that revokes, and re-pairing rotates the key.


### Why challenge–response (and the honest threat model)

Plain `ws://` on LAN is sniffable. If the phone just *sent* `k`, any passive listener could later impersonate the phone. With HMAC challenge–response, the secret never crosses the wire, and each handshake needs a fresh nonce — replaying a captured `mac` fails.

What this design **does** protect against: passive capture, replay, and unauthenticated connections on the LAN.
What it **does not** protect against (accepted for a LAN tool; see [ADR-002](../decisions/ADR-002-pairing-and-signaling-security.md)):

- Anyone who can *physically see/photograph* the QR obtains `k`. Physical presence at the desktop is treated as authority.
- An active MITM can still **deny service** (drop packets) though not successfully authenticate.
- No forward secrecy or mutual identity beyond possession of `k`.

## Failure modes and user-facing messages

Errors shown to users stay non-technical (spec §11); codes are for logs.

| Situation | Log/code | User sees (mobile) |
|---|---|---|
| No host in `h` reachable | `connect-unreachable` | "Couldn't reach the desktop. Make sure both devices are on the same Wi-Fi network." |
| WS connects but `expired` | `expired` | "This QR code has expired. Generate a new one on the desktop." |
| Wrong/unknown `s` | `unknown-session` | Same as expired (stale QR). |
| HMAC rejected | `bad-auth` | "Couldn't pair with this desktop. Scan the QR code shown on the desktop." |
| Another phone already paired | `busy` | "The desktop is already connected to another device." |
| QR of wrong app | parse `t`/`v` mismatch | "This isn't a Zero-Friction Cast QR code." |
| Reconnect, but the desktop Disconnected first | `unknown-device` | "This desktop no longer remembers this phone. Scan the QR code to pair again." (the saved entry is dropped automatically) |

## Versioning rules

- Bump `v` on any incompatible payload change (field rename, semantic change). Additive fields may keep `v` but must be defined as optional in this doc first.
- The WebSocket protocol version (`proto`, negotiated in `hello`/`auth-ok`) is independent of QR payload `v`; both bump separately.
