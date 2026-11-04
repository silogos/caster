# ADR-013: Game audio as AAC-LC over the DataChannel (importing TikTok's proven audio stack)

- **Status:** accepted (2026-11-04) — amends the payload of the `game-audio` DataChannel from [ADR-012](ADR-012-game-audio-pcm-over-datachannel.md); the channel itself, the two-PC architecture, and the mic's transport are unchanged

## Context

ADR-012 ships game audio as raw stereo PCM (~1.5 Mbps) because the pinned libwebrtc encoder is mono-hard. The TikTok teardown ([audio-importable.md](../../../tiktok-cast-teardown/audio-importable.md), dex-verified in #101/#104) proves a production config **7× lighter that is already battle-tested at scale**: AAC-LC **192 kbps CBR, stereo, 48 kHz**, every output frame wrapped in a **7-byte ADTS header**, sent with **no out-of-band codec config** — the receiver synthesizes the AudioSpecificConfig (`0x11 0x90` = LC / 48 kHz / stereo) itself.

**Architecture distance, stated explicitly:** TikTok does **not** use WebRTC or DataChannels — its transport is proprietary (`libvoip.so` RTP-style packetizer with FEC over `libbytelink.so`, audio on a port separate from video). What this ADR imports is the **codec + framing + production config only**; the transport stays 100% WebRTC (ADR-012/#104: "one PeerConnection, ICE/DTLS/SCTP unchanged; only the payload changes"). The `game-audio` DataChannel (`ordered=false, maxRetransmits=0`) is our equivalent of their dedicated audio port, and the documented teardown conclusion stands: on a healthy LAN, drop-late SCTP degrades comparably to their FEC/PLC — that trade is only revisited if #107's hotspot bucket shows audio fragility.

#104 (proposed) had picked Opus for the lighter payload but left AAC as its open question. This ADR resolves it toward AAC.

## Decision

Game audio is **encoded AAC-LC on the phone with the platform MediaCodec and shipped as ADTS frames over the existing `game-audio` DataChannel**, decoded on the desktop with WebCodecs:

- **Mobile encoder** (mirrors the teardown's `AudioEncoder.prepare()`): `MediaCodec.createEncoderByType("audio/mp4a-latm")`, `KEY_AAC_PROFILE = AACObjectLC`, `KEY_BIT_RATE = 192000`, `KEY_BITRATE_MODE = CBR` (explicit, where TikTok relied on the default), input PCM16 48 kHz stereo. Synchronous MediaCodec on a dedicated thread fed from a bounded drop-oldest queue — the ADM's recording thread never blocks (the same contract the DataChannel sender already honors).
- **Access units:** AAC-LC consumes 1024-sample frames (~21.3 ms, 4096 B), while the ADM delivers ~10 ms chunks (1920 B). A pure accumulator slices the stream into encoder-sized units; a sub-unit remainder is dropped at stop (inaudible).
- **Framing:** every encoder output frame gets the 7-byte ADTS header (TikTok's `addADTStoPacket`, ported byte-for-byte); the codec-config (`csd-0`) buffer is dropped exactly as TikTok's `handleOutputBuffer` drops it. One ADTS frame per DataChannel message.
- **In-band format handshake** on the same channel (label unchanged; no signaling/envelope change — recorded as a versioned sub-protocol in [webrtc.md](../architecture/webrtc.md)): the mobile opens in **PCM** mode (byte-identical to ADR-012) and sends a periodic text `announce` (`{"v":1,"type":"announce","format":"adts-aac-192"}`) while the channel is open; a desktop that understands it replies `{"v":1,"type":"ack","format":"adts-aac-192"}` and switches its inbound mode to AAC, after which the mobile feeds ADTS frames and stops the PCM feed. If no ack lands within a bounded window (old desktop — it ignores text messages today), the mobile stays on PCM forever; a late ack is still honored. An encoder failure mid-cast flips back to PCM and announces `pcm16` so the desktop switches with it. A lost announce on the unordered channel self-heals via the periodic retry.
- **Desktop decoder:** WebCodecs `AudioDecoder` (`codec: 'mp4a.40.2'`, 48 kHz, 2 ch, `description: [0x11, 0x90]`), behind a runtime feature probe. Each message is validated as one ADTS frame (syncword + length); an invalid frame is dropped and counted — the ADTS header gives the parser re-sync points for free. Chunk timestamps are **synthesized receiver-side from the sample clock** (frame index × 1024 × 1e6 / 48000) — TikTok's `getJitterFreePTS` insight (timestamp from counted samples, not wall clock) applied at the receiver, where loss cannot shift the grid. Decoded `AudioData` (f32-planar) converts to the same `{left, right}` Float32 pair the worklet already consumes — **the worklet, gain, routing, and the 3 s quiet fallback to the dual-mono track are untouched**.
- **Fallback chain:** AAC → raw PCM (no ack / no WebCodecs / encoder failure) → dual-mono track (channel quiet) — every step automatic, no renegotiation, degradation never a silence.

## Considered alternatives

| Option | Verdict |
|---|---|
| Opus via MediaCodec (#104's proposal) | **Rejected for now** — `c2.android.opus.encoder` availability varies by device (ADR-012 already noted this); AAC-LC encoders are essentially universal (the camcorder requirement) and are the TikTok-existence-proof codec. The ASC plumbing that made AAC "less simple" is one announced constant. Revisit if a device proves AAC encode unreliable |
| Ordered+reliable channel (protect AAC's bit reservoir) | **Rejected** — reintroduces the head-of-line blocking TikTok's separate-port design explicitly avoids, against the documented LAN conclusion (ADR-012 + teardown §4). ADTS self-describing keeps a lost frame's damage bounded at re-sync |
| Strip ADTS, send raw frames + one out-of-band csd | **Rejected** — ADTS is TikTok-proven, costs 7 B/frame (~1.4%), and buys the receiver free re-sync points |
| Per-frame sequence numbers (defend against SCTP reordering) | **Deferred** — unordered SCTP can reorder in theory; today's PCM path has the identical exposure; add only if hotspot-bucket evidence (#107) shows it |

## Consequences

- **Positive:** channel payload drops ~1.5 Mbps → ~0.21 Mbps; the quality reference is a production config audited at TikTok scale (192 CBR, not a guessed number); the receiver audio path (worklet/gain/routing/fallback) is byte-identical to ADR-012's; channel stats exist for the first time (ADR-012's DataChannel was invisible to getStats).
- **Costs / accepted:** ~+40–50 ms codec latency (encoder access unit + decoder priming) on top of ADR-012's ~30–60 ms buffering — audio-only tolerance on LAN absorbs it, and the PUBG ear test is the acceptance gate; **loss semantics change** — a lost frame is no longer an inaudible 10 ms sliver but a codec glitch until ADTS re-sync and bit-reservoir refill (a few frames). Accepted under the LAN-loss assumption; the same hotspot-bucket evidence that would justify FEC/PLC (#107) would justify ordered+reliable first.
- **Revisit if:** a prebuilt with `WebRTC-Audio-MultiChannelSend` ships (ADR-012's retire clause still stands and would supersede this too), or hotspot-mode loss shows AAC fragility (then test ordered+reliable before reaching for FEC).
