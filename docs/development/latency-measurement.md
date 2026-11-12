# Latency measurement

How to measure the cast's glass-to-glass latency — video from the phone's screen to the Mac's screen, game audio from the phone's mix to the Mac's playout — and what it measured. Use it before and after any change that claims to cut latency.

## Result in one paragraph (2026-11-10)

**Video on the Mac appears at the same moment as on the phone's own screen** (median 0–1 ms against SurfaceFlinger's actual present time, range about −18 to +20 ms). Earlier figures of 46–117 ms "phone screen → Mac screen" were inflated by an assumption in the tool: Android holds an app's frames 2–4 vsyncs before its own panel shows them (SurfaceFlinger `actual − desired` present 47–64 ms, varying per activity instance), while the virtual display captures them earlier. **Game audio on the Mac trails the phone's own speaker by ~150 ms** (click → phone speaker ~101 ms, click → Mac playout ~250 ms). Nearly all of it is Android's playback-capture path, about 220 ms from the click entering a track to leaving the capture record; it does not move with the capture record's buffer size. Wi-Fi adds ~4 ms and the Mac ring ~28 ms (its 40 ms target was a mean; arriving audio waits less). Nothing left on the video side is worth cutting. On the audio side, the Mac was the only part this app controls: [ADR-020](../decisions/ADR-020-audio-playout-steers-the-trough.md) took 8 ms out of the output buffer and made gaps shorter, and found the ring itself had nothing safe left to give.

**Update (2026-11-12):** the ~150 ms is the click track's delay against the phone speaker, not the gap between a game's sound and its picture. In a real PUBG live recording, gunshot sound and muzzle flash were within ±40 ms of each other with no sync offset set — see "Game audio vs picture in a real live recording" below.

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

## Game audio vs picture in a real live recording (2026-11-12)

The figures above use the debug pattern's click track. To check what a viewer actually gets, the product owner's own live-stream recording of a PUBG session was measured, `Record_2026-11-12-20-17-40_001.mp4`: 1920×1080 at 60 fps, AAC 48 kHz stereo, about 24 minutes. The cast was Lenovo TB321FU → Mac, and the recorder had **no audio sync offset** set.

**Method.** Gunshots fired through a scope give a clean marker on both sides. The muzzle flash appears in a fixed area under the scope, and the shot has a sharp sound onset.
- Picture: per-frame mean of bright, warm pixels (`R > 200`, `G > 120`, weighted by `R − B`) in that area, from frames decoded at 60 fps.
- Sound: peak amplitude in 1 ms bins of the mono downmix. The onset is the first bin where the level jumps from near silence to full scale.
- Both were taken from the same ffmpeg seek. The source frame timestamps were checked around the shots: no gaps, 16.7 ms apart.

| Moment in the recording | First flash | Sound onset | Sound vs picture |
|---|---|---|---|
| 16:57.9, full-auto burst (first shot) | 16:57.933 (faint), 16:57.950 (bright) | 16:57.964 | **+15 to +30 ms** |
| 03:48.0, single shot | 03:48.067 | 03:48.069 | **~0 ms** |
| 03:48.9, single shot | 03:48.917 | 03:48.876 | **−40 ms** (sound first) |

- All three are within ±40 ms, one or two frames, with no lean towards audio being late. That is below what people notice; broadcast practice (ITU-R BT.1359) puts the detectable lag of audio at around 125 ms.
- One orange glow at 03:48.58–03:48.80 was left out. It lasts ~230 ms with no new shot sound, which is too long for a muzzle flash. The picture was not frozen: the view kept moving, and the timestamps have no gap.
- It matches the product owner's listening check during live play, with a headset plugged into the Mac.

**What this means for the ~150 ms above.** The click-track figure is real for that track, but it does not describe a game's sound against its own picture. The click and the white flip are issued together, and the phone's speaker path for that test track holds ~80 ms in the track's own buffer (see "Where the audio delay sits inside Android"). A game's audio and frames each have their own queues, and in this recording they arrive together. **Why the two differ has not been worked out.** Until it has, the in-game recording is the figure to quote for A/V sync, and the click track remains the tool for comparing builds.

**Limits.** One device, one game, one recording, three shots. The offset is measured between the flash and the sound as the game produces them, so any offset inside PUBG itself is included, which is also what viewers get.

## Audio starves in a long game cast (2026-11-10/11, open)

