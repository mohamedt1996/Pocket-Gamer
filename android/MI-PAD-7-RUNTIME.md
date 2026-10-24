# Mi Pad 7 runtime audit (24 October 2026)

Target: the Chinese 128 GB Xiaomi Pad 7 reported by the tester. The screenshot
shows **Adreno 732**, the Qualcomm system **Vulkan 1.3.128** driver, and missing
features for the app's current DXVK and VKD3D-Proton requirements. The tablet's
RAM, Android build, kernel page size, and Vulkan feature list still need a device
capture. The 128 GB storage variant does not establish those properties.

## What the `android-proton-v0.1.0-alpha.6` APK actually carries

The published APK was downloaded and inspected, not inferred from its filename.
`verify-linux-proton-apk.py` passed its SHA-256 checks against the embedded
manifest: GE-Proton `proton.tgz` is 564,789,552 bytes and the Debian x86-64
`rootfs.tgz` is 137,807,981 bytes. The Proton archive contains both
`files/lib/wine/i386-windows/ntdll.dll` and
`files/lib/wine/x86_64-windows/ntdll.dll`, plus corresponding i386 and x86-64
Unix `ntdll.so` modules. The rootfs contains x86-64 glibc and libvulkan, but no
i386 glibc/loader, no 32-bit Linux translator, and no Vulkan ICD that connects
the x86-64 loader to Android's ARM64 Qualcomm driver. The APK contains a
16 KB-compatible Android ARM64 Box64 asset; the 4 KB build downloads separately
at installation. Its only installed Android `arm64-v8a` libraries are the
graphics path helper, probe, and Steam bridge. It has no `madeira-runtime.zip`
or `.sha256`, so the Android Wine/FEX path used by `WineActivity` is absent.

**Status:** Windows PE32 and PE64 Wine modules are present as files. A runnable
Android Wine/FEX stack, complete 32-bit execution path, GPU bridge, X11 server,
and successful on-device Proton session are not established by this APK. The
setup screen now says “Linux packages staged” rather than “Ready.”

The next combined APK workflow now compiles the repository's existing Android
ARM64EC Proton-Wine, FEX, x86/x64 DXVK and VKD3D modules, and bundles the
result alongside the Linux packages. Its APK verifier requires the native
runtime and the Windows modules for both bitnesses. CI packaging is still not
a Mi Pad 7 rendering test.

The subsequent Linux experiment adds an i386 Debian userland and an existing
16 KB-aligned Android Box64 build. It also bundles Mesa lavapipe
for both guest bitnesses. This is a **CPU-rendered Vulkan fallback** for testing
the Linux execution path, not an Adreno 732 graphics driver. It needs an X11
server on the tablet and has not been device-validated. Do not infer usable
game performance or EAC compatibility from its package checks.
The exported multiarch rootfs was validated locally; isolated x86-64 and i386
processes each created a Vulkan instance and enumerated one lavapipe device.
The i386 process ran through `qemu-i386-static` because the cloud host kernel
does not execute 32-bit x86 ELF directly. This validates the rootfs ICDs, not
Box32 on the tablet or Proton's X11 presentation. The pinned upstream Box32
build fails against Android's Bionic headers and glibc-only APIs, so this APK
does not include an Android Box32 executable. The i386 guest rootfs alone does
not provide a runnable 32-bit Linux process path.

## Device-specific graphics route

