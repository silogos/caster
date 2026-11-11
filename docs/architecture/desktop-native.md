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
| `syphon.rs` + `native/syphon_bridge.m` | The Syphon output for OBS: a publisher thread that releases each frame at its due time, and the Objective-C bridge that converts the frame's pixel buffer on the GPU and publishes it (ADR-021). | `video`, Metal, Core Image, `Syphon.framework` |
| `pacing.rs` | When each frame shows: a schedule with a bounded 50 ms delay that evens out Wi-Fi delivery jitter (ADR-017). Pure — no I/O. | — |
| `latency_probe.rs` | Measurement only, off unless `ZFC_LATENCY_PROBE=1`: marks when the latency test pattern's flips are decoded and its clicks arrive ([latency-measurement.md](../development/latency-measurement.md)). | — |
| `logging.rs` | `HH:MM:SS.mmm LEVEL [scope] message`; `ZFC_LOG=debug` for more. | stderr |
| `settings.rs` | The receiver's own persisted controls (game volume, mute, the Syphon output) — `native-receiver.json` beside the trust store. | filesystem |
| `tray.rs` | The menu-bar tray (ADR-010): close → hide, status line, Open window / Forget phone / Quit; pure `TrayModel` for the text and armed state. | Tauri tray |
| `power.rs` | Stay-awake: one IOKit `PreventUserIdleDisplaySleep` assertion held while the media pc is connected. | IOKit |

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
- The spare QR session a resume leaves behind expires after its TTL like any pending session. The sweep replaces it, but **never puts a QR on screen while a phone is connected** (fix 2026-11-10: ten minutes into a resumed phone's cast the window flipped from the deck to the QR while the video kept playing).
- `bye forget` deletes the trust; any `bye` closes both PeerConnections and stages a fresh QR (shown only when no trusted phone remains).
- After `hello`, every envelope's `sid` must match. Envelopes > 256 KiB close the socket.
- `auth-ok {name, proto: 1, desktopId, hosts}` — proto 1 because `vc-*`/`audio-routing-*` are not implemented (they are answered `bad-message`).
- `sdp-answer` is always sent **before** the candidates gathered while answering (a candidate before the remote description cannot be applied).

## Game audio

Two sources, never both: the `game-audio` DataChannel's raw PCM16 (true stereo, primary — `announce` is never acked, so the phone stays on PCM) and the dual-mono Opus track, which plays only before the DataChannel's first chunk or after 3 s of its silence. A source switch flushes the ring.

Playout (the CoreAudio callback owns it):

- starts from a **40 ms** cushion, then steers the buffer's **trough** (the least audio left after a callback) to a **15 ms margin** by playing **up to ±1 %** faster/slower (linear resampling) — never by discarding audio; only a backlog over **200 ms** (an outage) is cut back, counted as a `shed` ([ADR-020](../decisions/ADR-020-audio-playout-steers-the-trough.md));
- a starve is a short gap: the last frame decays over 2 ms, playback resumes once 5 ms is back and fades in; only a gap over 50 ms rebuilds the start cushion (an underrun is counted, and so is the silence it played);
- asks the device for a **128-frame IO buffer** (2.7 ms; the default 512 frames added 8 ms), falling back to the device default;
- adapts to the device: 48 kHz stereo when supported, else channel mapping (mono mixdown; extra channels silent) and resampling (44.1 kHz, 16/24 kHz HFP).

Why the trough and not the mean depth: the phone delivers audio in bursts (one capture period, ~21 ms, often two or three together), so the depth is a sawtooth. The mean says nothing about how close the low point comes to empty. With the old 40 ms mean, arriving audio waited ~28–30 ms; the 15 ms margin gives the same wait and as few gaps, and means the same on a phone with a different burst size.

Why rate control: a 60 ms trim (the first version) discarded the cushion that absorbs SCTP's next delivery stall — 244 trims and 195 underruns in a 274 s PUBG session. With rate control: 1 trim, ~0.24 underruns/s, median playout ~70 ms (the product owner chose fewer dropouts over draining faster to 40 ms).

The 1 Hz `dc:` log line carries `underruns` (with the ms of silence played while starved), `sheds`, `depth`, `trough`, `out` (callback frames / IO buffer ms), `gap` (the longest pause between two arriving chunks in that second / how many pauses reached 30 ms — a starve with a long pause came from the network or the phone, not from the playout) and `side` (side-vs-mid dB of the PCM: ≈ −∞ means the wire carries L = R — a phone-side capture fold, audio.md — not a receiver bug).

## Video

`NativeVideoStream` → `to_i420()` → CPU copy into a pooled NV12 `'420v'` or `'420f'` (the decoded range, below) CVPixelBuffer → `CMSampleBuffer` (pts = the pacer's due time on the host clock) → `AVSampleBufferDisplayLayer` (`resizeAspect`, on a host-clock timebase) in its own NSWindow.

**Syphon output (2026-11-10, [ADR-021](../decisions/ADR-021-native-syphon-output.md)).** With "Output to OBS → Syphon" on, the cast is also a Syphon server named "Zero-Friction Local Cast". The frame thread hands the NV12 buffer it made for the window to `syphon.rs` together with the pacer's due time; a publisher thread releases it then, and the bridge converts it to a BGRA texture on the GPU, bottom row first (Syphon's order; OBS shows it upside down otherwise). Frames are taken only while a client is connected, and then also while the video window is closed or covered. Between casts the server shows a "No input video" still. The `syphon` log scope reports clients coming and going, frames published and dropped, and the average publish time.

**Frame pacing (2026-11-09, [ADR-017](../decisions/ADR-017-receiver-frame-pacing.md)).** libwebrtc runs with zero playout delay (ADR-015), so frames leave the decoder as unevenly as Wi-Fi delivered them. During PUBG at 60 fps that meant bursts 2–5 ms apart, then 50–160 ms gaps, while the phone sent evenly. Shown on arrival, that was the stutter. `pacing.rs` gives each frame a slot one estimated frame interval after the previous one: never before the frame arrives, never more than 50 ms after it. The schedule drains 0.5 % faster than the stream, so it returns to the live edge when Wi-Fi is calm. The layer's timebase is the host clock, so the pts is the moment of display. libwebrtc's own jitter buffer was smoother, but its delay was felt; our cap keeps the delay at ~30 ms on average. Gaps longer than 50 ms still show. The `video:` log line reports `pacing delay avg/max`, `late` and `capped`.

**Colour range, per stream (2026-11-09).** The display layer takes the YCbCr range from the pool's pixel format, and a wrong guess is visible. Full-range data read as video range crushes dark tones to black and clips highlights: the phone app's `#161b22` background showed as near black, with a measured luma of 27 where video range would be 39. Video-range data read as full range turns blacks grey. Neither range can be assumed:
- VideoToolbox's H.264 decoder writes its own CVPixelBuffer format. On the TB321FU stream that format is full range.
- libwebrtc's software decoders emit the stream's nominal video range.

So the decoder buffer's pixel format is read before the I420 copy (`'420f'` and the other full-range formats → full), and the pool and format description are keyed by range.

**That read is rationed (fix 2026-11-10).** `read_range_of_frame` goes through webrtc-sys' `get_cv_pixel_buffer`, which leaves a reference on the decoded surface on every call. Called per frame, as the first version did, it pinned every decoded frame: 3.5 MB each at 1920×1200, about 180 MB a second in `VTDecoderXPCService`, until macOS ran out of memory after ~16 500 shown frames (57 GB) and killed the receiver. `RangeTracker` reads the range once per stream and once more when the dimensions change (a new encoder session), and remembers it; a software-decoded frame is answered without a read. Measured after the fix: the decoder service stays at 42 MB over a minute of shown frames. Limitation: a software-decoded frame carries no range the Rust API exposes, so it is taken as video range.

- **Why the copy:** webrtc-sys's zero-copy `get_cv_pixel_buffer` leaks an ObjC wrapper + a native ref per call (every decoded IOSurface pinned — 9 GB in 2 min).
- **Bounded memory:** the pool allocation threshold (10 buffers — paced samples wait in the layer) drops a frame rather than grow; a layer not ready drops at the live edge (enqueueing anyway reached 20 GB); a hidden window (not composited — each queued sample pins a surface, 8.8 GB in 7 min) drops frames and flushes.
- **Visibility** is read on the main thread every 250 ms and published through an atomic; a window that has never shown a frame is raised once.
- Every layer call runs inside one `objc2::exception::catch`: an exception drops a frame, never the process.
- **Frameless** (the Electron receiver's frameless spec): no title bar, no traffic lights, square corners — a pure video surface for OBS. Drag the video to move it, drag an edge to resize; it is closed from the deck's *Close video* or with Cmd+W.
- **Still an ordinary window of the app** (fix 2026-11-10): a window without a title bar refuses to become main or key by default, and AppKit then leaves it out of the Dock icon's window list and the Window menu and will not minimize it. The video window is a small `NSWindow` subclass (`ZFVideoWindow`) that accepts both, so it is listed in the Dock and the Window menu, takes Cmd+M (minimize to the Dock) and Cmd+W, and still draws no frame. Minimized counts as open: the sound keeps playing and nothing is composited, like a covered window.
- The window opens on **Open video** (never by itself); *Close video* hides it, and its state survives casts.
- **No input = a card, like a capture card with nothing plugged in:** whenever no stream is live (before the cast, the phone reconnecting, the cast stopped) the window shows a *NO INPUT* card (Signal Deck graphite, grey lamp, "Start casting from your phone") — a Core Animation sublayer over the display layer, toggled on stream start/end on the main thread, and the last frame is dropped (`flushAndRemoveImage`). OBS therefore records the card, never a frozen frame.

## The main window: QR hero → the deck

The main window looks like the phone app (the *Signal Deck* tokens, `designs/preview/05-tokens.png`): a QR hero while nothing is paired, then **the deck** once a phone is — its name, a STANDBY / ON AIR / RECONNECTING annunciator, the cast's status (timecode, the phone's own `session-info` readout, game-audio/mic chips) and **Open video**. Only receiver/environment controls live here (*mobile controls, desktop receives*):

- **Open video / Close video** — the native video window. **Video and sound play only while it is open** (product choice, 2026-11-09): the phone keeps sending, the decoder stays warm, so opening is instant; while closed no frame is converted or rendered and the ear output's gain is 0 (the ring keeps draining — opening never replays a backlog). Closing the window with its own button is noticed on the main thread and flips the UI and the sound the same way. The window stays open across casts until the user closes it. Cost, accepted for now: the phone encodes and the Mac decodes the full stream while nobody watches — an "idle mode" (ask the phone to pause/lower video while the window is closed) needs a protocol bump and is a candidate follow-up.
- **Game audio** on/off and volume (0–100 %, linear like the Electron mixer), applied as the playout callback's gain, persisted on release.
- **Disconnect** — the desktop's `bye forget` (pairing.md §bye forget): a two-step link (*Disconnect → Forget this phone?*) because it revokes the persistent pairing; works with the phone offline too. The UI returns to a fresh QR.
- **New QR code** (QR hero) — regenerates the pending session; ignored while a phone is connected.

UI → server requests travel a `ServerCommand` channel into the signaling thread; a connection that ends with a `bye` (either side) records it, so a forget leaves the QR on screen instead of flipping to "waiting to reconnect".

**Settings on the pill, the live picture in the readout (2026-11-11).** The deck shows the cast twice, on purpose. The pill (`Casting · 1920×1200 · 60 fps · <profile>`) and the tray's status line are the cast as the phone set it: its `session-info`. The readout beside the timecode (`640×400 · 59 fps`) is the picture that is arriving, measured once a second in the frame loop (`webrtc.rs` → `cast:picture`). The two differ whenever the sender scales the frame down or drops frames under pressure: 640×400 arrived under a status that read 1920×1200 · 60 fps. Until the first measured second the readout shows the settings. A measured second can read 61–63 fps: frames do not arrive evenly, the average is the sender's. Display only: the receiver still sets nothing about the cast.

**Tray (ADR-010, the Electron lifecycle):** closing the main window **hides** it — and the Dock icon (activation policy *Accessory*), unless the video window is open: the Dock icon stays while either window is open — while the signaling server keeps listening and a live cast keeps running; a phone connecting or the tray's *Open window* bring it back, and so does a Dock click while the video window is closed. While the video window is open, a Dock click goes to the video window instead (out of the Dock if it was minimized, and to the front): the picture is what the user is after, and raising the main window over a playing cast was the wrong answer (found live 2026-11-10). The tray menu: a status line in the Electron vocabulary (`Waiting…` / `Connected to <phone> — <cast summary>` / `Waiting for <phone> to reconnect…`), *Open window*, *Forget phone* (armed only while a phone is trusted — `bye forget`, same path as Disconnect), *Quit*. Quit is the only exit — tray, Cmd+Q and the app menu all send a plain `bye desktop-quit` first (the trust survives a quit, ADR-006), wait 300 ms for it to leave the socket, then exit. Not ported: *Launch at login* (needs a login-item plugin) and the virtual-camera checkbox (no virtual camera yet).

**Stay-awake:** while the media pc is connected an IOKit assertion prevents display sleep (Electron's `powerSaveBlocker('prevent-display-sleep')`, Phase 14) — check with `pmset -g assertions` during a cast.

## Logging

Leveled (`logging.rs`), local time, app scopes `signaling` / `webrtc` / `audio` / `video` / `pairing`; dependencies only at WARN+. Never logged: the session secret, trustKey, MACs, audio samples, frames. The webrtc state lines carry `pc=media|mic`.

## Known limitations

- macOS only (the video module).
- Not yet ported from Electron: microphone playback (the `mic` pc is answered and connected, its track not played), audio routing (proto 2), launch at login, frameless window / session-info window.
- The virtual camera is Syphon only (OBS on the same Mac) and is switched on the receiver; the phone's proto-2 `vc-*` messages are not answered ([ADR-021](../decisions/ADR-021-native-syphon-output.md)).
- ~0.7 % of video frames dropped at the pool bound (open).
- The video abort fix held a 6.4-minute run with the window hidden most of the time (the aborts' trigger); longer sessions are still worth watching.
- A mic pc the phone tears down is noticed only by its ICE failing; it is closed when the next mic offer arrives.
