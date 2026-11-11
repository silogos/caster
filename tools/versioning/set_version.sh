#!/usr/bin/env bash
# Sets the product's version everywhere it is written:
#   tools/versioning/set_version.sh 0.2.1
# VERSION is the source; the Mac receiver's Cargo.toml and Cargo.lock are its
# copies. The Android app reads VERSION when it builds.
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
version="${1:?usage: set_version.sh MAJOR.MINOR.PATCH}"
[[ "$version" =~ ^[0-9]+\.[0-9]{1,2}\.[0-9]{1,2}$ ]] ||
  { echo "'$version' is not MAJOR.MINOR.PATCH with MINOR and PATCH below 100" >&2; exit 1; }

printf '%s\n' "$version" > "$root/VERSION"
tauri="$root/apps/desktop-native/src-tauri"
python3 - "$tauri/Cargo.toml" "$tauri/Cargo.lock" "$version" <<'PY'
import re, sys
toml, lock, version = sys.argv[1:]
text = open(toml).read()
text, n = re.subn(r'(\[package\]\nname = "desktop-native"\nversion = ")[^"]+(")', rf'\g<1>{version}\g<2>', text, count=1)
assert n == 1, "Cargo.toml: [package] version not found"
open(toml, "w").write(text)
text = open(lock).read()
text, n = re.subn(r'(name = "desktop-native"\nversion = ")[^"]+(")', rf'\g<1>{version}\g<2>', text, count=1)
assert n == 1, "Cargo.lock: desktop-native entry not found"
open(lock, "w").write(text)
PY
"$root/tools/versioning/check_version.sh"
