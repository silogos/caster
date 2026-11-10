# Latency measurement

How to measure the cast's glass-to-glass latency — video from the phone's screen to the Mac's screen, game audio from the phone's mix to the Mac's playout — and what it measured. Use it before and after any change that claims to cut latency.

## Result in one paragraph (2026-11-10)

**Video on the Mac appears at the same moment as on the phone's own screen** (median 0–1 ms against SurfaceFlinger's actual present time, range about −18 to +20 ms). Earlier figures of 46–117 ms "phone screen → Mac screen" were inflated by an assumption in the tool: Android holds an app's frames 2–4 vsyncs before its own panel shows them (SurfaceFlinger `actual − desired` present 47–64 ms, varying per activity instance), while the virtual display captures them earlier. **Game audio on the Mac trails the phone's own speaker by ~150 ms** (click → phone speaker ~101 ms, click → Mac playout ~250 ms). Nearly all of it is Android's playback-capture path, about 220 ms from the click entering a track to leaving the capture record; it does not move with the capture record's buffer size. Wi-Fi adds ~4 ms and the Mac ring ~28 ms (its 40 ms target was a mean; arriving audio waits less). Nothing left on the video side is worth cutting. On the audio side, the Mac was the only part this app controls: [ADR-020](../decisions/ADR-020-audio-playout-steers-the-trough.md) took 8 ms out of the output buffer and made gaps shorter, and found the ring itself had nothing safe left to give.

## How it works

