#!/usr/bin/env python3
"""Patch a pinned Wine source, fail-closed on unexpected upstream edits.

usage: patch-wine.py WINE_SRC [--madeira]

Without --madeira the source is the Proton Android fork (sources.json "wine")
and its build-scripts/ are adjusted too. With --madeira the source is
Madeira's Wine (sources.json "madeira-wine"), which has no build-scripts/;
only the Pocket-Gamer integration shared by both variants is applied.
"""
from pathlib import Path
import shutil
import sys

args = [a for a in sys.argv[1:] if not a.startswith('--')]
madeira = '--madeira' in sys.argv[1:]
root = Path(args[0]).resolve()
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
        '    if (!names)\n    {',
        '    if (!strcmp( name, "dlltool" ) && getenv( "DLLTOOL" ))\n'
        '    {\n'
        '        strarray_add( &ret, getenv( "DLLTOOL" ) );\n'
        '        return ret;\n'
        '    }\n'
        '    if (!names)\n    {')

# The pinned upstream Makefile has a real tab before 'window.c' and a
# *real* newline after its continuation backslash. Avoid the old broken
# '\\t' / '\\n' double-escaping, which searched for literal slash characters.
replace('dlls/wineandroid.drv/Makefile.in',
        '\twindow.c \\\n',
        '\twindow.c \\\n\tmadeira_vulkan.c \\\n')
shutil.copyfile(here / 'patches/wineandroid_vulkan.c',
                root / 'dlls/wineandroid.drv/madeira_vulkan.c')
if madeira:
    # Both trees report WINE_VULKAN_DRIVER_VERSION 47, but Proton's
    # p_vulkan_surface_create takes an extra BOOL that upstream 11.4 lacks.
    # The adapter ignores it, so drop it for Madeira's (upstream) signature.
    replace('dlls/wineandroid.drv/madeira_vulkan.c',
            'static VkResult surface_create(HWND hwnd, BOOL raw, const struct vulkan_instance *instance,',
            'static VkResult surface_create(HWND hwnd, const struct vulkan_instance *instance,')
    replace('dlls/wineandroid.drv/madeira_vulkan.c', '    (void)raw;\n', '')
# Wine would otherwise also build its demo wine-debug.apk with Gradle. The
# app ships its own activity (WineActivity.java is copied out), so skip it.
replace('dlls/wineandroid.drv/Makefile.in', '\nEXTRA_TARGETS = wine-debug.apk\n', '\n')
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

if madeira:
    sys.exit(0)

# ---- Proton Android fork only ----
# The fork ships --enable-wineandroid_drv=no, so when it changed the win32u
# driver interfaces it left wineandroid.drv behind. Bring the driver up to
# the fork's own interfaces:
#  - pWindowPosChanged lost its BOOL fullscreen argument (stale declaration);
#  - OpenGL p_surface_create gained a BOOL raw argument, as in winex11/winewayland;
#  - set_window_opengl_drawable() became private to win32u. Its only user here,
#    update_gl_drawable(), is never called, so it goes;
#  - drawable_mutex lost its definition but kept its declaration and init;
#  - start_device_callback is a UINT64 field, so store the address as one.
# Each matches upstream Wine 11.4's wineandroid.drv.
replace('dlls/wineandroid.drv/android.h', 'extern pthread_mutex_t drawable_mutex;\n', '')
replace('dlls/wineandroid.drv/init.c', '    pthread_mutex_init( &drawable_mutex, &attr );\n', '')
replace('dlls/wineandroid.drv/dllmain.c',
        '    params.start_device_callback = android_start_device;\n',
        '    params.start_device_callback = (UINT_PTR)android_start_device;\n')
replace('dlls/wineandroid.drv/android.h',
        'extern void ANDROID_WindowPosChanged( HWND hwnd, HWND insert_after, HWND owner_hint, UINT swp_flags, BOOL fullscreen,\n',
        'extern void ANDROID_WindowPosChanged( HWND hwnd, HWND insert_after, HWND owner_hint, UINT swp_flags,\n')
