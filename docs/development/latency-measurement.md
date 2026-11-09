# Latency measurement

How to measure the cast's glass-to-glass latency — video from the phone's screen to the Mac's screen, game audio from the phone's mix to the Mac's playout — and what it measured. Use it before and after any change that claims to cut latency.

## How it works

| Piece | Where | What it marks |
|---|---|---|
| `LatencyTestActivity` | `apps/mobile/app/src/debug/` (debug builds only) | Flips the screen black ↔ white once a second, logging the vsync each flip was drawn for. A strip along the top changes every frame, so the stream runs at full fps like a game (a still screen sends no frames and would test an idle encoder). Writes a click into an already-running low-latency `AudioTrack` on every white flip, logging the moment. |
| `LatencyClickProbe` | `apps/mobile/.../debug/`, called from the ADM samples callback in debug builds | When the click comes out of the playback-capture record — splits the audio path into "inside the phone" and "phone → Mac". |
| `latency_probe.rs` | the native receiver, on with `ZFC_LATENCY_PROBE=1` | When each flip is decoded (sampled luma of the frame's centre) and how long the pacer holds it; when each click arrives and the playout ring depth ahead of it. |
| `tools/latency/measure.py` | Mac | Aligns the two clocks over adb and pairs the marks. |

Only a sampled luma mean and a peak amplitude are read — no frame or audio content is logged.

**Clock alignment.** The phone prints its clock over `adb shell` every 50 ms; the smallest (Mac arrival − phone time) is the offset plus the fastest one-way trip. Phone marks therefore land a few ms late on the Mac clock, and every figure reads a few ms low. The phone and Mac clocks differed by ~0.95 s in practice, so alignment is not optional.

**Estimates in the "Mac screen" figure:** the flip shows on the phone one vsync after its frame time (60 Hz assumed), and on the Mac within one 120 Hz refresh after the pacer releases it. Audio excludes the Mac's output device buffer.

## Running it

1. Receiver: `ZFC_LATENCY_PROBE=1 ./target/release/desktop-native > rx.log 2>&1`
2. Phone (debug build, cast running): `adb logcat -v time -s LatencyTest:I > phone.log`, then `adb shell am start -n com.zerofriction.localcast/.debug.LatencyTestActivity`
3. Keep the Mac's video window frontmost (a hidden window renders nothing, so no video marks) and leave the phone untouched for ~70 s.
4. `python3 -I tools/latency/measure.py --logcat phone.log --receiver rx.log` while adb is still connected (it aligns the clocks live).

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

**Limitations:** one device, one network; the phone vsync and Mac refresh terms are assumed, not measured; audio stops at the ring, before the Mac's output device.
