#!/usr/bin/env python3
"""Patch the pinned Proton Wine source, fail-closed on unexpected upstream edits."""
from pathlib import Path
import shutil
import sys

root = Path(sys.argv[1]).resolve()
here = Path(__file__).resolve().parent


def replace(path, old, new):
    file = root / path
    data = file.read_text()
    occurrences = data.count(old)
    if occurrences != 1:
        raise SystemExit(f"Patch anchor changed: {path}: expected once, got {occurrences}: {old!r}")
    file.write_text(data.replace(old, new, 1))



# Wine's host winebuild otherwise discovers Ubuntu's LLVM 18 dlltool even
# when the Android cross-compiler uses the pinned llvm-mingw toolchain.
# Honour an explicit DLLTOOL in the host generator, fail-closed if missing.
replace('tools/winebuild/utils.c',
        '    if (!names)\\n    {',
        '    if (!strcmp( name, "dlltool" ) && getenv( "DLLTOOL" ))\\n'
        '    {\\n'
        '        strarray_add( &ret, getenv( "DLLTOOL" ) );\\n'
        '        return ret;\\n'
        '    }\\n'
        '    if (!names)\\n    {')

replace('build-scripts/build-step-arm64ec.sh', '--enable-wineandroid_drv=no', '--enable-wineandroid_drv=yes')

# The pinned upstream Makefile has a real tab before 'window.c' and a
# *real* newline after its continuation backslash. Avoid the old broken
# '\\t' / '\\n' double-escaping, which searched for literal slash characters.
replace('dlls/wineandroid.drv/Makefile.in',
        '\twindow.c \\\n',
        '\twindow.c \\\n\tmadeira_vulkan.c \\\n')
shutil.copyfile(here / 'patches/wineandroid_vulkan.c',
                root / 'dlls/wineandroid.drv/madeira_vulkan.c')
replace('dlls/wineandroid.drv/init.c',
        '    .pOpenGLInit = ANDROID_OpenGLInit,',
        '    .pOpenGLInit = ANDROID_OpenGLInit,\n    .pVulkanInit = ANDROID_VulkanInit,')
with (root / 'dlls/wineandroid.drv/android.h').open('a') as handle:
    handle.write('\nstruct vulkan_driver_funcs;\n'
                 'extern UINT ANDROID_VulkanInit(UINT, void *, const struct vulkan_driver_funcs **);\n')
replace('dlls/winevulkan/make_vulkan', 'UNEXPOSED_EXTENSIONS = {',
        'UNEXPOSED_EXTENSIONS = {\n    "VK_KHR_android_surface",')
replace('dlls/winevulkan/make_vulkan', 'UNEXPOSED_PLATFORMS = {',
        'UNEXPOSED_PLATFORMS = {\n    "android",')
replace('dlls/ntdll/unix/loader.c',
        '    char error[1024];\n    int i, argc, length;',
        '    char error[1024] = "Wine initialization returned unexpectedly";\n'
        '    int i, argc, length;')

# Build host generators without requiring 32-bit FreeType development headers.
step0 = root / 'build-scripts/build-step0.sh'
script = step0.read_text()
configure = '../configure --without-x --without-gstreamer --without-vulkan --without-wayland'
if script.count(configure) != 1:
    raise SystemExit('Patch anchor changed: build-step0.sh configure')
script = script.replace(configure, configure + ' --without-freetype --without-fontconfig', 1)
if script.startswith('#!'):
    split = script.index('\n') + 1
    script = script[:split] + 'set -eu\n' + script[split:]
else:
    script = 'set -eu\n' + script
step0.write_text(script)

for name in ('build-scripts/build-step0.sh', 'build-scripts/build-step-arm64ec.sh'):
    file = root / name
    file.write_text(file.read_text().replace('-j$(nproc)', '-j2'))
