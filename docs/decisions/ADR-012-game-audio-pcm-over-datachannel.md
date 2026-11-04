# ADR-012: Game audio over a DataChannel (stereo PCM bypass of libwebrtc's audio encoder)

- **Status:** accepted (2026-11-04) — amends the transport of factory A's game audio from [ADR-003](ADR-003-two-audio-track-architecture.md); the two-PC architecture and the mic's transport are unchanged

## Context

True-stereo game audio is the product's PUBG requirement ([issue #101](https://github.com/silogos/caster/issues/101)). As of 2026-11-04 both halves of the stereo path are measured end to end:

- **Capture is healthy.** The pre-opened capture record ([audio.md](../architecture/audio.md), ADR-003 addenda) delivers true 48 kHz stereo PCM — proven by encoder-input WAV dumps and by a 4-minute PUBG session with zero one-channel windows.
- **The encoder is not.** The desktop receives `channelCount=2` with `left === right` identical: libwebrtc **downmixes the ADM's stereo input to mono before the Opus encoder**, and the stereo decoder duplicates it.

Every app-facing knob was exhausted: SDP `stereo=1;sprop-stereo=1;maxaveragebitrate=192000` on offer **and** answer, all APM constraints off, hardware AEC/NS off, `setUseStereoInput(true)`, and the `WebRTC-Audio-MultiChannelSend` field trial — which **does not exist** in the pinned native build (`strings libjingle_peerconnection_so.so` across `io.getstream:stream-webrtc-android` 1.3.8, 1.3.9, and 1.3.10). 1.3.8 is the newest release; Stream's own video SDK (source, `develop`) contains no stereo-send configuration either — its HiFi mode changes capture sources and speaker usage, not multichannel send. The downmix is therefore a hard constraint of every obtainable prebuilt, unfixable from application configuration.

## Decision

Game audio rides a **WebRTC DataChannel as raw PCM, bypassing libwebrtc's audio encoder**:

- **Mobile:** a DataChannel (`label = "game-audio"`, `ordered = false`, `maxRetransmits = 0`) opened on the existing `media` PC before `createOffer` (in-band negotiated — the desktop learns it via `ondatachannel`). The capture source feeds 20 ms chunks (3840 B: 48 kHz × 2 ch × PCM16) from the ADM samples callback through a small bounded queue to a sender thread; on overflow or `bufferedAmount` past a threshold, chunks are **dropped** (never queued unbounded — a late chunk is worse than a lost one). A muted cast sends silence, keeping the flow identical.
- **Desktop:** the channel's Int16LE frames convert to Float32 into a ring buffer inside an **AudioWorklet**, which renders into the mixer's existing game-audio gain node — so volume, mute, and virtual-cable routing keep working unchanged. On underrun the worklet plays silence. If no PCM arrives for a grace period (older mobile build), the mixer falls back to the libwebrtc audio track (dual-mono), so the feature degrades rather than silences.
- **Mic is untouched:** factory B's libwebrtc track stays the mic's transport (mono is correct for voice, and the APM belongs there).
- The libwebrtc game-audio track **stays negotiated** (it carries the fallback and keeps the SDP shape stable); the desktop simply does not render it while PCM data flows.

## Considered alternatives

| Option | Verdict |
|---|---|
| Newer `stream-webrtc-android` | **Rejected** — 1.3.9/1.3.10 native libs lack the trial too; 1.3.8 is the newest published |
| Patched/rebuilt libwebrtc | **Rejected** — a permanent native build system for one flag (the exact cost ADR-003's deferred option avoided) |
| Opus via MediaCodec + WebCodecs decode | **Rejected for now** — `audio/opus` encoder availability varies by API level, manual Opus framing; raw PCM needs neither and LAN bandwidth absorbs it |
| Stereo as two mono libwebrtc tracks | **Not possible** — one ADM per factory; splitting one stereo record into two libwebrtc sources has no public API |
| Fix on the desktop | **Impossible** — the received stream carries no stereo information (L is a copy of R); the fold precedes transmission |

## Consequences

- **Positive:** true end-to-end stereo with only public APIs; the desktop-side pipeline (gain, mute, cable routing, probes) keeps its shape; degradation to the old dual-mono path is automatic.
- **Costs / accepted:** +1.5 Mbps steady on the LAN (≈ 60% of a typical video bitrate — negligible for Wi-Fi 5+); ~30–60 ms added jitter depth (audio-only tolerance is high); a new transport surface (webrtc.md's channel registry gets a versioned entry); chunk loss under Wi-Fi stress manifests as dropped 20 ms slivers instead of a stalled stream — the right trade for positional audio.
- **Revisit if:** a prebuilt with the `WebRTC-Audio-MultiChannelSend` trial ships — the bypass can retire back to a single stereo Opus track.
