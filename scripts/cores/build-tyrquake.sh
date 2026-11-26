#!/usr/bin/env bash
# Builds GOA's TyrQuake core: libretro/tyrquake at the commit pinned in scripts/cores/cores.lock, with the patches in
# scripts/cores/patches/tyrquake applied in order, for arm64-v8a (phones) and x86_64 (the emulator), into
# third_party/tyrquake/jniLibs/<abi>/libtyrquake_libretro.so. NDK r28+ (16 KB page-aligned output, which Play requires).
#   scripts/cores/build-tyrquake.sh [path/to/ndk]
# Publishing a new build: upload both .so files to the R2 bucket "goa" under source/builds/tyrquake/<build id>/jniLibs/
# (with the patches and this script under source/builds/tyrquake/<build id>/), then update fetch-cores.sh and the
# tyrquake entry of cores.lock (build id, sha256) and redeploy the website.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
NDK="${1:-${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/29.0.14206865}}"
COMMIT="$(sed -n '/^\[tyrquake\]/,/^\[/s/^commit *= *//p' "$ROOT/scripts/cores/cores.lock")"
# A fixed build folder: source paths end up in the binary, so the same folder gives the same bytes
TMP="${TMPDIR:-/tmp}/goa-tyrquake-build"; rm -rf "$TMP"; mkdir -p "$TMP"; trap 'rm -rf "$TMP"' EXIT

git clone -q https://github.com/libretro/tyrquake.git "$TMP/tyrquake"
git -C "$TMP/tyrquake" checkout -q "$COMMIT"
# Quake prints its build time (__DATE__/__TIME__): the commit's date, so a rebuild gives the same bytes
export SOURCE_DATE_EPOCH="$(git -C "$TMP/tyrquake" log -1 --format=%ct)"
for p in "$ROOT"/scripts/cores/patches/tyrquake/*.patch; do
  git -C "$TMP/tyrquake" apply "$p"
  echo "applied $(basename "$p")"
done
(cd "$TMP/tyrquake/jni" && "$NDK/ndk-build" -j"$(nproc)" APP_ABI="arm64-v8a x86_64" >"$TMP/build.log" 2>&1) \
  || { tail -40 "$TMP/build.log" >&2; exit 1; }
for abi in arm64-v8a x86_64; do
  out="$ROOT/third_party/tyrquake/jniLibs/$abi/libtyrquake_libretro.so"
  install -D -m 644 "$TMP/tyrquake/libs/$abi/libretro.so" "$out"
  # every LOAD segment 16 KB aligned (Play rejects 4 KB-aligned native code on 16 KB devices)
  for align in $("$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-readelf -lW "$out" | awk '$1=="LOAD" {print $NF}'); do
    (( align >= 16384 )) || { echo "$abi: a LOAD segment is aligned to $align, not 16 KB" >&2; exit 1; }
  done
  echo "$abi: $(sha256sum "$out" | cut -d' ' -f1)"
done
