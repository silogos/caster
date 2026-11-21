# ADR-039: The receiver can feed OBS's own virtual camera on macOS, without OBS running

- **Status:** accepted (2026-11-22, product owner: "Accept, plan it"). The spike was verified end to end on the MacBook Pro M1 Pro: the probe wrote the frames, and the product owner saw them in a camera app as "OBS Virtual Camera" with OBS closed. Amends [ADR-007](ADR-007-virtual-camera.md) and [ADR-021](ADR-021-native-syphon-output.md) for macOS. Plan: [roadmap](../development/roadmap.md) Feature 2026-11ao.

## Context

On a Mac the receiver offers Syphon (ADR-021). Only OBS and VJ apps can read Syphon. Call apps and TikTok LIVE Studio see only *cameras*. For Caster to have its own camera it needs a CMIO Camera Extension, which needs Apple signing, notarization, a system-extension approval and an Apple entitlement. ADR-007 and ADR-021 deferred that.

OBS Studio already ships such an extension, "OBS Virtual Camera". It is signed and notarized. A user who has clicked *Start Virtual Camera* once has already approved it. The question from the product owner (2026-11-22) was whether Caster can send frames to that camera directly, without the OBS app.

## What the spike found

Source read: `obsproject/obs-studio` `plugins/mac-virtualcam` (main branch, 2026-11-22). Installed copy: OBS 32.1.1, extension `com.obsproject.obs-studio.mac-camera-extension`, `[activated enabled]`, macOS 26.2.

**The camera extension (`camera-extension/*.swift`).**
- It is one device with two streams: a *source*, which camera apps read, and a *sink*.
- `authorizedToStartStream(for:)` returns `true` for any client. It does not check that the client is OBS.
- While the sink is started, a timer at 180 Hz takes each sample buffer from the sink and sends it on the source unchanged. The size and pixel format are not checked: the format the device advertises (1920×1080 BGRA, 60 fps) is only a label. OBS itself sends its own output size, usually NV12.
- While the sink is stopped, the source shows OBS's placeholder image.

**OBS's side (`obs-plugin/plugin-main.mm`)** uses public CoreMediaIO C API only:
1. Find the device whose `kCMIODevicePropertyDeviceUID` equals `OBSCameraDeviceUUID`, a value in the extension's `Info.plist`. Here it is `7626645E-4425-469E-9D8B-97E0FA59AC75`.
2. Take the sink stream with `kCMIODevicePropertyStreams`. OBS picks the second stream; this spike picked the stream whose `kCMIOStreamPropertyDirection` is `0` (output), which turned out to be the same one.
3. Call `CMIOStreamCopyBufferQueue` on the sink, then `CMIODeviceStartStream`.
4. For each frame, wrap an IOSurface-backed `CVPixelBuffer` in a `CMSampleBuffer` and pass it to `CMSimpleQueueEnqueue`.

**The probe** (a 150-line Swift CLI, not committed) did the same with OBS.app not running:
- It found the device by reading the UID from `/Applications/OBS.app/.../Info.plist` at run time.
- It started the sink and pushed 1280×720 NV12 frames at 60 fps: 180/180, 300/300 and 480/480 frames were taken from the queue (capacity 10), with 0 refused for a full queue. The extension really does drain a non-OBS writer.
- **Writing needs no camera permission.** The probe's shell was attributed to a process that macOS refuses camera access ("requires entitlement com.apple.security.device.camera"), yet it wrote to the sink without a problem.
- *Reading* the source from the same process was refused by TCC (`kTCCServiceCamera denied`). So the probe could not check its own output. **The product owner checked it in a camera app they had allowed (Photo Booth or QuickTime), with OBS closed. The probe's pattern was on screen: a white bar sweeping across a grey that steps in brightness every second.** A 2-minute run pushed 7200/7200 frames with none refused.

## Decision

