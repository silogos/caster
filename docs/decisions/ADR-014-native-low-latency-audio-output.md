# ADR-014: Native low-latency audio output — and the native-receiver roadmap (Fases A/B/C)

- **Status:** accepted (2026-11-08) — Fase A shipped (the audio engine sidecar + the bounded PCM ring); Fase B (native video) and Fase C (shell decision) are scoped here but deliberately deferred. Amends nothing in ADR-012/013 — the wire and the codecs stand; this ADR owns the RECEIVER's playback path and the native-receiver roadmap.

## Context

The streamer's ear path measured (PR #115 telemetry + Phase16 rig): capture ~10 ms + transport ~5 ms + worklet ring 40–75 ms + **Chromium output chain 33 ms** (`ctxBaseLatencyMs` 5 + `ctxOutputLatencyMs` 28) ≈ **80–110 ms** end to end. The user set an explicit target: **≤45 ms** — competitive-game grade (the phone's own wired audio is ~10 ms; scrcpy measures 35–70 ms).

Two terms dominate, and neither is reachable in Chromium:
- the ring's depth is a *policy* (ours to bound);
- the 33 ms output chain is Chromium's buffer choice — `latencyHint: 'interactive'` is already the floor and no public API shrinks it (the `jitterBufferTarget` Phase16 experiment proved floor semantics; the same wall holds on the output side).

The user explicitly explored and the alternatives were assessed and deferred (each with its reason below): a Tauri/desktop-v2 rewrite, native receivers via str0m/webrtc-rs, and alternative transports (raw UDP/QUIC). Production validation for the chosen design comes from the TikTok teardown (`Projects/tiktok-cast-teardown/audio-importable.md`): the production cast expresses audio buffering **in milliseconds with a drop-late-never-stall contract** (`setAudioMaxDelayByMs` / `setAudioDropPolicyByMs`) and ships an explicit `AVSYNC_TYPE.SYNC_LOW_LATENCY` mode on both ends — the exact contract this ADR adopts (their numeric defaults live in `libvoip.so` and were not extracted).

## Decision

### The native-receiver roadmap (three phases, each shippable)

