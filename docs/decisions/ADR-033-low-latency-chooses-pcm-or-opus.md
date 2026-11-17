# ADR-033: "Low-latency audio" chooses PCM or the Opus track; AAC is removed

- **Status:** accepted (2026-11-17), product owner's decision. **Supersedes [ADR-013](ADR-013-game-audio-aac-over-datachannel.md)** (AAC on the DataChannel) and gives Feature 2026-11d's toggle a real effect. ADR-012's channel stays, as the Low latency path.

## Context

A profile's **Low-latency audio** field (Low latency | Standard) was meant to pick the DataChannel's payload: raw PCM16 or AAC-LC. AAC needed the desktop to ack an in-band `announce`. The native receiver never acks it (ADR-015), so every cast has been PCM whatever the profile says. "Standard", the default, did nothing. The only receiver that decoded AAC is the obsolete Electron one.

[ADR-032](ADR-032-stereo-opus-needs-the-receivers-answer.md) found that the game-audio Opus track is stereo end to end once the receiver's answer asks for it. That gives "Standard" a real meaning without AAC: compressed stereo on the Opus track, which the phone encodes anyway (ADR-016: it cannot be paused without stopping the capture).

## Decision

The phone decides at cast start, from the profile:

| Low-latency audio | Phone | Receiver |
|---|---|---|
| **Low latency** | opens the `game-audio` DataChannel and sends raw PCM16 on it (ADR-012's payload, unchanged) | plays the channel; the Opus track only before the first chunk or after 3 s of quiet |
| **Standard** (default) | opens **no** DataChannel; game audio is the Opus track alone (stereo, up to 192 kbps) | never sees a channel, so it plays the Opus track from the start |

- **AAC is removed from the phone**: the MediaCodec encoder, ADTS framing, the `announce`/`ack` handshake and its negotiator, and the sender that switched between the two payloads. The channel carries only PCM16, and only when asked to.
- **The channel's text sub-protocol (`v:1` announce/ack) is retired.** The binary payload is unchanged: PCM16 is what every receiver already played when no ack happened, so a receiver needs no change, and none was made.
- **The receiver is unchanged.** Its source ladder (channel first, the track before any channel audio and after 3 s of its silence) already plays the right thing for both settings.
- **The default stays Standard** (product owner's choice, 2026-11-17). Profiles keep the value they hold. A profile that says Standard, which until now sent PCM, now sends the Opus track: a little more delay, much less Wi-Fi.
- The field's label and options are unchanged. Its hint now says what both are: "Low latency sends raw sound: the smallest delay for headphones, at a higher Wi-Fi bitrate. Standard sends compressed stereo: less Wi-Fi, a little more delay." Designed first in `designs/mobile-app.figcraft` (page *Mobile design v2*, card · audio; render `designs/preview/mobile-profile-audio-hint.png`).

| Option | Verdict |
|---|---|
| Keep AAC as Standard | Rejected: no receiver in use decodes it, and the Opus track already carries compressed stereo |
| Label the field "PCM audio: On / Off" | Rejected by the product owner: keep the plain wording |
| Keep the channel open but silent in Standard | Rejected: an SCTP association and a drain thread for nothing; no channel is the same signal to the receiver |
| Default to Low latency, migrating stored profiles | Not chosen: the product owner kept Standard as the default |

## Verified (2026-11-17, Lenovo TB321FU debug build → MacBook Pro M1 Pro, receiver from `main` with ADR-032)

- **Standard:** two casts after the install (~24 min together). The offers carried no `m=application` section, the receiver saw no DataChannel and logged no `dc:` line, and it played the Opus track: 1 467 seconds with sound, 2 of them L = R. The product owner heard stereo.
- **Low latency:** the offer carried the channel, and the receiver played its PCM (75 s, 7 500 chunks, 21 underruns totalling 470 ms). The product owner heard stereo with minimal delay.
- Android `testDebugUnitTest`: 405 tests, 0 failures. `compileDebugKotlin` and `assembleDebug` clean. The design file was regenerated from the built UI (`check_auto_layout.py`: 0 layers off on all three pages).
- Not measured: the latency difference between the two settings in milliseconds.

## Consequences

- **Standard costs latency.** The Opus track goes through NetEq on the receiver, whose depth the receiver cannot set (ADR-014). Low latency keeps the receiver's own playout (ADR-020). The difference was not measured in this change.
- **Wi-Fi:** Standard sends ~0.2 Mbps of game audio instead of ~1.7 Mbps (PCM on the channel plus the Opus track).
- **The setting applies at the next cast**, as before: the channel is created, or not, before the offer.
- Removed tests went with their code (`AdtsFramerTest`, `GameAudioFormatNegotiatorTest`, `GameAudioSenderTest`, `DropOldestQueueTest`).
