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
HERE=$(cd "$(dirname "$0")" && pwd)
APK=$(realpath "$1"); TESTS=$(realpath "$2"); LOGS=$(realpath -m "$3")
APP=/data/data/org.pocketarcade.madeira/files
RT="$APP/madeira-runtime-v2"
GAME="$APP/games/smoke"
PREFIX="$GAME/prefix"
TERMUX=/data/data/com.termux/files/usr
mkdir -p "$LOGS"
failed=0

annotations=0
annotate() { # level title file
  local body
  # GitHub keeps 10 annotations of a level per step: the last one is the run's summary.
  annotations=$((annotations + 1))
  if [ "$1" = error ] && [ $annotations -gt 9 ] && [ "$2" != "Runtime test failed" ]; then return; fi
  # GitHub keeps the first ~4 KB of a message: files put what matters first.
  body=$(head -c 3900 "$3" 2>/dev/null | sed 's/%/%25/g' | sed ':a;N;$!ba;s/\r/%0D/g;s/\n/%0A/g')
  echo "::$1 title=$2::$body"
}
step() { # name timeout command...   stdout -> NAME.out, stderr (Wine's log) -> NAME.log
  local name=$1 limit=$2; shift 2
  local out="$LOGS/$name.out" log="$LOGS/$name.log" sum="$LOGS/$name.summary" start=$SECONDS
  echo "== $name: $*"
  [ -n "${RESULT:-}" ] && rm -f "$RESULT"   # a program run twice must not pass on the old file
  "$@" > "$out" 2> "$log" &
  local pid=$! rc i
  # Through the desktop, the launcher (explorer) may return at once while the program
  # runs on, or stay after it: with a result file, wait for its RESULT line instead.
  local done_=0
  for ((i = 0; i < limit; i++)); do
    if [ -n "${RESULT:-}" ]; then
      grep -q '^RESULT' "$RESULT" 2>/dev/null && { done_=1; break; }
      # The program is gone without a result: it crashed. No point waiting.
      local exe; exe=$(basename "$RESULT" .txt)
      if [ $i -gt 5 ] && ! kill -0 $pid 2>/dev/null && ! pgrep -f "smoke.$exe" > /dev/null; then done_=2; break; fi
    else
      kill -0 $pid 2>/dev/null || { done_=1; break; }
    fi
    sleep 1
  done
  if [ $done_ -ge 1 ] && [ -n "${RESULT:-}" ]; then sleep 1; kill $pid 2>/dev/null; fi
  if [ $done_ -eq 0 ]; then
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
    # First, since annotations keep only the start of a long message.
    [ $rc -eq 124 ] && { echo "--- hung: busy threads and stacks ---"; grep -v 'anon_pipe_read\|ep_poll\|hrtimer_nanosleep\|skb_wait\|poll_schedule\|do_sys_poll' "$LOGS/$name.hang"; }
    [ $done_ -eq 2 ] && { echo "--- program exited without a result: end of its log ---"; grep -v 'trace:\|fixme:\|^info:\|autostart' "$log" | tail -n 25; }
    echo "--- log (errors) ---"
    grep -E 'err:|wine:|DXVK|DXGI|D3D11|vkd3d|Vulkan|vulkan|info:  |warn:  |err:  |Fontconfig|not found|failed|Failed' "$log" \
      | grep -vE 'trace:seh|fixme:|_r_debug|autostart|egl_init|LdrGetProcedureAddress' | tail -n 30
  } > "$sum"
  cat "$sum"
  # GitHub keeps only 10 annotations of a kind per step: details for failures only,
  # and one summary line for the whole run at the end.
  if [ $ok -eq 0 ]; then echo "PASS $name ($((SECONDS-start))s)" >> "$LOGS/summary.txt"
  else echo "FAIL $name (exit $rc)" >> "$LOGS/summary.txt"; annotate error "$name failed (exit $rc)" "$sum"; failed=1; cleanup; fi
  RESULT=
  return $ok
}
hang_report() {
  # Native stacks first (annotations keep the start): each hung test program and
  # services.exe, which every program's start waits on.
  local p tid n
  for p in $(pgrep -f 'smoke\\' 2>/dev/null) $(pgrep -f 'system32.services\.exe' 2>/dev/null); do
    echo "=== stacks $p: $(tr '\0' ' ' < /proc/$p/cmdline 2>/dev/null | cut -c1-60)"
    sudo STACK_COMPACT=1 STACK_SYMBOLS="${STACK_SYMBOLS:-}" timeout 150 python3 "$HERE/stack.py" "$p" 2>&1 | head -30
  done
  # Where each test program's threads are over a few seconds: a pc that keeps moving is
  # slow work or a spin, one that never moves is a wait.
  for p in $(pgrep -f 'smoke\\' 2>/dev/null); do
    for n in 1 2 3; do
      echo "sample $n pid $p: $(sudo timeout 30 gdb -p "$p" -batch -nx -ex 'thread apply all p/x $pc' 2>/dev/null | grep -E '^\$' | tr '\n' ' ')"
      sleep 2
    done
  done
  # Every thread of every runtime process: state, kernel wait channel, syscall.
  for p in $(pgrep -f "$RT|\.exe" 2>/dev/null); do
    echo "pid $p: $(tr '\0' ' ' < /proc/$p/cmdline 2>/dev/null | cut -c1-80)"
    for tid in $(ls /proc/$p/task 2>/dev/null); do
      echo "  tid $tid $(awk '{print $3}' /proc/$p/task/$tid/stat 2>/dev/null) wchan=$(cat /proc/$p/task/$tid/wchan 2>/dev/null) syscall=$(sudo cat /proc/$p/task/$tid/syscall 2>/dev/null | cut -d' ' -f1-3)"
    done
  done | head -60
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
# A component to test in place of the APK's own (e.g. another FEX build).
if [ -n "${RUNTIME_OVERLAY:-}" ]; then
  cp -a "$RUNTIME_OVERLAY/lib/." "$RT/lib/"
  echo "overlaid $(find "$RUNTIME_OVERLAY/lib" -type f | wc -l) files from $RUNTIME_OVERLAY" >> "$LOGS/summary.txt"
fi
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
printf 'WINE REGISTRY Version 2\n\n#arch=win64\n\n[Software\\\\Wine\\\\Drivers]\n"Audio"="directaudio"\n"Graphics"="null"\n\n' > "$PREFIX/user.reg"
# env -i: nothing from the host leaks in, LD_LIBRARY_PATH included.
WINEENV=(
  HOME="$GAME" TMPDIR="$GAME/tmp" WINEPREFIX="$PREFIX"
  WINELOADER="$RT/lib/wine/aarch64-unix/wine" WINESERVER="$RT/bin/wineserver" WINEDLLPATH="$RT/lib/wine"
  WINEDEBUG="+timestamp,+pid,warn+module" PATH="$RT/bin:/system/bin"
  LC_ALL=C.UTF-8 LANG=C.UTF-8 WINEESYNC=0 WINEFSYNC=0 WINENTSYNC=0
  XDG_CACHE_HOME="$APP/wine-cache" ANDROID_ROOT=/system ANDROID_DATA=/data
  FONTCONFIG_FILE="$RT/etc/fonts/fonts.conf"
  FAKE_AAUDIO_STATS="$LOGS/aaudio-stats.txt"
  VK_ICD_FILENAMES="$(ls "$TERMUX"/share/vulkan/icd.d/lvp_icd*.json 2>/dev/null | head -1)"
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
# WineActivity starts games as: explorer.exe /desktop=shell,,android <command line>
DESKTOP=(explorer.exe /desktop=shell,,null)
RESULT="$SMOKE/cli-x86_64.txt" step cli-x64 600 "${WINE[@]}" "$LOADER" "${DESKTOP[@]}" 'C:\smoke\cli-x86_64.exe'
RESULT="$SMOKE/cli-i686.txt" step cli-x86 600 "${WINE[@]}" "$LOADER" "${DESKTOP[@]}" 'C:\smoke\cli-i686.exe'

# Graphics devices: DXVK and VKD3D installed into the prefix as WineLaunch does,
# with lavapipe behind Android's libvulkan.so name.
SYS32="$PREFIX/drive_c/windows/system32"; WOW="$PREFIX/drive_c/windows/syswow64"
cp "$RT"/graphics/dxvk/x64/*.dll "$RT"/graphics/vkd3d/x64/*.dll "$SYS32/"
cp "$RT"/graphics/dxvk/x86/*.dll "$RT"/graphics/vkd3d/x86/*.dll "$WOW/"
GFX=(WINEDLLOVERRIDES="mscoree,mshtml=d;d3d8,d3d9,d3d10core,d3d11,dxgi=n,b;d3d12,d3d12core=n,b"
     DXVK_LOG_LEVEL=info VKD3D_DEBUG=warn DXVK_LOG_PATH=none)
# Sound: WASAPI -> mmdevapi -> winedirectaudio -> AAudio (the stand-in measures what arrives).
audio_step() { # name exe
  : > "$LOGS/aaudio-stats.txt"
  RESULT="$SMOKE/$2.txt" step "$1" 120 "${WINE[@]}" "$LOADER" "${DESKTOP[@]}" "C:\\smoke\\$2.exe"
  sleep 2
  # Progress lines come every 50 callbacks while AAudio pulls; enough frames and a loud
  # enough peak mean the program's tone really arrived.
  if ! awk '{for(i=1;i<=NF;i++){split($i,kv,"="); if(kv[1]=="frames"&&kv[2]>f)f=kv[2]; if(kv[1]=="peak"&&kv[2]>p)p=kv[2]}} END{exit !(f>=24000 && p>=0.3)}' "$LOGS/aaudio-stats.txt"; then
    echo "FAIL $1-reached-aaudio" >> "$LOGS/summary.txt"; failed=1
    { echo "AAudio side:"; cat "$LOGS/aaudio-stats.txt"; } > "$LOGS/$1-aaudio.txt"
    annotate error "$1: the tone did not reach AAudio" "$LOGS/$1-aaudio.txt"
  else
    echo "PASS $1-reached-aaudio ($(grep -E '^(open format|close)' "$LOGS/aaudio-stats.txt" | tail -2 | tr '\n' ' '))" >> "$LOGS/summary.txt"
  fi
}
audio_step audio-x64 audio-x86_64
audio_step audio-x86 audio-i686

# Plain Vulkan first (winevulkan + driver), then DXVK/VKD3D on top of it.
RESULT="$SMOKE/vk-i686.txt" step vk-x86 90 "${WINE[@]}" "${GFX[@]}" "$LOADER" "${DESKTOP[@]}" 'C:\smoke\vk-i686.exe'
RESULT="$SMOKE/vk-x86_64.txt" step vk-x64 90 "${WINE[@]}" "${GFX[@]}" WINEDEBUG="+timestamp,+pid,+tid,warn+module,+vulkan" "$LOADER" "${DESKTOP[@]}" 'C:\smoke\vk-x86_64.exe'
# Regression check for the win32u Vulkan self-deadlock: started on its own, without the
# desktop, the program loads the display driver from inside Vulkan initialisation.
RESULT="$SMOKE/vk-x86_64.txt" step vk-x64-standalone 90 "${WINE[@]}" "${GFX[@]}" "$LOADER" 'C:\smoke\vk-x86_64.exe'
RESULT="$SMOKE/devices-i686.txt" step d3d11-x86 120 "${WINE[@]}" "${GFX[@]}" "$LOADER" "${DESKTOP[@]}" 'C:\smoke\devices-i686.exe' d3d11
RESULT="$SMOKE/devices-x86_64.txt" step d3d11-x64 120 "${WINE[@]}" "${GFX[@]}" "$LOADER" "${DESKTOP[@]}" 'C:\smoke\devices-x86_64.exe' d3d11
RESULT="$SMOKE/devices-i686.txt" step d3d12-x86 300 "${WINE[@]}" "${GFX[@]}" VKD3D_DEBUG=info "$LOADER" "${DESKTOP[@]}" 'C:\smoke\devices-i686.exe' d3d12
RESULT="$SMOKE/devices-x86_64.txt" step d3d12-x64 300 "${WINE[@]}" "${GFX[@]}" VKD3D_DEBUG=info "$LOADER" "${DESKTOP[@]}" 'C:\smoke\devices-x86_64.exe' d3d12

cleanup
if [ $failed -eq 0 ]; then annotate notice "Runtime test passed ($(grep -c '^PASS' "$LOGS/summary.txt") steps)" "$LOGS/summary.txt"
else annotate error "Runtime test failed" "$LOGS/summary.txt"; fi
exit $failed