| Piece | Where | What it marks |
|---|---|---|
| `LatencyTestActivity` | `apps/mobile/app/src/debug/` (debug builds only) | Flips the screen black ↔ white once a second, logging the vsync each flip was drawn for. A strip along the top changes every frame, so the stream runs at full fps like a game (a still screen sends no frames and would test an idle encoder). Writes a click into an already-running low-latency `AudioTrack` on every white flip, logging the moment. |
| `LatencyClickProbe` | `apps/mobile/.../debug/`, called from the ADM samples callback in debug builds | When the click comes out of the playback-capture record — splits the audio path into "inside the phone" and "phone → Mac". |
| `latency_probe.rs` | the native receiver, on with `ZFC_LATENCY_PROBE=1` | When each flip is decoded (sampled luma of the frame's centre) and how long the pacer holds it; when each click arrives and the playout ring depth ahead of it. |
| `tools/latency/measure.py` | Mac | Aligns the two clocks over adb and pairs the marks. |

Only a sampled luma mean and a peak amplitude are read — no frame or audio content is logged.

**Clock alignment.** The phone prints its clock over `adb shell` every 50 ms; the smallest (Mac arrival − phone time) is the offset plus the fastest one-way trip. Phone marks therefore land a few ms late on the Mac clock, and every figure reads a few ms low. The phone and Mac clocks differed by ~0.95 s in practice, so alignment is not optional.

**The phone's screen time.** On Android 13+ the pattern logs Android's expected presentation time for each flip (`Choreographer.FrameData`). SurfaceFlinger's `--latency` dump then shows how much later the panel actually showed the pattern's frames (`actual − desired`), and the run helper passes that as `--phone-screen-lag`. The "vs the phone's ACTUAL screen" line is the cast's real latency. On older Android the tool falls back to "one vsync after the frame". The Mac side adds the pacer hold and one 120 Hz refresh. Audio excludes the Mac's output device buffer.

**Audio reference.** The click track logs its own latency to the speaker (`track_ms`, from `AudioTrack.getTimestamp`) — the phone-speaker reference the Mac's audio is compared against.

## Running it

1. Receiver: `ZFC_LATENCY_PROBE=1 ./target/release/desktop-native > rx.log 2>&1`
2. Phone (debug build, cast running): `adb logcat -v time -s LatencyTest:I > phone.log`, then `adb shell am start -n com.zerofriction.localcast/.debug.LatencyTestActivity`
3. Keep the Mac's video window frontmost (a hidden window renders nothing, so no video marks) and leave the phone untouched for ~70 s.
4. Read the pattern layer's present lag: `adb shell dumpsys SurfaceFlinger --latency '<the LatencyTestActivity layer>'` — median of (column 2 − column 1) over the rows, in ms.
5. `python3 -I tools/latency/measure.py --logcat phone.log --receiver rx.log --phone-screen-lag <ms>` while adb is still connected (it aligns the clocks live).

Before ADR-019, restarting the receiver mid-cast left game audio on the fallback track (see "Reconnect" below), adding ~60 ms.

## Baseline (2026-11-10)

Lenovo TB321FU (Android 16) → MacBook Pro M1 Pro, 5 GHz Wi-Fi, cast 1920×1200 @ 60 fps, H.264 hardware encoder (libwebrtc's stock settings), game audio PCM16 over the DataChannel ("Low-latency game audio" on). ~70 flips and ~35 clicks per run.

| Path | Run A | Run B | Run C |
|---|---|---|---|
| Video: phone screen → Mac decoded | 44 ms (25–61) | 51 ms (43–68) | 102 ms (74–128) |
| **Video: phone screen → Mac screen (est.)** | **63 ms** (43–85) | **67 ms** (60–76) | **117 ms** (94–144) |
| Audio: click into phone track → captured | — | 219 ms (212–230) | 218 ms (204–229) |
| Audio: captured → Mac arrival | — | 5 ms (3–13) | 5 ms (3–10) |
| **Audio: click → Mac playout** | **250 ms** (245–274) | **254 ms** (246–256) | **253 ms** (242–257) |

Medians, range in brackets. Each run is a separate cast.

**What it shows:**
- **Video is at scrcpy's level** (35–70 ms claimed) in two casts of three — but one cast ran ~50 ms slower to decode on the same setup, steady for the whole run. Not explained yet; the encoder's own buffering is the first suspect (the stock libwebrtc encoder sets no latency or priority key).
- **Game audio is ~190 ms behind the picture**, and **~218 ms of it happens inside the phone**, between the click entering an `AudioTrack` and leaving the playback-capture record. Wi-Fi adds ~5 ms; the Mac's ring ~30–60 ms.
- **Not a start-up backlog.** An experiment drained the capture record when the ADM took it over: it held 64 ms (its whole buffer), and draining it changed nothing (218 ms after). The delay is Android's playback-capture path (the remote-submix pipe and the mixer buffering in front of it), which an app does not control. The figure also includes the test track's own output buffering, which a game's audio has as well.

## With the low-latency encoder keys ([ADR-018](../decisions/ADR-018-low-latency-encoder-keys.md))

Same setup, encoder configured with `latency=1, priority=0` (accepted by the codec). Two passes per cast where marked: the pattern asking for the display's highest refresh, then for 60 Hz.

| Cast | Pass | Video → Mac screen | Audio click → Mac playout |
|---|---|---|---|
| D | — | 113 ms | 252 ms |
| E | highest refresh / 60 Hz | 46 / 62 ms | 243 / 249 ms |
| F | highest refresh / 60 Hz | 82 / 51 ms | 233 / 252 ms |

- **No measurable gain from the keys:** stock 63–117 ms, with keys 46–113 ms.
- **The refresh request is not the variable either:** the faster pass flipped between casts E and F, and the phone reported 60 Hz in every pass.
- **The real finding is the spread.** Each pass is tight (±10 ms) but passes differ by up to ~60 ms, even within one cast. A fixed offset that is set at some moment and then held suggests a standing queue in the phone's capture → encode path (steps of one or two 60 Hz frames). Finding it is the next latency task.

## Where the spread is not (2026-11-10, casts G–I)

Per-segment probes were added to find the standing queue behind the pass-to-pass spread:
- **capture age** (phone, debug): now minus the virtual display buffer's timestamp when the capture thread gets the frame;
- **sender stats:** encode time and libwebrtc's pacer wait (`totalPacketSendDelay`);
- **DataChannel send-buffer fill** (phone, debug);
- **receive stats** (receiver, `ZFC_LATENCY_PROBE`): decode time, packet-to-decoded processing delay, jitter buffer.

| Cast | Pass | Video → Mac screen | Capture age | Encode | Pacer | Mac decode / processing | Jitter buffer |
|---|---|---|---|---|---|---|---|
| H | highest / 60 Hz | 79 / 47 ms | 0.8 / 0.8 ms | ~16 ms | — | 4.8 / 5.0, 6.8 / 6.9 ms | 0.1 ms |
| I | highest / 60 Hz | 84 / 50 ms | 0.9 / 0.9 ms | ~16 ms | 0.0 ms | 6.1 / 6.2, 7.0 / 7.1 ms | 0.0 ms |

- **Every measured segment is identical between a fast and a slow pass**: capture handoff under 1 ms, encode ~16 ms, no pacer wait, ~6–7 ms on the Mac, no jitter buffer. The ~30–35 ms difference therefore sits in the one segment not measured: **on the phone, between the pattern drawing a frame and the virtual display composing it** (the buffer timestamp is stamped at composition).
- It follows the pattern's refresh request more often than not: the pass asking for the display's highest rate was the slower one in 3 of 5 casts (equal in 1, faster in 1). Games ask for high rates too, so this likely applies to gameplay.
- **Audio send buffer:** in cast H the DataChannel leg took 64 ms instead of ~5 ms for the whole cast, which matches the `MAX_BUFFERED_BYTES` cap (12 000 B ≈ 62 ms of PCM). The buffer probe arrived one cast later, and in cast I the buffer stayed empty. The cap is a plausible standing queue, but it is not proven.

## Final passes (2026-11-10, casts J–M)

| Measure | Result |
|---|---|
| **Video vs the phone's actual screen** | **0 / 1 / −0 / 0 ms** median (four passes; range −18 to +20) |
| SurfaceFlinger actual − desired present (pattern) | 47–64 ms, per activity instance |
| Audio: click → phone speaker (`track_ms`) | 101–104 ms, the same with no cast running |
| **Audio: click → Mac playout** | **236–265 ms** |
| — click → out of the capture record | 200–229 ms |
| — capture record → Mac arrival (DataChannel) | 4 ms |

Experiments, all reverted:

| Experiment | Result |
|---|---|
| Capture record buffer 64 → 32 ms | Click → captured 200 / 223 ms vs 212–229 ms: within noise |
| Mac ring target 40 → 30 ms | 2 underruns/min (0 at 40 ms); click → Mac 244–249 ms, within noise |
| Mac ring target 40 → 20 ms | 84–120 underruns/min — audible dropouts |
| Audio send-buffer cap 12 000 B → 30 ms of audio | 3 backpressure drops per cast, no latency change; its premise (below) was wrong |

The 40 ms ring (the product owner's choice, desktop-native.md) stands: 30 ms saves ~10 ms within run-to-run noise for 2 dropouts a minute, on a quiet test stream rather than a game.

**Reconnect leaves game audio on the fallback track (bug, not fixed here).** Cast H's DataChannel leg of +64 ms was not a send backlog. The receiver had been restarted mid-cast:
1. The phone's re-auth kept the media pc ("media pc still connected — keeping it").
2. Its ICE restart reached the new receiver process.
3. The new receiver got video and the Opus fallback track, but **never a `game-audio` DataChannel**: the phone treats its channel as open and does not re-announce it.
4. The phone kept sending into a channel with no peer (1 536 backpressure drops in one pass).
5. The Mac played the mono Opus fallback, ~60 ms later than the DataChannel path.

Reproduced on purpose in cast K. **Fixed by [ADR-019](../decisions/ADR-019-ice-restart-keeps-the-receivers-pc.md)**, which also covers the more common case it shares a cause with: every ICE restart lost the channel, because the receiver built a new pc per offer.

## Where the audio delay sits inside Android (2026-11-10, later)

`adb shell dumpsys media.audio_flinger` during a cast shows the buffers a captured sound crosses after the phone's mixer has it. All of them belong to the system audio server, not to this app:

| Stage (TB321FU, Android 16) | Size | Measured |
|---|---|---|
| Tee buffer (`PatchTrack` on the remote-submix output thread) | 6 976 frames | holds **2 944–4 096 frames, ~73 ms on average**, the whole time |
| Remote-submix output mixer | 1 024 frames | one 21 ms period |
| Remote-submix pipe | 4 096 frames | about one period standing (~21 ms) |
| Capture input thread | 1 024 frames | one 21 ms read |
| App record buffer | 3 072 frames | empty (the ADM drains it) |

- The tee buffer is the largest single item. It starts playing only once it is about half full, and that fill then stays for the life of the game's track. Its level differs per track by start timing (40–85 ms).
- The speaker path is slow too (click → speaker ~102 ms, 80 ms of it the test track's own full buffer). That is why the capture trails the speaker by ~120 ms and not by the full ~220 ms.
- **Experiment: a second capture record at 96 kHz and at 192 kHz** (debug build, same clicks, both records read at once). The buffers above are sized in frames, so a higher rate would have shortened them. The system accepted both records, but kept the submix threads at 48 kHz / 1 024 frames and resampled for the client:

| Probe record | Click → captured (48 kHz record / probe record) |
|---|---|
| 96 kHz | 221 / 208 ms |
| 192 kHz | 224 / 221 ms |

The 13 ms at 96 kHz was that probe's tee buffer happening to start lower (1 920–2 816 frames in the dump); 192 kHz showed no gain. Reverted.

**What this leaves:** on this device nothing in the Android half is reachable through public APIs. A capture that bypasses the tee needs a system-only routing permission. The app-side remainder is the 10 ms ADM read, ~4 ms of Wi-Fi, the 40 ms Mac ring, and the Mac's output device buffer (cpal default, not measured).

## The Mac side (2026-11-10, [ADR-020](../decisions/ADR-020-audio-playout-steers-the-trough.md))

`ring at beep arrival` is the receiver probe's ring depth ahead of each click: how long arriving audio waits before it plays. Old and new binaries alternated on one cast, midday Wi-Fi, ~118 s per run:

| Playout | Wait in the ring (median) | Underruns | Silence while starved |
|---|---|---|---|
| Old: mean depth 40 ms, 512-frame IO buffer | 28 / 30 ms | 3 / 2 | not counted (each rebuilt 40 ms) |
| Trough margin 10 ms, 128-frame IO buffer | 20–21 ms | 6–10 | 42–76 ms |
| Margin 12 ms | 25 ms | 5 | 67 ms |
| Margin 15 ms | 27–28 ms | 2–4 | 0–86 ms |
| Margin 20 ms | 32 ms | 2 | 19 ms |
| **Shipped: margin 15 ms** (178 s) | **26 ms** | **7** | **102 ms** — one 94 ms network stall (3 underruns), then gaps of 1, 4 and 3 ms |

- The old 40 ms was a mean over a sawtooth, so arriving audio waited ~28–30 ms. A 15 ms trough margin is the same wait with as few gaps.
- Lateness beyond ~10 ms is common enough on this network that a 10 ms margin triples the gaps for ~8 ms.
- The IO buffer is the real saving: 512 → 128 frames, 8 ms less output delay. That figure comes from the buffer sizes (cpal reports the callback → playback delay as the buffer length), not from an acoustic measurement.
- Wi-Fi was busier than in the night runs above, where the old playout had 0 underruns in ~70 s.

**Limitations:** one device, one network; the phone vsync and Mac refresh terms are assumed, not measured; audio stops at the ring, before the Mac's output device.
