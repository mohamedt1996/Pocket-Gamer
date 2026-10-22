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



# 16 KB page kernels (Android 15+ devices) refuse to exec an ELF whose segments are 4 KB
# aligned, and for the static wine-preloader the kernel finds out after the point of no
# return: every process Wine starts (wineboot, explorer, the game) died with SIGSEGV. Its
# text address (0x7d400000) is already 16 KB aligned; only the segment alignment was missing.
# The preloader reads the real page size from AT_PAGESZ, so nothing else depends on it.
for script in ('configure.ac', 'configure'):   # builds may regenerate configure
    replace(script,
            'WINEPRELOADER_LDFLAGS="-static -nostartfiles -nodefaultlibs -Wl,-Ttext=0x7d400000"',
            'WINEPRELOADER_LDFLAGS="-static -nostartfiles -nodefaultlibs -Wl,-Ttext=0x7d400000 -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"')

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

# win32u self-deadlock on a program's first Vulkan call (seen in the runtime test,
# both trees): vulkan_init_once maps instance extensions through the lazy driver,
# which loads the display driver; loading it refreshes the display devices, which
# registers the GPU through D3DKMT, whose vulkan instance asks for
# __wine_get_vulkan_driver again on the same thread. pthread_once then waits for
# itself and the program hangs. It happens whenever a process reaches Vulkan
# before anything in it loaded the display driver (a DXGI factory created before
# the first window, a game started while the desktop is still initialising).
# A nested call on the initialising thread now gets "no Vulkan", and D3DKMT's
# instance is created on a later call instead of being latched as missing.
replace('dlls/win32u/vulkan.c',
        'static void vulkan_init_once(void)\n{\n',
        'struct gdi_dc_funcs;\n'
        'extern const struct gdi_dc_funcs *get_display_driver(void); /* ntgdi_private.h */\n\n'
        'static pthread_t vulkan_init_thread;\n'
        'static volatile int vulkan_init_running;\n\n'
        '/* whether this thread is inside vulkan_init_once (see __wine_get_vulkan_driver) */\n'
        'BOOL vulkan_init_in_progress(void)\n'
        '{\n'
        '    return vulkan_init_running && pthread_equal( vulkan_init_thread, pthread_self() );\n'
        '}\n\n'
        'static void vulkan_init_once_body(void);\n\n'
        'static void vulkan_init_once(void)\n'
        '{\n'
        '    vulkan_init_thread = pthread_self();\n'
        '    vulkan_init_running = 1;\n'
        '    vulkan_init_once_body();\n'
        '    vulkan_init_running = 0;\n'
        '}\n\n'
        'static void vulkan_init_once_body(void)\n{\n')
replace('dlls/win32u/vulkan.c',
        '    pthread_once( &init_once, vulkan_init_once );\n'
        '    if (!vulkan_handle) return NULL;\n',
        '    /* Load the display driver (and refresh the display devices) before\n'
        '     * initialising Vulkan, as any process with a window already has. Done\n'
        '     * from inside vulkan_init_once instead, the refresh registers the GPU\n'
        '     * through D3DKMT, which needs this Vulkan driver: a wait on ourselves. */\n'
        '    if (!vulkan_init_in_progress()) get_display_driver();\n'
        '    /* Should anything else still lead back here during the initialisation\n'
        '     * on this thread, answer "no Vulkan" rather than wait for ourselves. */\n'
        '    if (vulkan_init_in_progress()) return NULL;\n'
        '    pthread_once( &init_once, vulkan_init_once );\n'
        '    if (!vulkan_handle) return NULL;\n')
replace('dlls/win32u/d3dkmt.c',
        'static struct vulkan_instance *get_d3dkmt_vulkan_instance(void)\n'
        '{\n'
        '    static pthread_once_t once = PTHREAD_ONCE_INIT;\n'
        '    pthread_once( &once, d3dkmt_init_vulkan );\n'
        '    return d3dkmt_vulkan_instance;\n'
        '}\n',
        'extern BOOL vulkan_init_in_progress(void);\n\n'
        'static struct vulkan_instance *get_d3dkmt_vulkan_instance(void)\n'
        '{\n'
        '    static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;\n'
        '    static pthread_t init_thread;\n'
        '    static volatile int done, running;\n\n'
        '    if (done) return d3dkmt_vulkan_instance;\n'
        '    /* During win32u\'s own Vulkan initialisation on this thread, or our own:\n'
        '     * no instance yet, and try again on a later call rather than for good. */\n'
        '    if (vulkan_init_in_progress()) return NULL;\n'
        '    if (running && pthread_equal( init_thread, pthread_self() )) return NULL;\n'
        '    pthread_mutex_lock( &lock );\n'
        '    if (!done)\n'
        '    {\n'
        '        init_thread = pthread_self();\n'
        '        running = 1;\n'
        '        d3dkmt_init_vulkan();\n'
        '        running = 0;\n'
        '        done = 1;\n'
        '    }\n'
        '    pthread_mutex_unlock( &lock );\n'
        '    return d3dkmt_vulkan_instance;\n'
        '}\n')

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
