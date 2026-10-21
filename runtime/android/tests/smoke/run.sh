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
step() { # name timeout command...   stdout -> NAME.out, stderr (Wine's log) -> NAME.log
  local name=$1 limit=$2; shift 2
  local out="$LOGS/$name.out" log="$LOGS/$name.log" sum="$LOGS/$name.summary" start=$SECONDS
  echo "== $name: $*"
  "$@" > "$out" 2> "$log" &
  local pid=$! rc i
  for ((i = 0; i < limit; i++)); do kill -0 $pid 2>/dev/null || break; sleep 1; done
  if kill -0 $pid 2>/dev/null; then
    hang_report > "$LOGS/$name.hang" 2>&1
    kill -9 $pid 2>/dev/null; wait $pid 2>/dev/null; rc=124
  else
    wait $pid; rc=$?
  fi
  # Windows test programs write <program>.txt beside themselves (out.h); judge by that file.
  local ok=$rc
  if [ -n "${RESULT:-}" ]; then
    grep -q '^RESULT PASS' "$RESULT" 2>/dev/null && ok=0 || ok=1
  fi
  {
    echo "exit=$rc seconds=$((SECONDS-start))"
    if [ -n "${RESULT:-}" ]; then cat "$RESULT" 2>/dev/null || echo "(no result file)"; else cat "$out"; fi
    [ $rc -eq 124 ] && { echo "--- hung: what each Wine thread waits on ---"; cat "$LOGS/$name.hang"; }
    echo "--- log (errors) ---"
    grep -E 'err:|wine:|DXVK|DXGI|D3D11|vkd3d|Vulkan|vulkan|info:  |warn:  |err:  |Fontconfig|not found|failed|Failed' "$log" \
      | grep -vE 'trace:seh|fixme:|_r_debug' | tail -n 60
  } > "$sum"
  cat "$sum"
  if [ $ok -eq 0 ]; then annotate notice "$name passed" "$sum"; else annotate error "$name failed (exit $rc)" "$sum"; failed=1; cleanup; fi
  RESULT=
  return $ok
}
hang_report() { # every thread of every runtime process: state, kernel wait channel, syscall
  local p tid
  for p in $(pgrep -f "$RT|\.exe" 2>/dev/null); do
    echo "pid $p: $(tr '\0' ' ' < /proc/$p/cmdline 2>/dev/null | cut -c1-150)"
    for tid in $(ls /proc/$p/task 2>/dev/null); do
      echo "  tid $tid $(awk '{print $3}' /proc/$p/task/$tid/stat 2>/dev/null) wchan=$(cat /proc/$p/task/$tid/wchan 2>/dev/null) syscall=$(sudo cat /proc/$p/task/$tid/syscall 2>/dev/null | cut -d' ' -f1-3)"
    done
  done | head -60
  # Native stack of each hung test program (gdb reads bionic's and the libraries' symbols).
  for p in $(pgrep -f 'smoke\\' 2>/dev/null); do
    echo "=== gdb $p"
    sudo timeout 90 gdb -p "$p" -batch -nx -ex "set pagination off" -ex "thread apply all bt 25" 2>&1 \
      | grep -E '^#|^Thread' | head -60
  done
}
cleanup() { # a failed or hung step must not leave Wine processes behind for the next one
  [ -n "${WINE+x}" ] && timeout 30 "${WINE[@]}" "$RT/bin/wineserver" -k > /dev/null 2>&1
  pkill -9 -f "$RT" > /dev/null 2>&1
  sleep 1
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
  WINEDEBUG="+timestamp,+pid,warn+module" PATH="$RT/bin:/system/bin"
  LC_ALL=C.UTF-8 LANG=C.UTF-8 WINEESYNC=0 WINEFSYNC=0 WINENTSYNC=0
  XDG_CACHE_HOME="$APP/wine-cache" ANDROID_ROOT=/system ANDROID_DATA=/data
  FONTCONFIG_FILE="$RT/etc/fonts/fonts.conf"
  WINEDLLOVERRIDES="mscoree,mshtml=d"
)
WINE=(env -i "${WINEENV[@]}")

step loader-version 60 "${WINE[@]}" "$RT/lib/wine/aarch64-unix/wine" --version
step wineboot 900 "${WINE[@]}" "$RT/lib/wine/aarch64-unix/wine" wineboot --init
env -i "${WINEENV[@]}" timeout 120 "$RT/bin/wineserver" -w || true
grep -q '^#arch=win64' "$PREFIX/system.reg" || { echo "::error title=prefix::system.reg lost #arch=win64"; failed=1; }
cp "$PREFIX/system.reg" "$PREFIX/user.reg" "$LOGS/" 2>/dev/null

# The app's first Wine process is explorer's desktop, which games start next to;
# keep one running here too (null driver instead of android).
"${WINE[@]}" "$RT/lib/wine/aarch64-unix/wine" explorer.exe /desktop=shell,,null > "$LOGS/desktop.log" 2>&1 &
sleep 5

# 3. CPU and OS through FEX.
mkdir -p "$PREFIX/drive_c/smoke"
cp "$TESTS"/*.exe "$PREFIX/drive_c/smoke/"
cd "$PREFIX/drive_c/smoke"
SMOKE="$PREFIX/drive_c/smoke"
LOADER="$RT/lib/wine/aarch64-unix/wine"
RESULT="$SMOKE/cli-x86_64.txt" step cli-x64 600 "${WINE[@]}" "$LOADER" 'C:\smoke\cli-x86_64.exe'
RESULT="$SMOKE/cli-i686.txt" step cli-x86 600 "${WINE[@]}" "$LOADER" 'C:\smoke\cli-i686.exe'

# Graphics devices: DXVK and VKD3D installed into the prefix as WineLaunch does,
# with lavapipe behind Android's libvulkan.so name.
SYS32="$PREFIX/drive_c/windows/system32"; WOW="$PREFIX/drive_c/windows/syswow64"
cp "$RT"/graphics/dxvk/x64/*.dll "$RT"/graphics/vkd3d/x64/*.dll "$SYS32/"
cp "$RT"/graphics/dxvk/x86/*.dll "$RT"/graphics/vkd3d/x86/*.dll "$WOW/"
ICD=$(ls "$TERMUX"/share/vulkan/icd.d/lvp_icd*.json 2>/dev/null | head -1)
GFX=(WINEDLLOVERRIDES="mscoree,mshtml=d;d3d8,d3d9,d3d10core,d3d11,dxgi=n,b;d3d12,d3d12core=n,b"
     VK_ICD_FILENAMES="$ICD" DXVK_LOG_LEVEL=info VKD3D_DEBUG=warn DXVK_LOG_PATH=none)
# Plain Vulkan first (winevulkan + driver), then DXVK/VKD3D on top of it.
RESULT="$SMOKE/vk-i686.txt" step vk-x86 90 "${WINE[@]}" "${GFX[@]}" "$LOADER" 'C:\smoke\vk-i686.exe'
RESULT="$SMOKE/vk-x86_64.txt" step vk-x64 90 "${WINE[@]}" "${GFX[@]}" WINEDEBUG="+timestamp,+pid,+tid,warn+module,+vulkan" "$LOADER" 'C:\smoke\vk-x86_64.exe'
RESULT="$SMOKE/devices-i686.txt" step devices-x86 150 "${WINE[@]}" "${GFX[@]}" "$LOADER" 'C:\smoke\devices-i686.exe'
RESULT="$SMOKE/devices-x86_64.txt" step devices-x64 150 "${WINE[@]}" "${GFX[@]}" "$LOADER" 'C:\smoke\devices-x86_64.exe'

cleanup
exit $failed
