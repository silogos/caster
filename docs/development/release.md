# Release Guide

How to cut a release of Caster (called Zero-Friction Local Cast until v0.2.0, [ADR-027](../decisions/ADR-027-the-product-is-called-caster.md)). Rewritten for v0.2.0 (2026-11-11): the desktop app is the native Mac receiver (`apps/desktop-native`, [ADR-015](../decisions/ADR-015-native-receiver-tauri-libwebrtc.md)), and the product has one version number. The v0.1.0 process (Electron installers for macOS and Windows) is in this file's git history.

A release is: **an annotated git tag on `main` + a GitHub release carrying two assets** — the Android APK and the Mac receiver's disk image.

## Versioning

Decision and rejected alternatives: [ADR-026](../decisions/ADR-026-one-product-version.md).

**One version for the whole product**, in the `VERSION` file at the repository root. The phone app and the receiver are released together and carry the same number, so "use v0.2.0 on both" is always a true instruction.

| Rule | Why |
|---|---|
| `MAJOR.MINOR.PATCH`. While the product is `0.x`: MINOR for a new feature or a protocol change, PATCH for fixes only. `1.0.0` is the product owner's call, not a count of features. | A user can tell from the number whether both apps need updating |
| **The version does not decide whether two apps can talk.** The protocols do: the QR payload's `v` and the signaling `proto` range negotiated in the handshake ([pairing.md](../architecture/pairing.md)). A phone on 0.2.1 works with a receiver on 0.2.0 while they share a `proto`. | Tying compatibility to the app version would refuse pairs that work |
| Android's `versionCode` is computed from the version: `MAJOR × 10000 + MINOR × 100 + PATCH` (0.2.0 → 200, 1.0.0 → 10000). MINOR and PATCH stay below 100. | It must rise with every release or Android refuses the update; a derived number cannot be forgotten |
| Stored settings and protocols keep their own version numbers (`CastSettingsCodec.VERSION`, `proto`, QR `v`). | They change on their own schedule |

Where the number lives:

| Place | How it gets the version |
|---|---|
| `VERSION` | the source |
| Android (`apps/mobile/app/build.gradle.kts`) | reads `VERSION` at build time: `versionName`, and `versionCode` from it |
| Mac receiver (`apps/desktop-native/src-tauri/Cargo.toml`, `Cargo.lock`) | a copy (Cargo takes no version from a file); `tauri.conf.json` states none, so the bundle uses Cargo's |
| `apps/desktop` (Electron) | not released any more; its `0.1.0` is left alone |

```bash
tools/versioning/set_version.sh 0.2.1    # writes VERSION and the receiver's copies
tools/versioning/check_version.sh        # what CI runs: fails when a copy differs
```

## Prerequisites (build machine)

- An Apple Silicon Mac with the repo's toolchains: JDK 17 and the Android SDK (build-tools ≥ 36, for `apksigner`), Rust (`cargo`), Node 22 (only for `npx @tauri-apps/cli`).
- **The release keystore** at `apps/mobile/keystore/release.keystore`, with credentials in `apps/mobile/keystore/release.keystore.properties` (alias + passwords). This directory is **deliberately untracked** — `*.keystore` is gitignored and signing material must never be committed. It exists only on machines that sign releases; back it up separately.
- Run the packaging build in a normal terminal session. `hdiutil` (disk-image creation) can fail in a sandboxed shell.

## 0. Pre-flight

1. `main` is the release source. Release from `main`, never from a phase branch.
2. Green checks at the tagging point: `tools/versioning/check_version.sh`, mobile `./gradlew testDebugUnitTest`, receiver `cargo test` (in `apps/desktop-native/src-tauri`).
3. `VERSION` holds the version being released (`set_version.sh`, through a PR like any change).

## 1. Mac receiver (`.dmg`)

Packaging settings live in `apps/desktop-native/src-tauri/tauri.conf.json` under `bundle`.

```bash
cd apps/desktop-native/src-tauri
npx --yes @tauri-apps/cli@2 build
```

Output: `target/release/bundle/dmg/Caster_<ver>_aarch64.dmg` (about 10 MB), and the `.app` beside it under `bundle/macos/`.

What the bundle settings do, and why:

