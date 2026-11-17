# Proposal: USB Cast over Android Open Accessory (no ADB)

**Status: ACCEPTED (2026-11-17)** → [ADR-034](../decisions/ADR-034-usb-cast-over-aoa.md), protocol in [usb-link.md](../architecture/usb-link.md). First phase: video + game audio (PCM16); the mic over USB comes later. Kept as the record of the evidence and the spike.

## 1. What it is

A second way to carry a cast: the phone plugged into the desktop with a USB cable, using **Android Open Accessory (AOA)** — a public Android platform API (`android.hardware.usb.accessory`, `UsbManager.openAccessory`) available on every Android version caster supports (minSdk 29). The desktop acts as the USB host; the phone is the accessory. **No ADB, no Developer Options, no USB debugging, no driver on macOS.**

The user flow stays "plug → start → play":

```
Desktop: Caster running (waiting)
Phone:   plug the cable → Android asks "Open Caster when this USB accessory is connected?" → OK (once, with "use by default")
         → [Start Cast] → MediaProjection consent (as today) → play
```

Wi-Fi stays the default and is unchanged. USB is an alternative link, chosen by plugging the cable.

## 2. Why it is worth doing

- **Latency and stability.** A USB bulk link has no Wi-Fi jitter, no retransmission storms and no channel contention. The receiver pacer ([ADR-017](../decisions/ADR-017-receiver-frame-pacing.md)) exists largely to absorb Wi-Fi delivery bursts; a USB link should make the delivery side nearly flat. To be measured (§8 spike), not assumed.
- **Thermal and battery.** The cable charges the phone during a gaming cast, and the radio is idle — both relevant to the thermal ladder ([thermal.md](../architecture/thermal.md)).
- **Works without a usable network.** Venues with client isolation, captive portals or a congested 2.4 GHz band currently force the hotspot mode ([ADR-011](../decisions/ADR-011-hotspot-pairing-mode.md)) or fail.
- **The competition ships it.** TikTok LIVE Studio (desktop, v1.39.6) offers "Wired casting" for Android exactly this way — evidence in §3.

## 3. Evidence: how a shipping product does it

Static analysis of `/Applications/TikTok LIVE Studio.app` (v1.39.6, 2026-11-17): bundle inventory, exported symbols, strings, and the arm64 disassembly of `MediaSDK_V2/libcastsink.dylib` (`WiredCast::*`, same code duplicated in `libbytemate.dylib`). No code was run. Labels: **observed** = read from instructions or data; **inferred** = interpretation.

**Accessory switch** (`WiredCastTransport::sendModeChange`, observed):

| Step | libusb call | AOA meaning |
|---|---|---|
| 1 | `control_transfer(0xC0, 51, 0, 0, buf, 2, 1000)` | `ACCESSORY_GET_PROTOCOL`; 0 → "doesn't support AOA protocol" |
| 2 | `control_transfer(0x40, 52, 0, idx, str, len, 1000)` × 6 | `ACCESSORY_SEND_STRING` (identity, idx 0–5) |
| 3 | `control_transfer(0x40, 58, 1, 0, NULL, 0, 1000)` | `ACCESSORY_SET_AUDIO_MODE` (AOA2 audio on) |
| 4 | `control_transfer(0x40, 53, 0, 0, NULL, 0, 1000)` | `ACCESSORY_START` — the phone re-enumerates |

Identity strings (decoded from the static initializer, observed): manufacturer `Android`, model `Accessory Display`, description `Accessory Display Sink Application`, version `1.0`, uri empty, serial `0000000012345678` — the strings of AOSP's `frameworks/base/tests/AccessoryDisplay` sample. **Inferred:** the TikTok Android app declares an `accessory_filter.xml` matching them.

**Re-enumerated device** (`WiredCast::IsAccessory`, observed): VID `0x18D1`, PID `0x2D00`–`0x2D05`. Bulk IN/OUT endpoints are taken from configuration 0 (`GetBulkEndPoints`); reads and writes are libusb bulk transfers with a 1000 ms timeout, retried on `LIBUSB_ERROR_PIPE`.

