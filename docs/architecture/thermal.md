# Thermal Strategy

Status: Phase 0 (principles; profiles land in Phase 11, measurements in Phase 15).

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

## Initial profiles (hypotheses to validate in Phase 11/15)

| Profile | Resolution | FPS | Bitrate target | Intent |
|---|---|---|---|---|
| Cool | 540p | 30 | ~3 Mbps | Long sessions, warm devices, battery priority |
| Balanced (default) | 720p | 30 | 4–6 Mbps | The good-enough default for streaming |
| Performance | 720p | 60 | ~8 Mbps | Fast-motion games; expect measurably more heat |

Exact values are tuned from Phase 15 measurements; the tables in this doc are updated with the measured data when that happens.

## Monitoring inputs (Android, minSdk-compatible)

- `PowerManager.OnThermalStatusChangedListener` (API 29) — the platform's `THERMAL_STATUS_*` ladder (`NONE → LIGHT → MODERATE → SEVERE → CRITICAL …`).
- `PowerManager.getThermalHeadroom(expectedInSecond)` (API 30+) — forward-looking headroom forecast where available.
- `BatteryManager` battery temperature/current — coarse but comparable across devices.
- WebRTC sender stats (encoder implementation, dropped frames, target vs actual bitrate) as *indirect* thermal signals (encoder stress).

## How thermal data is used

- **Phase 11:** read-only — the app displays thermal state in diagnostics and logs it; profiles remain user-selected.
- **Phase 12 (only if the system is otherwise stable):** conservative adaptation with hysteresis — e.g., only step *down* one profile level after sustained `MODERATE`+ status (minutes, not seconds), notify the user, and never oscillate. Step back up only manually.
- Phase 15 defines the measurement protocol: fixed-duration casts per profile on real hardware, logging temperature ladder, battery drain, FPS stability, and dropped frames.

## Constraints & limitations

- Thermal behavior is device-specific (SoC, thermal mass, case, ambient); results from one phone do not generalize. Documentation reports the *method* plus measured samples, not universal claims.
- The OS may throttle the game itself regardless of the cast; the cast cannot fix that, only avoid contributing.
- Screen-content frame rate also follows game frame pacing; a 60 fps profile cannot conjure frames the game doesn't render.
