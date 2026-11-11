# Feature: Mobile Cast Settings

> **Superseded in part (2026-11-11, [ADR-024](../decisions/ADR-024-custom-profiles.md)).** The sections below record Phase10 and the 2026-09/10 redesigns as they were built. What is true today is the last section, *Profiles*: the `Preset | Custom` tabs, the Advanced page, the gear and Bitrate mode are gone; a profile holds every cast value.

Implemented in: **Phase10** (see [roadmap](../development/roadmap.md)). Status: **implemented; mobile JVM 83/83 green, build green; UI + persistence verified on an emulator** — the live-cast acceptance item (settings visibly changing the sent stream in stats, on the real device) needs the phone in hand; see *Verification*.

## What is implemented

The configuration-owner UI ([overview.md](../architecture/overview.md): every cast decision lives on the phone; the desktop exposes none of it — its only controls remain the receiver/environment ones from Phase9).

### The settings UI — the gear's screen

**Redesign 2026-10 (broadcast console, `designs/mobile-app.html`):** the mobile UI is now a *broadcast console* — one amber accent (tally lamps), grouped inset cards with silkscreen caps labels, stroke icons, tabular mono for data. The old #A8C7FA primary was stock Material 3 dark primary (an unmodified default) and was replaced. The home page is the **connected hub**: an on-air hero (tally lamp + caps overline + desktop name, a live elapsed timer while casting — `CastState.Casting.startedAtMillis`), the transport button (**Share screen** amber / **Stop share screen** outlined — stopping leaves the pairing connected), the Audio card (live toggles *and* next-cast defaults), and — while casting — the "This cast" card with LED rows (thermal, adaptive quality, headphone tip). All cast settings live behind the gear (`ui/settings/CastSettingsScreen.kt`; back button and system gesture both return to the hub — `BackHandler`, still state-based navigation, no nav library).

**v3 restructure (same month, user-directed iteration on the prototype):** the state words moved out of the hero into an **app-bar annunciator** (`STANDBY` / `STARTING` / `ON AIR` / `FAULT`, mono caps beside a brand tally chip — ON AIR is the screen's only blinking lamp), and the hero became the destination plate (desktop name + Disconnect only). The profile/share-screen sections and the "This cast" card merged into ONE **quality console** — a tabbed card (`Preset | Custom`) directly under the transport in *both* states: idle it writes the next-cast defaults; casting it steers the **running cast** (live application — see *Live quality* below). Its **STATUS** zone (casting only) carries the readout header — amber timecode (`startedAtMillis`) + shorthand quality (`720p30 · 3–6M`) — over the thermal/adaptive lamps; the headphones tip moved to the Audio card's foot. The gear's screen is **Advanced only** (always expanded, no collapse; the "Automatic quality" master switch is its first block as an On/Off chip pair). The transport→card gap is the prototype's 16/16 rhythm.

- **Share screen** card — profile rows: one-tap presets (`config/QualityProfile.kt`) with radio, thermal intent line, and the preset's real spec in mono (`1280 px · 30 fps · 4–6 Mbps`): `balanced` (1280 px long edge, 30 fps, 4–6 Mbps — the Phase5 values the app shipped with), `sharp` (1920, 30, 8–12), `performance` (1280, 60, 6–10), `cool` (960, 30, 2–3); picking one applies all of its values and resets the Advanced defaults. *(Phase11 renamed `light`→`cool` and `smooth`→`performance` — the presets are the thermal vocabulary now, with an intent line under the row; see [thermal.md](thermal.md). Stored v1 documents migrate through the codec.)* *Resolution* (capture long edge: 960 / 1280 / 1920 px) and *frame rate* (30 / 60 fps) are segmented controls, individually tweakable after a preset — tweaked values select no preset row: the honest "Custom" row appears instead, not a fake match. *Bitrate*: **Automatic** (follows the profile's window; preset picks restore it) or **manual**: a 2–20 Mbps ceiling slider; the window's floor is half of it (named constant `MANUAL_BITRATE_MIN_FRACTION`, no magic numbers).
- **Advanced** card (collapsible; presets reset it) — encoder implementation, preferred codec + H.264 profile, degradation strategy, encoder fps cap, bitrate mode, and the read-only "Encoder in use" readout (what libwebrtc actually picked; never faked).
- **Audio** card on the hub — the defaults for the next cast: "Game audio" (default on) and "Microphone" (default off — privacy: the mic never streams without an explicit choice, [audio.md](../architecture/audio.md)). Both remain live-toggleable during a cast; the mic hint says so.

