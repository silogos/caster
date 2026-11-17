# ADR-035: The identifiers machines read carry Caster too

- **Status:** accepted (2026-11-18). **Supersedes decision 2 of [ADR-027](ADR-027-the-product-is-called-caster.md)** ("nothing a machine keys on changes"). The product owner chose this knowing it makes a new app on both sides.

## Context

ADR-027 renamed the product to Caster wherever a person reads it, and kept every identifier a machine keys on, because changing them refuses an update or loses saved pairings. After that rename the old names still sat in the Android application id and package (`com.zerofriction.localcast`), the Mac bundle id (`app.zerofriction.desktopnative`), the receiver's data folder, the QR payload's tag (`"zfc"`), the signaling path (`/zfc/v1`), the trust key's derivation label (`zfc-trust-v1`), the phone's stored preferences, the WebRTC track ids, the developer switches (`ZFC_LOG`, …) and the obsolete Electron receiver (`apps/desktop`). The product owner wants none of them left: the app is Caster, inside as well as outside.

The product owner has no domain. The usual reverse-DNS prefix for a project hosted on GitHub without one is `io.github.<account>`; Google Play and F-Droid accept it, and the account owner controls it.

## Decision

1. **App identities:**
   - Android application id and Kotlin package: `io.github.silogos.caster` (was `com.zerofriction.localcast`).
   - Mac bundle id: `io.github.silogos.caster.receiver` (was `app.zerofriction.desktopnative`).
   - The receiver's data folder: `~/Library/Application Support/Caster` (was `…/Zero-Friction Local Cast`).
2. **Protocol, versioned per the pairing doc's rules:**
   - QR payload **`v` 2**, tag `t = "caster"`. A phone of the old app reading a new QR, or the new app reading an old one, shows "please update" (`v` mismatch), never a half-working pairing.
   - Signaling path `/caster/v1`; client string `Caster/<version> (Android <release>; <model>)`.
   - Trust key label `caster-trust-v1`; the phone's Keystore alias `caster-trust-key-v1`.
   - The envelope's `proto` is unchanged: the messages are the same, and a mixed pair never gets past the QR.
3. **Everything else written in code:** preference files (`caster.*`), WebRTC track and stream ids (`caster-*`), developer switches (`CASTER_LOG`, `CASTER_LATENCY_PROBE`, `CASTER_VIDEO_COPY`, the `caster_capture_dump` global setting), the Compose theme (`CasterTheme`, `Theme.Caster`), test fixtures and tools.
4. **The Electron receiver is removed** (`apps/desktop`, its CI job). It has been obsolete since 2026-11-09 ([ADR-015](ADR-015-native-receiver-tauri-libwebrtc.md)); renaming it would have put its own macOS audio driver beside Caster Audio under the same name. Git history keeps it.
5. **Documents** use the new names, including older ADRs: ADR-027's "older documents keep the name they were written under" no longer holds. ADR-027 and this ADR still name the old identifiers, because recording the change is their purpose.

| Option | Result |
|---|---|
| Keep the machine identifiers (ADR-027) | Rejected by the product owner: the old name stays visible to anyone who reads a package name, a log, a QR or a folder |
| Rename and migrate (read the old folder, accept `zfc` QRs) | Rejected: a new application id is a new app anyway — the phone keeps no data across it — so a migration would only keep the old names alive in code |
| **Rename everything, clean break** | **Chosen** |
| `com.silogos` prefix | Rejected: the product owner does not own `silogos.com` |

## Consequences

- **Every user re-installs and re-pairs once.** Android treats the new id as another app: the old Caster stays installed beside it until removed, and its saved desktop, profiles and settings do not carry over. The Mac app is new too: the old one and its `trust.json` stay until removed.
- **The cable (ADR-034) needs the new pairing**: it authenticates with the trust key, whose label changed.
- **Not changed here:** the signing key's certificate name (`CN=Zero-Friction Local Cast`) lives outside the repository ([release.md](../development/release.md)). A new application id may ship under a new key, so the product owner can make one named Caster before the next release; that step is theirs.
- **Old machines:** a Mac that once ran the Electron receiver may still carry `/Library/Audio/Plug-Ins/HAL/ZeroFrictionAudio.driver`. The native receiver no longer removes it; it is removed by hand once.
- **Revisit if** the project gets its own domain before the first store release: the application id can still change then at no cost to existing users, because there are none outside development.
