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

- Python and shell syntax; Java syntax parsing (not Java compilation).
- Wine patch anchors against the exact downloaded source revision.
- Workflow parsing and preservation of all original iOS/unrelated jobs.
- Host compilation and execution of the shared-memory adapter regression test.

**Not run:** the full GitHub Actions build, Android Java/native compilation,
Wine/FEX execution, Windows smoke programs, or a physical-device graphics test.
This is an implementation candidate, not a claim of a completed working port.

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
