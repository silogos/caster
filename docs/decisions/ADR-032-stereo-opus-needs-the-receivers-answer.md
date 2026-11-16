# ADR-032: The receiver's answer asks for stereo Opus — the mono was the decoder, not the phone

- **Status:** accepted (2026-11-17), measured on the product owner's Mac and tablet. **Corrects [ADR-012](ADR-012-game-audio-pcm-over-datachannel.md)'s diagnosis**; its decision (game audio over the DataChannel) stands, for the latency reasons of [ADR-014](ADR-014-native-low-latency-audio-output.md) and [ADR-020](ADR-020-audio-playout-steers-the-trough.md).

## Context

ADR-012 recorded that the desktop received the game-audio Opus track with `channelCount=2` and `left === right`, and concluded that the phone's libwebrtc build "downmixes the ADM's stereo input to mono before its Opus encoder", unfixable from the app. That conclusion was never tested on the receiving side. Both receivers (Electron, then native) set their own answer exactly as libwebrtc made it.

libwebrtc's source (checked against `m125_release`) says where a mono decode comes from:

- `AudioDecoderOpus::SdpToConfig`: the decoder has 2 channels only if the fmtp says `stereo=1`. Otherwise `return 1; // Default to mono.`
- `VoiceChannel::SetLocalContent_w`: the receive codecs, and so the decoder, come from the **local** description.

The receiver's answer carried libwebrtc's default Opus fmtp, `minptime=10;useinbandfec=1`, with no `stereo`. Its decoder was therefore mono: a stereo stream decoded to mono and duplicated to two channels, which is exactly `left === right`. The phone's `SdpOpusFmtp` adds `stereo=1` to the answer it receives, but that only shapes the phone's encoder. Its comment ("the fmtp constrains the *sender*, not the decoder") had it backwards for the decoder.

## Decision

The native receiver adds `stereo=1;sprop-stereo=1` to every Opus fmtp in the **media** pc's answer before `set_local_description` (`opus_fmtp::prefer_stereo`, applied in `webrtc::local_answer` for both the first answer and an ICE-restart re-answer). The mic pc's answer is left alone: voice is mono by design (webrtc.md codec policy).

The receiver also logs the Opus track's side-vs-mid level once a second (`opus track: … side … dB`), levels only, never audio. That makes a mono decode visible again if anything regresses.

## Verified (2026-11-17, Lenovo TB321FU → MacBook Pro M1 Pro, release build, same stereo content, phone unchanged)

The same build twice; the only difference was the answer's fmtp (the experiment's switch, since removed).

| | Answer as libwebrtc made it | Answer with `stereo=1` |
|---|---|---|
| Receiver's Opus fmtp | `minptime=10;useinbandfec=1` | `minptime=10;useinbandfec=1;stereo=1;sprop-stereo=1` |
| Opus track side level, seconds with sound | −106 to −123 dB in all 73 (L = R) | +5.9 dB in 58 of 92, about −41 dB in most others |
| DataChannel PCM side level, same seconds | +6 dB / 0 dB | +6 dB / −41 dB |

With `stereo=1` the Opus track follows the PCM: one-sided content reads +5.9 dB against the PCM's +6 dB, and near-centred content about −41 dB against −42 dB. The phone was not touched between the runs, so **the phone's encoder was sending stereo all along.**

Unit tests: `opus_fmtp` (stereo added and other parameters kept; an explicit `stereo=0` turned to stereo once; an fmtp line added when missing; a non-Opus answer unchanged) and `webrtc::tests::only_the_media_answer_asks_for_stereo_opus`.

## Consequences

- **The fallback track is stereo.** The desktop plays it before the DataChannel's first chunk and after 3 s of its silence. It was dual-mono until now.
- **The DataChannel stays the primary path.** Stereo was ADR-012's reason, and that reason is gone. Latency is the reason that remains: NetEq chooses its own buffer depth (ADR-014), while the receiver's own playout holds a 15 ms trough (ADR-020). Moving game audio back to the Opus track would be a new decision with its own measurement.
- **Corrected in the same change:** ADR-012's status, `architecture/webrtc.md`, `architecture/audio.md`, `architecture/desktop-native.md`, and the phone's comments that repeated the encoder diagnosis (`SdpOpusFmtp`, `GameAudioDataChannel`, `PlaybackCaptureAudioSource`, `MediaCastSession`). Phone code is unchanged.
- **Not measured:** the fallback switch itself with this change (the track's content was measured, not the moment the desktop falls back to it); the phone's `WebRTC-Audio-MultiChannelSend` field trial string, which the phone still passes and the library ignores, is left as it is.
