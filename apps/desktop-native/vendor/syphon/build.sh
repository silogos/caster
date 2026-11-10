#!/bin/bash
# Rebuilds the vendored Syphon.framework (BSD-3, github.com/Syphon/Syphon-Framework)
# the native receiver links for its OBS output (ADR-021). Universal
# (arm64 + x86_64). Run from this directory on a machine with Xcode
# (needs the Metal toolchain: xcodebuild -downloadComponent MetalToolchain).
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
echo "done."