- **Fase A (this ADR's shipped scope): the audio engine.** A small Rust sidecar (`apps/desktop/audio-engine/`, `caster-audio-engine`) owns the real-time audio output: stdin (raw PCM16LE 48 kHz stereo after a one-line format header) → hand-rolled SPSC ring (`AtomicI16` slots — safe Rust, lock-free, no allocation on the real-time path) → CPAL stream to the default output. Underruns play silence and are counted; a backlog above the shed trigger sheds the oldest samples down to the keep level (drop late, never stall). Tuned from the first live cast (2026-11-08 — 20 ms keep/30 ms trigger crackled on Wi-Fi chunk bursts): **keep 40 ms, shed trigger 60 ms**, capacity 100 ms. The worklet ring gains the same contract as the fallback (`targetMs` port message, `EAR_PLAYOUT_TARGET_MS = 20`). Electron main spawns the binary, forwards the renderer's chunks, parses depth, and pushes state; a missing binary or a dead engine hands the ear path back to the worklet with no crash and no double audio.
- **Fase B (deferred, the big one): native video.** str0m/webrtc-rs transport + an owned jitter buffer (10–30 ms — attacking the measured 91 ms Chromium wall) + VideoToolbox decode + a native window (winit+wgpu, the OBS model: GPU surface, no HTML in the video path) + explicit lip-sync (audio clock master). Gated on the T8 glass-to-glass measurement showing the video latency is a real user-visible problem.
- **Fase C (deferred, cheap by then): the shell decision.** When video is native, the webview's remaining job is settings/QR/session-info — replacing Electron (Tauri / GPUI / staying) becomes a small decision, and the existing vanilla-TS frontend ports as-is. Today's desktop UI is already framework-free vanilla TS.

### Fase A specifics

- **Activation:** the mobile's **"Low-latency game audio"** toggle (PR #115) is the master switch — the `pcm16` format announce *is* the low-latency intent. `pcm16` → the engine path (binary present) or the bounded worklet (fallback); `aac` → today's worklet play-on-arrival. No desktop UI: turning the toggle off returns everything to the known-good path.
- **Volume/mute** stay the mixer's levels (one truth): the renderer scales the PCM16 chunk before it leaves (`scalePcm16`), the engine writes verbatim.
- **The 3 s quiet fallback survives the engine path** — the renderer stamps liveness (`keepAlive`) on every forwarded chunk, so the dual-mono track fallback only fires when the channel truly dies.
- **Structure:** the engines are separate crates spawned by the existing desktop (Fase B adds `video-engine` the same way); a new app / shell replacement is a Fase C decision precisely because the desktop's non-media surface (pairing/signaling, tray, session-info, OBS window hardening, ADR-008 routing, virtual camera, 314+ tests) is months of verified work with zero latency involvement.
- **Constants are named and in the open:** `EAR_PLAYOUT_TARGET_MS = 20`, ring capacity 100 ms, shed trigger 30 ms / keep 20 ms — unlike the reference's native-hidden defaults.

## Considered alternatives

| Option | Verdict |
|---|---|
| Shrink Chromium's output buffer (`latencyHint`, flags) | **Rejected** — `interactive` is already the floor; no public API below it (the measured 28 ms stands) |
| Raise the mobile's capture rate to 5 ms chunks | **Deferred** — the substituted record mirrors the ADM's 10 ms frame clock; touching it risks the pinned libwebrtc audio pipeline for ~5 ms. Revisit only if Fase A+B leaves the budget short |
| **Migrate to Tauri/desktop-v2 now** | **Rejected** — WKWebView has no `AudioContext.setSinkId` (breaks ADR-008's per-track routing), WebKit's media stack re-zeros months of receiver verification, and the latency gain is **zero** (the lever is the engine, which runs identically in Electron). Re-evaluated at Fase C when the webview is a shell |
| **Native receiver now (str0m / webrtc-rs)** | **Deferred** — both are transport-only (no decode, jitter buffer, playout clock, lip-sync — all hand-built, months); it attacks the *video* 91 ms wall, not the ear path; gated on T8. Both stacks are the candidates to evaluate inside that future ADR |
| **Alternative transport (raw UDP / QUIC datagrams)** | **Rejected for now** — the transport is 3–6 ms of the 80–110 ms budget; a new protocol saves ≤4 ms and rewrites connection/pairing/encryption. The sidecar is protocol-agnostic |
| **Build the new app first, engines later** | **Rejected** — without Fase A there is no audio for months; the Fase A engine *is* the native receiver's audio component |

## Consequences

- **Positive:** the ear path budget drops to ~30–40 ms measured-side (capture 10 + transport ~5 + engine ring ≤20 + engine output ~8–10); the engine is a standalone crate that Fase B composes; every latency term is now instrumented (`engineRingDepthMs`/`engineUnderruns` joined into the `gameChannel` sample).
- **Costs / accepted:** a Rust toolchain becomes a desktop dev prerequisite (`setup.md`); the engine binary is dev-built (`audio-engine/target/release/`, gitignored) — packaging via electron-builder extraResources is a Fase A follow-up before any non-dev distribution; a bounded ring on real Wi-Fi may crackle under burst loss — accepted for the opt-in competitive mode (the toggle returns the safe path instantly); two stacks exist during A/B (JS UI + Rust media, strictly partitioned: Electron never sits in the audio critical path — the engine is its own real-time process).
- **Revisit if:** Windows becomes a primary rig (CPAL/WASAPI + the whole Fase B render stack differ there); T8 shows the video 91 ms wall matters to users (→ Fase B ADR: str0m vs webrtc-rs evaluation with an interop matrix against the pinned libwebrtc 1.3.8 sender); the bounded ring's shed policy shows audible over-shedding (tune trigger/keep from `engineUnderruns` telemetry).
