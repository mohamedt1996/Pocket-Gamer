#!/usr/bin/env bash
# FEX for the Madeira Wine APK, from the FEX fork Madeira pins next to its Wine
# (willfaust/FEX, the "FEX" submodule of willfaust/Madeira). Upstream FEX,
# built for the Proton fork's Wine 11.0, does not run Madeira's Wine 11.4's
# x64 (ARM64EC) programs: they hang.
set -euo pipefail
FEX_SOURCE=madeira-fex FEX_COMPONENT=fex-madeira exec bash "$(dirname "$0")/build-fex.sh"
