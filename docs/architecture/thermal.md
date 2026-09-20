# Thermal Strategy

Status: Phase11 (profiles shipped as the settings presets; monitoring implemented, read-only; measurements in Phase 15).

## Principles

1. **Measurement first.** No claim of "thermal safe" without numbers (spec §Phase 15). Values below are starting hypotheses, not truths.
2. **The user stays in control.** The active profile is a user choice; automatic degradation (Phase 12+) is conservative, slow, announced, and reversible.
3. **Profiles are the primary mitigation**, applied at cast start from the mobile `config` module — the desktop is never involved.

## Heat sources on the phone during a cast

| Source | Contribution | Mitigation lever |
|---|---|---|
| Hardware video encode (H.264) | High | Resolution/FPS/bitrate caps per profile; VP8 fallback only if an SoC's H.264 encoder proves less efficient (measured, not assumed) |
| `MediaProjection` capture pipeline | Medium | Lower capture resolution; avoid intermediate copies/scaling |
| Wi-Fi radio at sustained Mbps | Medium | Lower bitrate; single pass encoding |
| Phone display (game itself) | High, but owned by the game | Not ours to manage — the cast must simply not add much on top |
| Audio capture/encode | Low | Opus is cheap; no additional processing on phone |

The cast's goal: **keep added thermal load small enough that the cast is not what tips the device into throttling** during long sessions.

## Initial profiles (tuned in Phase11; to be re-tuned from Phase15 measurements)

Phase11 shipped these as the app's named profiles (`config/QualityProfile.kt`) — the settings screen's one-tap presets, applied at cast start from the config module. `light`/`smooth` from Phase10 were relabeled into the thermal vocabulary; the values are the tuned windows the app shipped with (codec v2 translates stored v1 documents). "540p/720p" name the *short* edge of a 16:9 frame; the capture target in the settings is the long edge in px.

| Profile | Capture (long edge) | FPS | Bitrate window | Intent |
|---|---|---|---|---|
| Cool (`cool`) | 960 px (≈540p) | 30 | 2–3 Mbps | Long sessions, warm devices, battery priority |
| Balanced (default, `balanced`) | 1280 px (720p) | 30 | 4–6 Mbps | The good-enough default for streaming |
| Performance (`performance`) | 1280 px (720p) | 60 | 6–10 Mbps | Fast-motion games; expect measurably more heat |

A fourth preset stays outside the thermal ladder: **Sharp** (`sharp`, 1920 px, 30 fps, 8–12 Mbps) — fidelity over thermals, the user's explicit choice (principle 2). Any manual tweak past a preset is labeled `custom`; the label derivation (`profileLabelFor`) is the display's source of truth, and the profiles→encoder mapping is pinned by a distinctness test (no two thermal profiles share a whole encoder target).

Exact values are re-tuned from Phase 15 measurements; this table is updated with the measured data when that happens.

## Monitoring inputs (Android, minSdk-compatible) — implemented in Phase11

- `PowerManager.OnThermalStatusChangedListener` (API29) — the platform's `THERMAL_STATUS_*` ladder (`NONE → LIGHT → MODERATE → SEVERE → CRITICAL …`), mirrored in the pure `thermal/ThermalMonitor` (the mirror is pinned by a test against `PowerManager`'s constants).
- `PowerManager.getThermalHeadroom(expectedInSecond)` (API 30+) — forward-looking headroom forecast; a 30 s horizon, sampled where the platform has it.
- `BatteryManager` battery temperature (sticky `ACTION_BATTERY_CHANGED` broadcast) — coarse but comparable across devices.
- WebRTC sender stats (encoder implementation, dropped frames, target vs. actual bitrate) as *indirect* thermal signals (encoder stress) — logged at ~1 Hz by `MediaCastSession` since Phase5/10.

The `thermal` module's cadence: ladder changes are logged immediately (one INFO line per transition, with the latest headroom + battery temperature), facts are re-sampled every 10 s (DEBUG), and the "This cast" section on the home screen shows the ladder in plain words — read-only diagnostics, per below.

## How thermal data is used

- **Phase11 (implemented):** read-only — the app displays thermal state in diagnostics (home screen "This cast" section + logcat) and logs it; profiles remain user-selected, and nothing in the app changes cast parameters because of thermal data.
- **Phase 12 (only if the system is otherwise stable):** conservative adaptation with hysteresis — e.g., only step *down* one profile level after sustained `MODERATE`+ status (minutes, not seconds), notify the user, and never oscillate. Step back up only manually.
- Phase 15 defines the measurement protocol: fixed-duration casts per profile on real hardware, logging temperature ladder, battery drain, FPS stability, and dropped frames.

## Constraints & limitations

- Thermal behavior is device-specific (SoC, thermal mass, case, ambient); results from one phone do not generalize. Documentation reports the *method* plus measured samples, not universal claims.
- The OS may throttle the game itself regardless of the cast; the cast cannot fix that, only avoid contributing.
- Screen-content frame rate also follows game frame pacing; a 60 fps profile cannot conjure frames the game doesn't render.
