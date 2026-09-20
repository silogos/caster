# Feature: Desktop Audio Mixer

Implemented in: **Phase9** (see [roadmap](../development/roadmap.md)). Status: **implemented; desktop 67/67 green; UI + persistence verified live in the running app (2026-09-20)** — the audible acceptance items (independent volumes heard, no clipping, long-session drift) need a live cast with the phone and the user's ears; see *Verification*.

## What is implemented

### The graph (audio.md, unchanged in shape since Phase0)

Each remote stream becomes a `MediaStreamAudioSourceNode` → its **own** `GainNode` → the one `AudioContext.destination`:

```text
"media" pc ─▶ MediaStreamAudioSourceNode ─▶ GainNode (game) ─┐
                                                              ├─▶ destination
"mic"   pc ─▶ MediaStreamAudioSourceNode ─▶ GainNode (mic)  ─┘
```

`AudioMixer` (`apps/desktop/src/renderer/src/audio/mixer.ts`) owns exactly this and nothing else — no EQ, no compression, no receiver-side AEC ([audio.md](../architecture/audio.md): no processing unless a measured need appears). The two channels are `game` and `mic`; turning one down or muting it never touches the other (ADR-003's end-to-end separation, now completed at the receiver).

### How streams reach the graph (the changes Phase9 made to Phase7/8 plumbing)

- **The `<video>` element is now muted** — deliberately, reversing Phase7's "NOT muted" note. Since Phase7 the element played the phone's game audio through its speakers; now that audio is routed through the mixer instead, and an unmuted element would play the game audio **twice** (once through the graph, once through the element). The element renders video only. The muted element also satisfies autoplay under any gesture policy.
- **A muted `<audio>` element still holds the mic stream** — as a *keep-alive*, not a playback path (found live during the Phase9 listen: with the window minimized, the mic went silent while game audio kept playing — Chromium stops pulling a `MediaStream` that no media element holds once the page is hidden; the game stream was safe because the `<video>` holds it). The element is muted so it adds no second playback; the audible path is the mixer's Web Audio graph. This closes the Phase8 TODO of "separate elements so the streams stay independent": the graph is the independence now.
- The `ReceiverSession` sink interfaces are unchanged (`show`/`clear`) — the sinks' *implementations* changed from elements to mixer channels.

### Levels and persistence

- `volume`: linear gain 0–1 per channel, default **1 = unity** — "no distortion at sensible defaults" means the mixer is transparent at defaults; a loud cast sounds exactly as it did before Phase9.
- `muted`: silences the channel (gain 0) **without** losing the stored volume — unmute restores it.
- Persistence is the renderer's `localStorage` (key `zfc.audio-mixer.v1`): restored on launch, written on every change. localStorage — not an IPC round-trip to a main-process file — because the data is per-receiver-machine trivia that never crosses the IPC boundary the architecture keeps thin ([desktop.md](../architecture/desktop.md) process model); corrupt or out-of-schema storage is a logged warning and fresh defaults, never a crash.
- Values are clamped (input and restore paths); the storage schema is versioned (`v: 1`).

### Behavior details

- The `AudioContext` is **created lazily on the first attached stream** — no audio thread until audio actually arrives. If it starts suspended (Chromium autoplay policy; Electron's default is no-user-gesture-required) `resume()` is called, re-checked on every attach so a failed resume gets another chance with the next stream.
- A failed context creation **degrades to silent audio, never a crash** — video keeps rendering (same defensive philosophy as Phase7's ADM substitution).
- A rebuilt pc produces a new `MediaStream`; re-attaching a channel disconnects the previous source first and the channel's gain node (and its level) is reused. Detaching a stream disconnects the source; the level survives for the next stream.
- Mute is instant: gain is a plain `AudioParam.value` assignment, no ramps (no processing, per the spec).

### UI

The mixer lives in the receiver's **settings modal** (review-time restructure after Phase9: the video fills the whole window; hovering reveals a small overlay with the status line and a *Settings* trigger; the trigger opens the modal). It is the receiver's one allowed audio control (receiver/environment control, [overview.md](../architecture/overview.md) — never a cast setting). One row per channel: label, 0–100 % slider, mute/unmute button (`aria-pressed`); a note under them states that cast quality is set on the phone. The modal closes via its button, Escape, or a backdrop click. Slider values initialize from the persisted levels; every change goes through the mixer (which persists it), so the panel and the graph cannot drift apart.

## Verification (2026-09-20)

**Tests:** desktop typecheck green; vitest **67/67** (14 new, `mixer.test.ts`: graph wiring per channel — source → own gain → destination, independent volume/mute, mute preserving volume, lazy context creation, suspended-context resume + reuse, failed resume logged, failed context creation degrading gracefully, source replacement, detach keeping the gain, persistence round-trip relaunch, corrupt/out-of-range storage). Production build green.

**Live in the running production build (CDP):**

- Initial state: mixer panel present and hidden while waiting for a phone, both sliders at 100 (unity defaults), mute buttons unmuted, `<video>` muted, the Phase8 `<audio>` element gone, nothing stored.
- Driving the real controls (DOM events): game slider → 40 % stored `{"v":1,"game":{"volume":0.4,…}}`; mic mute click → button flips to "Unmute", stored `mic.muted: true` — the two channels' state changed independently.
- **Persistence across launches:** app killed and relaunched — game slider restored to 40, mic button restored to "Unmute" from `localStorage` (stored levels cleared afterwards).
- **Found live during the user's listen and fixed:** with the window minimized, the mic went silent while game audio kept playing — Chromium stops pulling a `MediaStream` with no attached media element on a hidden page. Fix: the muted keep-alive `<audio>` element (above). The mic's audibility across a minimize is part of the remaining listen.

**Remaining acceptance items (live cast with the phone + the user's ears):**

- *Independent volume/mute heard*: game audio and mic audible simultaneously, each slider audibly moving only its own stream, each mute silencing only its own stream — the graph wiring is unit-verified, but the acceptance is written to be heard.
- *No distortion/clipping at sensible defaults*: unity gain should be bit-identical to Phase8's element playback; the listen confirms it.
- *Drift check over a long session* ([audio.md](../architecture/audio.md) predicts no drift-accumulation problem — per-stream pull, Chromium resampling into the output clock): a 30+ min session with both streams while watching/leveling (Phase15's matrix is the formal record; Phase9's is the first pass).

Phase8's pending audible mic listen now doubles as this phase's listen: the mic stream that was live-but-silent in the quiet room will be routed through the mic gain node from the start.