The panel itself is `ui/settings/CastSettingsPanel.kt` (stateless — settings + callbacks in; the `SettingsViewModel` wiring lives in `CastSettingsScreen`/`HomeScreen`). Every change persists **immediately** (`SettingsViewModel` → `CastSettingsStore`); the fixed sub-line under the header states the contract: **changes take effect on the next cast**. Nothing tries to reconfigure a running session — live application of new capture parameters would need capturer restarts or mid-cast renegotiation, which the roadmap explicitly allows deferring ("or live if cheap" — this wasn't cheap, and mid-cast reconfiguration risk isn't worth it before Phase15's reliability matrix). The desktop stays untouched: no new desktop code, no new messages.

### The model and its one conversion point

- `CastSettings` (`config/CastSettings.kt`) is what the settings UI manipulates and what persists. It carries the chosen preset plus the effective values and a `bitrateAuto` flag (UI state for the auto/manual toggle, not a send target).
- `toConfig()` is the **single** conversion into `CastConfig` — the shape the rest of the app (MediaCastSession, CastService, session-info) already consumed since Phase5. The pipeline didn't change; only where the config's values come from did.
- The display label (the `session-info` the desktop shows as "receiving W×H · fps · profile", [webrtc.md](../architecture/webrtc.md)) is derived, not stored: a config that exactly matches a preset carries that preset's label; any tweak beyond one is `custom` (`profileLabelFor`). A manual window that happens to equal a preset's window is that preset — the label describes the sent stream, not the user's path to it.

### Persistence

`CastSettingsStore` keeps one JSON document in SharedPreferences (`zfc.cast-settings.v1`, versioned), written on every change and read **fresh at cast start** (ScanScreen's consent callback) — that read is the whole "next cast" mechanism. `CastSettingsCodec` is pure Kotlin: round-trip, unknown-keys tolerance (forward compat), and strict validation — corrupt JSON, wrong version, unknown preset, or out-of-choice values decode to null and the store falls back to defaults with a logged warning. Same convention as the desktop mixer's localStorage: never a cast on guessed values.

### Sender-stats: resolution now visible

`SenderStats.sampleVideoSend` extracts `frameWidth`/`frameHeight` (defensive, like every other member) and the ~1 Hz log line now includes them (`800x1280`). That log is the honest place to see a settings change reach the encoder — the acceptance's "visible in stats" — next to the already-logged bitrate.

## Verification (2026-09-20)

**Tests:** mobile JVM **83/83** (21 new: `QualityProfileTest` — preset→label mapping, custom on tweak, audio defaults carried; `CastSettingsCodecTest` — round-trip, unknown-key tolerance, corrupt/version/unknown-preset/out-of-choice/inverted-window → defaults; `SettingsViewModelTest` — preset picks applying whole values and restoring auto bitrate, tweaks going custom, manual clamps + floor derivation, auto restore, immediate persistence; `SenderStatsTest` — width/height extraction + degradation). `assembleDebug` green.

**Emulator (API 34 AVD, uiautomator-driven, both UI iterations):** the restructured home page (header: title + connection state + Start Cast button; content: live-cast section while casting, then Share screen → Audio settings) renders per spec. Driving it: picking **sharp** persisted `profile:sharp / 1920 / 30 / auto 8–12 Mbps` and selected the sharp chip; flipping to **manual bitrate** persisted the derived window (`bitrateAuto:false, 6–12 Mbps` — floor = half the ceiling); the **mic toggle** persisted (`mic:true`); **force-stop + relaunch restored everything on screen** — and after the UI restructure the same store survived an APK reinstall too (screen state matched the stored document exactly both times). One UX note for Phase13: the settings content scrolls — with manual bitrate active, the audio rows sit below the fold on a phone-sized viewport (fine, but worth knowing for polish).

**Remaining acceptance item (real device, live cast — needs the phone in hand):** the phone was PIN-locked during the verification window, so the end-to-end proof is documented, not yet exercised:

1. In settings, pick **light** (or any value visibly distinct from the default; on the 800×1280 test tablet `light` is the one that changes resolution — `sharp`'s 1920 long edge exceeds the physical 1280, so capture stays native, [capture/CaptureSize.kt](../../apps/mobile/app/src/main/java/com/zerofriction/localcast/capture/CaptureSize.kt) never upscales).
2. Scan → cast.
3. Watch the sender stats line (~1 Hz, `MediaCastSession`): resolution drops to `960x600` and the receive bitrate ceilings at ~3 Mbps (vs `800x1280` / ~6 Mbps on the default profile); the desktop status line reads `light`.
4. Settings survive the app being killed (the store is read at cast start, so also visible in a follow-up cast).

## Known limitations

- Changes are next-cast only — nothing applies to a running cast (deliberate; see above).
- The manual bitrate floor is a fixed half of the ceiling; a separately tunable floor would be a second knob with no measured need.
- No resolution above the physical screen is offered as a choice: `CaptureSize` keeps the capture native below the target, so `sharp` on an 800×1280 tablet changes only fps/bitrate there. A per-device filtered choice list would need `DisplaySize` on the home page — deferred until a real device needs it.
- Thermal profiles landed in Phase11 as the preset relabels (`cool`/`performance`), with monitoring added — [thermal.md](thermal.md); the label derivation stayed the display's source of truth, as planned above.


## Redesign: scan-first flow + Advanced encoder settings (2026-09-23)

Supersedes parts of the Phase10 layout above (the settings *content* is unchanged; its home moved). Design source of truth: [designs/mobile-app.html](../../designs/mobile-app.html) — reviewed in the prototype, then ported.

### What changed

- **Scan-first (the app opens on the scanner).** A successful pairing moves to the **Home hub** automatically; the scan screen no longer starts casts. The `Connected` pairing state is transient there: the session is handed to `service/ConnectedDesktop.kt` (app-scoped) and navigation follows.
- **Home is the connected hub**: title + connection status + **Disconnect** (ends the session — back to the scanner); **Share screen** (the cast trigger; reads "Stop share screen" while casting) with the **gear** beside it; the **Audio** rows — *Game audio* and *Microphone*, each one switch. **Stopping the video is not disconnecting**: "Stop share screen" hands the socket back to `ConnectedDesktop` (no `bye`, rescan-free retry within the QR session's TTL; [mobile.md](../architecture/mobile.md) stop policy).
- **One control per audio track — no separate mute buttons** (user decision): only the **microphone** is ever fully removed from a cast (stream torn down, hardware freed for the game's voice chat — ADR-004 semantics); **game audio "off" mutes the track**, which always stays part of the cast (the desktop's mixer row never disappears). Mid-cast toggles act live (`CastService` actions) *and* persist as the next-cast defaults.
- **The gear opens the Cast settings screen** ("all cast settings"): the Phase10 Share screen section plus the new collapsible **Advanced** section.

### Advanced settings (settings document v4)

Encoder-level knobs — every default reproduces today's cast exactly:

| Setting | Values | Default | Wire effect |
|---|---|---|---|
| Encoder | Hardware / Software | Hardware | `webrtc/CastEncoderFactory`: `DefaultVideoEncoderFactory` vs MediaCodec software-only predicate (+libwebrtc software fallback chain) |
| Preferred video codec | H.264 / VP8 | H.264 | Offer payload order (`SdpCodecOrderer`) |
| H.264 profile | Baseline / High | High | `enableH264HighProfile` factory flag (High profile leaves the offer when off) |
| Under network pressure | Balanced / Keep frame rate / Keep resolution | Balanced (per-preset) | `RTCRtpSender.degradationPreference`. The sender's floor is the profile's least for all three; for one day **Keep frame rate** lowered it to 1 Mbps, which let the picture fall to 640×400 in a game cast ([ADR-023](../decisions/ADR-023-keep-frame-rate-follows-the-network.md), revised). |
| Encoder fps limit | Off / 15 / 30 / 60 | Off | `RTCRtpSender.encodings.maxFramerate` |
| Bitrate mode | CBR / VBR | CBR (per-preset) | **Removed 2026-11-11 ([ADR-024](../decisions/ADR-024-custom-profiles.md)).** It was a placebo: libwebrtc hardcodes CBR (verified in the prebuilt's bytecode), and real VBR would burst on busy scenes, against smooth frames and sound. The bitrate follows the network through the profile's window instead ([ADR-023](../decisions/ADR-023-keep-frame-rate-follows-the-network.md)). |

Presets set the Advanced defaults (user decision): Cool → Baseline + VBR + Balanced; Balanced → High + CBR + Balanced; Performance → High + CBR + **Keep frame rate**; Sharp → High + CBR + **Keep resolution** — all Hardware. The preferred codec and auto-quality switch survive preset picks; everything else advanced resets with the preset. A read-only **"Encoder in use"** readout shows the active cast's actual `encoderImplementation` (getStats, ~1 Hz, via `CastService`) — "visible during a cast" when unknown, never a faked value.

### Persistence (v3 → v4)

`CastSettingsCodec` v4 adds the six Advanced fields with today's-behavior defaults, so v1/v2/v3 documents decode unchanged (legacy ladder unchanged); `encodeDefaults = true` keeps the document fully explicit. Unknown Advanced labels or an fps limit outside the choices decode to null → defaults (same never-guess convention). JVM tests cover the migration, round-trips, and validation.

### Verification (2026-09-23)

Mobile JVM **147/147** (codec v4 migration + advanced round-trips/validation; preset→advanced defaults; setters), `assembleDebug` green. Lint reports 4 **pre-existing** errors in `MicRecordSubstituter`, `PlaybackCaptureAudioSource`, `ThermalSource`, and the manifest (`MissingPermission`/`NewApi`/camera-feature) — present before this phase, untouched by it, flagged for their owning phases. **Remaining (needs the device in hand):** the live A/B of Hardware vs Software on the Y700 (the original PUBG contention investigation this UI exists for), and a device walk of the new flow (scan → hub → share/stop/disconnect), including session-expiry falling back to the scanner.


## Profiles (2026-11-11, [ADR-024](../decisions/ADR-024-custom-profiles.md))

Supersedes the `Preset | Custom` console, the Advanced page and the single settings document above. Design: [`designs/mobile-app.figcraft`](../../designs/mobile-app.figcraft), page "Mobile design v2 · custom profiles".

### What the user sees

- **Home's quality console is one list of profiles** (`ui/home/QualityConsoleCard.kt`): the four built-in ones, then the user's own, then *Add profile*.
  - Every row shows the profile's name, a tag saying what it gives up when the cast struggles (KEEP IT SMOOTH / KEEP IT SHARP / BALANCED / DON'T ADJUST), and its real numbers (`1920 px · 60 fps · 6–12 Mbps`). A built-in also keeps its intent line.
  - Only the user's own rows have a `⋯` menu: Edit, Duplicate, Delete. Delete asks first.
  - Idle, a pick selects the profile for the next cast. Casting, a pick steers the running cast to that profile's picture and leaves the stored selection alone; the lit row is the one the running picture matches.
- **Add profile** (`ui/profiles/AddProfileSheet.kt`): a sheet with a name (the next free "Custom N" is suggested), *Start from* (the values on screen, or any profile), and a strip showing what will be copied.
- **Edit profile** (`ui/profiles/ProfileEditScreen.kt`): the name, then four cards.

  | Card | Settings |
  |---|---|
  | Picture | Resolution, frame rate, bitrate range: one slider with two thumbs, the least and the most (1–20 Mbps). The sender never goes under the least, whatever the pressure choice ([ADR-023](../decisions/ADR-023-keep-frame-rate-follows-the-network.md), revised) |
  | Behaviour | When the cast struggles: Keep it smooth / Keep it sharp / Balanced / Don't adjust ([ADR-025](../decisions/ADR-025-the-app-steps-the-picture.md)) |
  | Encoder | Encoder, video codec, H.264 profile, encoder fps limit |
  | Audio | Low-latency game audio (Low latency / Standard) |

  Every change saves at once and applies to the next cast. A name saves as it is typed, as long as it is not empty; typing past 20 characters is ignored.
- **No gear and no Advanced page.** "Encoder in use" is a row in the STATUS zone while casting.
- **Home's Audio card is unchanged**: the two switches and the microphone picker.

### The model

- `config/CastProfile.kt`: `ProfileValues` (what a profile fixes), `CustomProfile` (id, name, values), `ProfileSelection` (a built-in or one of the user's), and the limits (`CustomProfileRules`: 20 characters, 12 profiles, Balanced as the fallback).
- `config/CastSettings.kt` holds the selection, the user's profiles and the audio switches. The cast's values are the selected profile's (`active`); nothing duplicates them. The profile edits (`withProfileAdded`, `withProfileEdited`, …) return the settings unchanged when an edit is not allowed, and `toConfig()` is still the single conversion into `CastConfig`.
- `session-info.profile` carries a built-in's label or the user's profile name.

### Persistence (v6 → v7 → v8)

v8 ([ADR-025](../decisions/ADR-025-the-app-steps-the-picture.md)) replaces a profile's two behaviour fields with one; v7 documents migrate by their pressure strategy. `CastSettingsCodec` v7 stored the selection, the profiles and the audio switches. Every older document migrates: values that equal a built-in profile select it; anything else becomes "Custom 1", selected, with every value kept. `bitrateAuto` and `bitrateMode` are dropped. A document that breaks a rule (two profiles with one id, an empty name, a selection that names no profile, a value this build does not offer) decodes to null and the store falls back to defaults.

### Verification (2026-11-11)

Mobile `testDebugUnitTest`: 442 tests, 0 failures, 2 skipped. New or rewritten: `CastProfilesTest` (what each profile edit does and what the next cast runs on), `CastSettingsCodecTest` (round-trips, validation, and v1–v6 documents written out as those versions stored them), `SettingsViewModelTest`, `QualityConsoleCardUiTest`, `ProfileScreensUiTest`, and the profile screens in `AccessibilityUiTest`.

**Not done:** a walk on the tablet (the migration of its real stored settings, the list, edit, add, delete, a cast on the user's profile).

### Known limitations

- A built-in profile cannot be changed in place; the user copies it first.
- The live dials are gone: during a cast the picture is steered by picking a profile.
- The rule behind "When the cast struggles" ([ADR-025](../decisions/ADR-025-the-app-steps-the-picture.md)) is built and unit-tested, not measured in a cast.

