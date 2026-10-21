#!/usr/bin/env python3
"""Install an Android userspace for running the runtime on an arm64 Linux host.

Fetches from the official Termux repositories, with the same index parsing and
SHA-256 checks as termux-deps.py:
- aosp-libs: Android's bionic libc, libm, libdl, liblog and /system/bin/linker64,
  so the runtime's binaries run exactly as they would on a device;
- vulkan-loader-generic + mesa-vulkan-icd-swrast: a Vulkan loader and the
  lavapipe CPU driver, standing in for a phone's GPU driver.

Everything is extracted at its real Termux path (/data/data/com.termux/...).
usage: termux-env.py ROOT   (ROOT is usually /)
"""
from pathlib import Path
import importlib.util, subprocess, sys, tempfile

here = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('termux_deps', here.parent.parent / 'termux-deps.py')
deps = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deps)
deps.ROOTS = ['aosp-libs', 'vulkan-loader-generic', 'mesa-vulkan-icd-swrast']

dest = Path(sys.argv[1])
entries = []
for mirrors, dist, comp in deps.REPOS:
    text, base = deps.fetch_index(mirrors, dist, comp, None)
    for e in deps.parse_index(text, base):
        e['_mirrors'] = mirrors
        entries.append(e)
chosen = deps.resolve(entries)
with tempfile.TemporaryDirectory() as tmp:
    for name, entry in sorted(chosen.items()):
        deb = deps.download(entry, Path(tmp))
        subprocess.run(['dpkg-deb', '-x', str(deb), str(dest)], check=True)
        deb.unlink()
        print(f"{name} {entry['Version']}")
print(f'Installed {len(chosen)} Termux packages under {dest}')
