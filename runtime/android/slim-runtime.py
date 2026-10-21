#!/usr/bin/env python3
"""Make the staged runtime loadable on Android 9 and drop what nothing uses.

1. GStreamer's GL library (needed by winegstreamer, Wine's media decoding)
   links Mesa's libEGL.so.1 and GLVND's libGLX.so.0. Both use ELF TLS, which
   Android 9's linker rejects, so winegstreamer could not load there; Mesa's
   EGL could not draw into Android windows anyway. libgstgl is pointed at
   Android's own libEGL.so instead, and libGLX.so.0 becomes a stub
   (stubs/glx_stub.c, built with the NDK) whose functions report "no GLX".
2. Every library in lib/ that Wine can never reach is removed: Wine's unix
   modules and executables are the roots, plus every library name Wine
   dlopen()s (found as a string in its unix modules), followed through
   DT_NEEDED. In practice: the Turnip/freedreno Vulkan drivers (built for
   Wayland, unusable on Android, ~110 MB), unversioned copies of ffmpeg
   libraries Wine opens by their versioned names, and Mesa once nothing
   needs it.

usage: slim-runtime.py STAGE   (NDK from $ANDROID_NDK_HOME or $ANDROID_HOME/ndk/<version>)
"""
from pathlib import Path
import os, re, shutil, subprocess, sys

stage = Path(sys.argv[1])
lib = stage / 'lib'
here = Path(__file__).resolve().parent
NDK_VERSION = '27.3.13750724'


def needed(path):
    out = subprocess.run(['readelf', '-dW', str(path)], capture_output=True, text=True).stdout
    return re.findall(r'\(NEEDED\).*\[(.*?)\]', out)


def is_elf(path):
    with path.open('rb') as f:
        return f.read(4) == b'\x7fELF'


def ndk_clang():
    roots = [os.environ.get('ANDROID_NDK_HOME')]
    if os.environ.get('ANDROID_HOME'):
        roots.append(os.path.join(os.environ['ANDROID_HOME'], 'ndk', NDK_VERSION))
    roots.append(os.path.expanduser(f'~/Android/Sdk/ndk/{NDK_VERSION}'))
    for root in filter(None, roots):
        clang = Path(root) / 'toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android28-clang'
        if clang.is_file():
            return clang
    return None


# 1. libgstgl -> Android's EGL, GLX stub.
gstgl = lib / 'libgstgl-1.0.so'
if gstgl.is_file():
    deps = needed(gstgl)
    if 'libEGL.so.1' in deps:
        subprocess.run(['patchelf', '--replace-needed', 'libEGL.so.1', 'libEGL.so', str(gstgl)], check=True)
        print('libgstgl-1.0.so: libEGL.so.1 -> Android libEGL.so')
    if 'libGLX.so.0' in deps:
        clang = ndk_clang()
        if not clang:
            raise SystemExit('libgstgl needs libGLX.so.0: the NDK is required to build the GLX stub')
        subprocess.run([str(clang), '-shared', '-O2', '-fPIC', '-Wl,-soname,libGLX.so.0',
                        '-Wl,-z,max-page-size=16384', '-o', str(lib / 'libGLX.so.0'),
                        str(here / 'stubs/glx_stub.c')], check=True)
        print('libGLX.so.0: GLX stub (no GLX on Android)')

# 2. Remove what Wine cannot reach.
files = {p.name: p for p in lib.iterdir() if p.is_file() and is_elf(p)}
roots = [p for d in ('lib/wine', 'bin') for p in (stage / d).rglob('*') if p.is_file() and is_elf(p)]
unix_modules = b''.join(p.read_bytes() for p in (stage / 'lib/wine/aarch64-unix').glob('*.so'))
roots += [p for n, p in files.items() if (b'\0' + n.encode() + b'\0') in unix_modules]
seen, queue = set(), list(roots)
while queue:
    p = queue.pop()
    if p in seen:
        continue
    seen.add(p)
    queue += [files[n] for n in needed(p) if n in files]
removed = sorted(n for n, p in files.items() if p not in seen)
size = sum(files[n].stat().st_size for n in removed)
for n in removed:
    files[n].unlink()
print(f'Removed {len(removed)} unreachable libraries ({size / 1048576:.0f} MB): {", ".join(removed) or "none"}')
# Vulkan ICD manifests for removed drivers would point at nothing.
for manifest in (stage / 'share/vulkan').rglob('*.json') if (stage / 'share/vulkan').is_dir() else []:
    if any(n in manifest.read_text(errors='replace') for n in removed):
        manifest.unlink()
