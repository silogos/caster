# ADR-036: A cast survives a five-minute drop, over the cable and over Wi-Fi

- **Status:** accepted (2026-11-18), verified on the device (see Verification). The window is the product owner's ("waktunya 5 menit saja untuk cable dan wifi", 2026-11-18). Amends [ADR-009](ADR-009-cast-resilience-ice-restart-wifilock.md) (its 60 s / 3-restart bound) and [ADR-034](ADR-034-usb-cast-over-aoa.md) (unplugging ends the cast).

## Context

A cast that ends needs a new Share screen **and** a new screen-capture consent. That cannot be shortened: Android 14+ accepts a consent `Intent` for one `MediaProjection` only, and one projection may create one VirtualDisplay. The only way to resume without a new consent is to **never end the projection**.

Today the two links end a cast at very different moments:

| | Wi-Fi | Cable |
|---|---|---|
| Short drop | Survives: ICE-restart ladder (ADR-009) | **Ends at once** (`endCast("the cable link closed")`) |
| Bound | Bad stretch ≥ 60 s or 3 restarts → `ConnectionLost`; a dead socket stops after 10 reconnect steps (~3.3 min, `TRUSTED_RECONNECT_MAX_ATTEMPTS`) → `Failed` → `ConnectionLost` | — |
| While it lasts | Home still shows **ON AIR** | — |

The product owner wants pulling the cable (to move it, to charge from another port) and a Wi-Fi outage to be the same thing: the cast waits, then carries on by itself.

## Decision

1. **One window for both links: 5 minutes** (`CAST_DROP_WINDOW_MS = 300_000`, one named constant both paths read). A drop that lasts longer ends the cast as today.

2. **The cast keeps the projection while it waits.** The foreground service (type `mediaProjection`) stays, its notification says the cast is waiting and keeps **Stop**.

