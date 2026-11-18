# USB link protocol (v2)

The wire format of a USB cast ([ADR-034](../decisions/ADR-034-usb-cast-over-aoa.md)): one duplex byte stream over the Android Open Accessory bulk endpoints, carrying length-prefixed frames. **Versioned:** any change to a field below bumps `LINK_VERSION`, and this document changes first.

**v2 (2026-11-18, [ADR-037](../decisions/ADR-037-the-link-is-chosen-first.md)):** the cable makes its own trust. v1 authenticated with the trust key of a Wi-Fi pairing (ADR-006), so a phone that never scanned the QR could not use the cable. v2 adds **enrolment**: on the first plug-in the phone and the Mac make a cable key by ECDH, the Mac asks its user once to Allow the phone, and every later plug-in authenticates with that key. Frames, media kinds, `cast-start`/`mic`/`cast-stop` are unchanged; `bye` gains a `reason`; `error` gains two codes. v1 and v2 peers refuse each other (§Version mismatch).

## Why a framed stream

AOA gives one bulk IN and one bulk OUT endpoint, which is ordered and lossless (USB retries in hardware). The design follows from that:

- There is no FEC, NACK, jitter buffer or reordering.
- A frame is written whole, so a reader that knows the header can always find the next frame.
- A broken stream (bad version, impossible length) is fatal to the link. The reader never resynchronises by searching.

## Getting to the stream

1. **Desktop (USB host)**, only while the receiver is in **Cable mode** (ADR-037: in Wireless mode the USB thread does not run, nothing is polled and no phone is switched):
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
   | 3 | version | the link version, `2` |
   | 4 | URI | `https://github.com/silogos/caster` (Android shows it when no app matches) |
   | 5 | serial | the desktop's persistent id (`desktopId`) |
2. **The phone** re-enumerates as `18D1:2D00`–`2D05`. The desktop claims interface 0 and its two bulk endpoints.
3. **Android** asks the user whether to open Caster for the accessory, matched by manufacturer + model in `accessory_filter.xml`. The app calls `openAccessory()` **only in Cable mode** (ADR-037: the link is chosen first; in Wireless mode the accessory is never opened and the desktop shows nothing for this phone). Before opening, the app reads the accessory's version string (index 3) and refuses anything but `2` (§Version mismatch).

## Frame

All integers are big-endian.

```
offset size field
0      1    version      LINK_VERSION = 2
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

**A known phone** (it holds a cable key for this desktop, and the desktop holds one for it):

```text
Desktop                                         Phone
  │ ◀── ready {link} ────────────────────────────── │  the app just opened the accessory
  │ ── hello {link, desktopId, name, n} ─────────▶ │  as soon as the interface is claimed, and again on every ready (resent every 1 s until answered)
  │ ◀── auth {deviceId, n, mac} ─────────────────── │  mac = HMAC(cableKey, deviceId ‖ desktop n)
  │ ── auth-ok {mac} ─────────────────────────────▶ │  mac = HMAC(cableKey, desktopId ‖ phone n)
  │      or error {code}                            │
  │ ◀── cast-start {w, h, fps, audio, mic} ──────── │  user pressed Share; frames follow
  │ ◀── frames (video, game-audio, mic-audio) … ── │
  │ ◀── mic {on} ────────────────────────────────── │  the mic switched mid-cast
  │ ◀── cast-stop {} ────────────────────────────── │  user stopped; the link stays up
  │ ── bye {reason} / ◀── bye {reason} ─────────── │  either side closing the link
```

**A new phone** (enrolment, once per phone and Mac):

```text
Desktop                                         Phone
  │ ── hello {link, desktopId, name, n} ─────────▶ │  as above
  │ ◀── enrol {deviceId, ua, pub, n} ────────────── │  the phone has no cable key for this desktopId
  │ ── enrol-key {pub} ───────────────────────────▶ │  both derive cableKey (ECDH + HKDF, below)
  │ ◀── auth {deviceId, n, mac} ─────────────────── │  the same auth, with the new key: proves the phone derived it
  │      [the receiver asks in its cable guide: "Allow <phone>…?"]
  │ ── auth-ok {mac} ─────────────────────────────▶ │  Allow: both store the key, the link is trusted
  │      or error {code: "not-allowed"}             │  Don't Allow, or no answer in 60 s
