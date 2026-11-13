#!/bin/bash
# Developer path only: the receiver installs the plugin itself from the deck's
# "Plays on" choice (ADR-030). Installs CasterAudio.driver, removes the
# ZeroFrictionAudio.driver it replaces, and restarts coreaudiod so it picks
# the change up. Run with sudo.
#   sudo ./install.sh           install/update
#   sudo ./install.sh --remove  uninstall
set -euo pipefail
cd "$(dirname "$0")"

HAL="/Library/Audio/Plug-Ins/HAL"
DEST="$HAL/CasterAudio.driver"

if [ "${1:-}" = "--remove" ]; then
  rm -rf "$DEST"
  echo "removed $DEST"
else
  [ -d CasterAudio.driver ] || { echo "build first: ./build.sh"; exit 1; }
  rm -rf "$DEST" "$HAL/ZeroFrictionAudio.driver"
  cp -R CasterAudio.driver "$DEST"
  echo "installed $DEST"
fi

# coreaudiod discovers plugins at startup; launchd brings it back.
killall coreaudiod
echo "coreaudiod restarted"
