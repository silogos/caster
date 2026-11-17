# USB link protocol (v1)

The wire format of a USB cast ([ADR-034](../decisions/ADR-034-usb-cast-over-aoa.md)): one duplex byte stream over the Android Open Accessory bulk endpoints, carrying length-prefixed frames. **Versioned:** any change to a field below bumps `LINK_VERSION`, and this document changes first.

## Why a framed stream

AOA gives one bulk IN and one bulk OUT endpoint, which is ordered and lossless (USB retries in hardware). The design follows from that:

- There is no FEC, NACK, jitter buffer or reordering.
- A frame is written whole, so a reader that knows the header can always find the next frame.
- A broken stream (bad version, impossible length) is fatal to the link. The reader never resynchronises by searching.

## Getting to the stream

1. **Desktop (USB host):**
   1. It sees an Android device: a known Android vendor id, or a device already in accessory mode.
   2. It sends `GET_PROTOCOL` (vendor IN, request 51) and needs version ≥ 1.
   3. It sends the identity strings with `SEND_STRING` (request 52, index 0–5).
   4. It sends `START` (request 53).

   AOA2 audio (request 58) is **not** sent. The identity strings are:

   | Index | Field | Value |
   |---|---|---|
   | 0 | manufacturer | `Caster` |
   | 1 | model | `Caster Receiver` |
   | 2 | description | `Caster desktop receiver` |
   | 3 | version | the link version, `1` |
   | 4 | URI | `https://github.com/silogos/caster` (Android shows it when no app matches) |
   | 5 | serial | the desktop's persistent id (`desktopId`) |
2. **The phone** re-enumerates as `18D1:2D00`–`2D05`. The desktop claims interface 0 and its two bulk endpoints.
3. **Android** asks the user whether to open Caster for the accessory, matched by manufacturer + model in `accessory_filter.xml`. The app calls `openAccessory()`.

## Frame

All integers are big-endian.

```
offset size field
0      1    version      LINK_VERSION = 1
1      1    kind         0 control · 1 video · 2 game-audio · 3 mic-audio
2      2    flags        bit 0 keyframe · bit 1 codec config (video only); other bits 0
4      8    pts_us       sender's capture clock (CLOCK_MONOTONIC, µs); 0 for control
12     4    payload_len  ≤ MAX_PAYLOAD (4 MiB)
16     …    payload
```

| Kind | Payload |
|---|---|
| 0 control | UTF-8 JSON object with a `type` field (below) |
| 1 video | One H.264 Annex-B access unit, as MediaCodec emits it. The codec-config buffer (SPS/PPS) is its own frame with flag bit 1, sent before the first keyframe. The receiver keeps it and gives it to its decoder again whenever the decoder restarts. |
| 2 game-audio | PCM16 little-endian, 48 000 Hz, 2 channels interleaved, 10 ms (1 920 bytes) per frame |
| 3 mic-audio | The phone's mic, its own track (ADR-003): PCM16 little-endian, 48 000 Hz, **mono**, 10 ms (960 bytes) per frame. The phone captures at its input's own rate (ADR-004: 16 kHz built-in, 48 kHz wired/USB) and resamples to 48 kHz; the receiver plays each sample on both channels |

A reader that gets an unknown `version`, an unknown `kind`, or a `payload_len` over the cap closes the link and logs why.

## Control messages

The phone and the desktop take these turns. *Phone* means the app on the accessory side; *desktop* means the receiver.

```text
Desktop                                         Phone
  │ ◀── ready {link} ────────────────────────────── │  the app just opened the accessory
  │ ── hello {link, desktopId, name, n} ─────────▶ │  as soon as the interface is claimed, and again on every ready (resent every 1 s until answered)
  │ ◀── auth {deviceId, mac, n} ─────────────────── │  mac = HMAC(trustKey, deviceId ‖ desktop n)
  │ ── auth-ok {mac} ─────────────────────────────▶ │  mac = HMAC(trustKey, desktopId ‖ phone n)
  │      or error {code}                            │
  │ ◀── cast-start {w, h, fps, audio, mic} ──────── │  user pressed Share; frames follow
  │ ◀── frames (video, game-audio, mic-audio) … ── │
  │ ◀── mic {on} ────────────────────────────────── │  the mic switched mid-cast
  │ ◀── cast-stop {} ────────────────────────────── │  user stopped; the link stays up
  │ ── bye {} / ◀── bye {} ─────────────────────── │  either side closing the link
```

