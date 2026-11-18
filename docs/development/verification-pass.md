# Verification pass — closing what Android + macOS still owe (2026-11-19)

Why this file: before a Windows receiver starts, every item the roadmap leaves "awaiting on-device check", "not confirmed" or open is either verified, retired, or carried with a reason. The roadmap and [reliability.md](../features/reliability.md) stay the records; this is the run sheet. Delete it once every row has an outcome written back to its record.

Who: **PO** = needs the product owner's eyes or hands (on-screen look, sound, clicks on the Mac). **AG** = the agent can run it from adb + receiver logs while the tablet is connected, Caster is in front, and the tablet is unlocked by the PO (the agent never unlocks it).

## Scope: the native receiver only

Electron-era records are not carried over: the Electron receiver is gone (ADR-035), so its features (softcam virtual camera and issue #71, the audio sidecar, its telemetry, its tray matrix) and every result recorded against it are out of scope here. Every desktop-side check below runs against `apps/desktop-native`.

Not run here, with the reason: the thermal emergency (11ae) cannot be induced without harming the device — unit-tested, watched in logs (`DOWN (THERMAL)`); glass-to-glass latency (T8) is measured on the native receiver (latency-measurement.md).

## 1. Mac deck walk — one ordinary cast, ~15 min (PO, AG reads logs)

| # | Check | Source | Pass when |
|---|---|---|---|
| 1.1 | Launcher icon on the tablet, menu-bar image on the Mac | 11ag | Both show the Caster icon |
| 1.2 | Video window in the Dock | 11y | Listed while open, gone when closed |
| 1.3 | Open video / Close video | 11i | Picture and sound start and stop together |
| 1.4 | Game volume + mute | 11g | Audible change; mute is silent; survives relaunch |
| 1.5 | Stay-awake | 11g | AG: `pmset -g assertions` shows Caster's assertion during the cast, none idle |
| 1.6 | Readout beside the timecode | 11ad | Pill shows the cast's settings, readout shows the arriving size/fps |
| 1.7 | No QR while casting, 10 min | 11z | Main window never shows the QR during the cast |
| 1.8 | Tray: close hides, phone still connects | 11h | Window + Dock icon gone; one-tap Connect from the phone reopens it |
| 1.9 | Tray menu items + Forget phone armed only while trusted | 11h | Each item does what it says |
| 1.10 | Quit from tray | 11h | Phone receives `bye desktop-quit`, keeps the saved desktop |
| 1.11 | Disconnect from the desktop (two-step) | 11g | Phone drops to its QR flow, trust forgotten |

## 2. Mobile review leftovers — one sitting, ~15 min (AG + PO taps)

| # | Check | Source | Pass when |
|---|---|---|---|
| 2.1 | Scan a wrong QR, then the right one | 11j | Scanner not latched; right code pairs |
| 2.2 | Bitrate slider during a live cast | 11j | No crash; target follows |
| 2.3 | Kill the receiver mid-cast, relaunch | 11k | Phone reconnects in ~15–20 s |
| 2.4 | Socket-only drop (signaling down, media up) | 11l | Re-auth keeps the CONNECTED media pc (log: no pc rebuild) |

## 3. Phase 15 matrix — every row of [reliability.md](../features/reliability.md)

All rows run fresh against the native receiver. The PASS results already in reliability.md (L1–L3, L7, L8, L10, N8; 2026-09-24 and 2026-11-02) were recorded against the Electron receiver and do not count here.

- **3a Lifecycle (AG, ~30 min):** L1–L10, L11 after each.
- **3b Desktop & pairing (AG + PO, ~20 min):** D1–D8.
- **3c Network (AG + PO, ~50 min):** N1–N8, plus the **R12 background check**: drop Wi-Fi with a game in front and Caster in the background; if it sticks, capture `dumpsys activity processes | grep -A2 io.github.silogos.caster`.
- **3d Audio (PO listens, ~30 min):** A1–A5, A11. A6–A10 are mic rows — run them only if the mic is back in scope (the PO currently uses the laptop headset).
- **3e Hotspot pairing (PO, ~20 min, Feature 2026-11c):** permission → join → QR pair over the hotspot → full cast with both audio tracks → clean teardown; OS-revoked mid-cast; no mobile data used.

## 4. Thermal — T1–T4, T6, T7 (T5 is section 5) (AG drives, PO only starts/stops the game)

Four 20-min casts (cool, balanced, performance, sharp) with the same game, cool-down to baseline between runs, per the protocol in reliability.md §Thermal. ~2.5 h with cool-downs; can be split over days. T6 rides the first profile switch; T7 is recorded if any run reaches MODERATE+.

## 5. OBS session — T5 / Phase 14 acceptance on Syphon (PO, 30+ min)

OBS Syphon source + Caster Audio + Caster Mic, one game session: clean, correctly proportioned, smooth; rotation re-fits; game audio reaches OBS through exactly one source. Also closes 11ab's open half if run on "Keep frame rate" vs "Keep resolution" with a real game.

## Order

1 + 2 in one sitting (~30 min), 3 over one or two sittings, 4 and 5 when there is time to play.
