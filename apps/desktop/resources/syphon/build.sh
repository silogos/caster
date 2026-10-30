#!/bin/bash
# Rebuilds the vendored macOS Syphon half of the virtual camera (ADR-007):
#   1. Syphon.framework  — compiled from source (BSD-3, github.com/Syphon/Syphon-Framework)
#   2. syphon-helper      — the stdin→Metal→Syphon bridge (src/syphon-helper.m)
# Both universal (arm64 + x86_64). Run from this directory on a machine with
# Xcode (needs the Metal toolchain: xcodebuild -downloadComponent MetalToolchain).
set -euo pipefail
cd "$(dirname "$0")"

SYPHON_REPO="https://github.com/Syphon/Syphon-Framework.git"
WORK="$(mktemp -d)/syphon-src"
trap 'rm -rf "$(dirname "$WORK")"' EXIT

echo "→ cloning Syphon framework source…"
git clone --depth 1 "$SYPHON_REPO" "$WORK"

echo "→ building Syphon.framework (universal)…"
xcodebuild -project "$WORK/Syphon.xcodeproj" -target Syphon -configuration Release \
  ARCHS="arm64 x86_64" ONLY_ACTIVE_ARCH=NO SYMROOT="$WORK/build" >/dev/null

rm -rf Syphon.framework
cp -R "$WORK/build/Release/Syphon.framework" .
cp "$WORK/License.txt" ./LICENSE 2>/dev/null || true

echo "→ compiling syphon-helper (universal)…"
clang -fobjc-arc -O2 \
  -arch arm64 -arch x86_64 \
  -F. -framework Syphon -framework Foundation -framework Metal \
  -rpath @executable_path \
  src/syphon-helper.m -o syphon-helper
codesign --force --sign - syphon-helper 2>/dev/null || true  # ad-hoc, matches the app's unsigned dev builds

echo "✓ done: Syphon.framework + syphon-helper (universal)"
