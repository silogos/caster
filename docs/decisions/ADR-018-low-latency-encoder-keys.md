# ADR-018: Low-latency MediaCodec keys on the hardware video encoder

- **Status:** accepted (2026-11-10). The keys are applied and accepted by the device's encoder; a latency gain is **not** demonstrated (see Consequences).

## Context

The 2026-11-10 comparison with scrcpy, Moonlight/Sunshine and Parsec found one gap that a normal app can close: scrcpy configures the same Android hardware encoders with `KEY_LATENCY = 1` and `KEY_PRIORITY = 0` (realtime), while libwebrtc's `HardwareVideoEncoder` sets neither — and the pinned prebuilt (`stream-webrtc-android` 1.3.8) offers no hook to add MediaFormat keys.

## Decision

`org/webrtc/LowLatencyEncoderSupport.kt` (in package `org.webrtc`, same class loader as the AAR) wraps the hardware encoder factory:

1. `LowLatencyHardwareEncoderFactory` lets libwebrtc's `HardwareVideoEncoderFactory` create each encoder.
2. It replaces that encoder's package-private `mediaCodecWrapperFactory` field (one reflective write) with a wrapper whose codecs add `EncoderLatencyKeys.hardware()` to the MediaFormat just before `configure()`. Every other codec call delegates untouched.
3. `CastEncoderFactory` composes it with libwebrtc's own `DefaultVideoEncoderFactory(hardware)` constructor, so the software fallback is unchanged.

If the field is ever missing (library changed), the encoder runs stock and logs a warning; a mirror-guard unit test fails first. Not set: `KEY_OPERATING_RATE` (can raise clocks — heat for no measured gain), intra-refresh (changes the bitstream; separate decision).

| Option | Result |
|---|---|
| Port all of `HardwareVideoEncoder` into the project | Rejected: ~600 lines of pinned code to maintain for two keys |
| **Wrap the codec factory field** | **Chosen**: ~60 lines, every libwebrtc behaviour kept |
| Do nothing | Rejected: unverifiable whether the encoder buffers frames without trying |

## Consequences

- **Accepted by the codec:** `c2.qti.avc.encoder configured with {latency=1, priority=0}; codec input format reports {latency=1, priority=0}` (TB321FU, 2026-11-10).
- **No measurable gain yet.** Glass-to-glass video ([latency-measurement.md](../development/latency-measurement.md)), median per measured pass:
  - stock: 63, 67, 117 ms;
  - with the keys: 113, 46, 62, 82, 51 ms.
- The spread *between* passes, even within one cast (82 → 51 ms), is far larger than any difference between the two sets. Each pass is internally tight (±10 ms). That pattern — a fixed offset chosen at some point and then held — points at a standing queue somewhere in the capture → encode path, in steps of ~one or two 60 Hz frames. That is the next thing to find; the keys neither caused nor fixed it.
- Kept because the codec accepts them, they are what scrcpy ships on the same hardware, and nothing regressed (0 dropped frames, encode ~15–16 ms/frame).
- **Revisit if:** a device's encoder misbehaves with them (drop the keys for that codec name), or the standing-queue investigation shows they matter either way.
