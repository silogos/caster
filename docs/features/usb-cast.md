# USB cast (video, game audio and the mic over the cable)

Cast over a USB cable through Android Open Accessory: no ADB, no Developer Options, no Wi-Fi needed while the cable is in. Decision: [ADR-034](../decisions/ADR-034-usb-cast-over-aoa.md). Wire format: [architecture/usb-link.md](../architecture/usb-link.md). Design: page "Mobile design v3 · USB cast" of `designs/mobile-app.figcraft` ([preview](../../designs/preview/mobile-usb-cast.png)).

## What the user does

1. The phone has paired with the Mac over Wi-Fi once (the QR). The cable reuses that pairing; it never pairs by itself.
2. Plug the phone into the Mac while the receiver runs. The receiver switches the phone to accessory mode, and Android asks "Open Caster for Caster Receiver?" (or opens Caster directly once "always" was chosen).
3. Home shows **Connected by cable**, and the deck on the Mac shows **Connected by cable** with a USB tag (frame 10). A Mac opened with a saved phone shows the deck **OFFLINE** (frame 14), never the QR, until that phone connects by either link.
4. **Share screen** and the capture consent work as for Wi-Fi. The video window follows its last state: open last time (kept across restarts), it opens again; closed, click **Open video**.
5. Game audio and the **mic** travel as two tracks. The mic follows the Microphone switch and the chosen input (built-in, wired headset, USB, Bluetooth), live.
6. Stop it with **Stop share screen**, the notification's Stop, or by unplugging. The deck then says **Cable unplugged** (frame 12), not "reconnecting".

## How it works

