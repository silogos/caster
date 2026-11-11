# ADR-028: A covered video window keeps rendering

- **Status:** accepted (2026-11-11), measured on the product owner's Mac and tablet; see Consequences for what was not measured.

## Context

The native receiver's video window is the cast's output device: OBS Window Capture records it, and it is often covered by other windows. Reported by the product owner on 2026-11-11: the picture freezes when the window is not in front. The Electron receiver had the same defect and fixed it with `backgroundThrottling: false` (issue #48): Chromium stops presenting frames for a window macOS reports as occluded, and that option makes it paint anyway. The native receiver has no Chromium; its own gate did the same thing: `window_state` required `occlusionState` to carry the Visible bit, and while it did not, every decoded frame was dropped at the live edge (`drop_hidden`). Decode and audio kept running; the window stayed on its last frame.

That gate was added on 2026-11-09 because a covered window's queued samples pinned decoded surfaces (8.8 GB in 7 minutes). Two later fixes removed the known causes: the per-frame IOSurface leak (`get_cv_pixel_buffer`, 2026-11-10) and the unbounded display-layer queue.

## Decision

A window that is **open, not closed by the user, and on screen** (`isVisible`) is rendered, whether or not another window covers it. A minimized window, an app-hidden window and a closed window are still not rendered. The occlusion bit is read only to log `covered` / `uncovered`.

Memory stays bounded where it already was: the pool's allocation threshold (10 buffers), dropping a frame when the layer is not ready, and flushing a layer that refuses samples for 1 s.

| Option | Result |
|---|---|
| Keep the occlusion gate | Rejected: the picture freezes exactly when the receiver is watched or captured from another window |
| **Render while on screen, ignoring occlusion** | **Chosen**: the native twin of `backgroundThrottling: false`; the memory it was guarding against is bounded elsewhere |
| Render when covered only while a capture is running | Rejected: the receiver cannot tell that OBS is recording the window |

## Consequences

- **Measured (2026-11-11, release build, Mac + TB321FU, 1280×800 cast):** the video window was covered by another window from 20:05:18 on (the log reads `covered by another window — still rendering`; it had been brought forward once by a Dock click at 20:05:08). Over ~7.5 minutes of that and 21 600 frames shown in the whole run: 0 frames dropped as hidden, 69 dropped because the layer was busy (0.3 %), 0 pool-full; the receiver stayed at 145–164 MB and `VTDecoderXPCService` at ~15 MB. No growth.
- **Cost:** a covered window now spends CPU on the I420 → NV12 copy (~0.3 ms/frame) and the layer's queue while nobody sees it. The same trade as Electron's `backgroundThrottling: false`.
- **Not measured:** a screenshot or OBS capture of the covered window (the build machine's shell has no screen-recording permission), minimize and restore on this build, a run longer than ~8 minutes, other macOS versions. The earlier 8.8 GB figure was not reproduced because the gate was removed before a comparison run; the measurement above is what the new rule did.
- If memory ever grows again with a covered window, the revisit options are a shorter flush interval while covered, or an explicit setting.
