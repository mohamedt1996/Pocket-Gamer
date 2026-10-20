#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
: "${LLVM_MINGW:?}"
export PATH="$LLVM_MINGW/bin:$PATH"
OUT="$ROOT/.android-out/graphics"
for component in dxvk vkd3d; do
  SRC="$ROOT/.android-work/$component"
  python3 "$ROOT/runtime/android/fetch.py" "$component" "$SRC"
  for arch in x64 x86; do
    triple=x86_64; family=x86_64
    if [ "$arch" = x86 ]; then triple=i686; family=x86; fi
    cross="$SRC/madeira-$arch.ini"
    cat > "$cross" <<CROSS
[binaries]
c = '$triple-w64-mingw32-clang'
cpp = '$triple-w64-mingw32-clang++'
ar = '$triple-w64-mingw32-ar'
strip = '$triple-w64-mingw32-strip'
windres = '$triple-w64-mingw32-windres'
widl-mingw-tools-fallback = '$triple-w64-mingw32-widl'
[properties]
needs_exe_wrapper = true
[host_machine]
system = 'windows'
cpu_family = '$family'
cpu = '$family'
endian = 'little'
CROSS
    meson setup "$SRC/build-$arch" "$SRC" --cross-file "$cross" \
      --buildtype release --prefix "$OUT/$component" --bindir "$arch" --libdir "$arch"
    ninja -C "$SRC/build-$arch" -j2 install
  done
  mkdir -p "$OUT/share/licenses/$component"
  find "$SRC" -maxdepth 1 -type f \( -iname '*license*' -o -iname '*copying*' \) -exec cp '{}' "$OUT/share/licenses/$component/" \;
done
for arch in x64 x86; do
  for dll in d3d9 d3d11 dxgi; do test -s "$OUT/dxvk/$arch/$dll.dll"; done
  test -s "$OUT/vkd3d/$arch/d3d12.dll"
done
for arch in x64 x86; do
  triple=x86_64; [ "$arch" = x86 ] && triple=i686
  mkdir -p "$OUT/smoke/$arch"
  "$triple-w64-mingw32-clang" -O2 "$ROOT/runtime/android/tests/hello.c" -o "$OUT/smoke/$arch/hello.exe" -luser32
  "$triple-w64-mingw32-clang" -O2 "$ROOT/runtime/android/tests/d3d11.c" -o "$OUT/smoke/$arch/d3d11.exe" -ld3d11 -ldxgi -ldxguid -luser32
  "$triple-w64-mingw32-clang" -O2 "$ROOT/runtime/android/tests/d3d12-device.c" -o "$OUT/smoke/$arch/d3d12-device.exe" -ld3d12 -ldxguid -luser32
 done
