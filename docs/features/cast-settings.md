# Feature: Mobile Cast Settings

Implemented in: **Phase10** (see [roadmap](../development/roadmap.md)). Status: **implemented; mobile JVM 83/83 green, build green; UI + persistence verified on an emulator** — the live-cast acceptance item (settings visibly changing the sent stream in stats, on the real device) needs the phone in hand; see *Verification*.

## What is implemented

The configuration-owner UI ([overview.md](../architecture/overview.md): every cast decision lives on the phone; the desktop exposes none of it — its only controls remain the receiver/environment ones from Phase9).

### The settings UI — the home page's content

The home page is the settings hub (a Phase10 review-time restructure; there is no separate settings screen): a **header** carries the connection state plus the cast button (Start Cast / Stop casting, spinner while starting, the simple failure message when failed), and the page's **content** is the settings themselves. While a cast runs, a "This cast" section appears first with the live controls (game-audio mute, mic on/off — unchanged from Phases 7/8); below it, always, the settings for the *next* cast:

- **Share screen** section — *Profile*: one-tap presets (`config/QualityProfile.kt`): `balanced` (1280 px long edge, 30 fps, 4–6 Mbps — the Phase5 values the app shipped with), `sharp` (1920, 30, 8–12), `performance` (1280, 60, 6–10), `cool` (960, 30, 2–3); picking one applies all of its values. *(Phase11 renamed `light`→`cool` and `smooth`→`performance` — the presets are the thermal vocabulary now, with an intent line under the chips; see [thermal.md](thermal.md). Stored v1 documents migrate through the codec.)* *Resolution* (capture long edge: 960 / 1280 / 1920 px) and *frame rate* (30 / 60 fps) are individually tweakable after a preset — tweaked values select no preset chip: the honest "custom" state, not a fake match. *Bitrate*: **Automatic** (follows the profile's window; preset picks restore it) or **manual**: a 2–20 Mbps ceiling slider; the window's floor is half of it (named constant `MANUAL_BITRATE_MIN_FRACTION`, no magic numbers).
- **Audio** section — the defaults for the next cast: "Include game audio" (default on) and "Start with microphone on" (default off — privacy: the mic never streams without an explicit choice, [audio.md](../architecture/audio.md)). Both remain live-toggleable during a cast (the "This cast" section above); the mic hint says so.

The panel itself is `ui/settings/CastSettingsPanel.kt` (stateless — settings + callbacks in; the `SettingsViewModel` wiring lives in `HomeScreen`). Every change persists **immediately** (`SettingsViewModel` → `CastSettingsStore`); the fixed sub-line under the header states the contract: **changes take effect on the next cast**. Nothing tries to reconfigure a running session — live application of new capture parameters would need capturer restarts or mid-cast renegotiation, which the roadmap explicitly allows deferring ("or live if cheap" — this wasn't cheap, and mid-cast reconfiguration risk isn't worth it before Phase15's reliability matrix). The desktop stays untouched: no new desktop code, no new messages.

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
| Under network pressure | Balanced / Keep frame rate / Keep resolution | Balanced (per-preset) | `RTCRtpSender.degradationPreference` |
| Encoder fps limit | Off / 15 / 30 / 60 | Off | `RTCRtpSender.encodings.maxFramerate` |
| Bitrate mode | CBR / VBR | CBR (per-preset) | **Placebo until Phase "forked encoder"**: libwebrtc hardcodes CBR (verified in the prebuilt's bytecode); the setting persists and maps into `CastConfig`, but VBR needs the copied `HardwareVideoEncoder` source to reach the MediaFormat. The UI states CBR as today's behavior. |

Presets set the Advanced defaults (user decision): Cool → Baseline + VBR + Balanced; Balanced → High + CBR + Balanced; Performance → High + CBR + **Keep frame rate**; Sharp → High + CBR + **Keep resolution** — all Hardware. The preferred codec and auto-quality switch survive preset picks; everything else advanced resets with the preset. A read-only **"Encoder in use"** readout shows the active cast's actual `encoderImplementation` (getStats, ~1 Hz, via `CastService`) — "visible during a cast" when unknown, never a faked value.

### Persistence (v3 → v4)

`CastSettingsCodec` v4 adds the six Advanced fields with today's-behavior defaults, so v1/v2/v3 documents decode unchanged (legacy ladder unchanged); `encodeDefaults = true` keeps the document fully explicit. Unknown Advanced labels or an fps limit outside the choices decode to null → defaults (same never-guess convention). JVM tests cover the migration, round-trips, and validation.

### Verification (2026-09-23)

Mobile JVM **147/147** (codec v4 migration + advanced round-trips/validation; preset→advanced defaults; setters), `assembleDebug` green. Lint reports 4 **pre-existing** errors in `MicRecordSubstituter`, `PlaybackCaptureAudioSource`, `ThermalSource`, and the manifest (`MissingPermission`/`NewApi`/camera-feature) — present before this phase, untouched by it, flagged for their owning phases. **Remaining (needs the device in hand):** the live A/B of Hardware vs Software on the Y700 (the original PUBG contention investigation this UI exists for), and a device walk of the new flow (scan → hub → share/stop/disconnect), including session-expiry falling back to the scanner.
