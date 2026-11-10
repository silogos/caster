# ADR-021: The native receiver publishes the cast through Syphon, from inside the app

- **Status:** accepted (2026-11-10). Unit and bridge tests pass and an independent Syphon client sees the server and its idle still; **a live cast through it and OBS itself are not verified yet** (see Consequences). Amends [ADR-007](ADR-007-virtual-camera.md) for the native receiver.

## Context

The Electron receiver's macOS "virtual camera" was a Syphon server (ADR-007): OBS reads it with its built-in Syphon Client source. The native receiver (ADR-015) had no such output, so OBS could only capture its video window, which must then stay open and uncovered.

The product owner's decision, 2026-11-10 (issue #152): the consumers that matter are OBS and, if possible, TikTok LIVE Studio; **Syphon for now**, controlled **from the receiver**.

The Electron path does not carry over. It read each frame back from a canvas to the CPU, wrote it as BGRA to a helper process over a pipe, and the helper uploaded it to a texture: at 1920×1200 and 60 fps that is about 550 MB/s through the pipe. The native receiver already holds each frame as an IOSurface-backed NV12 `CVPixelBuffer` for its display layer.

## Decision

1. **In-process, on the GPU.** `native/syphon_bridge.m` (compiled by `build.rs`, linked against the vendored `Syphon.framework`) turns the frame's own pixel buffer into a BGRA Metal texture with Core Image and publishes it. No pixel is copied on the CPU beyond the I420 → NV12 copy the window already needs. Colour management is off: only the YCbCr → RGB matrix and the range the buffer's pixel format states.
2. **Published when due.** `syphon.rs` publishes each frame on its own thread at the pacer's due time (ADR-017), so OBS gets the window's even cadence, not Wi-Fi's bursts. The queue holds six frames; a fuller one drops the newest.
3. **Only while read.** The frame thread makes a buffer for the output only while it is on **and** a client is connected (polled every 250 ms). With the window closed and nobody reading, frames are still dropped before any conversion.
4. **Independent of the window.** While a client reads, frames are converted even when the video window is closed or covered.
5. **Between casts** the server stays up and shows one "No input video" still, not the last frame (ADR-007's live finding).
6. **A receiver control.** One switch in the receiver's window ("Output to OBS → Syphon"), saved in `native-receiver.json`, off by default. The phone's proto-2 `vc-*` messages stay unanswered: what this computer does with the stream it already has is a receiver/environment matter (ADR-007 reached the same conclusion for the desktop toggle).
7. **Same server name** as the Electron receiver, "Zero-Friction Local Cast", so an existing OBS scene keeps working.
8. **The framework is vendored** at `apps/desktop-native/vendor/syphon/` (BSD-3, a copy of `apps/desktop/resources/syphon/Syphon.framework`), so the native app no longer depends on the Electron tree (issue #152, step 2).

| Option | Verdict |
|---|---|
| Port the helper process and its BGRA pipe | Rejected: ~550 MB/s of CPU copies for frames that are already on the GPU |
| Hand-written Metal shader for NV12 → BGRA | Not now: Core Image does the same conversion in a few lines; revisit if its per-frame cost shows |
| objc2 bindings for Metal, Core Image and Syphon in Rust | Rejected: far more unsafe Rust than 190 lines of Objective-C behind five C functions |
| Publish on arrival | Rejected: OBS would show the delivery jitter the pacer exists to remove |
| Controlled from the phone (proto 2) | Not now: needs the `vc-*` messages in the native receiver for a switch that is a receiver matter |
| macOS Camera Extension | Deferred: the only camera both OBS and TikTok LIVE Studio would see, but it needs Apple signing, notarization and a user approval |
| NDI output | Deferred: research in issue #152 |

## Consequences

**Verified, 2026-11-10 (MacBook Pro M1 Pro):**
- `cargo test`: 87 passed (+6). One test goes end to end through the bridge: an NV12 frame in, the BGRA frame the Syphon server offers out — right size, top row first, video-range white ≥ 250 and black ≤ 5.
- An independent Syphon client (not the receiver) lists the server by name and receives the 1920×1080 "No input video" still, upright.

**Not verified yet:**
- A live cast through the output: frame rate, spacing, colours against the window, and the cost of the conversion.
- OBS's own Syphon Client source.

**Known limits:**
- OBS and other Syphon clients on the same Mac only. Call apps and TikTok LIVE Studio do not read Syphon; LIVE Studio is served by window capture until a real camera exists.
- The phone's virtual-camera switch does nothing against the native receiver.
- While a client reads with the window closed, the receiver does the per-frame work it otherwise skips (I420 conversion and the NV12 copy).
- **Revisit if:** the Core Image pass costs more than a fraction of a millisecond per frame, or a consumer needs a real camera.