1. **A second macOS output, "OBS Virtual Camera", next to Syphon, not instead of it.** It gets its own switch, independent of "Output to OBS → Syphon". Either or both can be on (product owner, 2026-11-22: "jangan replace syphon, buat saja toggle baru"). Syphon stays the zero-setup path for OBS itself; the camera is for apps that only see cameras. The new switch is a receiver control, off by default (ADR-021 §6: what the computer does with a stream it already has is a receiver matter). It is offered only when the extension is installed and its device is present.
2. **Zero-copy.** The frame thread already holds an IOSurface NV12 `CVPixelBuffer` (ADR-031). It is wrapped in a `CMSampleBuffer` and enqueued at the pacer's due time on the same publisher pattern as Syphon (ADR-021 §2). There is no conversion and no CPU copy.
3. **Found, never hard-coded.** OBS is located by its bundle identifier (`com.obsproject.obs-studio`) through Launch Services, not by a fixed `/Applications` path. The device UID is read from the extension bundle inside it, and the sink is chosen by direction, not by index.
4. **Between casts the sink is stopped,** so the camera shows OBS's own placeholder instead of a frozen last frame (ADR-007's finding). A "No input video" still, as Syphon shows, is not used. The extension only passes on frames it is given, so one still would mean a camera that stops delivering frames, and call apps may treat that as a stalled camera. OBS's placeholder, by contrast, is redrawn by the extension's own 60 fps timer. This choice can be revisited after the product owner sees it.
5. **Fed only while it is read, if CMIO can tell us.** Syphon converts frames only while a client is connected (ADR-021 §3). For the camera, V1 first checks whether `kCMIODevicePropertyDeviceIsRunningSomewhere` tells apart "a camera app is reading" from "only our sink is running". If it can't, frames are fed whenever the output is on; that costs nothing extra, because the buffer is enqueued as it is, without conversion.
6. **Its own small module,** `obs_camera.rs` plus `native/obs_camera_bridge.m`, the same shape as `syphon.rs`/`syphon_bridge.m`. It is macOS-only like Syphon (ADR-038 §1). The due-time publisher (`syphon::Scheduler`) moves to a shared module now that two outputs use it.
7. **The camera counts for sound, like Syphon** (product owner, 2026-11-22: "Yes, camera counts"). ADR-029 plays the tracks while the video window is open or Syphon is on. Now they also play while the camera is on. A call app takes the picture from the camera and the sound from Caster Audio / Caster Mic; without this rule, a user with only the camera on would get a silent call. The Audio row's line reads "Plays while the video window, Syphon or the camera is on."

## Consequences and risks

- **Reach:** every camera app on the Mac (Zoom, Meet, Discord, browsers, and possibly TikTok LIVE Studio) gets Caster with no Apple entitlement on our side. This is the gap ADR-007 called "the still-open CMIO door".
- **It depends on OBS.** The user must install OBS and start its virtual camera once, to approve the extension. Caster cannot install it for them.
- **It depends on OBS internals.** The UID key, the two-stream layout and the pass-through behaviour are not a public API. A future OBS could check the client, fix the format, or rename the key. The output must fail closed ("OBS Virtual Camera isn't available"), never crash.
- **One writer at a time.** The extension remembers only the last client that started the sink. If OBS starts its own virtual camera while Caster is feeding it, one of them silently stops being shown. This must be documented for users.
- **Licence:** we call the system CoreMediaIO API and copy no OBS code. OBS is GPL-2; reading its source to interoperate is fine, copying it is not.
- **Windows** has the same idea (OBS's DirectShow filter reads a named shared-memory queue). It is not part of this ADR: ADR-038 keeps the virtual camera out of Windows scope, and softcam or `MFCreateVirtualCamera` would avoid the dependency there.

## Open checks (V1 acceptance)

- [x] A camera app shows the probe's pattern (a sweeping white bar on grey that steps brightness each second) as "OBS Virtual Camera", with OBS closed (2026-11-22, product owner).
- [ ] Other sizes are shown correctly by Zoom/Meet/browser and are not cropped to the advertised 1920×1080 (try 1920×1200 and a portrait size).
- [ ] The placeholder comes back after the sink stops.
- [ ] Behaviour when OBS's own virtual camera starts while Caster is feeding.
