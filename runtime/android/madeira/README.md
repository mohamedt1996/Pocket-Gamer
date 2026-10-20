# Madeira Wine on Android (experimental)

This builds the Android runtime's Wine from **Madeira's Wine fork**
(`willfaust/wine`, branch `madeira-lgpl`, Wine 11.4) instead of the Proton
Android fork used by `build-wine.sh`. It is a separate CI job
(`android_madeira_wine`) with `continue-on-error`, so it never blocks the
shipped APK, and its output (`Android-component-wine-madeira`) is not packaged
yet.

## What Madeira's Wine brings, and what it does not

Almost all of Madeira's changes are behind `#ifdef WINE_IOS` (single-process
iOS, Metal, the iOS audio driver). On Android those compile out, so this is
close to upstream Wine 11.4 plus Madeira's platform-neutral ARM64EC, WoW64 and
server fixes. Madeira's graphics stack (DXMT, madeira-d3d12) is Metal-only and
is not used; graphics stay on `wineandroid.drv` + Vulkan (DXVK / vkd3d-proton).

## Sources (pinned in `../sources.json`)

| Name | Repository | Why |
|---|---|---|
| `madeira-wine` | willfaust/wine | the Wine tree |
| `madeira` | willfaust/Madeira (same commit as the iOS job) | `build/madeira_cfg.h`, which Madeira's ntdll includes on every platform |
| `directaudio` | The412Banner/directaudio | the AAudio sound driver the app selects (`Audio=directaudio`) |

Licensing: Madeira's Wine is LGPL-2.1-or-later, but `madeira_cfg.h` comes from
the main Madeira repository, which is GPL-3.0-or-later. Building it into
`ntdll.so` makes that library a GPL-3.0-or-later combination, as it already is
on iOS. The licences are staged under `share/licenses/{wine,madeira,directaudio}`.

## Patches (`patches/`, applied in order with `git apply`, fail-hard)

| # | Change | Origin |
|---|---|---|
| 0001 | ntdll: 39-bit address-space limit, no `userfaultfd`, PE images read into anonymous memory (Android W^X) | rewritten from the Android fork's `virtual.c` patch |
| 0002 | dnsapi: Android system resolver (`android_res_nquery`, looked up at run time, polled with a timeout) | rewritten; the fork's version crashed below Android 10 |
| 0003 | ws2_32: emulate `AI_V4MAPPED` / `AI_ALL`, which bionic rejects | the fork's patch, re-placed on 11.4 |
| 0004 | nsiproxy: list interfaces with `getifaddrs()` when Android 11+ blocks `if_nameindex()` | rewritten |
| 0005-0007 | UTF-8 locale on Android, shell32 drive-root copy guard, xinput wait retry | the fork's patches, unchanged |
| 0008 | configure: DirectAudio driver | from directaudio's `INTEGRATION.md` |
| 0009 | ntdll: Madeira's `sync.c` instrumentation used Apple APIs unguarded; portable equivalents elsewhere | new |
| 0010 | win32u: Madeira's `ios_srcwatch_arm()` hook made weak, as its sibling already is, so `win32u.so` links and loads | new |

Then `patch-wine.py --madeira` applies the Pocket-Gamer integration shared with
the Proton build (Android Vulkan surface, `DLLTOOL`, loader error text, no demo
`wine-debug.apk`). For Madeira's tree it also drops the extra `BOOL` argument the
Proton fork added to `p_vulkan_surface_create`: both trees call their Vulkan
driver interface version 47, but the signatures differ.

Left out on purpose: esync/fsync/ntsync (the app sets `WINEESYNC=0`,
`WINEFSYNC=0`, `WINENTSYNC=0`), X11/Wayland/PulseAudio/ALSA (the app uses
`wineandroid.drv` and DirectAudio), AMD AGS (Proton-only), GameNative product
behaviour (hard-coded drive paths, Steam user name, clipboard bridge,
winebrowser, menu builder), and fixes already in upstream 11.4 (unixlib load by
name, `M_PERTURB`).

## Status

Verified locally, with no Android NDK reachable. The toolchain was llvm-mingw's
clang 21 with bionic headers from Termux's `ndk-sysroot` and generated API-28
link stubs, so CI with the real NDK r27 is the authority:

- All ten patches and `patch-wine.py --madeira` apply to the pinned tree.
- Configure succeeds for `aarch64-linux-android28` with `wineandroid.drv`,
  `winedirectaudio.drv` and `winevulkan` enabled.
- The full build completes: the unix side for Android arm64, and 1,211
  Windows modules for ARM64EC/ARM64 (ARM64X) and i386.
- Install, staging, stripping, licences and library collection complete.
- None of the 31 unix binaries imports a symbol nothing provides, or a bionic
  function newer than API 28.
- The output has every Wine file `package-runtime.py` requires.

Not verified:

- A clean end-to-end run of `build-wine-madeira.sh`. Each stage passed, but
  across several runs while fixes were added; the first CI run is the clean
  pass.
- Running on a device. Nothing here shows that Wine starts or that games run.

Next: run the CI job, offer `wine-madeira` as an alternative `wine` component
in packaging, and test on hardware (Android 9 and 13+, arm64, 16 KB pages).