- **ready** (phone → desktop): `link`. The app has just opened the accessory, and the desktop sends `hello` at once. If the desktop held a **trusted** session on this cable, that session ends (media stops) and the new `hello` carries a fresh nonce. Mid-handshake the nonce stays the same, because the app may already be answering the `hello` sent before `ready`: a fresh nonce there refused that answer (found live 2026-11-18). Without it, a receiver that kept an earlier app's session (the app was replaced or killed while the cable stayed in) never sends `hello` again, and both sides wait (found live 2026-11-17).
- **hello** (desktop → phone):
  - fields: `link` (the version), `desktopId`, `name` (the Mac's display name), `n` (a fresh 16-byte nonce, base64url);
  - repeated once a second until `auth` arrives, because the app may open the accessory late (after the user taps Android's dialog), and writes to an accessory nobody has opened time out.
- **auth** (phone → desktop):
  - `deviceId` (the phone's id, ADR-005) and `n` (the phone's own fresh nonce);
  - `mac` = base64url(HMAC-SHA256(trustKey, ASCII(deviceId) ‖ ASCII(desktop n))): the construction of the Wi-Fi resume handshake ([pairing.md](pairing.md)), with the desktop's nonce.
  - The phone sends it only when `desktopId` is its saved desktop. Otherwise it shows "Pair this phone with the desktop over Wi-Fi first" and answers nothing.
- **auth-ok** (desktop → phone):
  - `mac` = HMAC(trustKey, ASCII(desktopId) ‖ ASCII(phone n)). The desktop proves it holds the same key, so a USB host that only pretends to be Caster never receives the screen.
  - The phone drops the link if this mac is wrong.
- **error** (desktop → phone): `code` is `unknown-device` (no trust entry for that `deviceId`, or a wrong `mac`). The phone shows the pair-first message.
- **cast-start** (phone → desktop):
  - `w`, `h`, `fps` (the encoder's actual configuration), `audio` (`"pcm16-48k-stereo"` or `null` when game audio is off), and `mic` (whether the mic starts on);
  - display-only on the desktop, like `session-info`.
  - Sent again on a **new link** when a cable cast outlives its link ([ADR-036](../decisions/ADR-036-a-cast-survives-a-five-minute-drop.md)): unplugged and back within five minutes, the phone authenticates as usual and starts the same cast with a fresh `cast-start`, codec config and keyframe. No field changes; the receiver builds its decoder per `cast-start` as for any cast.
- **mic** (phone → desktop): `on`. The mic was switched on or off mid-cast, so the desktop starts the mic track fresh and updates its MIC chip.
- **cast-stop** (phone → desktop): the picture ends and the desktop shows NO INPUT. The link and its authentication stay, so the next Share needs no handshake.
- **bye** (either way): the sender is closing the link (app exit, receiver quit).

Messages with an unknown `type` are ignored and logged at DEBUG, so a newer peer can add messages within a version.

## Clocks and pacing

- `pts_us` is the phone's capture clock. The receiver's pacer (ADR-017) uses the spacing between frames, never their absolute values.
- Audio frames carry their capture time too. The receiver's ring sets the audio delay and does not read it in v1.

## Limits

| Name | Value | Why |
|---|---|---|
| `MAX_PAYLOAD` | 4 MiB | A 1080p keyframe at the highest bitrate setting is well under 1 MiB; the cap only stops a corrupt length from allocating gigabytes |
| hello resend | 1 s | Android's accessory dialog takes the user a few seconds |
| audio frame | 10 ms | The DataChannel path's chunk size, so the receiver's engine sees the same input (game audio 1 920 B, mic 960 B) |
