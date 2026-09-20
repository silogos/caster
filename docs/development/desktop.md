# Desktop App Development Guide

App: `/apps/desktop` · Electron · Implemented in: Phases 2–9 (status: **Phase 9 implemented — awaiting the live-cast listen**, [features/audio-mixer.md](../features/audio-mixer.md)).

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

Launch with CDP for scripted UI checks: `npx electron out/main/index.js --remote-debugging-port=9222`, then `node scripts/read-qr-payload.mjs 9222` to extract the live QR payload.

Verified on 2026-09-20 (Phase 4): typecheck green, vitest 31/31 green — 11 new loopback protocol tests (full message set, heartbeat, bye both ways, reconnect-within-TTL, expiry); production build verified live via CDP (details: [features/signaling.md](../features/signaling.md)).
Previously (Phase 3): real-device pairing scan verified — details in [features/pairing.md](../features/pairing.md).

## Pinned toolchain (apps/desktop/package.json)

| Component | Version | Notes |
|---|---|---|
| Electron | 44.4.3 | Own Chromium everywhere: WebRTC receive, H.264 HW decode, Web Audio ([ADR-001](../decisions/ADR-001-tech-stack.md)). |
| electron-vite | 5.0.0 | Main/preload/renderer bundling; supports vite 5–7 (not 8 yet). |
| vite | 7.3.6 | Newest line electron-vite 5 accepts. |
| TypeScript | 5.9.3 | Stayed on the proven 5.9 line; TS 7.0 (native port) is too fresh for a scaffold — upgrade deliberately later. |
| qrcode (+@types) | 1.5.4 / 1.5.6 | Main-process QR → PNG data URL, error correction M per [pairing.md](../architecture/pairing.md). |
| ws (+@types) | 8.21.3 / 8.18.x | Signaling WebSocket server (Phase 3); also drives the loopback tests. |
| vitest |5.0.1 | Unit tests, node environment. |
| jsqr / pngjs | 1.4.0 / 7.0.0 | Dev-only: decode the generated QR PNG back to the payload (scannability tests + live checks). |

## Project layout

```text
apps/desktop/
├── package.json / electron.vite.config.ts
├── scripts/read-qr-payload.mjs   # manual-verification helper (CDP → live QR payload)
├── tsconfig.node.json (main+preload) / tsconfig.web.json (renderer) / vitest.config.ts
└── src/
    ├── main/                      # Node: sockets & sessions belong here (architecture/desktop.md)
    │   ├── index.ts               # app lifecycle, module wiring, IPC relay (no business logic)
    │   ├── window.ts              # window lifecycle (becomes WindowManager with powerSaveBlocker)
    │   ├── log.ts                 # level-tagged structured logging (AGENTS.md)
    │   ├── pairing/
    │   │   ├── pairingServer.ts   # session generation + lifecycle (pairing.md) — Electron-free
    │   │   ├── networkInfo.ts     # LAN IPv4 enumeration for the QR payload
    │   │   └── qr.ts              # payload → QR data URL
    │   └── signaling/
    │       ├── envelope.ts        # webrtc.md message envelope parse/serialize
    │       ├── handshake.ts       # nonce, HMAC, constant-time compare, proto negotiation
    │       └── signalingServer.ts # ws server at /zfc/v1 — handshake state, rate limit — Electron-free
    ├── preload/index.ts + index.d.ts  # contextBridge → window.desktopApi (sandboxed)
    ├── shared/                    # typed IPC channels + shared types (no runtime code)
    └── renderer/                  # Chromium: dark pairing screen + WebRTC, framework-free TS + CSS
        ├── index.html              # QR view + the <video> cast surface
        └── src/main.ts / renderer.css
            ├── webrtc/             # ReceiverSession (answerer), stats sampling (Phase 5)
            └── audio/              # AudioMixer — Web Audio graph, per-stream gain, persistence (Phase9)
```

## What is implemented (Phases 2–9)

