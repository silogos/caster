# Development Environment Setup

Status: Phase 0 (prerequisites for the phases ahead; app-specific guides `development/mobile.md` and `development/desktop.md` are added when the apps are initialized in Phases 1–2).

## Prerequisites

| Tool | Version | Used for |
|---|---|---|
| Android Studio (with SDK platform 36, platform-tools, build-tools) | Latest stable | Mobile app; SDK manager handles the rest |
| JDK | 17+ (bundled with Android Studio) | Gradle builds |
| Node.js | ≥ 20 LTS (22 LTS recommended) | Desktop app (Electron) |
| npm | Bundled with Node | Desktop package management |
| Git | Any recent | Version control |

## Android device

A **real Android 10+ device** is required for meaningful verification from Phase 5 onward: hardware H.264 encoders, real game audio capture, and thermal behavior do not exist on emulators. An emulator is fine for Phases 1–4 (UI, pairing, signaling).

- Enable Developer Options + USB debugging **for development installs only** — the end-user workflow never involves USB/ADB (product constraint).
- Keep the screen unlocked variance in mind: several manual tests depend on lock/unlock behavior.

## Network requirements

- Phone and desktop on the **same Wi-Fi network / subnet**.
- The router must **not** enable AP/client isolation (blocks client-to-client traffic and thus the whole product).
- mDNS resolution should be permitted (some mesh/AP setups filter it — see risk R4 in the [risk register](risk-register.md)).
- The desktop OS will show a **firewall prompt** on first run (incoming connections for the signaling port) — approve it.

## Verifying the environment

1. `java -version` → 17+; `node --version` → ≥ 20.
2. Android Studio → SDK Platforms → API 36 installed; a device or emulator visible under Device Manager.
3. Both machines/devices can reach each other (e.g. ping the desktop's LAN IP from the phone via any network tool) — optional sanity check for pairing tests.

## Repository conventions

- One phase at a time, review gates between phases ([roadmap](roadmap.md), AGENTS.md).
- Each phase ends with a coherent commit (`feat:` / `chore:` / `docs:` per AGENTS.md).
- Architecture docs live in `docs/architecture/`; update them in the same phase as the change they describe.
