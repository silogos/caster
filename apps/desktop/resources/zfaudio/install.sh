#!/bin/bash
# Installs the ZeroFrictionAudio HAL plugin (admin, ONCE per machine) and
# restarts coreaudiod so it picks the plugin up. Run with sudo.
#   sudo ./install.sh          install/update
#   sudo ./install.sh --remove uninstall
set -euo pipefail
cd "$(dirname "$0")"

DEST="/Library/Audio/Plug-Ins/HAL/ZeroFrictionAudio.driver"

if [ "${1:-}" = "--remove" ]; then
  rm -rf "$DEST"
  echo "removed $DEST"
else
  [ -d ZeroFrictionAudio.driver ] || { echo "build first: ./build.sh"; exit 1; }
  rm -rf "$DEST"
  cp -R ZeroFrictionAudio.driver "$DEST"
  echo "installed $DEST"
fi

# coreaudiod discovers plugins at startup
killall coreaudiod
echo "coreaudiod restarted"