GameNative's current [Adreno default selection](https://github.com/utkarshdalal/GameNative/blob/11dfac2f36f8fd2b66aaed30b46f3914582cbe17/app/src/main/java/app/gamenative/utils/ContainerUtils.kt#L48-L62)
uses its **System** wrapper for Adreno 710/720/732 because its bundled Turnip
driver has no device-table entries for that family. Its [GPU detection](https://github.com/utkarshdalal/GameNative/blob/11dfac2f36f8fd2b66aaed30b46f3914582cbe17/app/src/main/java/com/winlator/core/GPUInformation.java#L181-L196)
explicitly matches 732. GameNative's [glibc/Turnip setup](https://github.com/utkarshdalal/GameNative/blob/11dfac2f36f8fd2b66aaed30b46f3914582cbe17/app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt#L6355-L6371)
also uses `TU_DEBUG=gmem` for this family. These are implementation clues, not
proof that a particular Turnip build works on this tablet. AdrenoTools
[describes Turnip packages](https://github.com/K11MCH1/AdrenoToolsDrivers/blob/main/README.md)
as Mesa drivers for supported Android apps; installing one there does not place
an x86-64 Vulkan ICD inside Pocket Arcade's Linux rootfs.

The first viable graphics experiment is a native Android ARM64 Vulkan wrapper
that can expose the system Qualcomm driver to the selected Wine stack, followed
by a trivial Vulkan presentation test and a D3D11 test. Keep the system driver
as baseline; only test a Turnip build after confirming it explicitly supports
Adreno 732 and the tablet's page size. The Settings GPU probe reports exactly
which features the current system driver lacks for this app's pinned DXVK and
VKD3D; Vulkan 1.3 alone does not imply those layers can create a device.
The repository's native Wine path already includes an Android Vulkan WSI
adapter in `runtime/android/patches/wineandroid_vulkan.c`; it creates an
`ANativeWindow`-backed `VK_KHR_android_surface`. The native build also points
Wine at Android's `libvulkan.so` rather than a glibc Vulkan loader. Bundling
that path is the practical 732 driver integration for this app. The Qualcomm
GPU driver itself is part of HyperOS and cannot be generated or redistributed
as an app library. Missing Vulkan features still require a compatible upstream
driver or a different renderer; a wrapper cannot invent hardware/driver
features.

## Runtime route

The existing repository has a separate CI recipe in
`.github/workflows/build.yml` that compiles an Android ARM64EC Proton Wine fork,
FEX, x86/x64 graphics modules, and addons into the legacy-named
`madeira-runtime.zip`. Its headless VM checks are distinct from a Mi Pad 7 GPU
test. That bundle must be built, packaged into the same APK, and checked against
the tablet's page size and Vulkan driver before claiming Android Wine support.
The legacy filename is an app loader contract, not the Wine distribution name.

The app-private paths (Android may show the equivalent `/data/data/...` alias)
are `/data/user/0/org.pocketarcade.madeira/files/linux-proton/rootfs`,
`.../linux-proton/proton`, and `.../runtime-components/box64-0.4.2` for the
Linux path. The native Android path, when bundled, extracts to
`.../madeira-runtime-v2`. An X11 `DISPLAY=:0` names a display endpoint; it
does not install or start an X server. Neither an `arm64-v8a` Android
`libvulkan.so` nor an Android Turnip package can be loaded directly by the
x86-64 `libvulkan.so.1` in the Linux rootfs. An explicit bridge and matching
loader/ICD path are required.

[GameNative proton-wine](https://github.com/GameNative/proton-wine/releases)
also publishes a Bionic ARM64EC `.wcp` with an Android-linked ARM64 Wine binary,
and i386 and ARM64 Windows modules. It lacks this app's JNI `wineandroid.so` and
FEX translator components and cannot be dropped into `WineActivity` as-is.
It is a candidate only with its matching GameNative-style X server, graphics
wrapper, translators, and launch environment. Use its upstream code and packages
as references rather than assuming a `.wcp` import makes the old launcher work.

For the Linux route, [GE-Proton's upstream README](https://github.com/GloriousEggroll/proton-ge-custom#readme)
says non-Steam GE-Proton runs are supported through `umu-launcher` and its
Steam runtime container. Pocket Dock currently invokes `box64` → x86-64 Python
→ `proton run` directly with a minimal Debian rootfs. Its current setup can
therefore be considered an experimental launch probe, not an upstream-supported
Proton environment. A full Linux route needs an Android-compatible host for
the Steam runtime/container, an x86-64-to-ARM64 Vulkan bridge, an accessible
display and input path, plus a verified 32-bit strategy. EAC is a separate
official Steam runtime and still requires publisher enablement; no local file
change can guarantee server acceptance.

On the tablet, record `adb shell getconf PAGE_SIZE`, `adb shell getprop
ro.build.version.release`, `adb shell getprop ro.product.device`, the full GPU
feature list in Settings, and one Pocket Dock `proton.log`/`status.json` after a
simple desktop launch. Those results decide whether the existing Android fork
or a GameNative-style Bionic stack is the faster working route for this device.
