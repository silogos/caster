# ADR-008: Virtual audio devices — audio as input, the capture-card experience (macOS)

- **Status:** accepted (2026-10-31) — batch A of the capture-card north star; tracking: issue #69 (batch B, always-on, shipped in the same series; the one-click install UX shipped as batch A's closer).

## Context

The user framed the product goal: *the caster experience should feel like using a hardware capture card* — devices that are simply **there**, no ceremony, no window management, no cropping, no audio routing puzzles. Batch B shipped the "device just exists" behavior for the virtual camera. This ADR covers the audio half: **game audio and mic as two independent input devices** other apps can pick separately — something even a physical capture card cannot do (it carries one mix).

Until now, the cast's audio was an *output* story: the desktop mixer plays to speakers, or redirects to a virtual cable (BlackHole, issue #54). The capture-card model inverts this: audio arrives as **input devices** that never touch the speakers unless the user chooses to monitor.

## Decision

### macOS: own HAL plugin via libASPL

**`ZeroFrictionAudio.driver`** — a Core Audio AudioServerPlugIn (HAL plugin) built on [libASPL](https://github.com/gavv/libASPL) (MIT, commit-pinned), shipped in the app bundle (`resources/zfaudio/`), installed once into `/Library/Audio/Plug-Ins/HAL/` (admin prompt — same friction as installing BlackHole). No Apple entitlement, no kernel driver, no signing portal.

Two loopback devices, one per cast track (ADR-003's two-track architecture end to end):

- **"Zero-Friction Game Audio"** — the cast's game audio track
- **"Zero-Friction Mic"** — the cast's microphone track

Each device is a stereo 48 kHz float32 pair: an output stream (our mixer plays into it via `AudioContext.setSinkId`) and an input stream (OBS/Zoom/Discord capture from it), connected by a lock-free SPSC ring buffer (⅔ s) inside the plugin — no IPC with the app, the audio system itself is the transport.

### Routing model

The mixer's existing routing modes (Speakers / Cable / Both) now layer with per-track devices:

- When per-track devices are detected (`findPerTrackDevices`), each channel with its own device gets a **dedicated cable context** — its audio arrives only at its own input device.
- Channels without a per-track device fall back to the shared mix cable (the BlackHole-compatible path).
- Speaker behavior is orthogonal: speakers mode still plays to the default output; cable-only keeps speakers silent; both monitors locally. Per-track and shared routing coexist — e.g. game audio to its device + speakers while mic goes only to its device.

### Installation UX (shipped — the one-click Install row)

The settings modal's **Audio output** section carries an **Install** row whenever this desktop can install (macOS + the bundled `.driver` present, pushed as `virtual-audio:state`) **and** the devices are missing from enumeration. Clicking it runs the bundled `install.sh` through `osascript`'s **native administrator password dialog** (`do shell script … with administrator privileges`) — the script replaces the bundle at `/Library/Audio/Plug-Ins/HAL/` and restarts `coreaudiod` (launchd brings it back in ~1 s). The renderer then re-detects for a few seconds until both devices enumerate; per-track routing, the cable option, and the phone's `cableAvailable` mirror all follow the normal detection path — no protocol change. One password, once, forever.

Honest outcomes (`virtual-audio:install`): **installed** (verified on disk), **declined** (the password prompt was cancelled — a user decision, not an error; osascript reports it as exit 1 + "User canceled (−128)" on stderr, so the installer captures stderr), **failed** (copy didn't land), **unsupported** (non-macOS). Known costs: a `coreaudiod` restart blips system audio for ~1 s (a live cast's audio may need a moment to recover); install is allowed anytime but is naturally done once. No in-app uninstall — `sudo resources/zfaudio/install.sh --remove` remains the manual path.

### Platform map

| Platform | Virtual audio input device | Status |
|---|---|---|
| **macOS** | HAL plugin (libASPL, MIT) — this ADR | **shipped (batch A)** |
| **Windows** | Kernel driver + Microsoft signing — not viable for this project | OBS use case solved by WASAPI process loopback (Application Audio Capture, zero-install); call apps via VB-Cable (pragmatic) — **detected as a cable target + platform-honest guidance** (issue #74: `findCableOutputDevice` third tier, `virtual-audio:state` carries the platform) |
| **Linux** | PipeWire virtual source (user-space) | follow-up |

## Consequences

- **Positive:** game audio and mic arrive as two independent input devices — Zoom takes the mic, OBS takes the game audio, independently. Speaker behavior is a separate choice. The two-track architecture (ADR-003) stays visible end to end.
- **Costs:** one-time admin prompt for the HAL plugin install (~1.3 MB, universal). `coreaudiod` restart kills audio briefly (~1 s). The plugin runs inside coreaudiod — a crash there takes down all system audio (tested carefully; libASPL's design minimizes this).
- **Known limitations:** stereo only (the mic track is mono but arrives as 2ch — consumers downmix); the devices exist as soon as the plugin is installed, even when caster is not running (they output silence — capture-card-like); no per-device volume from our side (the OS mixer handles it).

## Rejected alternatives

| Option | Verdict |
|---|---|
| BlackHole only (keep the existing single-mix cable) | **Rejected** — one mixed track defeats the two-track architecture; and it's a third-party install. |
| Write a raw HAL plugin without libASPL | **Rejected** — hundreds of lines of boilerplate for no benefit; libASPL is MIT, small, and maintained. |
| Windows kernel driver (VB-Cable equivalent) | **Rejected** — Microsoft driver signing portal (EV cert + Hardware Dev Center); the OBS use case is already served by process loopback. |
| PCM IPC to a helper process (like syphon-helper) | **Rejected** — the HAL plugin runs inside `coreaudiod`; there is no clean way to feed it from outside without a private IPC protocol. The loopback-device model needs no IPC at all. |
