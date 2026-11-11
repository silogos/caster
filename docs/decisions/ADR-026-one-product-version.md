# ADR-026: One version for the product, in one file

- **Status:** accepted (2026-11-11). Built and checked on the build machine: the Android app reports 0.2.0 / 200, the packaged receiver reports 0.2.0. No release has been cut with it yet.

(ADR-024 and ADR-025 belong to the custom-profiles work, open at the time of writing.)

## Context

v0.1.0 (2026-10-07) is the only release. Preparing the second one showed:

- every version string still read `0.1.0` and Android's `versionCode` was `1`; an APK released like that is refused as an update;
- the version was written by hand in four places (the Android build, the Electron receiver, and twice for the native receiver);
- the release guide described the Electron receiver, which is obsolete (ADR-015);
- nothing said what a version number promises about the phone and the receiver working together.

## Decision

1. **One version for the whole product**, in `VERSION` at the repository root. The phone app and the receiver are released together under it.
2. **The version never decides compatibility.** The protocols do: the QR payload's `v` and the signaling `proto` range ([pairing.md](../architecture/pairing.md)). The version is for people.
3. `MAJOR.MINOR.PATCH`; while `0.x`, MINOR is a feature or a protocol change and PATCH is fixes only.
4. **Android's `versionCode` is computed**: `MAJOR × 10000 + MINOR × 100 + PATCH`.
5. The Android build reads `VERSION`. The receiver's `Cargo.toml`/`Cargo.lock` are copies written by `tools/versioning/set_version.sh`; `tauri.conf.json` states no version; CI runs `tools/versioning/check_version.sh` and fails when a copy differs.

| Option | Result |
|---|---|
| A version per app | Rejected: two numbers to explain for a product that is installed as a pair, and no gain while they ship together |
| Refuse to pair when the versions differ | Rejected: `proto` already says precisely what can talk; the app version would refuse pairs that work |
| A hand-kept `versionCode` | Rejected: it was forgotten once already |
| The receiver reads `VERSION` in `build.rs` | Rejected: Cargo's package version cannot come from a file, and the bundle takes its version from Cargo |
| **One file, a derived code, a checked copy** | **Chosen** |

## Consequences

- MINOR and PATCH are capped at 99 by the `versionCode` formula; both scripts and the Gradle build refuse more.
- 0.1.0's `versionCode` was 1 and 0.2.0's is 200, so the order holds across the change.
- The Electron receiver (`apps/desktop`) keeps its `0.1.0` and is outside the check; it is not released any more (issue #152).
- The first release under this scheme has a Mac receiver for Apple Silicon only and no Windows installer ([release.md](../development/release.md)).
- **Revisit if** the two apps start shipping on different schedules: then the copy check becomes two versions and the release notes must name pairs.
