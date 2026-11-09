# ADR-017: The receiver paces frames with a bounded 50 ms delay

- **Status:** accepted (2026-11-09) — verified on-device the same day (Lenovo TB321FU, PUBG Mobile, Performance preset → macOS native receiver). It refines [ADR-015](ADR-015-native-receiver-tauri-libwebrtc.md): libwebrtc keeps zero playout delay, and the receiver's renderer now decides *when* each frame shows.

## Context

ADR-015 gave the native receiver libwebrtc with **zero playout delay** (`WebRTC-ForcePlayoutDelay/min_ms:0,max_ms:0/`). That was to escape Chromium's ~91 ms video floor. In that mode a frame leaves the decoder the moment it is complete. The renderer then enqueued it with "now" as its presentation time, so the screen showed frames exactly as unevenly as Wi-Fi delivered them.

The user reported the video "masih putus-putus" (still stuttering) during PUBG. Measurements, 2026-11-09:

| Where | What was measured |
|---|---|
| Phone sender | Steady: 30 or 60 fps, 0 dropped, RTT 5–9 ms. The receiver got every encoded frame, so the network loses nothing. |
| Receiver, per frame (temporary probe) | Arrival gaps up to 139 ms at 30 fps, and 3–4 gaps over 50 ms each second. At 60 fps there were bursts 2–5 ms apart, then gaps of 50–160 ms, while the phone's own send timestamps stayed even (≤ 29 ms). |
| Both Wi-Fi links | 5 GHz channel 149 with strong signal. The Mac's AWDL interface was up; it is a plausible source of the gaps, but this was not proven — the user declined to disable it. |

Two causes sat on the phone side. Large drops while the game itself rendered few frames (loading, lobby) are not a receiver problem. Neither is the 97 → 30 fps decimation cadence: the Performance preset's 60 fps already makes it much finer.

## Options tried

| Option | Result |
|---|---|
| Keep zero delay (status quo) | Stutter, as measured above |
| libwebrtc's adaptive jitter buffer (`with_options(false, false)`) | Gaps over 50 ms fell from 3–4/s to 0–1/s, but **the user felt the delay**. Rejected. |
| Disable AWDL on the Mac | Not tested: the user chose not to change the system's Wi-Fi |
| **Our own pacer with a hard 50 ms cap** | **Chosen.** Average delay ~30 ms, max 50. The user's verdict: "Mulus, delay oke". |

## Decision

`pacing.rs` (pure, unit-tested) gives each frame a due time on the host clock:

- **Slot:** one estimated frame interval after the previous frame's due time. The estimate is a slow EWMA of arrival gaps.
- **Late frame:** never shown before it arrives. A frame that misses its slot shows on arrival, which pushes the schedule back and builds a cushion for the next gap.
- **Cap:** never held more than `MAX_DELAY` = 50 ms after arrival.
- **Drain:** the schedule runs `DRAIN` = 0.5 % faster than the stream, so a cushion drains back to the live edge (~5 ms/s at 60 fps) once Wi-Fi calms.
- **Restart:** a pause over 250 ms (a still screen sends no frames, or the window was hidden) restarts the schedule at the live edge.

`video.rs` puts the `AVSampleBufferDisplayLayer` on a timebase that *is* the host clock: `CMTimebaseCreateWithSourceClock`, rate 1, set to host time. Each sample's pts is its due time, and the layer shows it on that vsync. The pool allocation threshold rises from 8 to 10, because paced samples wait in the layer up to three more frames at 60 fps.

The 1 Hz `video:` log line carries the pacing figures: `pacing delay avg/max`, `late` (shown on arrival after a gap — a visible hitch) and `capped` (pulled forward by the cap).

## Consequences

- Up to 50 ms of added latency, ~30 ms on average during play, and ~0 on an even stream. libwebrtc's buffer measured smoother, but its delay was felt.
- **Gaps longer than 50 ms still show.** Removing them needs a quieter radio (for example AWDL off) or a larger cap.
- The capture-to-display chain no longer ends "on arrival". OBS window capture records the paced output, which is the intent.
- **Revisit if:** a user finds 50 ms too slow for fast games (lower `MAX_DELAY`), or gaps over 50 ms dominate (consider an adaptive cap). If the Rust API ever exposes the RTP timestamp, the slot could follow the sender's own cadence instead of an arrival estimate.
