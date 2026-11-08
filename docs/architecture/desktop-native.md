# Native Desktop Receiver Architecture (`apps/desktop-native`)

Status: the desktop receiver going forward ([ADR-015](../decisions/ADR-015-native-receiver-tauri-libwebrtc.md)); the Electron receiver ([desktop.md](desktop.md)) is obsolete. macOS only. First hardening pass 2026-11-09.

The role is unchanged — **a receiver**: pair, receive, render, play. No cast settings live here (*mobile controls, desktop receives*).

## Modules

Small modules with one concern each (AGENTS.md); `lib.rs` only wires them.

| Module | Concern | Talks to |
|---|---|---|
| `protocol.rs` | Message shapes: envelope / hello / offer / ICE / session-info validation, `proto` negotiation, error codes. Pure — no I/O. | — |
| `pairing.rs` | QR session store (pending → authorized → closed, expiry, `busy`, host-change regeneration), trust store, persistent `desktopId`, HMAC/HKDF. No sockets. | filesystem |
| `signaling.rs` | The WebSocket server: handshake, heartbeat, rate limit, routing by `pc`. Media lives behind the `SignalingHost` trait. | `protocol`, `pairing`, host |
| `webrtc.rs` | One `MediaAnswerer` **per pc** (`media`, `mic`): answer, ICE both ways, the `game-audio` DataChannel, the fallback track, the video frame loop. | `audio`, `video` |
| `audio.rs` | The game-audio ear path: source selection, ring, CPAL playout. | CoreAudio |
| `video.rs` | The native video window and the per-stream `Renderer`. | AppKit, CoreVideo, AVFoundation |
| `logging.rs` | `HH:MM:SS.mmm LEVEL [scope] message`; `ZFC_LOG=debug` for more. | stderr |

`lib.rs`'s `TauriHost` implements `SignalingHost` (answerers + UI events); that seam is why the protocol is tested without WebRTC (`src/signaling/tests.rs` — a real WebSocket client against the real server).

## Threads

| Thread | Runs | Why |
|---|---|---|
| main | Tauri, the webview, **every AppKit call** (window create / show / occlusion read) | AppKit is main-thread-only — calling window getters elsewhere aborted the app four times |
| `signaling` | current-thread tokio runtime + `LocalSet`: accept loop, one task per connection, the 1 s session sweep | one connection can never block another; no `Send` bounds on the host |
| `video-frames` (per stream) | frame loop: I420 copy, enqueue to the display layer | per-frame work never stalls signaling or audio |
| libwebrtc internal | DataChannel messages → `audio::feed_dc_pcm16`; candidates → signaling via channel | |
| Tauri async runtime | the fallback audio track → `audio::feed_track_pcm16` | |
| CoreAudio callback | `audio::render` — no locks, no allocation | real-time |

## Signaling behavior (pairing.md / webrtc.md)

- 10 s handshake window (upgrade → `auth-ok`); ping every 5 s once authorized, a peer silent > 15 s is closed.
- Failed auth rate-limits the address: 1 s, doubling, capped at 30 s.
- One authorized connection at a time (QR or resume) — another gets `busy`.
- Expiry enforced; an authorized session with its socket attached outlives the TTL (a live cast); a pending QR regenerates when the machine's IPv4 set changes.
- Resume (`hello.resume`, sid = deviceId) verifies against the stored trustKey and invalidates the bypassed QR; `unknown-device` when the phone is not trusted.
- `bye forget` deletes the trust; any `bye` closes both PeerConnections and stages a fresh QR (shown only when no trusted phone remains).
- After `hello`, every envelope's `sid` must match. Envelopes > 256 KiB close the socket.
- `auth-ok {name, proto: 1, desktopId, hosts}` — proto 1 because `vc-*`/`audio-routing-*` are not implemented (they are answered `bad-message`).
- `sdp-answer` is always sent **before** the candidates gathered while answering (a candidate before the remote description cannot be applied).

## Game audio

Two sources, never both: the `game-audio` DataChannel's raw PCM16 (true stereo, primary — `announce` is never acked, so the phone stays on PCM) and the dual-mono Opus track, which plays only before the DataChannel's first chunk or after 3 s of its silence. A source switch flushes the ring.

Playout (the CoreAudio callback owns it):

- starts from a **40 ms** cushion and re-primes after starving (an underrun = audible gap, counted);
- holds the smoothed depth near 40 ms by playing **up to ±1 %** faster/slower (linear resampling) — never by discarding audio; only a backlog over **200 ms** (an outage) is cut back, counted as a `shed`;
- adapts to the device: 48 kHz stereo when supported, else channel mapping (mono mixdown; extra channels silent) and resampling (44.1 kHz, 16/24 kHz HFP).

Why rate control: a 60 ms trim (the first version) discarded the cushion that absorbs SCTP's next delivery stall — 244 trims and 195 underruns in a 274 s PUBG session. With rate control: 1 trim, ~0.24 underruns/s, median playout ~70 ms (the product owner chose fewer dropouts over draining faster to 40 ms).

The 1 Hz `dc:` log line carries `underruns`, `sheds`, `depth` and `side` (side-vs-mid dB of the PCM: ≈ −∞ means the wire carries L = R — a phone-side capture fold, audio.md — not a receiver bug).

## Video

`NativeVideoStream` → `to_i420()` → CPU copy into a pooled NV12 `'420v'` (video-range — libwebrtc's I420 is limited range) CVPixelBuffer → `CMSampleBuffer` (host-clock pts) → `AVSampleBufferDisplayLayer` (`resizeAspect`) in its own NSWindow.

- **Why the copy:** webrtc-sys's zero-copy `get_cv_pixel_buffer` leaks an ObjC wrapper + a native ref per call (every decoded IOSurface pinned — 9 GB in 2 min).
- **Bounded memory:** the pool allocation threshold (8 buffers) drops a frame rather than grow; a layer not ready drops at the live edge (enqueueing anyway reached 20 GB); a hidden window (not composited — each queued sample pins a surface, 8.8 GB in 7 min) drops frames and flushes.
- **Visibility** is read on the main thread every 250 ms and published through an atomic; a window that has never shown a frame is raised once.
- Every layer call runs inside one `objc2::exception::catch`: an exception drops a frame, never the process.
- Closing the window hides it; the next cast shows it again.
- Until the window exists, a ~12 fps JPEG preview goes to the webview's receiving stage.

## Logging

Leveled (`logging.rs`), local time, app scopes `signaling` / `webrtc` / `audio` / `video` / `pairing`; dependencies only at WARN+. Never logged: the session secret, trustKey, MACs, audio samples, frames. The webrtc state lines carry `pc=media|mic`.

## Known limitations

- macOS only (the video module).
- Not yet ported from Electron: microphone playback (the `mic` pc is answered and connected, its track not played), virtual camera and audio routing (proto 2), volume controls, stay-awake, tray.
- ~0.7 % of video frames dropped at the pool bound (open).
- The video abort fix held a 6.4-minute run with the window hidden most of the time (the aborts' trigger); longer sessions are still worth watching.
- A mic pc the phone tears down is noticed only by its ICE failing; it is closed when the next mic offer arrives.
