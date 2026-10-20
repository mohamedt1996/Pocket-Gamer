#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
SRC="$ROOT/.android-work/wine"
OUT="$ROOT/.android-out/wine"
# Match the pinned ARM64EC compiler and dlltool. Wine's host winebuild
# must not silently fall back to /usr/lib/llvm-18/bin/llvm-dlltool.
MINGW="${LLVM_MINGW:-$HOME/toolchains/llvm-mingw-20250920-ucrt-ubuntu-22.04-x86_64}"
export DLLTOOL="$MINGW/bin/llvm-dlltool"
export PATH="$MINGW/bin:$PATH"
test -x "$DLLTOOL" || { echo "::error::Missing pinned llvm-mingw dlltool: $DLLTOOL"; exit 1; }
if ! "$DLLTOOL" --help 2>&1 | grep -Eiq 'arm64ec'; then
  echo "::error::Pinned llvm-mingw dlltool lacks ARM64EC support; do not use system LLVM 18."
  "$DLLTOOL" --help | tail -30
  exit 1
fi
echo "ARM64EC import tool: $DLLTOOL"
python3 "$ROOT/runtime/android/fetch.py" wine "$SRC"
python3 "$ROOT/runtime/android/fetch.py" ntsync "$ROOT/.android-work/ntsync"
export NTSYNC_ANDROID_DIR="$ROOT/.android-work/ntsync"
python3 "$ROOT/runtime/android/patch-wine.py" "$SRC"
cd "$SRC"
bash build-scripts/build-step0-autogen.sh
python3 dlls/winevulkan/make_vulkan
bash build-scripts/build-step0.sh
bash build-scripts/build-step-arm64ec.sh --build-sysvshm
bash build-scripts/build-step-arm64ec.sh --build-ntsync-android
bash build-scripts/build-step-arm64ec.sh --enable-16kb-pages --configure
make -j2
bash build-scripts/build-step-arm64ec.sh --enable-16kb-pages --install
mkdir -p "$OUT"
cp -a "$HOME/compiled-files-aarch64/." "$OUT/"
test -s "$OUT/lib/wine/aarch64-unix/ntdll.so"
test -s "$OUT/lib/wine/aarch64-unix/wineandroid.so"
test -s "$OUT/lib/wine/aarch64-windows/wineandroid.drv"
mkdir -p "$OUT/integration" "$OUT/share/licenses/wine"
cp dlls/wineandroid.drv/WineActivity.java "$OUT/integration/"
cp COPYING.LIB LICENSE "$OUT/share/licenses/wine/"
git submodule status --recursive > "$OUT/share/licenses/wine/submodules.txt"
mkdir -p "$OUT/share/licenses/directaudio"
find dlls/winedirectaudio.drv -maxdepth 1 -type f \( -iname '*license*' -o -iname '*copying*' \) -exec cp '{}' "$OUT/share/licenses/directaudio/" \;
git diff --binary > "$OUT/share/licenses/wine/madeira-android.patch"
cp "$ROOT/runtime/android/sources.json" "$OUT/share/licenses/wine/"
python3 "$ROOT/runtime/android/collect-libs.py" \
 "$HOME/termuxfs/aarch64/data/data/com.termux/files/usr" "$OUT"