- `macOS.frameworks` copies the vendored `Syphon.framework` into the app; the binary looks for it at `@executable_path/../Frameworks` (`build.rs`). Without it the app does not start on another Mac.
- `macOS.signingIdentity: "-"` signs the whole bundle ad hoc. There is no Developer ID, so the app is **not notarized**; but an Apple Silicon Mac refuses to run a bundle whose seal is broken, and the linker's own signature covers the binary only.

Check before shipping:

```bash
APP="target/release/bundle/macos/Caster.app"
codesign --verify --deep --strict -v "$APP"        # "valid on disk"
/usr/libexec/PlistBuddy -c "Print :CFBundleShortVersionString" "$APP/Contents/Info.plist"
open "$APP"                                         # it starts, pairs and plays a cast
```

Notes:

- **Apple Silicon only.** The build is for the machine's own architecture; an Intel build has not been made or tried.
- First launch on another Mac is blocked by Gatekeeper (not notarized): System Settings → Privacy & Security → **Open Anyway**. State this in the release notes every time.
- Building replaces `target/release/desktop-native`; a receiver started from that path stops being the binary on disk. Quit it first.

## 2. Android APK (build unsigned, then sign manually)

The Gradle `release` build type has **no signingConfig on purpose** (the config would reference untracked keystore files and break every machine without them). Build unsigned, then sign with `apksigner`:

```bash
cd apps/mobile
./gradlew assembleRelease        # → app/build/outputs/apk/release/app-release-unsigned.apk

SDK=~/Library/Android/sdk        # adjust if ANDROID_HOME differs
# read alias/passwords from keystore/release.keystore.properties — do not paste them into commands in scripts/docs
$SDK/build-tools/<latest>/apksigner sign \
  --ks keystore/release.keystore \
  --ks-key-alias <alias> \
  --ks-pass pass:<storePassword> --key-pass pass:<keyPassword> \
  --out app/build/outputs/apk/release/Caster-v<ver>.apk \
  app/build/outputs/apk/release/app-release-unsigned.apk

$SDK/build-tools/<latest>/apksigner verify --print-certs app/build/outputs/apk/release/Caster-v<ver>.apk
$SDK/build-tools/<latest>/aapt2 dump badging app/build/outputs/apk/release/Caster-v<ver>.apk | head -1
```

- `verify --print-certs` must print the `CN=Zero-Friction Local Cast` certificate (the key keeps the product's first name) — same key every release, or Android refuses the update over an installed previous version.
- `dump badging` must print the release's `versionName` and the `versionCode` derived from it.
- A phone carrying a **debug** build (a developer's device) cannot update to the release APK: the signatures differ. Uninstall first; the stored profiles go with it.

## 3. Tag

Packaging and version changes go through a branch + PR like everything else, **before** tagging — so the tag contains the exact config that produced the artifacts.

```bash
git checkout main && git pull
git tag -a v<ver> -m "v<ver> — <short description>"
git push origin v<ver>
```

Annotated tags only (they carry the message and the tagger). Never move or re-push an already-published tag; cut a new version instead.

## 4. GitHub release

```bash
gh release create v<ver> \
  --title "v<ver> — Caster" \
  --notes-file release-notes-v<ver>.md \
  "apps/desktop-native/src-tauri/target/release/bundle/dmg/Caster_<ver>_aarch64.dmg" \
  "apps/mobile/app/build/outputs/apk/release/Caster-v<ver>.apk"
```

Release notes template:

- one line: what the product is + the LAN-only/no-cloud invariant,
- what changed since the last release, in the user's words (phone, then receiver),
- **Notes and limitations**: not notarized + what the user sees (Gatekeeper), Apple Silicon only, no Windows or Linux receiver, self-signed APK, same-Wi-Fi requirement, anything shipped that was not measured on a device,
- Verification: test counts and on-device checks at the tagging point.

To fix notes or add assets after publishing: `gh release edit v<ver> --notes-file ...` / `gh release upload v<ver> ...`.

## Known limitations of this process

- **Fully manual, from one machine.** No CI builds releases; CI only checks that the version copies agree.
- **No notarization** — permanent until a paid signing identity exists.
- **No Windows or Intel Mac receiver.** v0.1.0's Electron installers covered them on paper (the Windows one was never run); the native receiver is macOS on Apple Silicon.
- The receiver's version is a copy of `VERSION`, kept in step by a script and checked by CI, not read at build time.
