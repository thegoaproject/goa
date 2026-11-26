#!/usr/bin/env bash
# Installs the emulator cores pinned in scripts/cores/cores.lock (core binaries aren't in git), checking each against
# the SHA-256 recorded there:
#   DOSBox Pure: upstream's official release build, from Codeberg
#   ScummVM, OpenLara: GOA's fixed copies of libretro's builds, from R2 (upstream only keeps rolling nightlies)
#   TyrQuake: GOA's patched build (scripts/cores/build-tyrquake.sh), from R2
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
cd "$ROOT"

curl -fsSL "https://codeberg.org/schelling/dosbox-pure/releases/download/1.0-preview7/dosbox_pure_libretro-android_arm64-1.0-preview7.zip" -o "$TMP/dbp.zip"
unzip -q -o "$TMP/dbp.zip" -d "$TMP/dbp"
install -D -m 644 "$(ls "$TMP"/dbp/dosbox_pure_libretro_android.so* | head -1)" app/src/main/jniLibs/arm64-v8a/libdosbox_pure.so

BASE="https://cdn.goodoldandroid.app/source/builds/scummvm/e0ddc60bfabb"
for f in jniLibs/arm64-v8a/libscummvm_libretro.so jniLibs/x86_64/libscummvm_libretro.so assets/scummvm_support.zip; do
  mkdir -p "third_party/scummvm/$(dirname "$f")"
  curl -fsSL "$BASE/$f" -o "third_party/scummvm/$f"
done

# Native ports: Quake (TyrQuake) and Tomb Raider (OpenLara), arm64 for phones and x86_64 for the emulator
fetch_port() {  # <core dir> <build commit> <library name>
  for abi in arm64-v8a x86_64; do
    mkdir -p "third_party/$1/jniLibs/$abi"
    curl -fsSL "https://cdn.goodoldandroid.app/source/builds/$1/$2/jniLibs/$abi/$3" -o "third_party/$1/jniLibs/$abi/$3"
  done
}
fetch_port tyrquake 67db338ff4d2-goa2 libtyrquake_libretro.so   # with GOA's patch: scripts/cores/build-tyrquake.sh
fetch_port openlara e4ce52edec5a libopenlara_libretro.so

grep '^sha256' scripts/cores/cores.lock | sed 's/^sha256 *= *//' | sha256sum -c
