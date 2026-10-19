#!/usr/bin/env bash
# Build the desktop JNI library (Controller only; Core runs as its own
# service) and copy it to desktopApp/appResources/<os>/, where packaged
# builds load it from.  `:desktopApp:run` uses target/release directly.
set -euo pipefail
cd "$(dirname "$0")"
cargo build --release
case "$(uname -s)" in
  Linux*)  os=linux;   lib=libmsst_jni.so ;;
  Darwin*) os=macos;   lib=libmsst_jni.dylib ;;
  *)       os=windows; lib=msst_jni.dll ;;
esac
dest=../desktopApp/appResources/$os
mkdir -p "$dest"
cp "target/release/$lib" "$dest/"
echo "copied $lib to $dest"
