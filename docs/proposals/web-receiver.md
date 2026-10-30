# Proposal (DRAFT — not decided): Web Receiver (LAN, zero-install)

**Status: draft for user review.** Not an ADR, not a roadmap phase. Shared context and the full evidence list are in [proposals/README.md](README.md); this doc is the feature itself.

## 1. What it is

The Android app serves a small receiver web page over the local Wi-Fi. Any computer's browser on the same network opens `http://<phone-ip>:<port>` and becomes a cast receiver — **no desktop install, no internet, no third-party service**. Video plus both audio tracks (game audio and mic, independent elements — same rule as the desktop mixer). Receiver-environment controls only (volume, fullscreen); no cast settings, ever.

What it is **not**: cloud signaling, internet relay, or cross-network casting. The invariants ("LAN only", "mobile controls, desktop receives") are preserved.

## 2. The fork that must be decided first: how a browser finds the phone

A browser cannot "listen" for LAN peers; something must host the rendezvous. There are exactly two shapes:

| | Option A — phone-served page (LAN-only) | Option B — hosted page + cloud signaling (the competitor's model) |
|---|---|---|
| How it works | Android app runs an embedded HTTP + WebSocket server; browser opens `http://<phone-ip>:<port>`; signaling goes straight to the phone; media is WebRTC host-candidates only | Page hosted on the internet; signaling over `wss://` to a server; STUN for NAT discovery; TURN relay fallback |
| Install on receiver | none | none |
| Internet required | **no** (works on an isolated router / phone hotspot with no data) | yes, for every session |
| Third parties at runtime | none | signaling host + STUN provider + TURN relay operator (the competitor uses Google STUN and a free public TURN relay — a known-congested service) |
| Cost / ops | zero | server, domain, TLS, monitoring, bandwidth (relay video is expensive) |
| Cross-network casting | no | yes |
| Privacy | video never leaves the LAN | video transits a third-party relay whenever direct connection fails |
| Fit with invariants | full | **violates "LAN only. No STUN/TURN, no internet relay, no accounts, no cloud."** |

**Recommendation recorded in this draft: Option A.** It keeps every documented invariant, has zero running cost, and gives caster a claim the competitor cannot make: *works with the internet down; video never leaves your router*. The only thing Option B buys is cross-network casting — not a gaming-cast use case. If cross-network is ever wanted later, the first-party fallback is a self-hosted TURN (coturn) behind an ADR, not third-party infrastructure.

## 3. Why it is worth doing

- **Receiver reach is where the competitor wins today.** Its zero-install browser receiver works on any machine with a browser — caster's only receiver is the desktop app.
- **Zero-install viewing without giving up the LAN promise.** The competitor's zero-install works *because* it accepts cloud dependence. A phone-served page gives caster the same first-run friction (type an address) with strictly better guarantees: offline-capable, private by construction, zero marginal cost.
- **A distribution funnel with no infrastructure.** The web receiver is a demo machine: anyone with a browser on the Wi-Fi can see a cast in seconds — trust-building before installing the desktop app.

## 4. Technical facts that make Option A work (validation items, not assumptions)

- `RTCPeerConnection` is **not** restricted to secure contexts — receiving media in a plain-`http://` LAN page works in Chrome/Edge/Firefox. Only `getUserMedia`/`getDisplayMedia` require HTTPS, and the receiver page captures nothing.
- The phone is a native app, so it has no secure-context constraint on the sending side; its HTTP server can be plain HTTP.
- Precedent: KDE Connect and "Screen Stream over HTTP" both use phone-served LAN pages; the pattern is proven on Android.
- mDNS candidate obfuscation (Chrome replaces the *page's* local ICE candidates with `.local` names on insecure pages) affects the browser's own candidates; connectivity still works because the browser dials the phone's real host candidate. Must be validated on the target browsers (risk item).
- Audio playback on the page requires a user gesture; the page has a natural one anyway (the connect/allow click).
- The page must be served while a cast is possible without draining the battery when idle — server lifecycle is a design item (§7).

## 5. What to adopt from the competitor (patterns, not code)

| # | Pattern | Reason |
|---|---|---|
| W-A1 | Offer direction: receiver offers, phone answers; offers arriving before the user picked a source get queued (`pendingOffer`) | the phone-served receiver naturally makes the *page* the initiator; the race they hit (offer before source selection) is real and caster will hit it too |
| W-A2 | Compact QR payload keys (the competitor uses 1-char JSON keys) | lower QR density = faster scans from small screens/angles; applies if the receiver address/pairing payload is ever shown as a QR (HMAC and versioning stay) |

## 6. Design constraints

- **LAN security**: anyone on the Wi-Fi can open the page. Needs a lightweight gate — a short code shown on the phone, entered in the browser once — *not* a desktop setting; the phone is the authority. Rate-limit like the pairing server.
- **Simple user-facing errors** on the page ("Couldn't reach the phone. Make sure both devices are on the same Wi-Fi network."), technical detail to logs/console.
- **One receiver at a time** (default proposal, matching today's model); multi-viewer is future scope, not this feature.
- **Protocols are versioned and docs-first** — the browser-signaling variant requires a webrtc.md/pairing.md spec bump *before* code.

## 7. Risks & open questions

- Browser matrix: mDNS candidate behavior, autoplay gesture, WS reconnect, tab-close teardown on Chrome/Edge/Firefox/Safari.
- HTTP server lifecycle on Android: run only while casting (or while a "web receiver" toggle is on), bound to the cast FGS; battery impact measured, not assumed.
- How the user gets the address (typed IP:port vs a friendlier rendezvous) — UX open question; the phone displaying the URL is the floor.
- Sequencing vs the remaining roadmap — the user's call.

## 8. Non-binding phase sketch (illustrative only — not added to the roadmap)

- **Phase W1 — Web receiver spike (LAN)**: phone-served page, video-only, host-candidate WebRTC, one browser, teardown on close. Accept: a cast is viewable in a browser with the router's internet unplugged.
- **Phase W2 — Web receiver feature**: audio (both tracks, independent), short-code gate, reconnect/teardown rules, protocol spec bump, browser matrix.