Found in the log of a 62-minute cast with a game running on a hot tablet (thermal SEVERE): the Mac's playout starved **9 times a minute** (597 underruns, 9.8 s of silence, median 13 ms each), against 0.3–2.6 a minute in the short casts above with an idle tablet.

What the existing log settles:

- **Not the IO buffer.** Another app on the Mac reset the output device to 512 frames 3 minutes in (the device's buffer size is shared; the receiver's 128 is a request, not a lock). The starve rate was the same before and after (10.6 a minute in the first 3 minutes at 128 frames).
- **Not video hiccups.** Seconds with a starve and seconds without had nearly the same video frame rate (49 vs 54 fps).
- **The ring was restless:** trough p10–p90 of 6–41 ms (idle: 12–17 ms) and a median depth of 55 ms (idle: 36–40 ms), which is what bursty arrival looks like.
- The 1 Hz log could not show a late chunk, so it could not say where the lateness came from.

**Probes added** (the longest pause between consecutive events in each second; 30 ms and more counts as late — a capture block is 21 ms):

| Point | Where | Log |
|---|---|---|
| A captured block reaches the app | phone, debug | `LatencyTest: audio read gap n=… max=… ms, long=…` |
| A chunk is handed to the DataChannel | phone, debug | `LatencyTest: audio send gap …` |
| A chunk arrives | receiver, always | `gap=<ms>/<long>` on the `dc:` line |

**Baseline, idle and cool tablet, test pattern (TB321FU → MacBook Pro M1 Pro, 2 minutes):**

| Point | Longest pause per second: p50 / p90 / max | Seconds with a pause ≥ 30 ms |
|---|---|---|
| Capture → app | 23 / 25 / 31 ms | 1 of 125 |
| App → channel | 24 / 27 / 36 ms | 2 of 125 |
| Arrival on the Mac | 26 / 31 / 43 ms | 21 of 147 |

- 3 underruns (10 ms of silence), each in a second whose longest arrival pause was 31–43 ms: a pause of a capture block (21 ms) plus the 15 ms margin starves the ring.
- Even idle, the network and the Mac's receive path add up to ~10 ms to the phone's rhythm.

**With a game running (same devices, 6.4 minutes, 1920×1200 60 fps, 8–12 Mbps, tablet at thermal SEVERE):** 128 underruns, 1.8 s of silence.

| Point | Longest pause per second: p50 / p90 / p99 / max | Seconds with a pause ≥ 30 ms |
|---|---|---|
| Capture → app | 24 / 28 / 33 / 42 ms | 19 of 382 |
| App → channel | 29 / 34 / 59 / 705 ms | 140 of 381 |
| Arrival on the Mac | 32 / 65 / 149 / 749 ms | 258 of 381 |

The same cast in half-minute steps, against the video sender's own queue (libwebrtc's pacer wait per packet, from the phone's stats line):

| Half minute | Video | Pacer wait | Arrival pause p90 / max | Underruns |
|---|---|---|---|---|
| 00:55:30 – 00:56:30 | 1.6 Mbps, ~40 fps | 0 ms | 27–28 / 38 ms | 0 |
| 00:56:30 | 3.4 Mbps, 16 fps | 294 ms | 100 / 138 ms | 6 |
| 00:57:00 | 6.7 Mbps, 28 fps | 61 ms | 104 / 251 ms | 9 |
| 00:57:30 | 7.7 Mbps, 28 fps | 206 ms | 84 / 749 ms | 67 |
| 00:58:00 | 8.2 Mbps, 37 fps | 590 ms | 95 / 435 ms | 38 |
| 00:58:30 | 9.1 Mbps, 59 fps | 90 ms | 86 / 93 ms | 3 |
| 00:59:00 – 01:01:30 (five steps) | 10.2–10.8 Mbps, 59 fps | 2–7 ms | 33–41 / 37–46 ms | 0 |
| 01:01:30 | 10.0 Mbps, 58 fps | 2 ms | 55 / 149 ms | 2 |
| 01:02:00 | 6.7 Mbps, 32 fps | 226 ms | 59 / 122 ms | 3 |

- **Android's capture is not the source.** Blocks reach the app almost as evenly as on an idle tablet (p90 28 vs 25 ms).
- **A game at full quality is not the source either:** two and a half minutes at 10–11 Mbps and 59 fps had no underrun.
- **The audio is late when the video sender is backed up.** Every half minute with a pacer wait over 50 ms had underruns; of the eight under 10 ms, seven had none. In the worst minute the phone's DataChannel send buffer filled to its cap and the app dropped 106 chunks (1.06 s of audio) by backpressure, so the audio was already waiting on the phone, behind the same uplink as the video.
- A small part shows in the app's own send call (p90 34 ms, with two calls blocked for 319 and 705 ms in that minute); most of it appears after the hand-over.