**Framing** (`WiredCastClient::read`, observed): a 20-byte big-endian header — `u16 version` (1 or 2), `u16 type` (< 101), `u32` (sent 0), `u64` (sent 0; inferred timestamp), `u32 payload_len` (≤ 0xFFFEC) — then the payload. Control payloads are JSON (`id`, `code`, `message`, `name`, `source`, …); message names include `MSG_QUERY`, `MSG_SINK_AVAILABLE`, `MSG_START_RECORDER`, `MSG_STOP`, `MSG_META_DATA`, `MSG_SINK_HEARTBEAT`, `MSG_STATISTICS`.

**Packaging:** on macOS the app needs no driver (libusb claims the interface). Its Windows build installs a driver ("The driver installs automatically when the PC and mobile device are connected" — UI string).

**What caster does not take from it:** their SDK is licensed to them (`CheckLicenseIosAndAndroid`); nothing of theirs is reused. The protocol shape above is ordinary AOA + a length-prefixed frame — caster designs its own, from the AOSP sample (Apache-2.0) and the AOA spec. Their desktop-side controls for bitrate/fps/resolution (`SetBitRate`, `SetFps`, `SetResolution` sent to the phone) are **rejected**: they break "mobile controls, desktop receives".

## 4. Design

### 4.1 The link

- **Desktop (`apps/desktop-native`, Rust):** a new `usb_link` module using libusb (via `rusb`, LGPL libusb dynamically linked). It watches hot-plug, performs the AOA switch (§3 steps 1, 2, 4 — **not** step 3, see 4.4), claims the accessory interface and exposes one duplex byte stream.
- **Phone (`apps/mobile`):** an activity/receiver for `USB_ACCESSORY_ATTACHED` with caster's own `accessory_filter.xml`; `openAccessory()` yields a `ParcelFileDescriptor` — one input and one output stream. Caster's identity strings, e.g. manufacturer `Caster`, model `Caster Receiver`, a version string, and a URI to the download page (shown by Android when the app is not installed).

### 4.2 Media over the link (the big change)

WebRTC cannot run over AOA: there is no IP link, so no ICE/DTLS/SRTP. Options:

| Option | Verdict |
|---|---|
| **A. Own framed transport:** H.264 Annex-B access units from MediaCodec, game audio AAC frames (already the ADR-013 payload), mic as its own track, control messages — multiplexed as length-prefixed frames on the one bulk stream | **favored** — simplest, and USB is lossless and ordered, so no FEC/NACK/jitter machinery is needed |
| B. Tunnel IP over AOA (a userspace TUN on both ends) so WebRTC runs unchanged | rejected — needs a VPN service on Android (a scary consent dialog), a TUN on the desktop (admin rights), and keeps WebRTC's jitter buffer where it buys nothing |
| C. AOA2 audio (request 58) for game audio | rejected — deprecated since Android 8, a fixed 44.1 kHz stereo path outside caster's capture design, and it would bypass the two-track model |

Option A means a **second media path on both ends**:

- **Phone:** the capture stays (`ScreenCapturer`, `AudioPlaybackCapture`, mic per ADR-004); the encoders feed a `UsbMediaSink` instead of the PeerConnection. The video encoder must be driven directly by MediaCodec with the ADR-018 low-latency keys (today libwebrtc's encoder factory owns it). Mic needs its own encoder (AAC or Opus via MediaCodec) since its WebRTC PeerConnection is not there.
- **Desktop:** a VideoToolbox H.264 decoder feeding the existing `video.rs` display path (`AVSampleBufferDisplayLayer`, pacer, Syphon), and the AAC decode already used for the DataChannel game audio, plus the mic track. Everything after the decoder stays shared.

### 4.3 Frame format (to be specified in `docs/architecture/usb-link.md`, versioned)

```
u8  magic/version   (link protocol version, starts at 1)
u8  kind            0 control(JSON) · 1 video · 2 game-audio · 3 mic-audio
u16 flags           keyframe, config (SPS/PPS / AudioSpecificConfig), …
u64 pts_us          capture clock
u32 payload_len     bounded (named constant)
…   payload
```

Control messages reuse the signaling vocabulary where it applies (cast state, quality summary, stop, heartbeat) so the desktop UI is the same for both links. Per the repo rules the doc and its version come before the code.

### 4.4 Invariants kept

- **Mobile controls.** Resolution, fps, bitrate, codec and thermal decisions stay on the phone; the desktop only receives. The desktop may request a keyframe (a receiver need, like PLI today).
- **Two audio tracks, end to end** (ADR-003): game audio and mic are separate frame kinds, never mixed. This is why AOA2 audio is rejected.
- **No ADB, no Developer Options.** AOA is a normal app permission flow.
- **No internet.** Still true; "LAN only" becomes "local only (LAN or cable)".

### 4.5 Trust

A cable is physical presence, but plugging into an unknown computer must not start a cast. The link opens with the same **HMAC challenge** as Wi-Fi pairing ([ADR-002](../decisions/ADR-002-pairing-and-signaling-security.md)) and the device registry ([ADR-005](../decisions/ADR-005-device-identity-and-registry.md)): an already-trusted desktop connects with no extra step; an unknown one shows the pairing code on the desktop for the phone to confirm. The MediaProjection consent remains on every cast.

## 5. Platforms

| Desktop | Status |
|---|---|
| macOS (native receiver, the only maintained desktop) | libusb, no driver, no admin rights — the first target |
| Windows | needs a WinUSB binding for the accessory VID/PID (a signed INF or libwdi) — later, and only if a Windows native receiver exists |
| Linux | a udev rule for user access — later |

## 6. Costs and risks

- **Second media path** — the largest cost: an encoder path on the phone outside libwebrtc and a decoder path on the desktop. Both must keep parity with features built on WebRTC stats (adaptive quality, [ADR-023](../decisions/ADR-023-keep-frame-rate-follows-the-network.md)), which would need USB-side equivalents (throughput and queue depth instead of RTT/loss).
- **Device support.** AOA is in AOSP and widely supported, but some OEMs ship broken accessory stacks; the TikTok UI has a "This device doesn't support Wired Casting" toast for exactly this. Must be checked on the test device (Lenovo TB321FU) first.
- **macOS USB contention.** Another host app holding the device (Android File Transfer, OpenMTP, a running `adb` server) can block claiming the interface after re-enumeration. Needs a plain-words error.
- **Cable/port quality.** USB 2.0 bulk (~30–40 MB/s practical) is ample for a 20 Mbps stream, but charge-only cables and hubs fail silently — the UI needs a "use a data cable" hint.
- **Accessory dialog.** Android shows it on each plug unless "use by default" is ticked; the onboarding must say so.
- **Licensing.** libusb is LGPL-2.1 (dynamic linking keeps the app's license free); rusb is MIT.

## 7. Open questions for the product owner

1. Change principle 3 to **"No ADB, no Developer Options. USB is allowed only through public accessory APIs (AOA)."** — and principle 4 to "local only (LAN or cable)"?
2. Scope: macOS only first (recommended), Windows/Linux later?
3. Accept a second media path (Option A), knowing adaptive quality must be re-done for it?
4. Sequencing against the roadmap — the spike (§8) can run before deciding 1–3.

## 8. Spike before any phase (proposed, non-binding)

Goal: measure, on the real rig, before committing to the architecture.

1. Desktop CLI (Rust + rusb): detect the TB321FU, AOA switch with caster identity strings, claim bulk endpoints, log the byte rate.
2. Android debug build: accept the accessory, encode the screen with MediaCodec (H.264, ADR-018 keys), write Annex-B access units with the §4.3 header.
3. Desktop: decode with VideoToolbox, display, and run the existing latency test pattern ([latency-measurement.md](../development/latency-measurement.md)).

**Exit criteria:** the AOA switch works on the test device without ADB/Developer Options; glass-to-glass latency and frame-interval jitter compared with Wi-Fi on the same pattern; the result written into this proposal. Go/no-go is the product owner's.

### 8.1 Spike result (2026-11-17)

Rig: Lenovo TB321FU (Android 16) → MacBook Pro M1 Pro over a USB-C cable, 1920×1200 @ 60 fps H.264 (MediaCodec, ADR-018 keys, 8 Mbps cap). Code: `tools/usb-aoa-spike` (desktop CLI, rusb) and the debug-only `UsbSpikeActivity` / `UsbSpikeService` in `apps/mobile/app/src/debug/`. adb stayed connected **only** to install the debug build and to align clocks for the measurement; the AOA path itself does not use it.

**Link: works.**
- `GET_PROTOCOL` returned **AOA v2**. After `START` the tablet re-enumerated as `18d1:2d01` (accessory + adb) in **3.5 s**. Interface 0 had bulk IN `0x81` and OUT `0x01`. No driver and no admin rights were needed on macOS.
- Android showed its own "open Caster for this accessory?" dialog (`UsbConfirmActivity`), then the activity matched by `accessory_filter`, then the normal screen-capture consent. That makes three taps for the first cast. The accessory dialog offers "always", which should take the count down to the capture consent alone, but that was not tested.
- The recorded stream decoded cleanly (`ffprobe`: H.264 High, 1920×1200, 753/753 frames).

**Latency (same pattern and tool as [latency-measurement.md](../development/latency-measurement.md), 141 flips, phone present lag 31 ms from SurfaceFlinger):**

| | USB (this spike) | Wi-Fi (2026-11-10, documented) |
|---|---|---|
| Phone screen → Mac decoded | median 30 ms (24–130) | — |
| vs the phone's **actual** screen | **median 7 ms** (2–107, p90 12) | median 0–1 ms (about −18 to +20) |

**Frame arrival on the Mac** (60 fps pattern, 9 055 frames): interval p50 16.6 / p95 19.7 / p99 21.6 ms, against the phone's own capture spacing of p50 16.6 / p95 17.6 / p99 18.1 ms. The cable adds about 2–3.5 ms at the tail.

**What it shows:**
- **The AOA switch works on the test device with no ADB or Developer Options in the path.** This was the first exit criterion and it is met.
- **USB is not faster than Wi-Fi for latency on this rig.** Both cast within a frame of the phone's own panel. The 7 ms against Wi-Fi's 0–1 ms falls inside run-to-run spread and the decoder difference: the spike uses ffmpeg + VideoToolbox, not the receiver's libwebrtc path, and it has no pacer. The documented Wi-Fi figure comes from another day. The case for USB therefore rests on reliability, charging and thermal, and having no network, not on latency.
- **Jitter on the cable is low** (p99 within ~3.5 ms of capture spacing). No Wi-Fi frame-interval figure on the same metric exists yet, so this half of the second criterion is **not compared**.

**Not covered:**
- Bitrate: the black/white pattern encodes at only ~0.2 Mbps median (4 Mbps peak), so a game-like 8–12 Mbps load was not stressed.
- Audio and mic tracks.
- Unplug and replug, and the "always" accessory default.
- Other OEMs.
- The receiver's own decode, pacer and display path.
- A same-day Wi-Fi run with the frame-interval metric.

## 9. ADR draft (becomes `docs/decisions/ADR-034-usb-cast-over-aoa.md` on acceptance)

> **ADR-034: USB cast over Android Open Accessory**
>
> - **Status:** proposed.
> - **Context:** principle 3 forbade USB together with ADB to keep the end-user workflow free of developer tooling. AOA gives a USB link through a public, consent-gated Android API with no developer tooling, and a shipping product (TikTok LIVE Studio) uses it for wired casting. Wi-Fi delivery jitter is the dominant source of receiver pacing work.
> - **Decision:** allow an optional USB link over AOA alongside Wi-Fi; principle 3 becomes "No ADB, no Developer Options; USB only through public accessory APIs"; principle 4 becomes "local only (LAN or cable)". Media over USB uses caster's own versioned framed transport (game audio and mic as separate kinds), not WebRTC and not AOA2 audio. The link authenticates with the ADR-002 HMAC challenge. macOS first.
> - **Rejected:** ADB/scrcpy-style capture (developer tooling); IP tunnel over AOA (VPN/TUN consents, admin rights); AOA2 audio (deprecated, would mix the tracks); reusing a vendor SDK (licensing).
> - **Consequences:** a second media path on both apps; adaptive quality and stats need USB-side signals; a USB-link protocol doc under `docs/architecture/`; a new device-support matrix for AOA.
