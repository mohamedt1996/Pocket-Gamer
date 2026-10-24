#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Rebuild the optional ARM64 Android Box64 asset from pinned upstream source.
set -euo pipefail

if [ "$#" -ne 3 ] && { [ "$#" -ne 4 ] || [ "$4" != --box32 ]; }; then
  echo "usage: $0 BOX64_SOURCE ANDROID_NDK OUTPUT_ZST [--box32]" >&2
  exit 2
fi
source_dir=$(realpath "$1")
ndk_dir=$(realpath "$2")
output=$(realpath -m "$3")
revision=420eda69ff51ab5d5a7cbc3dee2e70433be3411d
if [ "$(git -C "$source_dir" rev-parse HEAD)" != "$revision" ]; then
  echo "Box64 source must be at $revision" >&2
  exit 1
fi
cmake_bin="${ANDROID_CMAKE:-cmake}"
box32=OFF
if [ "$#" -eq 4 ]; then box32=ON; fi
build_dir=$(mktemp -d)
trap 'rm -rf "$build_dir"' EXIT
"$cmake_bin" -S "$source_dir" -B "$build_dir" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ndk_dir/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-31 \
  -DANDROID=ON -DARM64=ON -DBOX32="$box32" -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_EXE_LINKER_FLAGS=-Wl,-z,max-page-size=16384
"$cmake_bin" --build "$build_dir" --target box64 --parallel 4
"$ndk_dir/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" \
  --strip-unneeded -o "$build_dir/box64-stripped" "$build_dir/box64"
python3 - "$build_dir/box64-stripped" <<'PY'
import struct
import sys

data = open(sys.argv[1], 'rb').read()
assert data[:6] == b'\x7fELF\x02\x01'
assert struct.unpack_from('<H', data, 18)[0] == 183
offset = struct.unpack_from('<Q', data, 32)[0]
size, count = struct.unpack_from('<HH', data, 54)
loads = []
for index in range(count):
    header = offset + size * index
    if struct.unpack_from('<I', data, header)[0] == 1:
        _, file_offset, virtual_address = struct.unpack_from('<I4xQQ', data, header)
        alignment = struct.unpack_from('<Q', data, header + 48)[0]
        loads.append((file_offset, virtual_address, alignment))
assert loads and all(alignment >= 16384 and file_offset % 16384 == virtual_address % 16384
                     for file_offset, virtual_address, alignment in loads)
PY
mkdir -p "$(dirname "$output")"
zstd -9 -T2 -f "$build_dir/box64-stripped" -o "$output"
sha256sum "$build_dir/box64-stripped" "$output"
if [ "$box32" = ON ]; then
  python3 - "$build_dir/box64-stripped" "$output" "$revision" <<'PY'
import hashlib, json, pathlib, sys
binary, archive = map(pathlib.Path, sys.argv[1:3])
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
manifest = {'schema': 1, 'asset': archive.name, 'sourceCommit': sys.argv[3],
            'box32': True, 'binarySha256': sha(binary), 'archiveSha256': sha(archive)}
archive.with_suffix('.json').write_text(json.dumps(manifest, sort_keys=True) + '\n')
PY
fi
