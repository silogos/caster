# ADR-031: The receiver shows decoded frames zero-copy

- **Status:** accepted (2026-11-17), measured on the product owner's Mac and tablet; see Consequences for what was not measured. **Amends [ADR-015](ADR-015-native-receiver-tauri-libwebrtc.md)**'s video row: a VideoToolbox frame is no longer copied through I420.

## Context

libwebrtc decodes H.264 with VideoToolbox into an IOSurface-backed `CVPixelBuffer`, already NV12 (`'420f'` on the TB321FU stream). The receiver did not show that buffer. It called `to_i420()` (copy 1, NV12 → I420 inside libwebrtc) and then copied the I420 planes back into a pooled NV12 buffer of its own (copy 2) before handing that to the display layer and the Syphon output. The picture went out to the CPU and back to the format it started in.

The reason was a leak, recorded in ADR-015 and [desktop-native.md](../architecture/desktop-native.md): webrtc-sys' `get_cv_pixel_buffer`, the zero-copy hand-off, pinned every frame it was asked for (9 GB in 2 minutes; the decoder stopped at 16 384 live IOSurfaces).

The cause, found 2026-11-17 by reading webrtc-sys 0.3.53 and disassembling the prebuilt `libwebrtc.a`:

- `webrtc-sys/src/objc_video_frame_buffer.mm` is built **without ARC** (its `build.rs` passes no `-fobjc-arc`; the same file calls `release` by hand).
- It calls libwebrtc's `NativeToObjCVideoFrameBuffer`, which is **ARC** code: `ToObjCVideoFrameBuffer` returns through `objc_autoreleaseReturnValue`, so the `RTCCVPixelBuffer` comes back autoreleased.
- The receiver's frame thread never drains an autorelease pool. Each call parked one `RTCCVPixelBuffer`, with its `CVPixelBuffer` and IOSurface, until the thread ended.

The same diagnosis and a one-line fix (`@autoreleasepool` around the function body) are open upstream as [livekit/rust-sdks#1672](https://github.com/livekit/rust-sdks/issues/1672) / [#1673](https://github.com/livekit/rust-sdks/pull/1673), not merged as of 2026-11-17.

## Decision

1. **Every `get_cv_pixel_buffer` call goes through a pool we drain** (`objc2::rc::autoreleasepool` in `video::decoder_pixel_buffer`), and the buffer is retained before the pool drains. No webrtc-sys patch: the fix is on our side of the call, and stays correct after the upstream fix lands (a drained pool is then simply empty).
2. **A frame whose decoder buffer is 8-bit 4:2:0 NV12 (`'420v'`/`'420f'`) of the frame's own size is shown from that buffer**: the display layer and the Syphon output get the decoder's buffer, no copy. Its range is its pixel format.
3. **Anything else keeps the I420 copy** (a software-decoded frame, 10-bit or 4:2:2, a padded buffer). The frame loop logs which path frames take, and why, whenever it changes.
4. **`ZFC_VIDEO_COPY`** (set to anything) forces the copy, for comparing the two paths on the same build. A diagnostic, like `ZFC_LATENCY_PROBE`.
5. The decoder buffer and a pool buffer get **separate format descriptions** (`FormatKey.decoder_buffer`): a decoder buffer carries the stream's colour attachments, a pool buffer none.

| Option | Verdict |
|---|---|
| Keep the copy (ADR-015) | Rejected: the measured cost below is 3× what the zero-copy path costs, and the reason for it is gone |
| Patch webrtc-sys (`[patch.crates-io]` with #1673) | Not needed: a pool around our own call fixes the same leak without carrying a fork |
| Convert on the GPU (`VTPixelTransferSession`) | Not needed: the decoder's buffer is already the format the layer takes |

## Verified (2026-11-17, Lenovo TB321FU → MacBook Pro M1 Pro, release build, window open, ~3.5 min per path)

The same build twice, the second time with `ZFC_VIDEO_COPY=1`. Mostly 1920×1200, 45–48 fps.

| | Zero-copy | Copy (before) |
|---|---|---|
| Frame-thread cost per frame at 1920×1200 (`render cost`, debug log) | **430 µs** (10 704 frames) | 1 341 µs (9 506 frames) |
| Receiver CPU, mean of 5 s samples | **18.5 %** | 27.0 % |
| Receiver RSS, last | **105 MB** | 144 MB |
| `VTDecoderXPCService` RSS | 13–20 MB, flat over 10 730 frames | 13–20 MB |
| Frames dropped by the receiver | 24 layer busy of ~9 000 shown | 6 pool full of ~9 000 shown |

The decoder service staying flat over 10 000+ frames is the leak check: before the pool, the same path pinned ~3.5 MB per frame.

The "render cost" includes everything on the frame thread from deciding to render to enqueueing: pacing, sample-buffer creation, the enqueue. The copy path's 1.34 ms is `to_i420()` plus our copy; the ~0.3 ms ADR-015 records was our copy alone, at 1280×800.

**Unit tests** (`video::decoded_frame_tests`): an NV12 decoder buffer is shown as is, in both ranges; a 10-bit buffer goes through the copy; and asking for the decoder buffer five times on a thread without a pool leaves the buffer's retain count where it was. That last test fails without the pool (6 references instead of 1), so it guards the regression.

## Consequences

- **What bounds memory changes.** The pool threshold (10 buffers) bounded the copy path. Zero-copy frames come from the decoder's own pool, so the bound is now the layer's `isReadyForMoreMediaData` (the live-edge drop) and the Syphon queue. Measured: no growth over 10 730 frames. The 24 "layer busy" drops (0.3 %) against the copy path's 6 "pool full" are the same live-edge drop doing that job.
- **Colour:** the layer now reads the decoder buffer's colour attachments (YCbCr matrix, primaries) where the pool buffer had none. The product owner compared the two runs by eye and saw the same colours; not measured with a colour probe.
- **Syphon (2026-11-17, window closed, OBS reading through a Syphon Client source, same build with and without `ZFC_VIDEO_COPY`):** the product owner saw the picture upright, in the same colours, moving smoothly. Publisher figures at 3 600 frames: zero-copy 34 dropped, publish avg 1.20 ms (43 fps mean); copy 7 dropped, 1.06 ms (34 fps mean). One run each at different frame rates, so the higher drop count is not shown to come from zero-copy; a drop is the 6-frame queue (`syphon::QUEUE_FRAMES`) being full, which more frames per second make likelier. Worth a longer A/B at the same frame rate.
- **Not measured:** a software-decoded stream (falls back to the copy by design), and the two runs did not play identical content.
- **Revisit if** a decoder starts handing over a format the layer takes but the check refuses (the copy keeps working, the log names the format).
