# Proposals (drafts — not decided)

Draft feature directions under discussion. **Nothing here is decided, nothing is a phase.** Each proposal stands alone; when one is accepted, it gets its own ADR (and roadmap phase). Shared context — the competitive snapshot, small cross-feature observations, and the full evidence list — lives here so the feature docs don't repeat it.

## The three drafts

| Draft | One line |
|---|---|
| [web-receiver.md](web-receiver.md) | Zero-install browser receiver on the LAN, served by the phone |
| [virtual-camera.md](virtual-camera.md) | **Accepted 2026-10-30** → [ADR-007](../decisions/ADR-007-virtual-camera.md), shipped on Windows (issue #69); Linux (PipeWire) and macOS (Syphon) are follow-ups |
| [camera-source.md](camera-source.md) | *(optional)* Phone camera as an alternative capture source |

Written 2026-10-28 after a teardown of a shipping competitor product ("the competitor": marketing site, browser-receiver JS, Android APK, and Windows desktop app — see [Sources](#sources)). The single biggest takeaway: **the competitor's desktop app is Electron, same as caster**, so its virtual-camera recipe transfers directly.

## Shared competitive snapshot

Caster today (Phases 0–18) vs the analyzed competitor (v1.0.0):

| Aspect | Competitor | Caster |
|---|---|---|
| Primary use case | phone as webcam | gaming cast |
| Internal (game) audio | none — mic only, always mixed as the single audio track | separate game-audio track; mic coexistence verified on device (ADR-004) |
| Network | internet required; Google STUN; public TURN relay; own signaling server | LAN-only; no STUN/TURN; desktop-hosted signaling |
| Pairing | 6–8 char room code, no authentication | QR v1 + HMAC challenge + expiry + regeneration (ADR-002) |
| Quality control | hardcoded: 1080p30, 4–8 Mbps screen / 2–4 Mbps camera, fixed frame-rate-first degradation | user presets + manual override, applied live (Phase 10/17) |
| Adaptive quality | none | stats-driven ladder with hysteresis, measured on device (Phase 12/17) |
| Thermal | none | profile ladder + monitoring (Phase 11) |
| Rotation handling | stock `ScreenCapturerAndroid` (does not follow rotation) | owned `ScreenCapturer.kt`, resizes the single display in place, verified portrait↔landscape |
| Receiver | any browser (any network) + Windows app for virtual camera | desktop app only |
| Camera source | yes (front/back, live switch) | no |
| Virtual camera | yes (Windows; macOS "coming soon" since launch) — Electron + open-source softcam DirectShow filter via FFI | no |
| Release hygiene | APK ships `debuggable="true"`, hardcoded dev API key in the signaling URL, full SDP/ICE logged | strict logging rules; signed releases |

**Where the competitor is ahead:** receiver reach (browser, zero install, any network), camera-as-webcam, virtual camera shipping on Windows.
**Where caster is ahead:** everything about the cast itself — audio separation, quality adaptation, thermal, rotation, pairing security, privacy posture.

## Smaller observations (no feature doc of their own)

- **Adopt:** the cast notification should carry the *receiver identity* + source + Stop action — with the device registry (`feat/device-home`, ADR-005) the user should always see which desktop a cast is going to.
- **Reference only:** their bitrate windows (screen 1080p30 at 4–8 Mbps, camera 2–4 Mbps) independently confirm caster's 4–6 Mbps Phase-5 window. No change.
- **Note only:** the competitor runs MediaProjection + FGS on minSdk 26 — relevant *if* caster ever lowers minSdk below 29.
- **Reject:** unauthenticated room codes (anyone who guesses a 6-char room joins the stream; caster's HMAC pairing is stronger and built); QR-carried server URL + client allowlist (an attack surface caster's LAN-direct payload doesn't have); static-companion storage of the MediaProjection intent (caster's service-owned session is safer, Phase 6); their release hygiene (debuggable APK, dev API key in URL, full SDP logging — anti-patterns, kept here as a release-checklist reminder).

## Decision order (from the drafts)

1. Web receiver fork: LAN-only phone-served page vs cloud signaling — blocks everything receiver-side ([web-receiver.md §2](web-receiver.md)).
2. ~~Virtual camera scope~~ — **decided 2026-10-30**: accepted for Windows as [ADR-007](../decisions/ADR-007-virtual-camera.md) (screen-as-camera scope; the optional [camera-source.md](camera-source.md) stays a separate draft), with the platform map Windows-softcam / Linux-PipeWire / macOS-Syphon.
3. Sequencing vs the remaining roadmap (Phase 15 reliability matrices, Phase 16/18 performance) — the user's call; these drafts do not preempt them.

## Sources

- Competitor marketing site (fetched 2026-10-28): product description (phone → computer webcam/mirror over WebRTC), downloads (Android APK ~68 MB, Windows portable ~149 MB, macOS "coming soon"), "no data stored on our servers" claim, no pricing page.
- Competitor browser-receiver JavaScript (fetched 2026-10-28): a `wss://` signaling endpoint on the vendor's own domain; ICE servers = two public Google STUN entries + a free public TURN relay on ports 443/80 — the internet dependency and third-party media transit are in their own client code.
- Competitor Android APK (v1.0.0, versionCode 1, minSdk 26 / target 34), decompiled with jadx on 2026-10-28 (local copy on file): manifest (`debuggable="true"`, `FOREGROUND_SERVICE_MEDIA_PROJECTION|CAMERA`, custom deep-link scheme); the WebRTC client (STUN-only ICE list on the phone side, H.264 high profile enabled, screen 1920×1080@30 with min 4 / max 8 Mbps, camera 1280×720@30 with 2–4 Mbps, frame-rate-first degradation, `RtpSender.setParameters`); the signaling client (room-join protocol, a hardcoded dev API key in the URL, no reconnect logic, no audio-playback-capture anywhere); the foreground service (per-source FGS typing, static MediaProjection-intent retention); QR handling (deep link + compact-JSON fallback with 1-char keys, room-id regex `^[A-Z0-9]{6,8}$`, server allowlist); the streaming UI (`vc-status` / `vc-start` / `vc-stop` DataChannel protocol, `pendingOffer` queue, a `connection-established` signal).
- Competitor Windows desktop app (portable zip, extracted 2026-10-28; local copy on file): an **electron-builder `win-unpacked` Electron app** — `app.asar` extracted and read. Virtual camera = `softcam/softcam.dll` + `softcam_installer.exe` (open-source tshino/softcam) loaded from `app.asar.unpacked` via **koffi** FFI (`scCreateCamera`/`scSendFrame`/`scDeleteCamera`), one-time registration elevated via PowerShell `Start-Process -Verb RunAs` and detected via a registry CLSID query; frames shipped renderer→main as base64 JPEG over IPC (`virtual-camera:frame`), decoded/resized with **sharp**, RGB→BGR in a JS loop, ~30 fps `setInterval` capture from a canvas drawing the `<video>`; control = the same `vc-status`/`vc-start`/`vc-stop` DataChannel protocol plus `vc-query`; tray-based lifecycle, frameless receiver window, dimensions capped 1920×1080 and rounded to multiples of 4.
- Caster facts: [roadmap](../development/roadmap.md) Phases 0–18 and the feature docs it links; [ADR-002](../decisions/ADR-002-pairing-and-signaling-security.md), [ADR-004](../decisions/ADR-004-mic-capture-coexistence.md), [ADR-005](../decisions/ADR-005-device-identity-and-registry.md).
