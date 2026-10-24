#!/usr/bin/env bash
# A Wine prefix laid out as the app's WineLaunch writes it before the first
# start (Graphics: null for headless): drive links, FEX as the x86 and x64
# CPU modules, DirectAudio, and WineD3D on Vulkan.
# usage: prefix.sh PREFIX
set -eu
PREFIX=$1
mkdir -p "$PREFIX/drive_c" "$PREFIX/dosdevices"
ln -sfn ../drive_c "$PREFIX/dosdevices/c:"; ln -sfn / "$PREFIX/dosdevices/z:"
printf 'WINE REGISTRY Version 2\n;; All keys relative to \\\\Machine\n\n#arch=win64\n\n[Software\\\\Microsoft\\\\Wow64\\\\amd64]\n@="libarm64ecfex.dll"\n\n[Software\\\\Microsoft\\\\Wow64\\\\x86]\n@="libwow64fex.dll"\n\n' > "$PREFIX/system.reg"
printf 'WINE REGISTRY Version 2\n\n#arch=win64\n\n[Software\\\\Wine\\\\Drivers]\n"Audio"="directaudio"\n"Graphics"="null"\n\n[Software\\\\Wine\\\\Direct3D]\n"renderer"="vulkan"\n\n' > "$PREFIX/user.reg"
