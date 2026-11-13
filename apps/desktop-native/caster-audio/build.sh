#!/bin/bash
# Rebuilds the CasterAudio HAL plugin (ADR-030): clones a pinned libASPL
# (MIT, github.com/gavv/libASPL), compiles the .driver bundle (universal),
# signs it ad hoc like the app, and leaves it next to this script. The
# receiver's bundle carries that copy (tauri.conf.json → resources), so
# rebuild and commit it after changing src/Driver.cpp.
# Needs Xcode's command-line tools and cmake.
set -euo pipefail
cd "$(dirname "$0")"

LIBASPL_COMMIT="633e0f70203edd87d320fc5a3cae901e1363aac5"  # pin: master @ 2026-10, as ADR-008

echo "→ fetching libASPL (pinned ${LIBASPL_COMMIT:0:9})…"
rm -rf libASPL build
git clone --quiet https://github.com/gavv/libASPL.git libASPL
git -C libASPL checkout --quiet "$LIBASPL_COMMIT"

echo "→ configuring + building (universal)…"
cmake -S . -B build \
  -DCMAKE_OSX_ARCHITECTURES="arm64;x86_64" \
  -DCMAKE_OSX_DEPLOYMENT_TARGET=12.0 \
  -DCMAKE_BUILD_TYPE=Release >/dev/null
cmake --build build --target CasterAudio >/dev/null

rm -rf CasterAudio.driver
cp -R "build/CasterAudio.driver" .
codesign --force --sign - CasterAudio.driver  # ad hoc, like the app (release.md)

echo "✓ built CasterAudio.driver ($(lipo -archs CasterAudio.driver/Contents/MacOS/CasterAudio))"
echo "  developer install (admin): sudo ./install.sh"
