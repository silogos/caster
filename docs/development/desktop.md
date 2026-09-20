# Desktop App Development Guide

App: `/apps/desktop` · Electron · Implemented in: Phase 2 (status: **complete**).

## Prerequisites

Node **22.12+** (project verified on 22.21.1), npm 10. Electron's binary (~100 MB) downloads on first `npm install`. If the postinstall silently skips it (symptom: `electron-vite` errors with `Error: Electron uninstall`), run `node node_modules/electron/install.js` manually.

## Build & run

```bash
cd apps/desktop
npm install          # first time only
npm run dev          # dev mode (HMR renderer, devtools)
npm run build        # production build → out/
npm start            # launch the production build (electron-vite preview)
npm test             # vitest unit tests
npm run typecheck    # tsc for node + web configs
```

From the repo root: `npm run desktop:dev` / `desktop:build` / `desktop:start` / `desktop:test` / `desktop:typecheck`.

Verified on 2026-09-20: typecheck green, 3 unit tests green, production build launches, QR scannable (details below).

## Pinned toolchain (apps/desktop/package.json)

| Component | Version | Notes |
|---|---|---|
| Electron | 44.4.3 | Own Chromium everywhere: WebRTC receive, H.264 HW decode, Web Audio ([ADR-001](../decisions/ADR-001-tech-stack.md)). |
| electron-vite | 5.0.0 | Main/preload/renderer bundling; supports vite 5–7 (not 8 yet). |
| vite | 7.3.6 | Newest line electron-vite 5 accepts. |
| TypeScript | 5.9.3 | Stayed on the proven 5.9 line; TS 7.0 (native port) is too fresh for a scaffold — upgrade deliberately later. |
| qrcode (+@types) |1.5.4 / 1.5.6 | Main-process QR → PNG data URL, error correction M per [pairing.md](../architecture/pairing.md). |
| vitest | 5.0.1 | Unit tests, node environment. |
| jsqr / pngjs | 1.4.0 / 7.0.0 | Dev-only: decode the generated QR PNG back to the payload (scannability test). |

## Project layout

```text
apps/desktop/
├── package.json / electron.vite.config.ts
├── tsconfig.node.json (main+preload) / tsconfig.web.json (renderer) / vitest.config.ts
└── src/
    ├── main/                      # Node: sockets & sessions belong here (architecture/desktop.md)
    │   ├── index.ts               # app lifecycle + IPC handlers
    │   ├── window.ts              # window lifecycle (becomes WindowManager with powerSaveBlocker)
    │   └── pairing/
    │       ├── staticSession.ts   # static, spec-shaped QR payload v1 — Phase 3 replaces with PairingServer
    │       ├── qr.ts              # payload → QR data URL
    │       └── staticSession.test.ts
    ├── preload/index.ts + index.d.ts  # contextBridge → window.desktopApi (sandboxed)
    ├── shared/                    # typed IPC channels + shared types (no runtime code)
    └── renderer/                  # Chromium: dark pairing screen, framework-free TS + CSS
        ├── index.html
        └── src/main.ts / renderer.css
```

## What is implemented (Phase 2)

- electron-vite + TypeScript scaffold with the main/preload/renderer split per [desktop.md](../architecture/desktop.md).
- Dark UI (#0a0c10): app title, "Waiting for mobile device…", QR rendered from **static test data**.
- The static payload is a *spec-shaped* `pairing.md` v1 object (fixed test hosts/port/secrets — nothing listens on them), so the QR rendering path is production-grade; Phase 3 only swaps the payload source for real session generation.
- Single typed IPC channel (`pairing:get-session`); channel names live in `src/shared/ipc.ts`.
- Resource trims from the Phase 2 RAM discussion: spellcheck off, renderer sandbox on, strict CSP, devtools only in dev, single window, no framework in the renderer.

## Not implemented yet (by design)

PairingServer/SignalingServer/NetworkInfo (Phases 3–4), WebRTC and media (Phases 5+), audio mixer (Phase 9), packaging/installer (deferred until there is something worth shipping). The desktop will **never** gain cast settings ([overview.md](../architecture/overview.md) ownership rules).

## Verification record (2026-09-20, macOS arm64)

- `typecheck` green; `vitest` 3/3 green — including a round-trip test: payload → QR data URL → decode with jsQR → exact payload.
- End-to-end visual check: launched the production build with CDP, dumped the renderer state (title/status/QR `data:image/png` src/dark bg all correct) and decoded the QR *from the window screenshot pixels* → exact v1 payload.
- Idle RAM (informal baseline, per the RAM-efficiency agreement): **~364–376 MB summed RSS** across the 6 Electron processes (RSS double-counts pages shared between processes; the main process's true `phys_footprint` was 41 MB). Baseline to compare against in later phases; real load (video decode) gets measured in Phases 5/15.

## Conventions

- Main process owns sockets and sessions; renderer owns WebRTC and media; IPC carries only typed events defined in `src/shared/ipc.ts`.
- The pairing module (`src/main/pairing/`) is deliberately Electron-import-free so unit tests can exercise it in plain Node — keep it that way when PairingServer lands.
- Renderer stays framework-free while it is this small; revisit only if a later phase (13, UX) justifies it with a measured reason.
- Tests assert behavior (spec shape, scannability), not implementation details.
