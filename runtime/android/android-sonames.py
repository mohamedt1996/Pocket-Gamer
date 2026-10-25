#!/usr/bin/env python3
"""Point Wine at Android's own graphics libraries.

Wine's configure records the library names it finds in the Termux sysroot it
builds against, and Wine later dlopen()s exactly those names. Termux's generic
Vulkan loader and its GLVND EGL/GLES are versioned (libvulkan.so.1,
libEGL.so.1, libGLESv2.so.2); Android devices only have the system libraries
libvulkan.so, libEGL.so and libGLESv2.so. With the Termux names Wine finds no
Vulkan at all (so DXVK and VKD3D cannot start), and its OpenGL would use
Mesa's EGL, which cannot draw into Android windows.

Run after configure, before make. usage: android-sonames.py WINE_BUILD_DIR
"""
from pathlib import Path
import re, sys

ANDROID = {
    'SONAME_LIBVULKAN': 'libvulkan.so',
    'SONAME_LIBEGL': 'libEGL.so',
    'SONAME_LIBGLESV2': 'libGLESv2.so',
}

build = Path(sys.argv[1])
headers = [p for p in build.rglob('include/config.h') if 'SONAME_' in p.read_text(errors='replace')]
if not headers:
    raise SystemExit(f'No configured include/config.h under {build}')
for header in headers:
    text = header.read_text()
    if 'SONAME_LIBVULKAN' not in text:
        raise SystemExit(f'{header}: configure found no Vulkan loader; Wine would have no Vulkan')
    for name, value in ANDROID.items():
        text, count = re.subn(rf'^#define {name} "[^"]*"$', f'#define {name} "{value}"', text, flags=re.M)
        if count:
            print(f'{header}: {name} -> {value}')
    header.write_text(text)
