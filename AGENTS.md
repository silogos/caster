# AGENTS.md — Rules for coding agents working in this repository

This is **Zero-Friction Local Cast**: an Android → desktop screen/audio/mic caster over LAN Wi-Fi. Read [docs/README.md](docs/README.md) before doing anything. If architecture docs conflict with code, fix the mismatch — do not silently work around documentation.

## Non-negotiables

1. **One phase at a time.** Development follows [docs/development/roadmap.md](docs/development/roadmap.md). Implement a phase, test it, document it, report, then **stop and wait for user review**. Never roll the next phase into the current one.
2. **Never claim a feature works without testing it.** Never silently skip a failed test. Report failures verbatim.
3. **Inspect before modifying.** Read existing code and docs before changing anything. Follow the established architecture.
4. **Ask, don't guess, on irreversible architecture changes.** If a decision would materially alter the architecture, stop and ask the user instead of replacing a documented decision.

## Product invariants

- **Mobile controls. Desktop receives.** All cast configuration (resolution, FPS, bitrate, codec preferences, audio sources, thermal profile) belongs to the Android app. The desktop may expose only receiver/environment controls (volume, window behavior). Never duplicate a cast setting on the desktop.
- **Game audio and microphone are separate tracks**, end to end. Never mix them on Android.
- **The end-user workflow must never require USB, ADB, or Developer Options.** Prefer Android platform APIs (`MediaProjection`, `AudioPlaybackCapture`, foreground services, hardware encoders).
- **Simple user-facing errors.** Show "Couldn't connect to the desktop. Make sure both devices are on the same Wi-Fi network." — not "ICE candidate gathering failed". Technical detail goes to logs.
- **LAN only.** No STUN/TURN, no internet relay, no accounts, no cloud.

## Architecture rules

- Keep these concerns in separate, small modules: pairing, signaling, media transport, video capture, audio capture, rendering, configuration. **Do not create a giant "CastManager".**
- The signaling channel is plain data plumbing for WebRTC offer/answer/ICE — keep it independent of media logic.
- No hidden global state, no magic constants (name them), no premature abstraction.
- Protocols (QR payload, signaling messages) are specified in `docs/architecture/` and are versioned. Changing a protocol field requires bumping the version and updating the doc first.

## Documentation rules

- Every significant architectural decision gets an ADR in `docs/decisions/` (see existing ADRs for format).
- When architecture changes, update `docs/architecture/` **in the same phase** as the code change.
- Docs must explain *why*, not just *what*: decision rationale, constraints, known limitations.
- Do not create documentation for its own sake. Do not put application source code in `/docs`.

## Testing rules

- Every phase ships tests appropriate to its scope (unit / protocol / integration / instrumentation / manual device tests).
- Tests verify **behavior**, not implementation details. Fake tests that only mirror the implementation are worse than no tests.
- Manual verification on a real device is expected for phases that touch capture, audio, or thermal behavior — emulators do not exercise hardware encoders, real game audio capture, or thermals.

## Logging rules

- Structured logging with `INFO` / `WARN` / `ERROR` / `DEBUG` levels on both apps.
- **Never log**: microphone content, audio samples, screen frames, pairing credentials (token/HMAC), or unnecessary personal information.
- Logs should be sufficient to diagnose: pairing, signaling, WebRTC, encoder, audio, network, thermal.

## Git conventions

- One coherent commit per phase (or per logical unit inside a phase). Suggested prefixes: `feat:`, `fix:`, `chore:`, `docs:`, `test:`.
- Never mix unrelated features into one commit.
