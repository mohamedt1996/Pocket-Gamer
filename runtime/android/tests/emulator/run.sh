#!/usr/bin/env bash
# The app (built without the Windows runtime) on a real Android system: an
# x86_64 emulator image with ARM64 translation (Android 11+ Google images run
# arm64-only apps). Checks that it installs, starts, shows its first screen and
# survives a few hundred random taps, swipes and back presses without a crash
# or ANR. Wine itself does not run here: the translation layer covers the app's
# JNI libraries, not the runtime's own executables.
#
# usage: run.sh APK LOG_DIR
set -uo pipefail
APK=$(realpath "$1"); LOGS=$(realpath -m "$2"); mkdir -p "$LOGS"
PKG=org.pocketarcade.madeira
failed=0
annotate() { # level title file
  local body; body=$(tail -c 6000 "$3" 2>/dev/null | sed 's/%/%25/g' | sed ':a;N;$!ba;s/\r/%0D/g;s/\n/%0A/g')
  echo "::$1 title=$2::$body"
}
check() { # name ok? detail-file
  if [ "$2" = 0 ]; then echo "PASS $1" >> "$LOGS/summary.txt"
  else echo "FAIL $1" >> "$LOGS/summary.txt"; annotate error "emulator: $1" "$3"; failed=1; fi
}
# This app's crashes (Android's crash buffer) and ANRs only, not other apps' log noise.
crashes() {
  adb logcat -d -b crash 2>/dev/null | grep -B 2 -A 30 "Process: $PKG\(:wine\)\?," | head -120
  adb logcat -d -b main,system,events 2>/dev/null | grep -A 12 "ANR in $PKG" | head -40
}

adb wait-for-device
adb shell getprop ro.build.version.release > "$LOGS/device.txt"
adb shell getprop ro.product.cpu.abilist >> "$LOGS/device.txt"
adb logcat -c

adb install -r -g "$APK" > "$LOGS/install.log" 2>&1
check install $? "$LOGS/install.log"

adb shell am start -W -n "$PKG/.ui.MadeiraActivity" > "$LOGS/start.log" 2>&1
sleep 20
pid=$(adb shell pidof "$PKG" | tr -d '\r')
crashes > "$LOGS/crash-start.log"
{ cat "$LOGS/start.log"; echo "pid=$pid"; cat "$LOGS/crash-start.log"; } > "$LOGS/start-report.log"
[ -n "$pid" ] && [ ! -s "$LOGS/crash-start.log" ]; check start $? "$LOGS/start-report.log"

# What the first screen shows (text of every node in the view hierarchy).
adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
adb shell cat /sdcard/ui.xml 2>/dev/null | grep -o 'text="[^"]*"' | sed 's/text="//;s/"$//' | grep -v '^$' | head -40 > "$LOGS/first-screen.txt"
[ -s "$LOGS/first-screen.txt" ]; check first-screen-has-text $? "$LOGS/first-screen.txt"

# The GPU check (gpu_caps.c through JNI) ran on the emulator's own Vulkan driver at start.
sleep 3
adb shell run-as "$PKG" cat files/gpu-caps.json > "$LOGS/gpu-caps.json" 2>&1
grep -q '"vulkan' "$LOGS/gpu-caps.json"; check gpu-check $? "$LOGS/gpu-caps.json"

# Random use: taps, swipes, back, app switches; fixed seed so a failure repeats.
adb logcat -c
adb shell monkey -p "$PKG" -s 4242 --throttle 150 --pct-syskeys 5 --pct-appswitch 5 \
  --ignore-security-exceptions -v 400 > "$LOGS/monkey.log" 2>&1
sleep 3
crashes > "$LOGS/crash-monkey.log"
{ tail -20 "$LOGS/monkey.log"; cat "$LOGS/crash-monkey.log"; } > "$LOGS/monkey-report.log"
! grep -q "// CRASH\|// NOT RESPONDING" "$LOGS/monkey.log" && [ ! -s "$LOGS/crash-monkey.log" ]; check monkey-400-events $? "$LOGS/monkey-report.log"

# Process death while in the background, then back: state must come back without a crash.
adb shell am start -W -n "$PKG/.ui.MadeiraActivity" > /dev/null 2>&1
adb shell input keyevent KEYCODE_HOME; sleep 2
adb shell am kill "$PKG"; sleep 2
adb logcat -c
adb shell am start -W -n "$PKG/.ui.MadeiraActivity" > "$LOGS/restore.log" 2>&1; sleep 10
crashes > "$LOGS/crash-restore.log"
{ cat "$LOGS/restore.log"; cat "$LOGS/crash-restore.log"; } > "$LOGS/restore-report.log"
[ ! -s "$LOGS/crash-restore.log" ]; check restart-after-process-death $? "$LOGS/restore-report.log"

adb logcat -d > "$LOGS/logcat.txt" 2>&1
{ echo "device: $(tr '\n' ' ' < "$LOGS/device.txt")"; cat "$LOGS/summary.txt"; echo "GPU check: $(head -c 600 "$LOGS/gpu-caps.json")"; echo "first screen:"; head -12 "$LOGS/first-screen.txt"; } > "$LOGS/report.txt"
if [ $failed = 0 ]; then annotate notice "Emulator test passed" "$LOGS/report.txt"; else annotate error "Emulator test failed" "$LOGS/report.txt"; fi
exit $failed