3. **The phone says so.** A new `CastState.Interrupted(desktopName, link, sinceMillis, endsAtMillis)` replaces ON AIR while a drop lasts, on both links. The annunciator reads `RECONNECTING` (the receiver's deck uses the same word). Home shows why (Wi-Fi lost / cable unplugged), how long it waits, and Stop. Drawn first in `designs/mobile-app.figcraft`, page *Mobile design v5 · Cast survives a drop (ADR-036)*.

4. **Over the cable** (the cast now outlives its link):
   - Link closed mid-cast → the encoder stops and releases its codec; game audio and mic stop their `AudioRecord`s. The **VirtualDisplay stays** with `setSurface(null)`: no frame is captured or encoded while nobody receives. The VirtualDisplay moves from `UsbVideoEncoder` to the service, which owns it for the whole cast.
   - The mic's foreground-service type stays while waiting: re-adding `microphone` from the background is refused on Android 14+, and keeping the type without recording records nothing.
   - The link is Ready again **with the same desktop** (`desktopId`) → a new encoder's input surface goes onto the same display (`setSurface`), game audio and the mic restart as they were, and `cast-start` is sent again. The receiver already builds a fresh decoder per `cast-start` (`UsbMedia` per session): **no link-protocol change**.
   - Another desktop on the cable → the waiting cast ends. The screen never resumes to a Mac the user didn't cast to.

5. **Over Wi-Fi** (ADR-009's ladder, bounded by time instead of count):
   - The ICE ladder's honest bound becomes the window: `SUSTAINED_FAILURE_GIVE_UP_MS` = `CAST_DROP_WINDOW_MS`. `MAX_RESTARTS` no longer closes the ladder; restarts keep coming on the backoff's last rung (10 s) until the window ends.
   - **During a cast**, the signaling socket's trusted reconnects are bounded by the same window from the moment it dropped, not by 10 attempts, and its backoff stops at 2 s instead of 30 s: an attempt with no network fails at once, and a 10 s rung brought the picture back ~8 s after Wi-Fi did (found live). Between casts the reconnect rules are unchanged (pairing.md).
   - A connection made from a **QR** resumes the persistent session its auth-ok granted on its next reconnect (hello `resume`, sid = deviceId, trustKey, no expiry). Found live: a cast begun right after a scan died on its first drop once the QR's ten minutes had passed ("session expired").
   - **Re-auth re-offers when an offer was lost.** A restart offer sent while the socket was down never reaches the desktop, whose pc may have failed and stopped its frame loop, while ours reconnects on the old credentials. On re-auth a connected pc with an unanswered offer re-offers on the same pc (the receiver keeps its pc for the same session, ADR-019); a connected pc with nothing pending is kept, as before. Found live: the Mac stayed on "waiting for cast".
   - `Interrupted` starts when the ladder starts (a `failed`, or a `disconnected` outliving its 10 s grace) and ends at the next `connected`. A socket drop alone is not an interruption: media may still flow over the pc.
   - The encoder keeps running into the unconnected pc while it waits, as it already does for 60 s. Pausing it would touch libwebrtc's capturer; not done (see Consequences).

6. **Still ends at once:** the user's Stop (app or notification), the projection stopped by the system (on the TB321FU, locking the screen does this, [reliability.md](../features/reliability.md) L2), a `bye` from the desktop, process death.

## Consequences

- **Positive:** unplugging and replugging within 5 minutes needs no Share and no consent; neither does a Wi-Fi outage of up to 5 minutes (was 60 s). The phone stops claiming ON AIR while nothing reaches the desktop.
- **Privacy:** the system's screen-sharing indicator stays on for up to 5 minutes after a drop. Over the cable nothing is captured in that time (no surface); over Wi-Fi frames are encoded but go nowhere. The notification and Home say the cast is waiting and offer Stop.
- **Battery:** a Wi-Fi cast keeps encoding for up to 5 minutes into a dead link (was 60 s), and keeps the WifiLock. Accepted for now; pausing capture is a follow-up if it shows in a measurement.
- **A dead network surfaces later:** `ConnectionLost` now takes up to 5 minutes (was ~60 s). That is the request.
- **A cast resumes only on its own link.** A Wi-Fi cast whose network dropped does not move to a cable plugged in meanwhile, nor the other way round; Stop, then Share.

## Rejected alternatives

- **Reuse the consent `Intent` for a new projection.** Refused by Android 14+ (`SecurityException`); the reason this ADR exists.
- **Keep the USB encoder running while unplugged**, writing nowhere. It would capture and encode the screen for minutes for nobody, and the codec cannot resume a link anyway (the receiver needs SPS/PPS and a keyframe on the new link).
- **Different windows per link** (60 s for Wi-Fi, 5 min for the cable). Rejected by the product owner: the same drop should behave the same way.

## Verification

TB321FU (Android 16) → MacBook Pro M1 Pro, 1920×1200 @ 120 fps, 2026-11-18:

- **Cable, unplugged and back:** `cable gone mid-cast — waiting up to 300 s`, replugged after 6 s → `cable back — the cast carries on`, a new encoder on the same display, no new projection (`dumpsys media_projection`: one) and no consent dialog; the cast then ran 3+ minutes with game audio.
- **Cable, Stop while waiting:** `cast over USB ends: stopped by the user`, no error.
- **Wi-Fi off ~44 s** (a cast begun from a fresh QR pairing): the Mac's pc `Failed` and its frame loop stopped; Wi-Fi back at 03:56:48 → the phone reconnected 1 s later with `resume=true` → re-offer on the same pc → picture back at 03:56:50, no Share. A 1 s blip afterwards recovered by itself.
- **Before the fixes above** (same day): a lost restart offer left the Mac on "waiting for cast"; a QR session past its ten minutes ended the cast at the drop; the 10 s rung delayed the picture ~8 s. Each is now pinned by a JVM test.
- JVM: `IceRestartControllerTest` (restarts past 3, give-up at the window, the interruption's start and end), `SignalingClientTest` (the window and 2 s rungs during a cast; a QR session resumes after its expiry), `ReauthActionTest`, `UsbCastDropTest`. Mobile unit tests: 438, 0 failures.
- **Not verified:** a full five-minute wait ending in the timeout message on the device (JVM only); a different desktop on the cable during a wait (the single saved pairing makes it a re-pair, JVM only).
