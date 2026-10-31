#!/bin/bash
# Rebuilds the ZeroFrictionAudio HAL plugin (batch A, ADR-008): clones a
# pinned libASPL (MIT, github.com/gavv/libASPL), compiles the .driver bundle
# (universal), and leaves it next to this script for the app to install.
# Run from this directory on a machine with Xcode + cmake + ninja.
set -euo pipefail
cd "$(dirname "$0")"

LIBASPL_COMMIT="633e0f70203edd87d320fc5a3cae901e1363aac5"  # pin: master @ 2026-10

echo "→ fetching libASPL (pinned ${LIBASPL_COMMIT:0:9})…"
rm -rf libASPL build
git clone --quiet https://github.com/gavv/libASPL.git libASPL
git -C libASPL checkout --quiet "$LIBASPL_COMMIT"

echo "→ configuring + building (universal)…"
cmake -S . -B build \
  -DCMAKE_OSX_ARCHITECTURES="arm64;x86_64" \
  -DCMAKE_BUILD_TYPE=Release >/dev/null
cmake --build build --target ZeroFrictionAudio >/dev/null

cp -R "build/ZeroFrictionAudio.driver" .
codesign --force --sign - ZeroFrictionAudio.driver 2>/dev/null || true  # ad-hoc, like the dev app builds

echo "✓ built ZeroFrictionAudio.driver (universal)"
echo "  install (admin, once): sudo ./install.sh"
