# Pocket Arcade for Android, built on Madeira

The app is now a port of Madeira's iOS interface and Steam client to Android
(Kotlin, Jetpack Compose), licensed GPL-3.0-or-later like Madeira
(`COPYING`, `LICENSE-EXCEPTION.md`; files ported from Madeira keep its SPDX and
copyright lines).

What the app does:

- **Setup** on first start: Steam sign-in, Madeira Dock's components, Wine Mono.
- **Steam sign-in** with password + Steam Guard (email or authenticator code, or
  approval in the Steam Mobile app), or a QR code. Only the refresh token is
  kept, encrypted with an Android Keystore key.
- **Library**: Steam games with their artwork, playtime and last played, games
  not installed yet, and games added by hand; search, sort, three layouts, pull
  to refresh, Liquid Metal title and ambient artwork glow.
- **Downloads** from Steam's CDN (port of SwiftSteam: PICS, depot keys,
  manifests, AES + VZ/VSZa/zip chunks, resumable journals, `.acf` records),
  in a foreground service, with pause, resume, update, check files and uninstall.
- **Steam Cloud** saves: sync before and after a session, conflicts resolved by
  your choice, replaced copies kept as backups.
- **Madeira Dock**: Steam games start through Valve's own client (downloaded
  from Valve, SHA-256-pinned) with `dockhost.exe` from willfaust/madeira-dock,
  including the games' one-time installs. "Start with › The game" skips Steam.
- **In game**: touch controls (Xbox layout and a keyboard layout, editable), an
  XInput bridge so physical controllers and touch controls appear as Xbox pads,
  keyboard/mouse mode, an on-screen keyboard, FPS overlay, and a session menu.
- **Per-game settings**: graphics (Auto picks DXVK/VKD3D/Wine from the program's
  imports), touch controls, performance overlay, arguments, environment, DLL
  overrides, runtimes (Visual C++, installers).

The original test interface (`MainActivity`) is still in the APK under
Settings › Troubleshooting › Developer tools.

CI: `android_app` runs the Kotlin unit tests (`gradle testDebugUnitTest`) and
builds an APK without the Windows runtime in a few minutes. The `addons`
component builds `dockhost.exe` and the XInput bridge DLLs; `package-runtime.py`
requires them in the runtime.

Steam is a trademark of Valve Corporation. This project is not affiliated with
Valve; Valve's client components are downloaded from Valve on request.

---

# Madeira Android port candidate — source integration, not a verified release

This revision replaces the externally supplied launcher as the primary Android
path. The workflow now attempts to build Wine ARM64EC/WoW64 for Android bionic,
FEX's two Windows translation modules plus matching bionic Unix libraries,
DXVK and VKD3D-Proton. It assembles the resulting files into the APK. The source
archive itself contains no precompiled runtime, APK or IPA.

## Install this source update

Copy **all** archive contents into the repository root, including `.github`,
`android`, and `runtime`. Replace the existing `.github/workflows/build.yml`.
Push to `main` to start the unified workflow. Do not upload only build.yml:
the Android build now uses the accompanying source and scripts.

The original iOS jobs and embedded iOS payload are unchanged. Android uses
standard `ubuntu-24.04` runners, three component jobs, independent source/recipe
cache keys, and a final APK job. A failed component prevents APK packaging.
`publish_releases` remains an optional manual input for separate prereleases.

## Implemented in this revision

- Locked Wine, FEX, DXVK, VKD3D and ntsync source revisions.
- Android Wine build using the pinned fork's bionic patches and dependency SDK.
- A Wine Android Vulkan driver adapter, registered with the Wine driver table.
- Android extension generation in Wine's Vulkan dispatch tables.
- FEX ARM64EC and WoW64 PE builds, with a matching Android Unix-call companion.
- A private-file implementation of FEX's POSIX shared-memory statistics API.
- Runtime dependency collection, PE/ELF architecture checks, bionic interpreter
  checks, per-file SHA-256 inventory, staged installation and rollback backup.
- Wine's matching Java window/input implementation in a separate app process.
- One Wine prefix per game, game ZIP import, executable selection, graphics
  selection, installer import and HTTPS installer download with bounded redirects.
- Microsoft Visual C++ x86/x64 download entries. AIR, .NET, Mono and other
  dependencies can be imported as EXE/MSI or supplied by HTTPS URL per game.
