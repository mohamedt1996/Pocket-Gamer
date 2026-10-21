#!/usr/bin/env bash
# Prepares an arm64 Ubuntu runner to run the Android runtime (see run.sh):
# Android's bionic at /system, a Vulkan loader under Android's libvulkan.so
# name with lavapipe behind it, and MinGW to build the Windows test programs.
# Each failing command is reported with its output as a CI annotation.
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
LOGS=$(realpath -m "${1:-setup-logs}"); mkdir -p "$LOGS"
TERMUX=/data/data/com.termux/files/usr

report() { # level title file
  local body
  body=$(tail -c 6000 "$3" 2>/dev/null | sed 's/%/%25/g' | sed ':a;N;$!ba;s/\r/%0D/g;s/\n/%0A/g')
  echo "::$1 title=$2::$body"
}
run() { # name command...
  local name=$1; shift
  echo "== $name"
  if ! "$@" > "$LOGS/$name.log" 2>&1; then
    report error "setup: $name failed" "$LOGS/$name.log"; exit 1
  fi
}

run apt bash -c 'sudo apt-get update -qq && sudo apt-get install -y -qq gcc-mingw-w64-x86-64 gcc-mingw-w64-i686 libvulkan-dev binutils unzip dpkg gdb'
run data-dir bash -c 'sudo mkdir -p /data/data && sudo chown -R "$USER" /data'
STAGE=$(mktemp -d)
run termux-packages python3 "$HERE/termux-env.py" "$STAGE"
run termux-install cp -a "$STAGE/data/." /data/
cat "$LOGS/termux-packages.log"

# aosp-libs keeps Android's /system tree under the Termux prefix.
linker=$(find "$TERMUX" -path '*bin/linker64' -type f 2>/dev/null | head -1)
find "$TERMUX/opt" -maxdepth 3 2>/dev/null | sort | head -80 > "$LOGS/aosp-layout.log"
if [ -z "$linker" ]; then report error "setup: no linker64 in aosp-libs" "$LOGS/aosp-layout.log"; exit 1; fi
system=$(dirname "$(dirname "$linker")")
run system-link sudo ln -sfn "$system" /system
run linker-check test -x /system/bin/linker64
lib64=/system/lib64; [ -d "$lib64" ] || lib64=/system/lib
ls -la "$lib64" > "$LOGS/system-libs.log" 2>&1
for lib in libc.so libdl.so libm.so liblog.so; do
  [ -e "$lib64/$lib" ] || { report error "setup: $lib64/$lib missing" "$LOGS/system-libs.log"; exit 1; }
done

# Android's Vulkan loader name, backed by the Khronos loader (+ lavapipe ICD).
vk=$(ls "$TERMUX"/lib/libvulkan.so* 2>/dev/null | head -1)
if [ -n "$vk" ]; then ln -sf "$vk" "$lib64/libvulkan.so"; else echo "::warning title=setup::no Vulkan loader installed"; fi
ls "$TERMUX"/share/vulkan/icd.d/ > "$LOGS/vulkan-icds.log" 2>&1 || true

report notice "Android userspace ready ($system)" "$LOGS/system-libs.log"
