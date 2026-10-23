#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Generates the optional large APK assets; they are not committed to Git.
set -euo pipefail
repo=$(cd "$(dirname "$0")/../.." && pwd)
out="$repo/.android-out/linux-proton"
mkdir -p "$out/buildx"
proton="$out/GE-Proton11-11-x86_64.tar.gz"
rootfs="$out/proton-rootfs-bookworm-x86_64.tar.gz"
proton_sha512=45721a7b625b2404d8896bb5e2a8b87fc9623a394873a6dd6b900c839681d5c01ec694ca00d003fc9c733c5f6c5648af8171985656f9fa47e20999fbebdbe235

if [ ! -f "$proton" ] || [ "$(sha512sum "$proton" | cut -d' ' -f1)" != "$proton_sha512" ]; then
  curl -fL --retry 3 --output "$proton" \
    https://github.com/GloriousEggroll/proton-ge-custom/releases/download/GE-Proton11-11/GE-Proton11-11-x86_64.tar.gz
fi
if [ "$(sha512sum "$proton" | cut -d' ' -f1)" != "$proton_sha512" ]; then
  echo "GE-Proton release checksum mismatch" >&2
  exit 1
fi

BUILDX_CONFIG="$out/buildx" docker --host=unix:///var/run/docker.sock build --platform linux/amd64 \
  --progress=plain -t pocket-proton-rootfs:bookworm "$repo/runtime/android/linux-proton-rootfs"
container_id=$(docker --host=unix:///var/run/docker.sock create --platform linux/amd64 pocket-proton-rootfs:bookworm)
trap 'docker --host=unix:///var/run/docker.sock rm "$container_id" >/dev/null' EXIT
docker --host=unix:///var/run/docker.sock export "$container_id" | gzip -1 > "$rootfs"

package=(python3 "$repo/runtime/android/package-linux-proton.py" --proton "$proton" --rootfs "$rootfs")
if [ "$#" -gt 0 ]; then
  if [ "$#" -ne 2 ] || [ "$1" != "--eac" ]; then
    echo "usage: $0 [--eac OFFICIAL_EAC_RUNTIME_TAR_GZ]" >&2
    exit 2
  fi
  package+=(--eac "$2")
fi
"${package[@]}"
