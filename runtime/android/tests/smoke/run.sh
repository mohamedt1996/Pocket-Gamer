#!/usr/bin/env bash
# Runs a built APK's Windows runtime on an arm64 Linux host with Android's own
# bionic libc and linker (termux-env.py), the way the app runs it on a phone:
#   1. unpack madeira-runtime.zip to the app's real path and mark it executable
#      (PortRuntime.installBundled);
#   2. seed a prefix like WineLaunch.seedRegistry and use WineLaunch's
#      environment, without LD_LIBRARY_PATH: the app's first Wine process is
#      loaded with System.load, where only the libraries' RUNPATH applies;
#   3. wineboot, then CPU/OS checks through FEX (x64 = ARM64EC, x86 = WoW64),
#      then D3D11 (DXVK) and D3D12 (VKD3D) device creation on lavapipe.
# Headless: the graphics driver is Wine's null driver, since wineandroid.drv
# needs the app's Java side.
#
# usage: run.sh APK TESTS_DIR LOG_DIR
# Prerequisite: Android userspace at /system (see the CI job).
set -uo pipefail
APK=$(realpath "$1"); TESTS=$(realpath "$2"); LOGS=$(realpath -m "$3")
APP=/data/data/org.pocketarcade.madeira/files
RT="$APP/madeira-runtime-v2"
GAME="$APP/games/smoke"
PREFIX="$GAME/prefix"
TERMUX=/data/data/com.termux/files/usr
mkdir -p "$LOGS"
failed=0

annotate() { # level title file
  local body
  body=$(tail -c 6000 "$3" 2>/dev/null | sed 's/%/%25/g' | sed ':a;N;$!ba;s/\r/%0D/g;s/\n/%0A/g')
  echo "::$1 title=$2::$body"
}
step() { # name timeout command...
  local name=$1 limit=$2; shift 2
  local log="$LOGS/$name.log" start=$SECONDS
  echo "== $name: $*"
  timeout --kill-after=20 "$limit" "$@" > "$log" 2>&1
  local rc=$?
  echo "exit=$rc seconds=$((SECONDS-start))" >> "$log"
  if [ $rc -eq 0 ]; then annotate notice "$name passed" "$log"; else annotate error "$name failed (exit $rc)" "$log"; failed=1; fi
  return $rc
}

# 1. The runtime as PortRuntime installs it.
rm -rf "$APP"; mkdir -p "$RT" "$GAME/tmp"
unzip -q -o "$APK" assets/madeira-runtime.zip -d "$LOGS/apk"
unzip -q -o "$LOGS/apk/assets/madeira-runtime.zip" -d "$RT"
rm -rf "$LOGS/apk"
chmod -R u+x "$RT"
python3 -c "import json,sys;m=json.load(open(sys.argv[1]));print('runtime',m['wine'],len(m['files']),'files')" "$RT/runtime.json"

