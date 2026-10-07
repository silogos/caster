# Release Guide

How to cut a release of Zero-Friction Local Cast. Written from the v0.1.0 process (2026-10-07) — the first tagged release, done manually from a macOS build machine.

A release is: **an annotated git tag on `main` + a GitHub release carrying four assets** — the Android APK and three desktop installers (macOS dmg ×2, Windows setup.exe).

## Prerequisites (build machine)

- **Node 22** via nvm (`nvm use 22`). Verified on 22.21.1.
- **Android SDK** with build-tools ≥ 36 (for `apksigner`). `ANDROID_HOME` at `~/Library/Android/sdk` or set explicitly.
- **The release keystore** at `apps/mobile/keystore/release.keystore`, with credentials in `apps/mobile/keystore/release.keystore.properties` (alias + passwords). This directory is **deliberately untracked** — `*.keystore` is gitignored and signing material must never be committed. It exists only on machines that sign releases; back it up separately.
- **A working `python` on PATH.** electron-builder's dmg-builder invokes `python` (not `python3`) to lay out the DMG. Two traps hit on the v0.1.0 machine:
  - Homebrew's `python@3.14` fails at `import plistlib` (broken `pyexpat` stdlib module) — do not symlink to it.
  - The Apple `/usr/bin/python3` shim dispatches on the invoked name, so symlinking it as `python` just errors.
  The fix: symlink the real Command Line Tools interpreter, whose stdlib works:

  ```bash
  ln -sf /Library/Developer/CommandLineTools/usr/bin/python3 /opt/homebrew/bin/python
  ```

  Verify with `python -c "import plist; print('ok')"`.
- Run the packaging builds in a normal terminal session. `hdiutil` (disk-image creation) fails in sandboxed/limited contexts — if you are an agent in a sandboxed shell, builds must run with sandboxing disabled.

## 0. Pre-flight

1. `main` is the release source. The working branch (e.g. the current phase branch) is usually **ahead** of `main` — that's fine and expected. Release from `main`, never from a phase branch.
2. Green checks at the tagging point: desktop `npm run typecheck` + `npm test`, mobile `./gradlew test` + `assembleDebug`.
3. Version numbers match the tag in **both apps**:
   - `apps/desktop/package.json` → `"version"` (the installer reports this version),
   - `apps/mobile/app/build.gradle` → `versionName` (user-visible) and `versionCode` (bump by 1 every release; it must be monotonic for Android updates).

## 1. Desktop installers (cross-buildable from macOS)

Config lives in `apps/desktop/package.json` under `build` (electron-builder). Both targets are **unsigned** — there is no code-signing identity available (the macOS Developer ID situation is documented in ADR-005), and no Windows certificate either.

```bash
cd apps/desktop
npm install                      # if electron-builder is newly added
npm run dist                     # macOS: dmg for arm64 + x64
npm run dist:win                 # Windows: one NSIS setup.exe (combined x64 + arm64)
```

Outputs in `apps/desktop/release/`:

| File | For |
|---|---|
| `Zero-Friction Local Cast-<ver>-arm64.dmg` | Apple Silicon Macs |
| `Zero-Friction Local Cast-<ver>.dmg` | Intel Macs |
| `Zero-Friction Local Cast Setup <ver>.exe` | Windows — one installer containing both x64 and arm64 runtimes, picks at install time |

Notes:

- First launch on macOS triggers a Gatekeeper warning (unsigned). On Windows, SmartScreen warns. This is accepted for now; it must be stated in the release notes every time.
- The Windows installer is **cross-built from macOS and never executed on a Windows machine** — say "untested on Windows" in the release notes until the process gains a Windows test pass.
- The 218 MB setup.exe carries both Electron runtimes. If size matters later, split per-arch (`nsis` target with one arch each) — two ~110 MB installers.

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
  --out app/build/outputs/apk/release/Zero-Friction-LocalCast-v<ver>.apk \
  app/build/outputs/apk/release/app-release-unsigned.apk

$SDK/build-tools/<latest>/apksigner verify --print-certs app/build/outputs/apk/release/Zero-Friction-LocalCast-v<ver>.apk
```

`verify --print-certs` must print the `CN=Zero-Friction Local Cast` certificate — same key every release, or Android will refuse updates over an installed previous version.

## 3. Tag

Packaging-config changes (if any were needed) go through a branch + PR like everything else, **before** tagging — so the tag contains the exact config that produced the artifacts.

```bash
git checkout main && git pull
git tag -a v<ver> -m "v<ver> — <short description>"
git push origin v<ver>
```

Annotated tags only (they carry the message and the tagger). Never move or re-push an already-published tag; cut a new version instead.

## 4. GitHub release

```bash
gh release create v<ver> \
  --title "v<ver> — Zero-Friction Local Cast" \
  --notes-file release-notes-v<ver>.md \
  "apps/desktop/release/Zero-Friction Local Cast-<ver>-arm64.dmg" \
  "apps/desktop/release/Zero-Friction Local Cast-<ver>.dmg" \
  "apps/desktop/release/Zero-Friction Local Cast Setup <ver>.exe" \
  "apps/mobile/app/build/outputs/apk/release/Zero-Friction-LocalCast-v<ver>.apk"
```

Release notes template (keep this shape; it is what v0.1.0 shipped):

- one-line what the product is + the LAN-only/no-cloud invariant,
- Android section: the user-visible feature set at this version,
- desktop section: per-platform files and what they contain,
- **Notes and limitations**: unsigned builds + what the user sees (Gatekeeper / SmartScreen), untested-Windows caveat when applicable, self-signed APK, same-Wi-Fi requirement, platforms without packaging,
- Verification: test counts and on-device checks at the tagging point.

To fix notes or add assets after publishing: `gh release edit v<ver> --notes-file ...` / `gh release upload v<ver> ...`.

## Known limitations of this process

- **Fully manual, from one machine.** No CI builds releases. The natural next step is a workflow that builds on tag push; until then, this document is the process.
- **No notarization** (macOS) and no certificate (Windows) — the unsigned-build caveats in the notes are permanent until a paid signing identity exists (see ADR-005 for why).
- **Windows artifacts are never executed before shipping.** Cross-build catches packaging errors, not runtime ones.
- Version bumps are manual in two places (desktop `package.json`, mobile `build.gradle`). Forgetting one ships a mismatched version string.
