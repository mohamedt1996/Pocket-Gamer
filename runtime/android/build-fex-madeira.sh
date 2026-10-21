#!/usr/bin/env bash
# FEX for the Madeira Wine APK: Madeira's FEX fork (willfaust/FEX, the "FEX"
# submodule of willfaust/Madeira) in its Android host mode
# (madeira/fex-android.patch, -DFEX_ANDROID_HOST).
set -euo pipefail
FEX_SOURCE=madeira-fex FEX_COMPONENT=fex-madeira exec bash "$(dirname "$0")/build-fex.sh"
