#!/usr/bin/env bash
# Build Madeira's Wine (willfaust/wine, LGPL branch) for Android arm64 with
# ARM64EC, using the same toolchains and Termux libraries as build-wine.sh.
# Experimental: see runtime/android/madeira/README.md.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
HERE="$ROOT/runtime/android"
# Mirror Madeira's layout: its Wine includes ../../../../build/madeira_cfg.h,
# i.e. madeira/build/ next to madeira/wine/.
MADEIRA="$ROOT/.android-work/madeira"
SRC="$MADEIRA/wine"
OUT="$ROOT/.android-out/wine-madeira"
JOBS="${JOBS:-2}"

MINGW="${LLVM_MINGW:-$HOME/toolchains/llvm-mingw-20250920-ucrt-ubuntu-22.04-x86_64}"
NDK="${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/27.3.13750724}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
TARGET=aarch64-linux-android28
DEPS="${TERMUX_PREFIX:-$HOME/termuxfs/aarch64/data/data/com.termux/files/usr}"
INSTALL="$MADEIRA/install"

# Same ARM64EC import-tool guard as build-wine.sh.
export DLLTOOL="$MINGW/bin/llvm-dlltool"
test -x "$DLLTOOL" || { echo "::error::Missing pinned llvm-mingw dlltool: $DLLTOOL"; exit 1; }
dlltool_help=$("$DLLTOOL" --help 2>&1 || true)
if ! grep -Eiq '(^|[[:space:],])arm64ec([[:space:],]|$)' <<< "$dlltool_help"; then
  echo "::error::Pinned llvm-mingw dlltool lacks ARM64EC support"; exit 1
fi
test -x "$TOOLCHAIN/$TARGET-clang" || { echo "::error::Missing NDK clang: $TOOLCHAIN/$TARGET-clang"; exit 1; }
test -d "$DEPS/lib" || { echo "::error::Missing Termux libraries: $DEPS (run setup-tools.sh wine-madeira)"; exit 1; }

# 1. Sources: Madeira's Wine plus the DirectAudio (AAudio) driver.
python3 "$HERE/fetch.py" madeira-wine "$SRC"
# Madeira's runtime switches header (GPL-3.0-or-later, from the same Madeira
# commit the iOS build pins); ntdll's unix side includes it on every platform.
python3 "$HERE/fetch.py" madeira "$ROOT/.android-work/madeira-repo" --no-submodules
mkdir -p "$MADEIRA/build"
git -C "$ROOT/.android-work/madeira-repo" show HEAD:build/madeira_cfg.h > "$MADEIRA/build/madeira_cfg.h"
python3 "$HERE/fetch.py" directaudio "$ROOT/.android-work/directaudio"
git -C "$SRC" reset -q --hard
git -C "$SRC" clean -qfdx
mkdir -p "$SRC/dlls/winedirectaudio.drv"
git -C "$ROOT/.android-work/directaudio" archive HEAD | tar -x -C "$SRC/dlls/winedirectaudio.drv"

