#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Windows-side helpers the app installs into its Wine prefixes:
#   addons/dock/dockhost.exe      Madeira Dock's host (willfaust/madeira-dock, pinned)
#   addons/xinput/{x64,x86}/      the XInput bridge (addons/xinput in this repo)
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
: "${LLVM_MINGW:?}"
OUT="$ROOT/.android-out/addons"
rm -rf "$OUT"; mkdir -p "$OUT/addons/dock" "$OUT/addons/xinput/x64" "$OUT/addons/xinput/x86" "$OUT/share/licenses/madeira-dock"
SRC="$ROOT/.android-work/madeira-dock"
python3 "$ROOT/runtime/android/fetch.py" madeira-dock "$SRC" --no-submodules
(
  cd "$SRC"
  "$LLVM_MINGW/bin/x86_64-w64-mingw32-clang" -std=c11 -O2 -Wall -Wextra -Werror -Wno-cast-function-type \
    -static -Wl,--strip-all -o "$OUT/addons/dock/dockhost.exe" \
    src/main.c src/probe.c src/session.c src/launch.c src/validation.c src/auth.c src/client_layout.c src/scm.c -ladvapi32
  cp LICENSE LICENSE-EXCEPTION.md COPYING THIRD-PARTY-NOTICES.md "$OUT/share/licenses/madeira-dock/"
  cp notices/*.txt "$OUT/share/licenses/madeira-dock/"
)
X="$ROOT/runtime/android/addons/xinput"
for arch in x64 x86; do
  triple=x86_64; [ "$arch" = x86 ] && triple=i686
  "$LLVM_MINGW/bin/$triple-w64-mingw32-clang" -std=c11 -O2 -Wall -Werror -shared -static -Wl,--strip-all \
    -o "$OUT/addons/xinput/$arch/xinput_pocket.dll" "$X/xinput_pocket.c" "$X/xinput_pocket.def"
done
ls -l "$OUT/addons/dock" "$OUT/addons/xinput"/*
