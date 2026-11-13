# ADR-029: Audio and mic play while the window or Syphon shows the cast, each on Speaker or its own Caster device

- **Status:** accepted (2026-11-13) — on-device verification pending (see *Consequences*). Amends the "video and sound only while the video window is open" rule in [architecture/desktop-native.md](../architecture/desktop-native.md) (2026-11-09) and completes [ADR-021](ADR-021-native-syphon-output.md) for sound. Ports the Electron receiver's mic playback and per-track routing ([ADR-008](ADR-008-virtual-audio-devices.md), audio-mixer.md) to the native receiver.

## Context

On 2026-11-09 the deck put video and sound behind **Open video**: game audio played only while the native video window was open. A day later ADR-021 added the Syphon output, which keeps sending the picture to OBS with the window closed. Sound was left on the window rule, and on the system default output only. The product owner found the result: **with Syphon on, the sound is gone.**

- With the window closed, the playout gain is 0. Nothing plays anywhere, and OBS gets a silent picture.
- With the window open, sound plays on the speakers. OBS's Window Capture used to bring the app's sound along with the window; the Syphon Client source carries only video, so OBS has no source for the game audio.
- The phone's mic was not played at all: the native receiver answered the mic pc but never played its track.

## Decision

1. **Sound goes with the picture.** Both tracks play while the video window is open **or** the Syphon output is on (`audio::sound_plays`). With both off, the gain stays 0 and the rings keep draining, so turning either on never replays a backlog (unchanged).
2. **Two tracks, two engines.** The game's sound ("Audio" in the UI, renamed from "Game audio") and the phone's mic each have their own engine: ring, playout (ADR-020) and output (`audio::Track`). They are never mixed (ADR-003). The mic's only source is its own pc's Opus track, decoded by libwebrtc to 48 kHz stereo; the game audio keeps its DataChannel-first ladder.
3. **"Plays on": Speaker or the track's own Caster device.** Each track has a two-way choice in the deck's *Sound on this computer* card: **Speaker** (the system default) or its virtual device for OBS: **Caster Audio** for the audio, **Caster Mic** for the mic. Saved as `gamePlaysOn` / `micPlaysOn` (`"speaker"` | `"caster"`) in `native-receiver.json`, next to each track's own volume and mute. A value this version does not know reads as the speaker and leaves the rest of the file intact. These are receiver/environment controls (*mobile controls, desktop receives*).
4. **Which device "Caster" means.** The first installed of `Caster Audio`, then `Zero-Friction Game Audio` (and `Caster Mic`, then `Zero-Friction Mic`). Until Caster ships its own devices, the Electron receiver's ADR-008 devices stand in, and the UI names the one OBS will list. **Never a shared cable** such as BlackHole: one device for both tracks would mix them. With no device installed, the choice is disabled and says so.
5. **Switching restarts the stream.** A new choice stops the running stream and flushes the ring; the next chunk opens the new device, which primes from its 40 ms cushion like a fresh cast.
6. **One output per track; the track's volume and mute apply to it.**
7. **The mic starts muted.** A missing `micMuted` (a fresh install, or a file from before this ADR) reads as muted; the user turns it on once and it stays on. Receivers before this change never played the mic, so an upgrade must not start playing it unasked, and on **Speaker** the phone hears its own mic again (an echo). The audio track keeps its old default (on).

| Option | Verdict |
|---|---|
| Mirror the video per destination: speakers follow the window, a virtual device follows Syphon (two outputs per track) | Considered first. Rejected **for now** by the product owner in favor of the smaller change: two devices run on two clocks, so each needs its own ring and playout. Revisit if listening on the speakers while OBS takes the sound becomes a need |
| "Speakers + virtual device" as a third choice | Rejected for the same reason |
| A picker listing every output device on the Mac | Built first, then narrowed by the product owner: users need two answers, not a device list. It also allowed one cable for both tracks |
| Switch the output to a virtual device automatically when Syphon turns on | Rejected: a hidden coupling that silences the speakers without being asked |
| Leave sound on the window rule | Rejected: Syphon with the window closed is silent, which is the reported bug |

## Consequences