# 2. Android port patches, fail-hard and in order, then the Pocket-Gamer
#    integration shared with the Proton build.
for patch in "$HERE"/madeira/patches/*.patch; do
  echo "Applying $(basename "$patch")"
  git -C "$SRC" apply --whitespace=nowarn "$patch" || { echo "::error::$(basename "$patch") does not apply"; exit 1; }
done
python3 "$HERE/patch-wine.py" "$SRC" --madeira

cd "$SRC"
autoconf -f
python3 dlls/winevulkan/make_vulkan

# 3. Host tools (winebuild, widl, nls) for the cross build.
mkdir -p wine-tools
# 64-bit host tools; sfnt2fon needs the host FreeType (libfreetype-dev) for fonts/.
( cd wine-tools && ../configure --enable-win64 --without-x --without-gstreamer --without-vulkan --without-wayland \
    --with-freetype --without-fontconfig && make -j"$JOBS" __tooldeps__ nls/all )

# 4. Cross build. Flags mirror the Proton fork's build-step-arm64ec.sh, minus
#    the X11, Wayland, PulseAudio and ALSA paths the app does not use.
export PATH="$MINGW/bin:$PATH"
export CC="$TOOLCHAIN/$TARGET-clang" CXX="$TOOLCHAIN/$TARGET-clang++"
export AR="$TOOLCHAIN/llvm-ar" RANLIB="$TOOLCHAIN/llvm-ranlib" STRIP="$TOOLCHAIN/llvm-strip"
export PKG_CONFIG_LIBDIR="$DEPS/lib/pkgconfig:$DEPS/share/pkgconfig"
export CPPFLAGS="-I$DEPS/include"
export CFLAGS="-g0 -O2 -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES -Wno-declaration-after-statement"
export CXXFLAGS="$CFLAGS" CROSSCFLAGS="-g0 -O2"
export LDFLAGS="-L$DEPS/lib -Wl,-z,max-page-size=16384"
export FREETYPE_CFLAGS="-I$DEPS/include/freetype2"
export FONTCONFIG_LIBS="-L$DEPS/lib -lfontconfig -lfreetype -lexpat"
export SDL2_CFLAGS="-I$DEPS/include/SDL2" SDL2_LIBS="-L$DEPS/lib -lSDL2"
export GSTREAMER_CFLAGS="-I$DEPS/include/gstreamer-1.0 -I$DEPS/include/glib-2.0 -I$DEPS/lib/glib-2.0/include"
export GSTREAMER_LIBS="-L$DEPS/lib -lgstapp-1.0 -lgstvideo-1.0 -lgstaudio-1.0 -lgsttag-1.0 -lgstbase-1.0 -lgstreamer-1.0 -lgobject-2.0 -lglib-2.0"
export FFMPEG_CFLAGS="-I$DEPS/include" FFMPEG_LIBS="-L$DEPS/lib -lavutil -lavcodec -lavformat"

./configure \
  --enable-archs=arm64ec,aarch64,i386 --host="$TARGET" \
  --prefix="$INSTALL" --exec-prefix="$INSTALL" --bindir="$INSTALL/bin" --libdir="$INSTALL/lib" \
  --with-mingw=clang --with-wine-tools=./wine-tools \
  --enable-win64 --disable-win16 --enable-nls --disable-tests \
  --enable-wineandroid_drv=yes --with-aaudio \
  --without-x --without-wayland --without-pulse --without-alsa --without-oss \
  --with-vulkan --with-opengl --with-freetype --with-fontconfig --with-gnutls \
  --with-ffmpeg --with-gstreamer --with-sdl --with-pthread \
  --without-capi --without-coreaudio --without-cups --without-dbus --without-gphoto \
  --without-gssapi --without-krb5 --without-netapi --without-opencl --without-pcap \
  --without-pcsclite --without-sane --without-udev --without-unwind --without-usb \
  --without-v4l2 --without-gettext --with-gettextpo=no --without-inotify

# Configure succeeding without these would ship a runtime that cannot draw or play sound.
disabled=" $(sed -n 's/^DISABLED_SUBDIRS *= *//p' Makefile) "
for module in dlls/wineandroid.drv dlls/winedirectaudio.drv dlls/winevulkan; do
  case "$disabled" in *" $module "*) echo "::error::configure disabled $module"; exit 1;; esac
done

make -j"$JOBS"
rm -rf "$INSTALL"
make install

# 5. Stage in the same layout as build-wine.sh's output.
rm -rf "$OUT"
mkdir -p "$OUT/bin" "$OUT/lib" "$OUT/share"
cp -a "$INSTALL/bin/." "$OUT/bin/"
cp -a "$INSTALL/lib/wine" "$OUT/lib/"
cp -a "$INSTALL/share/wine" "$OUT/share/"
find "$OUT/lib" "$OUT/bin" -type f \( -name '*.dll' -o -name '*.exe' -o -name '*.drv' -o -name '*.so' \) \
  -exec "$STRIP" --strip-all '{}' \; 2>/dev/null || true
# The app starts Wine through bin/wine (WINELOADER), like build-wine.sh's output.
ln -sf ../lib/wine/aarch64-unix/wine "$OUT/bin/wine"
ln -sf ../lib/wine/aarch64-unix/wine-preloader "$OUT/bin/wine-preloader"
test -x "$OUT/bin/wine" && test -x "$OUT/bin/wineserver"
test -s "$OUT/lib/wine/aarch64-unix/ntdll.so"
test -s "$OUT/lib/wine/aarch64-unix/wineandroid.so"
test -s "$OUT/lib/wine/aarch64-windows/wineandroid.drv"
test -s "$OUT/lib/wine/aarch64-windows/winedirectaudio.drv"

mkdir -p "$OUT/integration" "$OUT/share/licenses/wine" "$OUT/share/licenses/directaudio"
cp dlls/wineandroid.drv/WineActivity.java "$OUT/integration/"
cp COPYING.LIB LICENSE LICENSE-MADEIRA.md "$OUT/share/licenses/wine/"
mkdir -p "$OUT/share/licenses/madeira"
cp "$ROOT/.android-work/madeira-repo/COPYING" "$MADEIRA/build/madeira_cfg.h" "$OUT/share/licenses/madeira/"
cp dlls/winedirectaudio.drv/COPYING dlls/winedirectaudio.drv/NOTICE "$OUT/share/licenses/directaudio/"
git diff --binary -- . ':!configure' > "$OUT/share/licenses/wine/madeira-android.patch"
cp "$HERE/sources.json" "$OUT/share/licenses/wine/"
python3 "$HERE/collect-libs.py" "$DEPS" "$OUT"
echo "Madeira Wine for Android staged in $OUT"
