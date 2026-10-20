#!/usr/bin/env bash
set -euo pipefail
# Versioned paths match the pinned Wine fork's build scripts.
mkdir -p "$HOME/toolchains" "$HOME/Android/Sdk/ndk"
MINGW="$HOME/toolchains/llvm-mingw-20250920-ucrt-ubuntu-22.04-x86_64"
NDK="$HOME/Android/Sdk/ndk/27.3.13750724"
if [ ! -x "$MINGW/bin/arm64ec-w64-mingw32-clang" ]; then
  curl -fL --retry 3 https://github.com/bylaws/llvm-mingw/releases/download/20250920/llvm-mingw-20250920-ucrt-ubuntu-22.04-x86_64.tar.xz -o "$RUNNER_TEMP/madeira-mingw.tar.xz"
  tar -xf "$RUNNER_TEMP/madeira-mingw.tar.xz" -C "$HOME/toolchains"
fi
if [ ! -x "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android28-clang" ]; then
  SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/usr/local/lib/android/sdk}}"
  SDKMANAGER=$(find "$SDK/cmdline-tools" -type f -name sdkmanager | sort -V | tail -1)
  test -x "$SDKMANAGER"
  "$SDKMANAGER" --sdk_root="$SDK" --install 'ndk;27.3.13750724'
  ln -s "$SDK/ndk/27.3.13750724" "$NDK"
fi
printf 'LLVM_MINGW=%s\nANDROID_NDK_HOME=%s\n' "$MINGW" "$NDK" >> "$GITHUB_ENV"
if [ "${1:-}" = wine ] || [ "${1:-}" = wine-madeira ]; then
  DEST="$HOME/termuxfs/aarch64"
  if [ ! -d "$DEST/data/data/com.termux/files/usr/lib" ]; then
    # Official Termux packages (termux-main + termux-x11), SHA256-checked
    # against the repo index; no third-party prebuilt filesystem.
    python3 "$(dirname "$0")/termux-deps.py" "$DEST"
  fi
fi