replace('dlls/wineandroid.drv/opengl.c',
        'static BOOL android_surface_create( HWND hwnd, int format, struct opengl_drawable **drawable )\n',
        'static BOOL android_surface_create( HWND hwnd, BOOL raw, int format, struct opengl_drawable **drawable )\n')
replace('dlls/wineandroid.drv/opengl.c',
        'void update_gl_drawable( HWND hwnd )\n'
        '{\n'
        '    /* clear any cached opengl drawable */\n'
        '    set_window_opengl_drawable( hwnd, NULL, TRUE );\n'
        '    set_window_opengl_drawable( hwnd, NULL, FALSE );\n'
        '    NtUserRedrawWindow( hwnd, NULL, 0, RDW_INVALIDATE | RDW_ERASE );\n'
        '}\n\n', '')

# The fork's make_vulkan pulls in every extension of a platform listed in
# UNEXPOSED_PLATFORMS, so adding "android" (for VK_KHR_android_surface) also
# generates thunks for AHardwareBuffer extensions that do not compile. The
# Android driver needs only the surface extension; leave the rest out.
replace('dlls/winevulkan/make_vulkan', 'UNSUPPORTED_EXTENSIONS = [\n',
        'UNSUPPORTED_EXTENSIONS = [\n'
        '    "VK_ANDROID_external_memory_android_hardware_buffer", # AHardwareBuffer thunks do not compile\n')

# Adjust the fork's build-scripts/.
replace('build-scripts/build-step-arm64ec.sh', '--enable-wineandroid_drv=no', '--enable-wineandroid_drv=yes')

# Drop GameNative's userspace ntsync. It is opt-in at runtime (WINENTSYNC=1;
# esync stays the default), and its patch is the only thing that links
# -lntsync_android, so removing the patch and its source markers leaves the
# esync path unchanged. The vendored LICENSE/NOTICE are no longer shipped.
replace('build-scripts/build-step-arm64ec.sh',
        '\t  # userspace ntsync, OPT-IN at runtime (WINENTSYNC=1); must follow esync\n'
        '\t  "ntsync_userspace.patch"\n', '')
for marker in (
        '      "server/inproc_sync.c|WINENTSYNC set, no usable /dev/ntsync, using userspace ntsync|userspace ntsync, opt-in via WINENTSYNC=1 (server)"\n',
        '      "dlls/ntdll/unix/esync.c|if (ntsync_opt_in_active) return 0;|esync steps aside only under the WINENTSYNC opt-in (client)"\n',
        '      "dlls/ntdll/unix/sync.c|userspace_wait_objs|userspace ntsync wait path (client)"\n'):
    replace('build-scripts/build-step-arm64ec.sh', marker, '')
replace('build-scripts/build-step-arm64ec.sh',
        '    # ntsync-android (statically linked, LGPL-3.0-only): ship its licence + provenance.\n'
        '    mkdir -p "$OUTPUT_DIR/share/licenses/ntsync-android"\n'
        '    _NTS="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/android/ntsync_android"\n'
        '    cp "$_NTS/LICENSE" "$_NTS/NOTICE" "$OUTPUT_DIR/share/licenses/ntsync-android/" || exit $?\n', '')

# Build the host generators 64-bit: they need no 32-bit target, and sfnt2fon
# needs the host FreeType (libfreetype6-dev) to build fonts/*.fon.
step0 = root / 'build-scripts/build-step0.sh'
script = step0.read_text()
configure = '../configure --without-x --without-gstreamer --without-vulkan --without-wayland'
if script.count(configure) != 1:
    raise SystemExit('Patch anchor changed: build-step0.sh configure')
script = script.replace(configure, configure + ' --enable-win64 --with-freetype --without-fontconfig', 1)
if script.startswith('#!'):
    split = script.index('\n') + 1
    script = script[:split] + 'set -eu\n' + script[split:]
else:
    script = 'set -eu\n' + script
step0.write_text(script)

for name in ('build-scripts/build-step0.sh', 'build-scripts/build-step-arm64ec.sh'):
    file = root / name
    file.write_text(file.read_text().replace('-j$(nproc)', '-j2'))