- electron-vite + TypeScript scaffold with the main/preload/renderer split per [desktop.md](../architecture/desktop.md); resource trims from the Phase 2 RAM discussion (spellcheck off, renderer sandbox on, strict CSP, devtools only in dev, single window, no renderer framework).
- Real pairing (Phase 3): session generation + expiry/regeneration sweep, WebSocket handshake server with HMAC challenge–response, rate limiting, busy/bye semantics, multi-interface LAN IP advertisement. Details: [features/pairing.md](../features/pairing.md).
- Typed IPC: `pairing:get-session` (invoke), `pairing:regenerate` (invoke), `pairing:session-updated` + `pairing:mobile-state` (pushes). Channel names live in `src/shared/ipc.ts`.
- Renderer: QR + "Waiting for mobile device…", "Connected to \<phone\>" on auth-ok, Regenerate button, cast `<video>` view, and (Phase9) the mixer panel — independent volume/mute per audio stream, levels persisted in `localStorage`. Details: [features/audio-mixer.md](../features/audio-mixer.md).

## Not implemented yet (by design)

WebRTC receive of a mobile offer beyond Phase5/8's scope, packaging/installer (deferred until there is something worth shipping). The desktop will **never** gain cast settings ([overview.md](../architecture/overview.md) ownership rules).

## Verification record

- 2026-09-20 (Phase 2): typecheck green; 3 unit tests green (QR scannability round-trip); production build verified live via CDP; idle RAM baseline ~364–376 MB summed RSS across the 6 Electron processes (RSS double-counts shared pages; main-process `phys_footprint` 41 MB) — compare real load in Phases 5/15.
- 2026-09-20 (Phase 3): typecheck green; vitest **20/20** green — session lifecycle tests, HMAC vector, and loopback protocol tests over a real `ws` server (a scripted phone scans the QR PNG, then success/`unknown-session`/`bad-auth`+rate-limit/`busy`+reconnect/`bad-version`/recoverable `bad-message`/`expired`/`bye` paths). Production build launches, serves a real session on the LAN (verified via CDP payload extraction); real-device pairing scan verified — a Lenovo TB321FU (Android 16) scanned the QR and completed the handshake, desktop showed "Connected to TB321FU".
- 2026-09-20 (Phase 4): typecheck green; vitest **31/31** green — new `signalingProtocol.test.ts` loopback suite (11 tests): full message set (recorded SDP blobs; ICE incl. a Chromium mDNS `*.local` candidate + `candidate: null`; ping→pong; session-info), heartbeat (keeps a ponging phone, closes a silent one), `bye` both directions, reconnect-within-TTL, expiry refusal. Live check against the production build: scripted phone over the LAN — heartbeat ping observed, session-info rendered on the status line, desktop back to a fresh QR after `bye`. Details: [features/signaling.md](../features/signaling.md).
- 2026-09-20 (Phase 5): typecheck green; vitest **48/48** green (17 new: ReceiverSession answer/ICE/teardown behaviors incl. maplike `[id, stats]` tuples; stats sampling + bitrate math). Live on-device cast received end to end from a Lenovo TB321FU — H.264 HW-encoded ~4–6 Mbps video rendered in the `<video>` view; ~1 Hz stats logging verified in the renderer console. Details: [features/screen-capture.md](../features/screen-capture.md).
- 2026-09-20 (Phase 9): typecheck green; vitest **67/67** green (14 new `mixer.test.ts` behaviors: graph wiring, independent volume/mute, lazy/suspended AudioContext handling, persistence round-trip, corrupt-storage recovery). Production build green and live-checked via CDP: mixer panel hidden while waiting, controls drive the persisted levels (`game 0.4`, `mic muted`) and those levels restore after a kill+relaunch. The audible live-cast listen is the remaining acceptance item — [features/audio-mixer.md](../features/audio-mixer.md).

## Conventions

- Main process owns sockets and sessions; renderer owns WebRTC and media; IPC carries only typed events defined in `src/shared/ipc.ts`.
- The `pairing/` and `signaling/` modules are deliberately Electron-import-free so unit tests can exercise them in plain Node — keep it that way.
- Renderer stays framework-free while it is this small; revisit only if a later phase (13, UX) justifies it with a measured reason.
- Tests assert behavior (spec shape, scannability, protocol outcomes), not implementation details.
- Never log the pairing secret or HMAC ([AGENTS.md](../../AGENTS.md)); `src/main/log.ts` is the level-tagged logger.
