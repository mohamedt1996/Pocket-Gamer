#!/usr/bin/env python3
"""Make Madeira's FEX fork (willfaust/FEX) build for Android, fail-closed.

The fork is only ever built for iOS (FEX_IOS_HOST). Its ARM64EC module calls
the iOS JIT-pool helper IosJitReverseTranslate outside the FEX_IOS_HOST guard
that declares it. Without iOS's JIT pool there is no alias to translate, so
the non-iOS fallback is the identity -- the same thing the fork already does
for its WoW64 module (see FEXCore/Source/Interface/Core/Core.cpp).

usage: patch-fex.py FEX_SRC
"""
from pathlib import Path
import sys

root = Path(sys.argv[1])

def replace(path, old, new):
    f = root / path
    text = f.read_text()
    if text.count(old) != 1:
        raise SystemExit(f'Patch anchor changed: {path}: expected once, got {text.count(old)}')
    f.write_text(text.replace(old, new, 1))

replace('Source/Windows/ARM64EC/Module.cpp',
        'extern "C" int ios_va_log_len;\n\n#ifdef FEX_IOS_HOST\n',
        'extern "C" int ios_va_log_len;\n\n'
        '#ifndef FEX_IOS_HOST\n'
        '/* Android: no iOS JIT pool, so no pool-copy aliases (see runtime/android/madeira/patch-fex.py). */\n'
        'static inline uint64_t IosJitReverseTranslate(uint64_t Addr) { return Addr; }\n'
        '/* The iOS VA band is defined beside the iOS allocator; zero means "no band", upstream behaviour. */\n'
        'extern "C" { uintptr_t ios_fex_band_base = 0; uintptr_t ios_fex_band_end = 0; }\n'
        '#endif\n\n'
        '#ifdef FEX_IOS_HOST\n')
# A diagnostic log line in the JIT's hot path timestamps itself with time(),
# which needs the C runtime this module deliberately does not link.
replace('FEXCore/Source/Interface/Core/Core.cpp',
        '        uint64_t now = (uint64_t)time(NULL);\n',
        '        uint64_t now = 0; /* Android: no C runtime in this module; the log shows t=0s */\n')
# The WoW64 module registers threads with the iOS JIT code-buffer sweep and reads
# its gate outside the FEX_IOS_HOST guard of their definitions (CPUBackend.cpp).
# Without iOS's JIT pool there is no sweep: registration is a no-op and the
# gate stays open (0). The iOS VA band is zero, as in the ARM64EC module.
replace('Source/Windows/WOW64/Module.cpp',
        'extern "C" void IosSweepUnregisterThread(FEXCore::Core::InternalThreadState* Thread);\n'
        'extern "C" uint64_t IosCodeBufferSweepGate;\n',
        'extern "C" void IosSweepUnregisterThread(FEXCore::Core::InternalThreadState* Thread);\n'
        'extern "C" uint64_t IosCodeBufferSweepGate;\n'
        '#ifndef FEX_IOS_HOST\n'
        '/* Android: no iOS JIT-pool sweep (see runtime/android/madeira/patch-fex.py). */\n'
        'uint64_t IosCodeBufferSweepGate = 0;\n'
        'extern "C" void IosSweepRegisterThreadEx(FEXCore::Core::InternalThreadState*, volatile uint8_t*, volatile uint8_t*) {}\n'
        'extern "C" void IosSweepUnregisterThread(FEXCore::Core::InternalThreadState*) {}\n'
        'extern "C" { uintptr_t ios_fex_band_base = 0; uintptr_t ios_fex_band_end = 0; }\n'
        '#endif\n')
print('Madeira FEX patched for Android')

# The ARM64EC module's export list names iOS-host-only hooks and diagnostic
# buffers, which a non-iOS build does not define (a .def entry for a missing
# symbol is a link error). Madeira's Wine only looks them up under WINE_IOS.
IOS_ONLY_EXPORTS = ['BTCpu64IosAddAliasMapping', 'BTCpu64IosRipFromHostPC', 'BTCpu64IosSetMonoBridge',
                    'ios_va_log DATA', 'ios_va_log_len DATA', 'ios_fex_band_base DATA', 'ios_fex_band_end DATA',
                    'IosXpFex DATA', 'IosGuestBranchTraceLayout DATA']
def_file = root / 'Source/Windows/ARM64EC/libarm64ecfex.def'
lines = def_file.read_text().splitlines(keepends=True)
kept = [l for l in lines if l.strip() not in IOS_ONLY_EXPORTS]
if len(lines) - len(kept) != len(IOS_ONLY_EXPORTS):
    raise SystemExit(f'Patch anchor changed: libarm64ecfex.def: removed {len(lines) - len(kept)} of {len(IOS_ONLY_EXPORTS)} iOS exports')
def_file.write_text(''.join(kept))
print('libarm64ecfex.def: iOS-only exports removed')
