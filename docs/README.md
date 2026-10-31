# Documentation Index

`/docs` is the shared knowledge base for **both humans and AI agents**. It is the source of truth for architecture and decisions. If code and docs disagree, that is a bug — fix one or the other in the same change.

## Reading order (new contributor or agent)

1. [architecture/overview.md](architecture/overview.md) — what the system is, boundaries, configuration ownership
2. [development/roadmap.md](development/roadmap.md) — phases and current status
3. The protocol specs you need for the task:
   - [architecture/pairing.md](architecture/pairing.md) — QR payload + auth handshake
   - [architecture/webrtc.md](architecture/webrtc.md) — signaling messages + connection lifecycle
4. The component you are working on:
   - [architecture/mobile.md](architecture/mobile.md) — Android app
   - [architecture/desktop.md](architecture/desktop.md) — desktop receiver
5. Cross-cutting concerns:
   - [architecture/audio.md](architecture/audio.md) — the two-track audio architecture
   - [architecture/thermal.md](architecture/thermal.md) — thermal strategy
6. [development/risk-register.md](development/risk-register.md) — known risks and how they are mitigated

## Layout

| Directory | Content |
|---|---|
| `architecture/` | How the system is built: boundaries, protocols, constraints, limitations |
| `features/` | Feature-level behavior docs, added as features land (Phase 3+) |
| `decisions/` | Architecture Decision Records (ADRs) — *why* choices were made |
| `proposals/` | Draft feature directions under discussion — **not decided, not a phase**; accepted proposals become ADRs + roadmap phases |
| `development/` | Setup, per-app dev guides, roadmap, risk register, release process |

## Rules

- Every doc states: **why** decisions were made, the **current** architecture, important **constraints**, **known limitations**, and implementation details future agents need.
- Significant architectural decisions require an ADR: copy the format of an existing ADR in `decisions/`, number sequentially (`ADR-004-…`), and set a Status (`proposed` / `accepted` / `superseded by ADR-0XX`).
- Do not create files just to create files. Do not put application source code in `/docs`.
- Update docs in the same phase as the architecture change that motivated them.

## Current documentation status

Phases 0–14 are implemented (14 awaiting on-device review); Phases 15–18 are in progress or remaining; the persistent pairing session (reconnect without QR, [ADR-006](decisions/ADR-006-persistent-trust-sessions.md)) shipped 2026-10-30 as an unnumbered feature and awaits on-device review (see roadmap). The virtual camera ([ADR-007](decisions/ADR-007-virtual-camera.md)) shipped 2026-10-30/31 as an unnumbered feature (Windows softcam + macOS Syphon) and awaits manual review. `architecture/` describes the planned-and-evolving system; `features/*` and `development/*` describe implemented behavior and are added as phases complete.