```

- **ready** (phone → desktop): `link`. The app has just opened the accessory, and the desktop sends `hello` at once. If the desktop held a **trusted** session on this cable, that session ends (media stops) and the new `hello` carries a fresh nonce. Mid-handshake the nonce stays the same, because the app may already be answering the `hello` sent before `ready`: a fresh nonce there refused that answer (found live 2026-11-18). Without it, a receiver that kept an earlier app's session (the app was replaced or killed while the cable stayed in) never sends `hello` again, and both sides wait (found live 2026-11-17). An open Allow prompt is closed by a `ready` (the enrolment starts over).
- **hello** (desktop → phone):
  - fields: `link` (the version), `desktopId`, `name` (the Mac's display name), `n` (a fresh 16-byte nonce, base64url);
  - repeated once a second until `auth` or `enrol` arrives, because the app may open the accessory late (after the user taps Android's dialog), and writes to an accessory nobody has opened time out.
  - The phone shows `name` from here on (in the cable guide during enrolment, then on Home's link card), so the user sees which Mac is on the cable before Share (ADR-037 §Risks).
- **auth** (phone → desktop):
  - `deviceId` (the phone's id, ADR-005) and `n` (the phone's own fresh nonce; during enrolment, the same `n` as its `enrol`);
  - `mac` = base64url(HMAC-SHA256(cableKey, ASCII(deviceId) ‖ ASCII(desktop n))): the construction of the Wi-Fi resume handshake ([pairing.md](pairing.md)), with the cable key and the desktop's nonce.
  - The phone sends it straight after `hello` when it holds a cable key for `desktopId`, and after `enrol-key` during enrolment.
- **auth-ok** (desktop → phone):
  - `mac` = HMAC(cableKey, ASCII(desktopId) ‖ ASCII(phone n)). The desktop proves it holds the same key, so a USB host that never enrolled with this phone does not receive the screen.
  - The phone drops the link if this mac is wrong.
  - During enrolment the desktop sends it only after its user taps **Allow**, and the phone stores its key only once this mac checks.
- **enrol** (phone → desktop): the phone has no cable key for this `desktopId`.
  - `deviceId`; `ua` (the same user-agent string as the Wi-Fi `hello`, from which the Mac takes the phone's name, e.g. "Lenovo TB321FU"); `n` (the phone's fresh nonce);
  - `pub`: a fresh ephemeral P-256 public key, the uncompressed SEC1 point (65 bytes, `04 ‖ X ‖ Y`), base64url.
  - The desktop refuses it with `busy` while it is paired with another phone (ADR-037 §6), and with `not-allowed` when its user already refused this `deviceId` on this attach (one prompt per plug-in). It checks the point is on the curve (otherwise it closes the link, like a broken frame).
- **enrol-key** (desktop → phone): `pub`, the desktop's own fresh ephemeral P-256 public key, same encoding. It is sent at once, before any prompt: the prompt opens only when the phone's `auth` that follows checks, so a peer that can't derive the key never reaches the Mac's user.
- **error** (desktop → phone), `code` is one of:
  - `unknown-device`: no cable entry for that `deviceId`, or a wrong `mac`. A phone that sent `auth` with a stored key forgets that desktop's cable key and answers the same `hello` with `enrol` (the desktop keeps its nonce until the handshake ends). A phone that was enrolling shows "Couldn't connect over the cable. Unplug it and plug it in again." and waits for a new attach.
  - `not-allowed`: the receiver's user tapped Don't Allow, closed the prompt, or let it run 60 s. The phone shows "The computer didn't allow this phone. Unplug the cable and plug it in again to ask again." and sends nothing more on this attach; the desktop discards the half-made key.
  - `busy`: the receiver is paired with another phone (ADR-037 §6). The phone shows "The computer is paired with another phone."
- **cast-start** (phone → desktop):
  - `w`, `h`, `fps` (the encoder's actual configuration), `audio` (`"pcm16-48k-stereo"` or `null` when game audio is off), and `mic` (whether the mic starts on);
  - display-only on the desktop, like `session-info`.
  - Sent again on a **new link** when a cable cast outlives its link ([ADR-036](../decisions/ADR-036-a-cast-survives-a-five-minute-drop.md)): unplugged and back within five minutes, the phone authenticates as usual and starts the same cast with a fresh `cast-start`, codec config and keyframe. No field changes; the receiver builds its decoder per `cast-start` as for any cast.
- **mic** (phone → desktop): `on`. The mic was switched on or off mid-cast, so the desktop starts the mic track fresh and updates its MIC chip.
- **cast-stop** (phone → desktop): the picture ends and the desktop shows NO INPUT. The link and its authentication stay, so the next Share needs no handshake.
- **bye** (either way): the sender is closing the link (app exit, receiver quit). `reason` is optional; the reserved value `"forget"` also ends the cable trust (§Cable trust), as Wi-Fi's `bye forget` does. Any other value, or none, keeps it.

Messages with an unknown `type` are ignored and logged at DEBUG, so a newer peer can add messages within a version.

## The cable key

```text
Z          = ECDH-P256(own ephemeral private key, peer's pub)        32 bytes, the shared point's X
transcript = SHA-256( phone pub ‖ desktop pub                         the 65-byte points
                    ‖ ASCII(deviceId) ‖ 0x00 ‖ ASCII(desktopId) ‖ 0x00
                    ‖ ASCII(desktop n) ‖ 0x00 ‖ ASCII(phone n) )
cableKey   = HKDF-SHA256(ikm = Z, salt = transcript, info = "caster-cable-v2", L = 32)
```

- **Both keys are ephemeral**, made for one enrolment and discarded after it. The long-lived secret is `cableKey`, and it never crosses the cable: a capture of the USB traffic (a logging hub, an analyser, the Mac's USB logs) holds two public keys and HMAC outputs, nothing that authenticates a later plug-in.
- **The transcript binds the key to both identities and both nonces**, so a key made for one phone and Mac can't be replayed as another pair's, and a later "compare this code" line (ADR-037, rejected for now) can be a few digits of `transcript` without a version bump.
- **What it does not do:** prove who is on the other end. ECDH without a confirmed code is open to a device spliced into the cable; the Allow prompt is the receiver's gate, and the name in `hello` plus Android's capture consent are the phone's (ADR-037 §Risks).
- Platform: the phone uses `KeyPairGenerator("EC")` with `secp256r1` and `KeyAgreement("ECDH")`; the receiver uses the `p256` crate (RustCrypto). Shared test vectors (fixed private keys → `Z`, `transcript`, `cableKey`, both macs) live in both apps' tests.

## Cable trust

Each side holds **one pairing at a time, tagged with its link** (ADR-037 §3): a Wi-Fi pairing (ADR-006, `trust.json`) or a cable pairing (`cable-trust.json`), never both. Writing one removes the other, and a pairing is what lets an app skip the Wireless/Cable choice and open on Home in that mode.

| | Desktop | Phone |
|---|---|---|
| Store | `cable-trust.json` beside `trust.json` in `~/Library/Application Support/Caster`, mode 0600 | SharedPreferences `caster.cable-trust`, the key sealed by the Keystore vault (`TrustVault`); excluded from backup and device transfer like the Wi-Fi entry |
| Entry | `{deviceId, name, key, createdAt, lastConnectedAt}` | `{desktopId, name, key, createdAt}` |
| Written | On Allow, once the phone's `auth` has checked; removes `trust.json` | When the enrolment's `auth-ok` mac checks; removes the saved Wi-Fi desktop |
| Ends | The deck's Terminate (sends `bye {reason: "forget"}` when the phone is on the cable); `bye forget` from the phone. `unknown-device` never deletes on the desktop | Terminate (sends `bye forget`); `bye forget` from the desktop; `unknown-device` for the stored key |

- **Ending the pairing returns that app to the Wireless/Cable choice** (ADR-037 §4). The other side learns it from `bye forget`, or, if it was unplugged, from `unknown-device` on its next plug-in.
- **Both stores degrade to empty** on anything unreadable (logged), as the Wi-Fi stores do: the honest fallback is one more Allow, never a link on a guessed key.
- **The phone's Disconnect** (not Terminate) sends a plain `bye`; the pairing stays, and the next plug-in connects by itself.
- **v1's cable trust is not migrated.** v1 had no cable entry of its own (it read the Wi-Fi key); a v1 user meets the Allow once.

## Version mismatch

v1 and v2 can't share a link: the frame's first byte differs, and v1's authentication is gone. Each side refuses the other early and says "update", never a half-working link.

| Phone | Desktop | What happens |
|---|---|---|
| v2 | v1 | The accessory's version string (AOA index 3) is `1`. The app does not open the accessory and shows "Update Caster on the computer to cast over the cable." |
| v1 | v2 | The old app opens the accessory and sends `ready` with version byte 1. The receiver closes the link without answering (v1 could not read a v2 frame), logs `phone speaks link 1`, and its deck shows "Update Caster on the phone to cast over the cable." for that attach. The old app's reader rejects the v2 `hello` it may already have received. |
| v2 | v2 | As above. Any frame with a version other than 2 is fatal to the link (§Frame). |

## Clocks and pacing

- `pts_us` is the phone's capture clock. The receiver's pacer (ADR-017) uses the spacing between frames, never their absolute values.
- Audio frames carry their capture time too. The receiver's ring sets the audio delay and does not read it (v1 or v2).

## Limits

| Name | Value | Why |
|---|---|---|
| `MAX_PAYLOAD` | 4 MiB | A 1080p keyframe at the highest bitrate setting is well under 1 MiB; the cap only stops a corrupt length from allocating gigabytes |
| hello resend | 1 s | Android's accessory dialog takes the user a few seconds |
| Allow prompt | 60 s | Long enough to walk to the computer; a prompt left open forever would hold the link in enrolment |
| Allow prompts | 1 per phone per attach | A phone that loops `enrol` can't flood the receiver with prompts |
| audio frame | 10 ms | The DataChannel path's chunk size, so the receiver's engine sees the same input (game audio 1 920 B, mic 960 B) |
