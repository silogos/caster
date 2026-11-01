# ADR-009: Cast resilience — survive transient mid-game UDP outages (ICE restart + WifiLock)

- **Status:** accepted (2026-11-01) — implements the resilience half of the live-debugged mid-game cast death (issue #86); the honesty half (failures park the session instead of `bye`-ing the desktop) shipped earlier in PR #85.

## Context

While gaming (caster app backgrounded, game foreground), the cast died mid-session. Both sides were instrumented simultaneously (desktop log + phone logcat), and the evidence chain is decisive:

- The phone's encoder reported 0 kbps / 0.0 fps for ≥15 s, then the pc hit `FAILED` and the app ended the cast.
- During the **exact same window**, the signaling TCP answered pong in **14 ms** — the network was not down; the **UDP/ICE flow** was.

Fifteen-plus seconds of dead UDP outlives libwebrtc's ICE consent (~3 missed STUN checks), so the pc declared `FAILED` and the old code mapped that straight to `Failure.ConnectionLost` — cast over, even though the link was fine seconds later. On a LAN with no NAT, the plausible mechanisms (AP band-steering under load, the AP deprioritizing a backgrounded app's UDP while its TCP stays refreshed, phone radio firmware under game load) share one profile: **a transient outage of tens of seconds, not a permanent failure**. Calls apps (Zoom/Meet) survive exactly this with an ICE restart; until now we died.

## Decision

1. **ICE restart instead of session death** (`webrtc/IceRestartController` + `MediaCastSession`): the phone (the offerer) keeps the same pc and sends a fresh offer with fresh ICE credentials (`PeerConnection.restartIce()` — tracks, senders, and DTLS state survive). The ladder is bounded and JVM-tested as a pure state machine:
   - `disconnected` alone gets a 10 s grace (most blips heal alone);
   - `failed` (or a disconnect outliving the grace) fires restart #1 immediately; each offer gets 15 s to reconnect;
   - a failed attempt backs off 2 s → 5 s before the next; at most 3 restarts per bad stretch;
   - two honest closes — restart exhaustion, or a bad stretch ≥60 s — surface the existing `ConnectionLost` UX. A genuinely dead network still fails honestly, within a bounded window (~≤60 s).
   - Any `connected` resets the budget: each transient outage gets the full ladder again.
2. **The desktop changes nothing** — a restart offer is an ordinary `sdp-offer`; the answerer is already rebuilt per offer and its sinks re-attach via `ontrack` (loopback-tested for exactly this flow). The desktop never renegotiates on its own (unchanged rule).
3. **WifiLock for the cast's lifetime** (`CastService`): `WIFI_MODE_FULL_LOW_LATENCY` (not the issue's letter `WIFI_MODE_FULL_HIGH_PERF` — since API 34 that mode is treated as plain FULL, i.e. a no-op on the actual target devices; low-latency is the only effective hold there and our minSdk 29 makes it always available). Acquired when the cast starts, released on every teardown path (`endCast` all branches + `onDestroy`), non-reference-counted. This keeps the radio out of aggressive power-save while the app sits in the background during a game.
4. **Out of code, recorded for the user** (the source attacks directly, not an app concern): router-side hardening — disable band-steering/aggressive roaming, pin a fixed 5 GHz channel, per-MAC QoS for the phone and desktop.

## Consequences

- **Positive:** the diagnosed failure class — tens-of-seconds UDP loss with healthy TCP — recovers by itself in seconds: fresh offer over the healthy socket, desktop re-answers, video resumes with no `bye`, no QR churn, no user action, no re-pair. The mic pc is independent (ADR-003) and remains mic-local on failure: if it dies in the same outage it reports `MicState.Failed` and the user toggles it back — the cast itself is unaffected (a mic restart ladder is deliberately not built yet; see limitations).
- **Costs / accepted:** a genuinely dead network now takes up to ~60 s to surface `ConnectionLost` (was: the first `FAILED`, ~15–20 s). That trade is the point — the transient class is common, the permanent class is rare. The WifiLock holds the radio at higher power for the cast's duration (a cast is a streaming session; this is what streaming apps do).
- **Interaction with the honesty half (PR #85):** if the ladder does exhaust, the failure still *parks* the session (no `bye` over a healthy socket) — the desktop keeps its session and the phone reconnects in one tap. With the ladder, most drops should never reach that path.

## Known limitations

- The mic pc has no restart ladder of its own (scope: the media pc is the cast's lifeline; the mic is per-toggle and cheap to re-toggle). Revisit if mic users hit the same class of outage.
- The sustained-failure bound (60 s) is policy, not physics: a pathological AP that steers for 55 s repeatedly would keep the cast alive through each cycle — intended (that network is annoying but usable), and the bound caps the worst case.
- On-device verification of the recovery path (forced UDP outage mid-cast) is owed — recorded in [features/reliability.md](../features/reliability.md).

## Rejected alternatives

| Option | Verdict |
|---|---|
| Full pc rebuild per recovery attempt (the re-auth path) | **Rejected as the primary mechanism** — loses DTLS/ICE state and takes longer; kept as the fallback when the *socket* itself drops (each re-auth's `negotiate()` still rebuilds, and it joins the ladder's deadline watch without spending the restart budget). |
| Unlimited restarts while the socket lives | **Rejected** — an honest bounded window is an acceptance criterion; a dead network must surface `ConnectionLost`, not spin forever. |
| Restart from the desktop side | **Rejected** — violates the offerer rule (the mobile owns renegotiation; the desktop answers, webrtc.md). |
| `WIFI_MODE_FULL_HIGH_PERF` (the issue's letter) | **Rejected** — a no-op since API 34; the intent (radio out of power-save) needs low-latency mode on modern devices. |
| Router-side fixes in app docs as *the* mitigation | **Recorded for the user, not shipped as code** — the app must survive hostile APs on its own; the router advice is a complementary hardening note. |

## Verification pointers

- Mobile JVM: `IceRestartControllerTest` (12 scenarios — grace, immediate first restart, backoff ladder, deadline timeout, budget reset on recovery, exhaustion vs sustained-window give-ups, backoff-bypass and post-give-up inertness).
- Desktop: `receiverSession.test.ts` — "a mid-cast re-offer (ICE restart) replaces the answerer and re-attaches the sink" (old pc dropped, fresh answer, candidates routed to the fresh pc, sink re-attached, never blanked).
- On-device: the forced-outage case is logged in [features/reliability.md](../features/reliability.md) as pending; the live evidence that motivated it is issue #86 (both-side instrumented logs).
