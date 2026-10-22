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

CI builds every component and both APKs, runs the Kotlin unit tests, and
checks that the app's native libraries are 16 KB page aligned.

The `android_runtime_test` job then **runs** each APK's Windows runtime on a
native arm64 runner with Android's own bionic libc and linker (Termux
`aosp-libs`, Android 9), the way the app runs it: the runtime unpacked to the
app's path, the prefix and environment `WineLaunch` creates, no
`LD_LIBRARY_PATH`, programs started through `explorer /desktop=shell,...` like
`WineActivity` starts games. See `runtime/android/tests/smoke/`.

| Check | Default APK (Proton fork + upstream FEX) | Madeira APK (Madeira's Wine + Madeira's FEX, Android mode) |
|---|---|---|
| wineboot (new 64-bit prefix) | passes | passes |
| x64 program via FEX ARM64EC: threads, memory, exceptions, files, registry, child process, timers, sockets | passes | passes |
| x86 program via FEX WoW64: same checks | passes | passes |
| Vulkan (lavapipe CPU driver standing in for the GPU) | passes, x64 and x86 | passes, x64 and x86 |
| Direct3D 11 device through DXVK | passes, x64 and x86 | passes, x64 and x86 |
| Direct3D 12 device through VKD3D-Proton | passes, x64 and x86 | passes, x64 and x86 |
| Sound: WASAPI → DirectAudio → AAudio (a stand-in AAudio that measures what arrives) | passes, x64 and x86 | passes, x64 and x86 |

Both APKs must pass this job. A failing program is run again directly; a
crash leaves a core whose report (`core.py`) names each address's library or
PE image, and Madeira's Wine prints the caller of any branch into non-code
memory.

### How the Madeira APK runs Madeira's design on Android

Madeira's Wine (willfaust/wine) and FEX (willfaust/FEX) were written for iOS.
They are kept as they are, with Android changes as patches
(`runtime/android/madeira/`):

- **FEX in Android mode** (`fex-android.patch`, `-DFEX_ANDROID_HOST`): upstream
  FEX's Linux design where iOS has its own (x18 is the TEB, plain RWX memory,
  signals), the fork's host-independent parts where Madeira's Wine relies on
  them, and fixes for paths the fork only ran on iOS: no implicit TLS in the
  Windows modules, CheckCall keeping already-native ntdll targets, no
  guard-page "restart" before a guard page exists, lookup cache committed up
  front, no syscall parking (it serves the iOS-only sweeper).
- **Wine**: iOS assumed a 39-bit user space. On Linux/Android Wine maps modules
  and stacks near 2^47, so the ARM64EC call checker sent every ntdll call into
  the emulator and the IO_STATUS_BLOCK guard refused every socket (patch 0013);
  Wine's own host address-limit detection replaces a hard-coded 39-bit limit
  (patch 0001). Madeira's iOS-only unix calls get Android entries (0011).

The `android_emulator_test` job runs the app itself (without the runtime) on
Android 11 and 14 emulator images with ARM64 translation: install, start, first
screen, 400 random taps/swipes/back presses, and a restart after Android kills
the process, failing on any crash or ANR.

**Not covered:** Wine's window, input and presentation on a real Android surface
(`wineandroid.drv` needs the app's Java side, and Wine cannot run inside the
emulator's ARM translation), a real GPU driver, a real speaker, and actual games.
Those still need a physical-device run.

## Important unresolved integration risks

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
