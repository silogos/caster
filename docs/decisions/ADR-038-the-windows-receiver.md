# ADR-038: The Windows receiver: cable, Wi-Fi, speaker and the video window

- **Status:** accepted (2026-11-20) by the product owner ("Approve ADR-038"), proposed the same day. Scope set by the product owner the same day: "fokus ke cable, wifi (webrtc), audio (speaker), video (render window) di windows". Decision 4 (Media Foundation) chosen by the product owner the same day after the alternatives below were laid out ("oke pakai MF"); 60 fps is the live-stream target and 120 fps is kept as a best-effort bonus. Decisions 5 and 6 rest on assumptions that spikes S1 and S2 must confirm before the code phases start.

## Context

The native receiver (`apps/desktop-native`, [ADR-015](ADR-015-native-receiver-tauri-libwebrtc.md)) runs only on macOS. A ~5-hour live over the cable (2026-11-20, [reliability.md](../features/reliability.md) Native session 1) showed that the Mac receiver works for the product owner's main use, so the next receiver is Windows.

Most of the crate is portable: protocol, pairing, signaling, the link owner (ADR-037), the USB link protocol, the pacer, settings, logging, the tray (Tauri), the WebView UI, and audio playout through CPAL. These parts are tied to macOS:

| Part | macOS today |
|---|---|
| Video window and display (`video.rs`) | AppKit `NSWindow`, `AVSampleBufferDisplayLayer`, CoreVideo |
| Cable H.264 decode (`h264.rs`, `native/h264_decoder.m`) | VideoToolbox |
| Wi-Fi H.264 decode | libwebrtc's Objective-C VideoToolbox factory, inside `webrtc-sys` |
| Output for OBS (`syphon.rs`) | Syphon |
| Virtual audio devices (`virtual_audio.rs`, `caster-audio/`) | A CoreAudio plug-in |
| Audio device tuning (`audio.rs`) | A 128-frame IO buffer, `coreaudiod` restart recovery |
| Stay-awake (`power.rs`) | IOKit assertion |
| USB access (`usb_link.rs`) | libusb reaches any device; macOS needs no driver |

Two facts shape the Windows design:

- **libwebrtc on Windows has no usable H.264 decoder in livekit's prebuilt.** `webrtc-sys` 0.3.53 (`src/video_decoder_factory.cpp`) states that the desktop prebuilts link an FFmpeg without H.264, and it stops advertising H.264 when the internal decoder fails to configure. Its NVIDIA decoder is compiled only when CUDA headers are present at build time, and it runs only on NVIDIA GPUs. On a Mac the Objective-C VideoToolbox factory fills this gap; on Windows nothing does. The phone encodes H.264 with its hardware encoder, so a Windows receiver as built today could not show Wi-Fi video.
- **Windows binds an Android phone to its own drivers** (MTP, and ADB when enabled). libusb can open a device only through a driver it supports (WinUSB, libusbK, libusb0, or the UsbDk filter). The AOA switch is a control transfer to the phone in that state. After the switch, the accessory (VID `18D1`, PID `2D00`–`2D05`) has no inbox driver at all. ADR-037 already expects "a driver step on Windows" in the cable guide.

## Decision

1. **One receiver, one crate.** Windows is a target of `apps/desktop-native`, not a new app. The macOS-only parts sit in `cfg(target_os)`-gated modules that expose the same functions on both platforms; the `objc2*` and AVFoundation dependencies move under a macOS-only target table. No cross-platform trait layer beyond those functions (AGENTS.md: no premature abstraction).
2. **Scope (product owner, 2026-11-20):** the cable, Wi-Fi (WebRTC), audio on the speakers, and the video window. **Not in scope:** an OBS output (Syphon has no Windows counterpart here; Spout is a later choice), Caster Audio / Caster Mic devices, the virtual camera, launch at login, a signed installer. OBS still gets the picture through its Window Capture of the video window. Target: Windows 10/11 x64.
3. **The video window: a Win32 window with a D3D11 flip-model swap chain.** Decoded NV12 textures go through `ID3D11VideoProcessor` to the back buffer: it converts NV12 to RGB with the frame's own colour range, which keeps the Mac lesson that range cannot be assumed, and it scales with aspect fit. The window behaves like the Mac one: frameless, dragged and resized by the video, opened by **Open video**, a NO INPUT card between casts, and a covered window keeps rendering (ADR-028). `pacing.rs` stays the clock: each frame is presented at its due time. Windows Graphics Capture (OBS Window Capture) records flip-model swap chains.
4. **One H.264 decoder of our own: Media Foundation.** The H.264 decoder MFT, bound to the same D3D11 device (DXVA), produces NV12 textures that the window draws without a CPU copy, the Windows counterpart of ADR-031's zero-copy path. The cable feeds it Annex-B access units directly, as `h264.rs` does with VideoToolbox.
   - **Why it is fast enough:** with a D3D11 device the decode runs on the GPU's video block (Intel Quick Sync, AMD VCN, NVIDIA NVDEC), the same silicon FFmpeg's D3D11VA and the browsers use. 1920×1200 @ 60 fps is ~140 Mpixel/s, well inside what any GPU since ~2015 decodes; the licence is Windows', not ours.
   - **Low-latency mode is required:** `CODECAPI_AVLowLatencyMode = TRUE`, or the MFT holds frames for reordering (tens of ms). The phone's real-time stream has no B-frames, so nothing is lost.
   - **The pool is finite:** decoded samples are released as soon as the pacer presents or drops them, or the decoder stalls. Decoder, video processor and swap chain share one D3D11 device with multithread protection on.
   - **Rotation:** a size change arrives as `MF_E_TRANSFORM_STREAM_CHANGE`; the output type is renegotiated there.
   - **Software fallback:** without DXVA (a VM, Remote Desktop, an old driver) the MFT decodes on the CPU. The receiver logs which mode is active. Windows N/KN editions have no Media Foundation until the Media Feature Pack is installed; the receiver detects that and says so in plain words.
   - **120 fps is best-effort:** 1920×1200 @ 120 fps needs ~1.08 M macroblocks/s, above H.264 Level 5.1 (983,040), and Microsoft documents its decoder up to Level 5.1. Many drivers decode past it, so the 120 fps option stays on the phone; if a PC refuses it, the receiver logs the refusal and 60 fps is the supported rate on Windows.
