# Feature: Thermal Profiles

Implemented in: **Phase11** (see [roadmap](../development/roadmap.md)). Status: **implemented; mobile JVM 94/94 green, `assembleDebug` green** — the on-device acceptance items (profile switches visible in the sender-stats line, a live thermal-ladder read during a warm cast) need the phone in hand; see *Verification*.

## What is implemented

### The thermal vocabulary became the app's profiles

thermal.md's three profiles (Cool · Balanced · Performance) are the settings screen's one-tap presets — Phase10's `light` and `smooth` were relabeled `cool` and `performance`, the values unchanged ([config/QualityProfile.kt](../../apps/mobile/app/src/main/java/com/zerofriction/localcast/config/QualityProfile.kt)). Nothing new to configure: the profile → sender-parameters chain is the one Phase10 shipped — picking a preset applies its capture long edge, fps and bitrate window (`SettingsViewModel`), the store is read fresh at cast start, and the values reach the encoder as the capture size + fps at `startCapture` and the bitrate window on the video sender (`MediaCastSession.applySenderParameters`). A distinctness test pins the acceptance "profiles switch measurably distinct encoder configs": no two of cool/balanced/performance share a whole encoder target, and the profile chips' intent line states each one's thermal cost (the settings row is the user's thermal decision).

Sharp stays as the fourth preset — fidelity over thermals, not part of the thermal ladder; the profile label derivation (`profileLabelFor`) remains the display's source of truth (`custom` on any tweak), so the desktop's status line just shows the new labels (`cool`, `performance`) with no desktop change.

### Thermal monitoring — read-only

New module `thermal/` ([mobile.md](../architecture/mobile.md) module map):

- **`ThermalMonitor`** — pure ladder state machine (no Android types, JVM-testable, GameAudioMonitor's discipline). It publishes `ThermalState` (ladder status, headroom, battery temperature) and fires one transition callback per real ladder change; repeats and unknown platform values are ignored. Read-only by construction — nothing consumes it that can change cast parameters.
- **`ThermalSource`** — the Android side, owned by `CastService` for exactly the cast's lifetime: the `PowerManager` ladder listener (API29, the minSdk floor; executor overload on API30+), `getThermalHeadroom(30 s)` where the platform has it (API30+), battery temperature from the sticky `ACTION_BATTERY_CHANGED` broadcast, sampled every 10 s (`SAMPLE_INTERVAL_MS`).

Where it surfaces:

- **Diagnostics (acceptance: "thermal ladder visible in diagnostics")** — the read-out rides wherever the cast's status is rendered: the "This cast" section on the home screen *and* the scan screen's cast-status block (a review-time find: the user watches a started cast from the scan screen). Both show a plain-words line per ladder rung (normal / getting warm / hot + cooler-profile advice / very hot + consider stopping / overheating — stop). Advice, never action (AGENTS.md: simple user-facing text; the numbers stay out of the UI).
- **Logs (Phase15's measurement trail)** — one INFO line per ladder transition (`thermal: NONE → MODERATE, headroom 2.1, battery 39.5°C`), a DEBUG fact sample every 10 s, start/stop lines; next to the existing ~1 Hz sender-stats line (resolution, bitrate, drops, encoder), logcat is now the complete thermal-measurement channel Phase15's protocol needs. The ~1 Hz stats remain the encoder-stress signal (thermal.md: indirect thermal signal).

### Stored-settings migration

The settings document is now v2; v1 documents (Phase10 shape) decode through an explicit legacy-label translation (`light`→`cool`, `smooth`→`performance` — a rename, never a guess; values are identical), everything else v1-shaped still validates the same. Anything this build can't validate still degrades to defaults with a logged warning (the store's convention).

## Verification (2026-09-20)

**Tests:** mobile JVM **94/94** (11 new: `ThermalMonitorTest` — the `PowerManager` constant mirror, transition-once semantics, unknown values ignored, sample-without-transition; `QualityProfileTest` — thermal vocabulary labels + the encoder-target distinctness acceptance; `CastSettingsCodecTest` — v1 light/smooth migration, v1 unknown label → null, v2 round-trips; `SettingsViewModelTest` — cool-profile pick). `assembleDebug` green.

**On-device (review session, 2026-09-20):** installed on the Lenovo TB321FU / Android 16; a live cast to `MacBookPro.lan` ran on the new build, and `ThermalSource` sampled on real hardware at exactly the 10 s cadence — `status SEVERE, headroom 0.94, battery 39.9°C` — matching `dumpsys thermalservice`'s own `Thermal Status: 3` (CPU cores ~90 °C; the device was genuinely hot, cooling to 38.9 °C during sampling). The ladder mirror is verified live, not only by the constant test.

**Remaining on-device acceptance items (need the phone in hand):**

1. Pick `performance`, scan → cast, then end and recast with `cool`: the ~1 Hz sender-stats line must show the switch (1280@60 with a 6–10 Mbps ceiling vs 960@30 with ~3 Mbps, resolution now logged since Phase10).
2. The plain-words thermal line visible on screen while the ladder moves (the log side is verified; the on-screen read-out should follow the same transitions — eyeball it during a warm cast).

## Known limitations

- Thermal behavior is device-specific (SoC, thermal mass, case, ambient); the profiles are tuned hypotheses, re-tuned only with Phase15 measurements on real hardware (thermal.md).
- The OS may throttle the game itself regardless of the cast; the read-out reports the device, not attribution.
- Headroom is API30+ only — on the API29 floor the log line carries `n/a` for it.
- The UI advice lines are suggestions; the app never acts on thermal data itself (auto-degradation is Phase12, deliberately not now).
- Screen-content frame rate still follows game frame pacing — `performance` cannot conjure frames the game doesn't render.
