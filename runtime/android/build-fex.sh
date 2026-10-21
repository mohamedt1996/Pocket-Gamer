#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
# FEX_SOURCE: the sources.json entry; FEX_COMPONENT: the output component.
FEX_SOURCE="${FEX_SOURCE:-fex}"
FEX_COMPONENT="${FEX_COMPONENT:-fex}"
WORK="$ROOT/.android-work/$FEX_COMPONENT"
OUT="$ROOT/.android-out/$FEX_COMPONENT"
: "${ANDROID_NDK_HOME:?}" "${LLVM_MINGW:?}"
export PATH="$LLVM_MINGW/bin:$PATH"
python3 "$ROOT/runtime/android/fetch.py" "$FEX_SOURCE" "$WORK"
HOST_FLAGS=()
if [ "$FEX_COMPONENT" = fex-madeira ]; then
  git -C "$WORK" checkout -q -- .
  git -C "$WORK" apply "$ROOT/runtime/android/madeira/fex-android.patch"
  HOST_FLAGS=(-DCMAKE_C_FLAGS=-DFEX_ANDROID_HOST -DCMAKE_CXX_FLAGS=-DFEX_ANDROID_HOST)
fi
mkdir -p "$OUT/lib/wine/aarch64-windows" "$OUT/lib/wine/aarch64-unix" "$OUT/share/licenses/fex"
for target in arm64ec aarch64; do
  cmake -S "$WORK" -B "$WORK/build-$target" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_TOOLCHAIN_FILE="$WORK/Data/CMake/toolchain_mingw.cmake" \
    -DMINGW_TRIPLE="$target-w64-mingw32" -DENABLE_LTO=OFF -DBUILD_TESTING=OFF \
    -DENABLE_ASSERTIONS=OFF -DENABLE_JEMALLOC_GLIBC_ALLOC=OFF -DTUNE_ARCH=generic -DTUNE_CPU=none -DRANGES_NATIVE=OFF \
    "${HOST_FLAGS[@]}"
  # Only the module Wine loads: the rest (libFEXCore.dll, tools) is never shipped.
  module=$([ "$target" = arm64ec ] && echo arm64ecfex || echo wow64fex)
  cmake --build "$WORK/build-$target" --parallel 2 --target "$module"
  find "$WORK/build-$target" -type f -name 'lib*fex.dll' -exec cp '{}' "$OUT/lib/wine/aarch64-windows/" \;
done
# Match the Unix-call ABI to the exact same FEX revision as the PE modules.
TC="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
for name in arm64ecfex wow64fex; do
  "$TC/aarch64-linux-android28-clang++" -shared -fPIC -O2 -std=c++17 -static-libstdc++ \
    -Wl,-z,max-page-size=16384 -Wl,-soname,lib$name.so \
    -include "$ROOT/runtime/android/patches/fex_android_shm.h" \
    "$WORK/Source/Windows/UnixLib/FEXUnixLib.cpp" -o "$OUT/lib/wine/aarch64-unix/lib$name.so"
done
test -s "$OUT/lib/wine/aarch64-windows/libarm64ecfex.dll"
test -s "$OUT/lib/wine/aarch64-windows/libwow64fex.dll"
cp "$WORK/LICENSE" "$OUT/share/licenses/fex/LICENSE"
cp "$ROOT/runtime/android/sources.json" "$OUT/share/licenses/fex/sources.json"