# Bionic must resolve every runtime library by itself: any NEEDED entry that only
# the old Termux RUNPATH could satisfy shows up here, not on a phone.
{
  for f in "$RT"/lib/wine/aarch64-unix/*.so "$RT"/bin/wine "$RT"/bin/wineserver; do
    readelf -dW "$f" | grep -E 'RUNPATH|RPATH' | sed "s|^|$(basename "$f"): |"
  done
} > "$LOGS/runpath.log" 2>&1
if grep -q com.termux "$LOGS/runpath.log"; then annotate error "Termux RUNPATH still in runtime" "$LOGS/runpath.log"; failed=1; fi

# 2. Prefix and environment, as WineLaunch writes them (Graphics: null for headless).
mkdir -p "$PREFIX/drive_c" "$PREFIX/dosdevices"
ln -sfn ../drive_c "$PREFIX/dosdevices/c:"; ln -sfn / "$PREFIX/dosdevices/z:"
printf 'WINE REGISTRY Version 2\n;; All keys relative to \\\\Machine\n\n#arch=win64\n\n[Software\\\\Microsoft\\\\Wow64\\\\amd64]\n@="libarm64ecfex.dll"\n\n[Software\\\\Microsoft\\\\Wow64\\\\x86]\n@="libwow64fex.dll"\n\n' > "$PREFIX/system.reg"
printf 'WINE REGISTRY Version 2\n\n#arch=win64\n\n[Software\\\\Wine\\\\Drivers]\n"Audio"=""\n"Graphics"="null"\n\n' > "$PREFIX/user.reg"
# env -i: nothing from the host leaks in, LD_LIBRARY_PATH included.
WINEENV=(
  HOME="$GAME" TMPDIR="$GAME/tmp" WINEPREFIX="$PREFIX"
  WINELOADER="$RT/lib/wine/aarch64-unix/wine" WINESERVER="$RT/bin/wineserver" WINEDLLPATH="$RT/lib/wine"
  WINEDEBUG="+timestamp,+pid,+seh,err+all,warn+module" PATH="$RT/bin:/system/bin"
  LC_ALL=C.UTF-8 LANG=C.UTF-8 WINEESYNC=0 WINEFSYNC=0 WINENTSYNC=0
  XDG_CACHE_HOME="$APP/wine-cache" ANDROID_ROOT=/system ANDROID_DATA=/data
  WINEDLLOVERRIDES="mscoree,mshtml=d"
)
WINE=(env -i "${WINEENV[@]}")

step loader-version 60 "${WINE[@]}" "$RT/lib/wine/aarch64-unix/wine" --version
step wineboot 1500 "${WINE[@]}" "$RT/lib/wine/aarch64-unix/wine" wineboot --init
env -i "${WINEENV[@]}" timeout 120 "$RT/bin/wineserver" -w || true
grep -q '^#arch=win64' "$PREFIX/system.reg" || { echo "::error title=prefix::system.reg lost #arch=win64"; failed=1; }
cp "$PREFIX/system.reg" "$PREFIX/user.reg" "$LOGS/" 2>/dev/null

# 3. CPU and OS through FEX.
mkdir -p "$PREFIX/drive_c/smoke"
cp "$TESTS"/*.exe "$PREFIX/drive_c/smoke/"
cd "$PREFIX/drive_c/smoke"
step cli-x64 600 "${WINE[@]}" "$RT/lib/wine/aarch64-unix/wine" 'C:\smoke\cli-x86_64.exe'
step cli-x86 600 "${WINE[@]}" "$RT/lib/wine/aarch64-unix/wine" 'C:\smoke\cli-i686.exe'

# Graphics devices: DXVK and VKD3D installed into the prefix as WineLaunch does,
# with lavapipe behind Android's libvulkan.so name.
SYS32="$PREFIX/drive_c/windows/system32"; WOW="$PREFIX/drive_c/windows/syswow64"
cp "$RT"/graphics/dxvk/x64/*.dll "$RT"/graphics/vkd3d/x64/*.dll "$SYS32/"
cp "$RT"/graphics/dxvk/x86/*.dll "$RT"/graphics/vkd3d/x86/*.dll "$WOW/"
ICD=$(ls "$TERMUX"/share/vulkan/icd.d/lvp_icd*.json 2>/dev/null | head -1)
GFX=(WINEDLLOVERRIDES="mscoree,mshtml=d;d3d8,d3d9,d3d10core,d3d11,dxgi=n,b;d3d12,d3d12core=n,b"
     VK_ICD_FILENAMES="$ICD" DXVK_LOG_LEVEL=info VKD3D_DEBUG=warn DXVK_LOG_PATH=none)
step devices-x64 600 "${WINE[@]}" "${GFX[@]}" "$RT/lib/wine/aarch64-unix/wine" 'C:\smoke\devices-x86_64.exe'
step devices-x86 600 "${WINE[@]}" "${GFX[@]}" "$RT/lib/wine/aarch64-unix/wine" 'C:\smoke\devices-i686.exe'

env -i "${WINEENV[@]}" timeout 60 "$RT/bin/wineserver" -k || true
exit $failed
