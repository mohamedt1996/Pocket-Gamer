#!/usr/bin/env python3
"""Apply small, fail-closed Android integration edits to the pinned Wine tree."""
from pathlib import Path
import shutil, sys
root=Path(sys.argv[1]); here=Path(__file__).resolve().parent

def replace(path, old, new):
    p=root/path; text=p.read_text()
    if text.count(old)!=1: raise SystemExit(f'Patch anchor changed: {path}: {old!r}')
    p.write_text(text.replace(old,new))

replace('build-scripts/build-step-arm64ec.sh','--enable-wineandroid_drv=no','--enable-wineandroid_drv=yes')
# JNI / per-HWND input handling remains Wine's own, matched to its Unix driver.
replace('dlls/wineandroid.drv/Makefile.in','\\twindow.c \\\\', '\\twindow.c \\\\n\\tmadeira_vulkan.c \\\\')
shutil.copyfile(here/'patches/wineandroid_vulkan.c',root/'dlls/wineandroid.drv/madeira_vulkan.c')
replace('dlls/wineandroid.drv/init.c','    .pOpenGLInit = ANDROID_OpenGLInit,','    .pOpenGLInit = ANDROID_OpenGLInit,\\n    .pVulkanInit = ANDROID_VulkanInit,')
with (root/'dlls/wineandroid.drv/android.h').open('a') as f:
    f.write('\\nstruct vulkan_driver_funcs;\\nextern UINT ANDROID_VulkanInit(UINT, void *, const struct vulkan_driver_funcs **);\\n')
# Generate the Android host extension and entry point in Wine's Vulkan tables.
replace('dlls/winevulkan/make_vulkan','UNEXPOSED_EXTENSIONS = {','UNEXPOSED_EXTENSIONS = {\\n    "VK_KHR_android_surface",')
replace('dlls/winevulkan/make_vulkan','UNEXPOSED_PLATFORMS = {','UNEXPOSED_PLATFORMS = {\\n    "android",')
# A JNI init failure should never return uninitialised stack memory.
replace('dlls/ntdll/unix/loader.c','    char error[1024];\\n    int i, argc, length;', '    char error[1024] = "Wine initialization returned unexpectedly";\\n    int i, argc, length;')

# Host-only Wine generator tools do not need FreeType. Ubuntu's 32-bit
# development headers are absent, so avoid the configure failure.
step0=root/'build-scripts/build-step0.sh'
step0_text=step0.read_text()
anchor='../configure --without-x --without-gstreamer --without-vulkan --without-wayland'
if step0_text.count(anchor)!=1:
    raise SystemExit('Patch anchor changed: build-step0.sh configure flags')
step0_text=step0_text.replace(anchor,anchor+' --without-freetype --without-fontconfig')
if step0_text.startswith('#!'):
    pos=step0_text.index('\\n')+1
    step0_text=step0_text[:pos]+'set -eu\\n'+step0_text[pos:]
else:
    step0_text='set -eu\\n'+step0_text
step0.write_text(step0_text)

# Restrict make parallelism on standard hosted runners, including host tools.
for path in ('build-scripts/build-step0.sh','build-scripts/build-step-arm64ec.sh'):
    p=root/path;p.write_text(p.read_text().replace('-j$(nproc)','-j2'))
