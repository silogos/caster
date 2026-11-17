# ADR-034: USB cast over Android Open Accessory

- **Status:** accepted (2026-11-17) by the product owner after the spike in [proposals/usb-cast.md §8.1](../proposals/usb-cast.md). The first phase carries video and game audio as PCM16 (product owner, 2026-11-17: "video + pcm/opus"), and the mic as its own track ("sekaligus headset", the same day). **Amended by [ADR-036](ADR-036-a-cast-survives-a-five-minute-drop.md) (2026-11-18):** unplugging no longer ends the cast; it waits five minutes for the cable to come back.

## Context

Principle 3 of [overview.md](../architecture/overview.md) forbade USB together with ADB, to keep developer tooling out of the user's workflow. Android Open Accessory (AOA) gives a USB link through a public, consent-gated API (`UsbManager.openAccessory`) with no ADB and no Developer Options. A shipping product (TikTok LIVE Studio) uses it for wired casting ([proposal §3](../proposals/usb-cast.md)).

The spike on the Lenovo TB321FU ([proposal §8.1](../proposals/usb-cast.md)) showed:

- The switch works: AOA v2, re-enumeration in 3.5 s, no macOS driver.
- Latency is the same as Wi-Fi: 7 ms behind the phone's actual screen.
- Frame arrival on the cable is steady: p99 within ~3.5 ms of the phone's capture spacing.

The value of USB is therefore reliability, charging during play, and needing no network. It is not latency.

## Decision

1. **Principle 3 becomes "No ADB, no Developer Options"**: USB is allowed only through public accessory APIs (AOA). **Principle 4 becomes "Local only (LAN or cable)."** Wi-Fi stays the default; USB is optional.
2. **The desktop is the USB host and switches the phone.** The receiver performs the AOA switch with Caster's identity strings (manufacturer `Caster`, model `Caster Receiver`). It does not send AOA audio (request 58). Android then offers to open Caster, and the app matches the accessory with its `accessory_filter`.
3. **Media over USB uses Caster's own framed transport, not WebRTC.** There is no IP link, so there is no ICE/DTLS/SRTP. The transport is specified and versioned in [architecture/usb-link.md](../architecture/usb-link.md):
   - a 16-byte header, then the payload;
   - kinds: `control` (JSON), `video` (H.264 Annex-B access units straight from MediaCodec), `game-audio` (PCM16LE 48 kHz stereo, the same samples as the DataChannel path of ADR-013), and `mic-audio` (PCM16LE 48 kHz mono, captured under ADR-004's rules: source `MIC`, the user's chosen input, at that input's own rate).

   The tracks stay separate (ADR-003).
4. **Game audio is PCM, not compressed.** On the cable 1.5 Mbps is free, and PCM adds no codec delay. The receiver feeds it into the existing game-audio engine (ring, pacing, Caster Audio).
5. **The link authenticates both ways with the trust key of the Wi-Fi pairing** (ADR-002/ADR-006):
   - The phone proves knowledge of the key: `HMAC(trustKey, deviceId ‖ desktopNonce)`.
   - The desktop proves it too: `HMAC(trustKey, desktopId ‖ phoneNonce)`.
   - Neither side starts media before both proofs check.
   - A phone that never paired over Wi-Fi is told to pair first. USB adds no pairing of its own.
6. **The phone starts the cast** (principle "Mobile controls"). Opening the app over USB shows a connected state, and the user starts sharing the same way as over Wi-Fi. The quality is the user's settings (resolution, fps, bitrate) from the phone's store.
7. **macOS first.** The receiver uses libusb via `rusb`, built from source into the app (LGPL-2.1; the app's source is public, so relinking stays possible), so a user's Mac needs no Homebrew libusb. It decodes with VideoToolbox. The decoded picture enters the existing display path: pacer, `AVSampleBufferDisplayLayer`, Syphon. Windows (WinUSB driver) and Linux (udev rule) come later.

| Option | Verdict |
|---|---|
| ADB / scrcpy-style capture | Rejected: developer tooling in the user's path (the reason principle 3 existed) |
| Tunnel IP over AOA so WebRTC runs unchanged | Rejected: a VPN consent on Android, a TUN device (admin rights) on the desktop, and WebRTC's jitter machinery where the link is lossless |
| AOA2 audio (request 58) for game audio | Rejected: deprecated since Android 8, fixed 44.1 kHz, outside Caster's capture design |
| Opus for game audio over USB | Not now: no bandwidth reason on a cable, and it adds an encoder and a decoder; a later phase can add it as another `game-audio` format |
| Reuse the competitor's SDK | Rejected: licensed to them; only the protocol's *shape* was learned |
| The desktop starts the cast when the cable goes in | Rejected for this phase: the phone owns cast decisions; Android's capture consent needs a tap anyway |

## Consequences

- There is a **second media path on both apps**. Capture is shared, but the phone drives MediaCodec directly for USB, where libwebrtc's encoder factory owns it for Wi-Fi. The receiver needs its own H.264 decoder for USB.
- **Adaptive quality, thermal steering and live quality changes do not apply to USB in this phase.** The user's settings at cast start hold for the whole cast.
- **Any Android phone plugged into a Mac running the receiver is switched to accessory mode.** A phone without Caster shows Android's "no app for this accessory" note with the download link (the identity URI). This is TikTok LIVE Studio's behavior too. A phone that never opens the accessory is left alone until it is replugged.
- A new device-support matrix for AOA is needed, since OEM support varies.
- `usb-link.md` is a protocol, and changing any field bumps its version.
