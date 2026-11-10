# ADR-022: A video encoder that stops answering is re-created

- **Status:** accepted (2026-11-10). Verified on-device with an injected stall; the stall that prompted it has not been reproduced on demand (see Consequences).

## Context

Found live 2026-11-10, 37 minutes into a 1920×1200 60 fps cast with a game running: the picture on the Mac froze for good while the game audio carried on. The receiver was healthy. On the tablet (TB321FU):

- the sender's `framesEncoded` stood still at 114,991 and the stats line read `0 kbps … 0.0 fps`;
- the encoder thread logged `HardwareVideoEncoder: Dropped frame, encoder queue full` for every captured frame;
- the codec's output thread was alive, waiting in `MediaCodec.dequeueOutputBuffer` — the codec (`c2.qti.avc.encoder`) was not in an error state, it just emitted nothing;
- the thermal status was SEVERE (CPU cores ~90 °C, skin 46.6 °C); in the minute before, the frame rate had sagged from 60 to 30–40 fps with dips to 1–5 fps.

Why it never healed: libwebrtc's `HardwareVideoEncoder.encode` refuses a frame when more than `MAX_ENCODER_Q_SIZE` (2) frames are waiting for output (read from the pinned prebuilt's bytecode). Three frames went into the codec and never came out; from then on every frame was refused before reaching the codec, so the codec got no input and had nothing to answer. Nothing in libwebrtc resets that state except an encoder re-initialization, and nothing asked for one. The user had to stop and start the cast.

What made the codec swallow three frames is not known: the tablet log from that second had already been overwritten. Heat is the suspect. The low-latency keys of [ADR-018](ADR-018-low-latency-encoder-keys.md), added the same day, are not excluded.

## Decision

`webrtc/EncoderStallRecovery.kt` wraps every MediaCodec encoder the cast uses (`CastEncoderFactory`, both the Hardware and the Software setting):

1. **The signature is exact.** `EncoderStallWatch` calls an encoder stalled when it has *refused* frames (`NO_OUTPUT`) for `STALL_TIMEOUT_MS` = 3 s without emitting one frame. A slow encoder refuses frames too, but keeps emitting some; a quiet screen offers no frames to refuse. Elapsed time alone is not the signal.
2. **Recovery is a re-creation.** `StallRecoveringEncoder` releases the encoder and initializes it again, on the encoder thread, with the settings and rates libwebrtc last gave it, and asks for a key frame. libwebrtc sees the same encoder object throughout.
3. **Bounded, then repeated.** After `MAX_RECOVERIES_WITHOUT_OUTPUT` = 3 re-creations in a row without a frame out it returns `FALLBACK_SOFTWARE` once and starts over. Where libwebrtc has a software encoder (VP8, VP9, AV1) it takes over. For H.264 it has none, the request changes nothing, and the watch keeps re-creating the codec every 3 s.

3 s, not less: the throttled encoder was crawling at 1–3 frames a second just before it stalled, and a false re-creation there would add a key frame to an encoder already out of headroom.

| Option | Result |
|---|---|
| Do nothing | Rejected: a frozen picture until the user restarts the cast by hand |
| Restart the whole cast from `MediaCastSession` when `framesEncoded` stands still | Rejected: stats cannot tell a stalled encoder from a quiet screen (both read 0 fps), and a restart drops the audio too |
| Return `FALLBACK_SOFTWARE` at once | Rejected: no software H.264 encoder exists here, and software 1920×1200 60 fps on a hot SoC is worse than a fresh hardware codec |
| **Re-create the codec behind the same encoder** | **Chosen**: ~150 lines, video back in ~3 s, audio untouched |

## Consequences

- **Verified on-device (TB321FU, 2026-11-10)** with a temporary switch that silenced the codec's output (removed before commit). It reproduced the incident's signature: `framesEncoded` frozen, `0.0 fps`, 191 `encoder queue full` drops. Then: `emitted nothing for 3000 ms while refusing frames — re-creating it (attempt 1)`, release `OK` and a fresh `c2.qti.avc.encoder` 107 ms later with the low-latency keys re-applied, and 60 fps again on both ends. The Mac's picture stood still for 3–4 s (receiver log, one line a second).
- **A codec that stays silent:** held for 32 s, the cycle ran as designed (three re-creations, one `FALLBACK_SOFTWARE`, repeat) and the picture returned within a second of the switch being released. The encoder stayed `c2.qti.avc.encoder`: libwebrtc did not change codec.
- **Not verified:** a codec hung inside the vendor driver. The injected stall silences only the output side, so `release()` returned in ~70 ms. A truly hung codec may hold `release()` until libwebrtc's 5 s timeout, on the encoder thread, per attempt.
- **Not addressed:** why the codec stalled. With *Auto quality* off the app does not lower its load at thermal SEVERE — the user's explicit choice ([thermal.md](../architecture/thermal.md), principle 2) — so a hot tablet can stall again; it now recovers instead of freezing.
- **No user-facing notice.** A 3 s freeze is visible but unexplained, and a codec that never comes back still shows as a running cast. Logs carry every step (`EncoderStall`, WARN/ERROR).
- **Revisit if:** recoveries show up in logs on a cool device (then the stall is not thermal — test without ADR-018's keys first), or a real hang shows the 5 s release timeout stacking up.