- With **Plays on** set to Caster, the user hears nothing of that track on the speakers unless OBS monitors the source. This is the accepted cost of a single output. A mobile streamer usually listens on the phone itself (playback capture does not mute it), which this fits.
- With the mic turned on and on **Speaker**, the phone's mic plays in the room, where the phone can pick it up again (an echo). The Electron receiver played it there by default; here it starts muted (decision 7), and turning it on is left to the user.
- **Follow-up (its own change):** Caster's own "Caster Audio" / "Caster Mic" devices, shipped in the app with an install button (the ADR-008 driver renamed and ported to the native receiver).
- With **Plays on** left at the system default and Syphon on, the speakers play even with the window closed. OBS can then capture the sound only through a desktop-audio capture (the one-source rule in [obs-streaming.md](../features/obs-streaming.md) still applies).
- The output device is fixed for the stream's life. A device that disappears mid-cast behaves as before (cpal logs a stream error; the next device change or app restart reopens).

**Found live (2026-11-13): a dropped cpal stream keeps playing.** After a few switches in the picker, the sound turned garbled: ~200 underruns a second, every old stream still pulling from the ring next to the new one. In cpal 0.15.3 a stream opened on a device *by name* registers a device-disconnect listener that holds a strong clone of the stream, so dropping the handle never stops its audio unit. `Output`'s `Drop` now pauses the stream first. The leaked unit stays allocated but idle (a few per session at most, one per switch). A cpal upgrade that breaks the cycle would let us remove the pause.

**Verified (2026-11-13, Lenovo TB321FU → MacBook Pro M1 Pro, live cast, audio read back from Zero-Friction Game Audio's input with ffmpeg, 5 s each).** These runs were made with the full device picker that came before the two-way choice (see the options table). Picking "Zero-Friction Game Audio" there opens the same device, on the same code path, as **Caster Audio** does now, and the system default is what **Speaker** means. Only the game-audio track was measured.

| Window | Syphon | Device (today's choice) | Zero-Friction Game Audio |
|---|---|---|---|
| closed | on | System default (Speaker) | −91 dB (the sound is on the headphones) |
| closed | on | Zero-Friction Game Audio (Caster Audio) | mean −30 dB, peak −1.5 dB — the game's sound |
| closed | off | Zero-Friction Game Audio (Caster Audio) | −91 dB — silent; the ring keeps draining |

- `cargo test --lib` (2026-11-14, the two-way choice): 97 passed, 2 ignored. New: `sound_plays_while_the_window_or_syphon_shows_the_cast`; `each_track_gets_its_own_caster_device` (Caster's names first, then the ADR-008 devices, never a shared cable); `an_unknown_output_value_keeps_the_rest_of_the_file` (an unknown `gamePlaysOn`/`micPlaysOn` reads as Speaker). The settings round-trip covers `gamePlaysOn`, `micPlaysOn` and the mic's level; an old file reads as Speaker, full level, mic muted.
- `switching_outputs_leaves_one_stream` (ignored: needs two named output devices): 8 switches between BlackHole 2ch and Zero-Friction Game Audio, then 2 s of paced audio: **0 underruns**; with the pause on drop removed: **400 underruns, 1 985 ms starved**.

**A/B against v0.2.1 (2026-11-13, same tablet and game, window closed, release builds, ~3 min each, first 10 s skipped):** the playout is unchanged, and so are its numbers. v0.2.1: 9.8 underruns/min, 418 ms starved in 135 s, 129 arrival pauses ≥ 30 ms. This branch: 11.6 underruns/min, 607 ms starved in 170 s, 103 pauses. Audio arrived at 100 % of real time in both. A garbled run earlier the same night was a **debug** build (`cargo run`) under a 1920×1200 60 fps stream: thousands of video frames dropped at the frame queue, and audio arriving at ~88–94 % of real time. Judge audio only on release builds.

**Not verified yet:**

- **The mic, at all.** Nothing about its playback has been checked on a device: that it is heard on Speaker, that it reaches Zero-Friction Mic on Caster, that it starts muted after an upgrade, and that the audio device carries no mic (and the mic device no game audio).
- The two-way **Plays on** choice in the built app: switching mid-cast by ear, after the pause-on-drop fix.
- OBS capturing Zero-Friction Game Audio and Zero-Friction Mic next to the Syphon picture.
