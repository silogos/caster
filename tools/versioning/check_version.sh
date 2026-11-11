#!/usr/bin/env bash
# The product has one version: the VERSION file at the repository root
# (docs/development/release.md, "Versioning"). The Android app reads it at
# build time; the Mac receiver cannot (Cargo takes no version from a file),
# so its copies are checked here. Fails, naming the place, when one differs.
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
version="$(tr -d '[:space:]' < "$root/VERSION")"
status=0

fail() { echo "version mismatch: $1" >&2; status=1; }

[[ "$version" =~ ^[0-9]+\.[0-9]{1,2}\.[0-9]{1,2}$ ]] ||
  fail "VERSION holds '$version'; expected MAJOR.MINOR.PATCH with MINOR and PATCH below 100"

cargo_toml="$root/apps/desktop-native/src-tauri/Cargo.toml"
cargo_version="$(awk '/^\[package\]/{p=1;next} /^\[/{p=0} p && /^version *=/{gsub(/[" ]/,"",$0); sub(/^version=/,"",$0); print; exit}' "$cargo_toml")"
[[ "$cargo_version" == "$version" ]] ||
  fail "apps/desktop-native/src-tauri/Cargo.toml says $cargo_version, VERSION says $version"

cargo_lock="$root/apps/desktop-native/src-tauri/Cargo.lock"
lock_version="$(awk '/^name = "desktop-native"$/{getline; gsub(/[" ]/,"",$0); sub(/^version=/,"",$0); print; exit}' "$cargo_lock")"
[[ "$lock_version" == "$version" ]] ||
  fail "apps/desktop-native/src-tauri/Cargo.lock says $lock_version, VERSION says $version"

# The receiver's bundle takes its version from Cargo.toml only while
# tauri.conf.json states none of its own.
if grep -q '^  "version"' "$root/apps/desktop-native/src-tauri/tauri.conf.json"; then
  fail "apps/desktop-native/src-tauri/tauri.conf.json states a version of its own; remove it"
fi

[[ $status -eq 0 ]] && echo "version $version: VERSION, Cargo.toml and Cargo.lock agree"
exit $status