| Piece | Where | What it does |
|---|---|---|
| `usb_link.rs` | receiver | Polls USB once a second. Sends the AOA switch to known Android vendor ids, once per plug-in. Claims an accessory, sends `hello` until the app answers, checks the phone's proof, and runs the cast inline on its thread. |
| `usb_protocol.rs` | receiver | Pure: the 16-byte frame, the reader, and the desktop's half of the handshake |
| `h264/mac.rs` + `native/h264_decoder.m` | receiver | VideoToolbox, hardware: Annex-B access unit → NV12 IOSurface pixel buffer, shown zero-copy through `Renderer::render_decoded` (ADR-031; the same pacer, layer and Syphon as Wi-Fi) |
| `UsbLink` / `AccessoryLink` | phone | The link's lifetime (opened from the accessory intent, or by asking for the permission when the app is opened with the cable in). Re-opens the accessory after the receiver restarts. |
| `UsbHandshake` | phone | Pure: the phone's half (proves the trust key; checks the desktop's proof) |
| `UsbCastService` | phone | The FGS (`mediaProjection`, plus `microphone` while the mic is on) owning one cable cast: `UsbVideoEncoder` (MediaCodec surface encoder, ADR-018 keys, repeat-frame 100 ms), `UsbGameAudioCapture` (playback capture, 10 ms PCM16 48 kHz stereo) and `UsbMicCapture` (source `MIC` at the input's own rate, ADR-004, resampled to 48 kHz mono) |

Game audio feeds the receiver's existing game-audio engine (`audio::feed_dc_pcm16`), so the ring, Caster Audio, and the Syphon-follows rule apply unchanged.

## Verified on the device (2026-11-17)

Lenovo TB321FU (Android 16) → MacBook Pro M1 Pro, USB-C cable. Profile "Low Latency": 1920 px, 60 fps, 8–14 Mbps.

- **The switch and the handshake:**
  - AOA v2; Android opened Caster for the accessory.
  - Both proofs checked in ~4 ms (phone: "desktop proved the shared key"; receiver: "phone authenticated over USB").
  - Media sent before authentication is dropped (seen once, from a previous session's leftovers).
- **A real game, played by the product owner (~90 s of measured play), judged "bagus, stabil":**

  | | |
  |---|---|
  | Encoder | `c2.qti.avc.encoder`, 1920×1200 @ 60 fps, 14 Mbps cap |
  | Frames rendered | median **60 fps** (19–95 per second: the game's own frame rate, plus repeat frames on still scenes) |
  | Link | median **5.4 Mbit/s**, max 9.3 |
  | Pacer hold | avg **20 ms**, max 50; 453 frames shown late in 89 s |
  | Errors | none on either side |
  | Phone | charging on AC throughout (level 34 %); skin 39–41.5 °C (throttle threshold 46.5 °C) |

- **Audio:** game audio reached the receiver's engine and played on the Mac's speakers. The phone's monitor reported both Active and Silent during the session. Silent means the app on screen gave no capturable sound (audio.md opt-outs), not a link fault.
- **A second session after the deck and mic changes (2026-11-17, 22:47–23:00):**
  - the link came back by itself ~1.5 s after a receiver restart;
  - the cast ran 13+ minutes with **0 dropped frames** (`[video] 43200 frames shown | dropped: 0 hidden, 0 layer busy, 0 pool full`);
  - **the mic switched on mid-cast:** built-in mic at 16 kHz → 48 kHz mono on the link, and the first mic chunk reached the Mac's mic engine 170 ms after the switch. The product owner heard it and confirmed the flow.
- **The profile's frame rate is now a cap.** Before the fix, a 144 Hz panel fed the encoder every frame: median 90 fps, peaks of 144, 887 of 1 055 seconds above 65 fps, all under a 60 fps profile. `KEY_FRAME_RATE` is only a rate-control hint. `KEY_MAX_FPS_TO_ENCODER` now drops frames above the profile's rate, and the product owner saw a steady 60 fps after the fix (2026-11-17).
- **Restarting the receiver mid-cast:** the phone's link closed, the app re-opened the accessory, and the next Share worked without unplugging.

## Profiles over the cable (design v4, decided 2026-11-17)

- **One profile list for both links.** **Capture** (1920 px · 120 fps · 20–30 Mbps) is a fifth built-in profile. It is the cable's profile by default (`CastSettings.cableSelection`, stored as `cable`; documents from before it read Capture). While the cable is in, the list shows and picks the cable's profile, and Wi-Fi keeps its own pick. A profile of the user's own can take the cable over from its menu ("Use when the cable is in").
- **Tags:** BEST OVER USB on a profile above 60 fps or 20 Mbps, CABLE on the cable's profile. In the editor, WI-FI ONLY on Under network pressure, Video codec and Low-latency audio, each with a line saying what the cable does instead. Frame rate offers 120 fps, and the bitrate slider reaches 40 Mbps.
- **Fields that now apply over the cable too:**
  - Encoder: a software or hardware H.264 encoder that takes the format, otherwise the platform default;
  - H.264 profile: `KEY_PROFILE` High or Baseline;
  - Encoder fps limit: the lower of the limit and the frame rate.
- **Measured with Capture** (78 s of a real game, 23:53–23:55): the encoder set to 1920×1200 @ 120 fps. The frames reaching the Mac followed the game's own rate: median 60 fps, p90 94, max 117. The link ran at a median 6.3 Mbit/s with 5-second peaks up to 64 Mbit/s (keyframes under VBR). Pacer hold median 18 ms.
- **On the tablet:** the product owner confirmed that Home (Capture with CABLE and BEST OVER USB) and the profile editor (WI-FI ONLY tags, 120 fps, 40 Mbps) match design v4 (2026-11-17).
- **`ready` (found live):** a receiver that kept an earlier app's session (the app replaced or killed while the cable stayed in) never sent `hello` again, so neither side moved. The app now sends `ready` when it opens the accessory, and the receiver starts the handshake over. Verified: force-stopping and reopening the app on the same cable re-authenticated in 2 ms.

- **After the rebase onto main (2026-11-18, 00:04–00:06):**
  - a cable cast through main's zero-copy path: `format description created for 1920x1200 (Limited range, decoder buffer, zero-copy)`, 0 frames dropped by the pool;
  - the video window opened by itself, its remembered state;
  - Capture reached 115–120 fps while the screen moved, and the repeat-frame floor of ~10 fps on a still screen;
  - link up to 9.6 Mbit/s, no errors.

- **A ~5-hour live (2026-11-20, user-reported):** a copy of Capture at 60 fps over the cable, Caster → Syphon → OBS → TikTok LIVE Studio — smooth by the product owner's account. The OBS recording (3h48m of it) holds 59.95 fps with **one ~25 s video gap**: the product owner re-logged PUBG at 21:17 under a single-app share, and the receiver showed "No input video" until the game drew again; the cast recovered by itself. No logs were captured ([reliability.md](reliability.md) Native session 1).

## Known limitations

- **The pacer holds USB frames ~20 ms on average.** ADR-017's pacer smooths Wi-Fi bunching. The cable delivers steadily (spike: p99 arrival within ~3.5 ms of capture), so on USB the hold is pure delay. The follow-up is to give the cable a much smaller hold or none, and to measure it with the latency pattern.
- **Automatic quality and changing the profile mid-cast** do not act on a cable cast yet (next phase). The profile's values hold for the whole cast, at the profile's **maximum** bitrate.
- **Rotation:** the virtual display keeps the size from the cast's start. A device rotated mid-cast is letterboxed.
- **Any Android phone plugged into the Mac is switched** while the receiver runs (ADR-034 consequences).
- **Not seen in the logs yet:** the unplug ("Cable unplugged") and the offline launch (frame 14); the product owner confirmed the flow, but no log line records either.
- **Not measured yet:**
  - glass-to-glass latency on this path with the latency pattern;
  - other phone brands;
  - Windows and Linux receivers (not built).