5. **Wi-Fi video uses the same decoder inside libwebrtc.** The receiver registers a Media Foundation H.264 decoder factory with libwebrtc, the Windows counterpart of the Objective-C factory. Livekit's Rust API cannot do that, so it means a patched `webrtc-sys` vendored in the repository: a small C++ factory plus the build line, pinned to the version in use. *Spike S2 confirms the assumption first:* build today's receiver on Windows and read the SDP answer and the `webrtc` log. If H.264 is absent, prototype the factory. If the patch turns out unworkable, stop and bring the alternatives back to the product owner.
6. **Cable access through UsbDk, installed once.** UsbDk (Red Hat, Apache-2.0, a signed filter driver) lets libusb open any USB device without replacing its driver; libusb uses it through `LIBUSB_OPTION_USE_USBDK`. That covers both the switch (the phone still on MTP) and the accessory afterwards (no driver at all). It installs once, with Windows' administrator prompt, from the cable guide's driver step: the Windows counterpart of Caster Audio's one-time password dialog on the Mac. Nothing changes on the phone: no ADB, no Developer Options. *Spike S1 confirms it on the PO's Windows PC and phone:* switch, claim, and a cable cast's first frames.
7. **Audio: CPAL's WASAPI host, shared mode, speakers only.** Both tracks keep their own engines (ADR-003); **Plays on** offers only Speaker on Windows. The 128-frame IO buffer request stays macOS-only. WASAPI's shared-mode period is ~10 ms, so expect a few ms more playout delay than the Mac; it gets measured, not assumed. The engine still reopens its output when the callback stops (a device change, an endpoint removed).
8. **Stay-awake:** `SetThreadExecutionState(ES_CONTINUOUS | ES_DISPLAY_REQUIRED)` while a cast runs, released after it.
9. **Firewall:** Windows Defender Firewall asks the first time the receiver listens on port 52341. The user must allow it on private networks, or Wi-Fi pairing fails silently. The QR hero says so on Windows, and the receiver logs when no phone reached the port.
10. **Order:** spikes S1 (USB) and S2 (H.264 in libwebrtc) first, each recorded in `docs/proposals/windows-receiver.md`. Then **W1**: the Windows build, the video window, speaker audio, and the cable. Then **W2**: Wi-Fi. The cable comes first because it is the product owner's proven use, it needs only our own decoder, and W2's libwebrtc patch reuses that decoder.

| Option | Verdict |
|---|---|
| A second app for Windows | Rejected: duplicates protocol, pairing and link code that is already tested |
| Render in the WebView (WebCodecs / canvas) | Rejected: a copy per frame across the WebView boundary, and the Mac's native window is the proven path |
| `wgpu` for the window | Not now: D3D11 is what Media Foundation decodes into; a second graphics API adds a texture-sharing step |
| FFmpeg (with H.264) or OpenH264 for decode | Rejected as the main path: a software decoder at 1920×1200 @ 60–120 fps costs CPU, and a custom libwebrtc/FFmpeg build is heavier than one factory |
| Ask the phone for VP8/VP9 when the receiver is Windows | Rejected: a phone-side codec switch, hardware VP8 support varies, and the thermal tuning is for H.264 |
| OpenH264 (Cisco) | Rejected: software decode, and Cisco covers the licence only for its binary downloaded at runtime |
| Vendor SDKs (NVDEC, oneVPL, AMF) | Rejected: three code paths for what Media Foundation reaches on every GPU |
| NVIDIA decoder only | Rejected: excludes Intel and AMD PCs |
| A WinUSB INF for the accessory IDs (libwdi / Zadig style) | Kept as S1's fallback: it covers the accessory, not the switch, and needs a self-signed certificate in the trusted root |

## Consequences

- The crate builds on Windows only on Windows (MSVC, the libwebrtc prebuilt, Media Foundation, D3D11). CI gains a `windows-latest` build + `cargo test --lib` job; device tests need the product owner's Windows PC (AGENTS.md: real devices).
- A vendored, patched `webrtc-sys` is a maintenance cost: each livekit update re-applies the patch.
- Windows users install one driver (UsbDk) before their first cable cast; Wi-Fi needs no driver.
- Without an OBS output or virtual audio devices, OBS on Windows takes the picture by Window Capture and the sound from the speakers (desktop audio). That is a step back from the Mac's Syphon + Caster Audio, accepted for this scope.
- `docs/architecture/desktop-native.md` describes the Windows modules once W1 lands, in the same phase as the code.
