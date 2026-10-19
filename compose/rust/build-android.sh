#!/usr/bin/env bash
# Build libmsst_jni.so (with the embedded Core) for every ABI packaged by
# androidApp/build.gradle.kts and copy it into androidApp/src/main/jniLibs.
# Requires cargo-ndk and an Android NDK (ANDROID_NDK_HOME, or the newest NDK
# under $ANDROID_HOME/ndk).
set -euo pipefail
cd "$(dirname "$0")"
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  sdk="${ANDROID_HOME:-$HOME/Android/Sdk}"
  ANDROID_NDK_HOME="$(ls -d "$sdk"/ndk/* 2>/dev/null | sort -V | tail -1)"
  export ANDROID_NDK_HOME
fi
profile="${1:-release}"
flag=$([ "$profile" = release ] && echo --release || true)
out=../androidApp/src/main/jniLibs
cargo ndk -P 26 -t arm64-v8a -t armeabi-v7a -t x86_64 -o "$out" build --features embedded-core $flag
# cargo-ndk also copies dependency cdylibs (boringtun, tun2); libmsst_jni.so
# links them statically, so only it is packaged.
find "$out" -name '*.so' ! -name 'libmsst_jni.so' -delete