**Not settled:** whether the video backlog delays the audio, or one cause (a slow Wi-Fi uplink at that moment) delays both. Either way the audio shares the video's transport and has no priority on it. The first experiment is a lower video bitrate floor (the Sharp preset holds 8 Mbps), which lets libwebrtc back the video off when the uplink cannot carry it.

### With "Keep frame rate" below the window (2026-11-11, [ADR-023](../decisions/ADR-023-keep-frame-rate-follows-the-network.md))

One 14-minute cast, same devices and settings (1920×1200, 60 fps, 8–12 Mbps), with *Under network pressure → Keep frame rate*. Not a game and not a hot tablet: a video streamed on the tablet (4K for the first seven minutes, then a 60 fps one), thermal status NONE throughout. It shows what the lower floor does; it is not a like-for-like comparison with the game cast above.

| | Streamed 4K video (7.4 min) | 60 fps video (6.0 min) |
|---|---|---|
| Sent bitrate p10 / p50 / p90 | 2.9 / 4.9 / 7.9 Mbps | 5.3 / 9.6 / 12.1 Mbps |
| libwebrtc's target, lowest | 2.3 Mbps | 5.5 Mbps |
| Seconds with the target under the old 8 Mbps floor | 351 of 438 | 111 of 359 |
| Frame rate p10 / p50 / p90 | 38 / 43 / 51 fps | 59 / 60 / 61 fps |
| Frame rate in the seconds under 8 Mbps, p10 / p50 | 36 / 44 fps | 59 / 60 fps |
| Seconds at 1280×800 | 30 | 149 |
| Seconds with a pacer wait ≥ 60 ms / longest wait | 75 / 2089 ms | 35 / 258 ms |
| Underruns / silence | 42 / 0.69 s (5.7 a minute) | 34 / 0.61 s (5.7 a minute) |

The three probes over the whole cast:

| Point | Longest pause per second: p50 / p90 / p99 / max | Seconds with a pause ≥ 30 ms |
|---|---|---|
| Capture → app | 23 / 24 / 27 / 29 ms | 0 of 833 |
| App → channel | 24 / 27 / 33 / 64 ms | 20 of 832 |
| Arrival on the Mac | 27 / 48 / 114 / 158 ms | 273 of 816 |

- **The floor does what it was built for.** The target went down to 2.3 Mbps, the frame went to 1280×800 and came back, and the encoder refused no frame. In the 60 fps video, the 111 seconds under 8 Mbps still ran at 59–60 fps.
- **The frame rate of the first half is not explained, and it is not the bitrate.** It was 40–47 fps in the 87 seconds with the target at 8 Mbps or more too, and it rose to 60 while the target was still 4–5 Mbps, within seconds of the content changing. The content itself or the tablet's work on a 4K stream; not measured. During that half the tablet's Wi-Fi was downloading 36.7 Mbps beside the cast's 7.1 Mbps up (one 5 s sample; 0.1 Mbps down in the second half).
- **The audio still starves, and it does not follow the video queue.** 80 underruns in 13.9 minutes (1.46 s of silence), at the same rate in the seconds near a pacer wait of 60 ms or more (22 in 215 s) as in the seconds with the queue under 10 ms (41 in 427 s). Every one of them fell in a second with an arrival pause of 30 ms or more; the 543 seconds without such a pause had none.
- **The pauses appear after the hand-over.** The phone captured and handed over on time (late in 0 and 20 seconds); the Mac saw a late chunk in a third of all seconds, against one in seven on an idle tablet.

**What this settles:** the video backlog is not the only thing that delays the audio. With the queue empty and the tablet cool, a pause of a capture block (21 ms) plus a little jitter still exceeds the 15 ms playout margin about six times a minute. Left to look at: the link carrying 5–12 Mbps of video beside the audio, and the margin itself.

**Not measured:** the same content under "Keep resolution" (no A/B), a real game, a hot tablet. The lower underrun rate than the game cast's 20 a minute cannot be credited to the floor.

**Limitations:** one device, one network; the phone vsync and Mac refresh terms are assumed, not measured; audio stops at the ring, before the Mac's output device.