- Basic W/A/S/D/Space/Enter/Escape touch keys; physical keyboard/mouse handling
  uses Wine's Android driver. This is not an XInput gamepad implementation.
- x86/x64 CPU/thread/file smoke programs, D3D11 presentation tests, and D3D12
  device-creation tests, built in CI and selectable inside the app.

Dependency installers are launched interactively in the selected game's prefix.
Downloading an installer is not recorded as successful installation. The app
cannot guarantee that AIR, .NET or any individual game will work under Wine.
It does not download arbitrary individual Windows DLLs from DLL collection sites.

## What has actually been verified here

CI builds every component and the APK, runs the Kotlin unit tests, and
checks that the app's native libraries are 16 KB page aligned.

The `android_runtime_test` job then **runs** each APK's Windows runtime on a
native arm64 runner with Android's own bionic libc and linker (Termux
`aosp-libs`, Android 9), the way the app runs it: the runtime unpacked to the
app's path, the prefix and environment `WineLaunch` creates, no
`LD_LIBRARY_PATH`, programs started through `explorer /desktop=shell,...` like
`WineActivity` starts games. See `runtime/android/tests/smoke/`.

| Check | Result |
|---|---|
| wineboot (new 64-bit prefix) | passes |
| x64 program via FEX ARM64EC: threads, memory, exceptions, files, registry, child process, timers, sockets | passes |
| x86 program via FEX WoW64: same checks | passes |
| Vulkan (lavapipe CPU driver standing in for the GPU) | passes, x64 and x86 |
| Direct3D 11 device through DXVK | passes, x64 and x86 |
| Direct3D 12 device through VKD3D-Proton | passes, x64 and x86 |
| Sound: WASAPI → DirectAudio → AAudio (a stand-in AAudio that measures what arrives) | passes, x64 and x86 |

The APK must pass this job. A failing program is run again directly; a crash
leaves a core whose report (`core.py`) names each address's library or PE
image.

### One Wine, one translator

The app ships one runtime: the Proton 11 Android fork (ARM64EC Wine built
against bionic) with upstream FEX for x64 (ARM64EC) and x86 (WoW64) programs.
FEX runs only the game's code as x86; Wine itself runs natively, which keeps
the CPU cost per system call low. The experimental second APK around
Madeira's iOS Wine and FEX fork has been dropped; the app keeps Madeira's
design, Madeira Dock and its log keeping.

The `android_emulator_test` job runs the app itself (without the runtime) on
Android 11, 14, 15 and 16 emulator images with ARM64 translation: install,
start, first screen, the GPU check, 400 random taps/swipes/back presses, and a
restart after Android kills the process, failing on any crash or ANR.

### The phone's GPU

