# ADR-019: An ICE restart keeps the receiver's PeerConnection; a restarted receiver gets a fresh one

- **Status:** accepted (2026-11-10) — verified on-device the same day (Lenovo TB321FU → macOS native receiver). Amends [ADR-009](ADR-009-cast-resilience-ice-restart-wifilock.md)'s "the desktop changes nothing" for the native receiver.

## Context

ADR-009's recovery ladder sends an ICE restart: a fresh offer on the phone's **same** pc, so tracks, DTLS and SCTP survive. On the receiver side it relied on "the answerer is rebuilt per offer" (true for the Electron receiver, with sinks re-attaching on `ontrack`). The native receiver does the same thing: `MediaAnswerer::answer` builds a **new** PeerConnection for every offer. For video that is harmless. For the `game-audio` DataChannel it is fatal:
1. The new receiver pc has a new DTLS/SCTP endpoint.
2. The phone's channel is still "open" on its side, so it never re-announces the channel (DCEP).
3. The receiver never gets `on_data_channel`, and game audio falls back to the mono Opus track (~60 ms later) for the rest of the cast.

Found while measuring latency (latency-measurement.md, casts H and K): the phone logged 1 536–1 725 backpressure drops into a channel with no peer.

There are two ways in:
- **an ICE restart to the same receiver process** (a Wi-Fi blip — ADR-009's case);
- **a receiver restarted mid-cast:** the phone's re-auth keeps its pc ("media pc still connected"), and its ICE restart then reaches a fresh process.

## Decision

1. **Receiver (`webrtc.rs`):**
   - An offer whose SDP session id (`o=` line, fixed per pc across renegotiations — JSEP) equals the one the current pc answered is a renegotiation of the same phone pc. It is applied to the **existing** pc: `set_remote_description` / `create_answer` / `set_local_description`. DTLS, SCTP and the channel survive.
   - The frame loop restarts on the kept video track, because a `Failed` state on the way ends it.
   - Local candidates go through a sender swapped on every answer, so they reach the phone's current signaling connection.
   - A new session id still builds a new pc, as before.
2. **Phone (`MediaCastSession`):** an answer whose session id differs from the previous answer on the same pc comes from a **different remote pc** (the receiver restarted). It is not applied; the phone runs a full `negotiate()` (new pc, new channel) instead. Pure helper: `SdpSession`.
3. **Debug only:** `DebugIceRestartReceiver` (`adb shell am broadcast -n com.zerofriction.localcast/.debug.DebugIceRestartReceiver`) asks the running cast for one ICE restart. It exists so this path can be exercised without breaking the network.

No protocol message changes: both sides read a field every SDP already carries.

| Option | Verdict |
|---|---|
| Phone re-opens the channel after every ICE restart | Rejected: on a kept receiver pc the old channel still works, and a duplicate would need receiver-side dedup |
| Receiver keeps the pc on any re-offer | Rejected: a different phone pc (after a phone-side `negotiate()`) must get a fresh pc |
| **Session-id match on both sides** | **Chosen**: each side tells "same remote pc" from "new remote pc" with no new message |
| Desktop instance id in `hello` | Rejected: a protocol change (version bump) for information the SDP already has |

## Consequences

**Verified on-device, 2026-11-10:**
- **ICE restart, same receiver** (debug trigger): `re-offer on the same session (ice restart) — keeping the pc`; the next frame arrived 4 ms later; the DataChannel's chunk counter kept counting with no switch to the fallback track.
- **Receiver restarted mid-cast:** the phone logged `answer from a new desktop session — rebuilding the media pc`; the new receiver got `data channel arrived` and `source switch → DataChannel` within ~1 s.

**Known limitation, not addressed:** after a long UDP outage (~35 s, simulated by freezing the receiver), ICE survives without a restart, but the DataChannel only resumes ~60 s later. That is SCTP's retransmission backoff (RTO up to 60 s), and the fallback track covers the gap. Revisit if real outages that long turn out to be common: the cure would be a phone-side "channel stalled" detector that rebuilds the pc.
