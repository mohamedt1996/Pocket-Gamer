#!/usr/bin/env python3
"""Would bionic load these libraries on Android 9 (API 28)?

bionic binds every symbol when a library loads: one strong undefined symbol
that none of its dependencies defines makes dlopen() fail. For each ELF in the
staged runtime, its DT_NEEDED tree is resolved against the bundle and against
the NDK's API 28 system library stubs (which export exactly what Android 9
provides), and every strong undefined symbol must be defined somewhere in it.

Libraries in STRICT (Wine's own modules that must load, and what they pull
in) fail the build; anything else is reported.

usage: check-symbols.py STAGE
"""
from pathlib import Path
import os, re, subprocess, sys

stage = Path(sys.argv[1])
NDK_VERSION = '27.3.13750724'
roots = [os.environ.get('ANDROID_NDK_HOME'),
         os.path.join(os.environ.get('ANDROID_HOME', '/nonexistent'), 'ndk', NDK_VERSION),
         os.path.expanduser(f'~/Android/Sdk/ndk/{NDK_VERSION}')]
api = next((Path(r) / 'toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/28'
            for r in roots if r and (Path(r) / 'toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/28').is_dir()), None)
if not api:
    print('::warning::NDK API 28 stubs not found; Android 9 symbol check skipped')
    sys.exit(0)

def is_elf(p):
    with p.open('rb') as f:
        return f.read(4) == b'\x7fELF'

bundle = {}
for p in stage.rglob('*.so*'):
    if p.is_file() and is_elf(p):
        bundle.setdefault(p.name, p)
system = {p.name: p for p in api.glob('*.so')}
cache = {}

def info(path):
    if path not in cache:
        dyn = subprocess.run(['readelf', '-dW', str(path)], capture_output=True, text=True).stdout
        defined = set(subprocess.run(['nm', '-D', '--defined-only', '--format=just-symbols', str(path)],
                                     capture_output=True, text=True).stdout.split())
        undef = subprocess.run(['nm', '-D', '--undefined-only', str(path)], capture_output=True, text=True).stdout
        strong = {l.split()[-1].split('@')[0] for l in undef.splitlines() if l.split()[:1] == ['U']}
        cache[path] = (re.findall(r'\(NEEDED\).*\[(.*?)\]', dyn), {d.split('@')[0] for d in defined}, strong)
    return cache[path]

def closure(path):
    seen, order, queue, missing = set(), [], [path], []
    while queue:
        p = queue.pop(0)
        if p in seen:
            continue
        seen.add(p); order.append(p)
        for n in info(p)[0]:
            target = bundle.get(n) or system.get(n)
            if target: queue.append(target)
            else: missing.append(n)
    return order, missing

# Wine's unix modules the app relies on; a failure in their tree breaks a feature.
STRICT = {'ntdll.so', 'win32u.so', 'wineandroid.so', 'winevulkan.so', 'winegstreamer.so',
          'winedirectaudio.so', 'bcrypt.so', 'secur32.so', 'crypt32.so', 'dwrite.so', 'winedmo.so', 'ws2_32.so'}
failures, reports = [], []
for name, path in sorted(bundle.items()):
    # dlopen() loads the whole tree as one group; every member's symbols must
    # resolve within it (plus the system libraries it reaches).
    order, missing_libs = closure(path)
    defined = set().union(*(info(p)[1] for p in order))
    missing = sorted({f'{s} ({p.name})' for p in order for s in info(p)[2] - defined})
    if not missing and not missing_libs:
        continue
    line = f'{path.relative_to(stage)}: ' + (f'missing libraries {missing_libs} ' if missing_libs else '') + \
           (f'{len(missing)} unresolved: {", ".join(missing[:8])}' if missing else '')
    if name in STRICT and path.parent.name == 'aarch64-unix':
        failures.append(line)
    else:
        reports.append(line)
for r in reports[:40]:
    print('::warning::Android 9: ' + r)
if len(reports) > 40:
    print(f'... {len(reports) - 40} more')
for f in failures:
    print('::error::Android 9: ' + f)
print(f'Android 9 symbol check: {len(bundle)} libraries, {len(failures)} failures, {len(reports)} warnings')
if failures:
    raise SystemExit(1)