Games always draw on the phone's own GPU: DXVK / VKD3D-Proton / Wine's
Direct3D → Wine's Vulkan → Android's `libvulkan.so` → the Adreno or Mali
driver. (lavapipe is only CI's stand-in.)

Current DXVK and VKD3D-Proton need more than many phone drivers offer (Vulkan
1.3 plus BC textures, 64-bit shader integers, maintenance5/6, robustness2,
and for Direct3D 12 transform feedback and push descriptors; Mali GPUs have no
BC textures at all). So the app asks the driver at start (`gpu_caps.c`,
checking the requirements written in the pinned DXVK and VKD3D-Proton
sources), shows the result under Settings › GPU, and **Auto** graphics steps
down instead of failing: VKD3D-Proton → DXVK with Wine's own Direct3D 12 →
Wine's own Direct3D on its Vulkan renderer (which decodes BC textures itself).
Wine's own Direct3D now uses that Vulkan renderer everywhere; its default,
desktop OpenGL, does not exist on Android, so before this the "Wine" path could
not draw. On the Android 15/16 emulators the check correctly reports that
SwiftShader cannot run DXVK. (WineD3D itself cannot be exercised by the
headless runtime test: its DXGI needs a display output that Wine's null driver
does not have.)

### Heat and CPU

- **Frame cap** per game (`core/Performance.kt`): Auto = 60 fps, or 30 in
  Android's battery game mode; 30/40/60/off by hand. DXVK reads it from
  `DXVK_CONFIG`, VKD3D-Proton from `VKD3D_FRAME_RATE`. DXVK compiles shaders on
  three threads instead of one per core.
- **CPU mode** per game: Balanced (FEX's defaults), Compatible (stricter
  vector/memcpy ordering) or Fast (no x86 memory-ordering emulation, reduced
  x87 precision: much less CPU, for games that tolerate it). Set through
  FEX's `FEX_<OPTION>` environment, which its Windows modules read.
- **Steady performance** (Settings › Heat, on by default): Android's sustained
  performance mode for the game window where the phone supports it.
- The app declares itself a game (`appCategory="game"`, game mode config), so
  the system's game dashboard and phone makers' game boosters apply; thermal
  status changes are logged and a hot phone is reported once.
- Wine's fast sync stays off: this Proton 11 fork has fsync and ntsync only,
  and Android has neither `/dev/shm` for fsync nor the ntsync driver.

### 16 KB page devices (Android 15+)

`android_pagesize_test` boots Debian's arm64-16k kernel in QEMU on the CI
runner, swaps Android 9's bionic for Android 15's page-size-agnostic linker
and libc (taken from Google's arm64 emulator image), and runs the same
runtime test. It found that every process Wine starts died at exec: the
static `wine-preloader` had 4 KB-aligned segments. Both Wine builds now link
it 16 KB aligned, and packaging fails if any runtime ELF is not 16 KB aligned.

With that and the FEX guard fix below, **the runtime passes all 15 checks on
16 KB pages** (x64 and x86 programs through FEX, Vulkan, D3D11,
D3D12, sound), as they do on 4 KB pages.

Notes from getting there:

- The VM emulates a Neoverse-N1. On QEMU's ARMv9 models (Cortex-A710 with or
  without SVE, and `max`) x86 programs under FEX fail, but they fail the same
  way on a 4 KB kernel and with Android 9's bionic, while the identical build
  passes natively on the ARMv9 CI runner (Neoverse-N2). That is the emulator,
  not the runtime.
- Windows pages are 4 KB. On a 16 KB kernel Wine applies the union of the four
  4 KB protections to each 16 KB page, so a no-access guard page or a
  read-only page next to writable data is not enforced at 4 KB granularity.
  FEX relied on two such guard pages: its call-ret stack's (the Proton
  build's 32-bit wineboot step ran past it and left the 32-bit registry view
  empty) and its JIT buffer's. FEX now uses 64 KB guard regions
  (`patches/fex-16k-guards.patch`). A game relying on
  fine-grained guard pages or write traps of its own might still notice.

**Not covered:** Wine's window, input and presentation on a real Android surface
(`wineandroid.drv` needs the app's Java side, and Wine cannot run inside the
emulator's ARM translation), a real phone's GPU driver, a real speaker, and
actual games. Those still need a physical-device run.

## Important unresolved integration risks

The 32-bit Direct3D 11 device check sometimes hangs after its device was
created (seen in about one run in four, natively and in the VM):
every thread of the program waits and lavapipe's threads are idle. It has not
been diagnosed yet.

Wine's legacy Android driver uses private native-window interfaces and a legacy
loader search-path hook. Modern device/vendor compatibility is not established.
The Vulkan adapter uses Wine's per-window IPC wrappers; whether a given Android
Vulkan loader accepts those windows must be tested. Resize/surface-loss recovery
and synchronization need validation. A successful blue-frame lab check alone
would not establish that the Wine Vulkan path works.

The APK retains target SDK 28 for this experimental sideloaded build. It is not
an App Store/Play Store distribution configuration. Loading native code from app
storage may be rejected by device policy. Full XInput controller support,
customizable touch layouts, exhaustive media compatibility and a searchable
DLL recipe catalog are not implemented.

## Combined acceptance run

1. Obtain a successful `android_components` and `android_port` workflow run.
2. Install the APK. Prepare the bundled runtime.
3. Add each CPU smoke test and run it. Confirm PASS and the output text file.
4. Import a small standalone Windows app. Confirm visible windows and input.
5. Run both D3D11 smoke tests; confirm an actual blue game window.
6. Run both D3D12 device tests. These do not prove D3D12 rendering; a real
   D3D12 presentation test is still required.
7. Test rotation, background/return, close/relaunch, stop, and two distinct game
   prefixes. Install a dependency in one prefix and confirm the other is isolated.
8. Test representative games and retain `wine.log`, DXVK and VKD3D logs.

Use Android Back in the Wine session to stop it before starting another game.
The app's legacy external-runtime importer remains available for investigation;
it is independent of the new bundled runtime path.
