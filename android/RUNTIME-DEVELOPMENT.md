# Android runtime continuation

Target: the user's Xiaomi Mi Pad 7, Chinese 128 GB variant. Confirm the actual
model, Android/HyperOS build, RAM, kernel page size and Vulkan driver on the
device; storage capacity alone does not establish these properties. No thermal
or FPS measurements from this tablet are available in the cloud environment.

## Working baseline

Continue `fix/android-runtime` from `95b3c96a39a68a8d9d814e44dcdabd515f71864d`.
Madeira Wine has already been removed. The remaining runtime uses the pinned
Proton 11 Android fork and upstream FEX with the project's Android and 16 KB
guard patches. Preserve the tested loader, bionic, Vulkan and prefix fixes.

[CI run 39960804524](https://github.com/mohamedt1996/Pocket-Gamer/actions/runs/39960804524)
completed successfully for that commit. It reports the 15 headless runtime
checks passing on native ARM64 and the 16 KB Neoverse-N1 VM. That covers neither
this tablet's GPU nor game rendering, power use or temperature. The intermittent
x86 D3D11 teardown hang remains unresolved even when one run passes.

The frame limiter and compiler-thread options were checked against the pinned
[DXVK config](https://github.com/doitsujin/dxvk/blob/ef3ef8712ecfd619775662ee8d2778bf7db89af4/dxvk.conf),
[VKD3D-Proton docs](https://github.com/HansKristian-Work/vkd3d-proton/blob/0bb64c56c8f0dcc11ecc0f6c56e00e4f7bd8d506/README.md),
and [FEX option definitions](https://github.com/FEX-Emu/FEX/blob/d7b8e1497a646fa7cdf63c38215c1f7c3a52852f/FEXCore/Source/Interface/Config/Config.json.in).
They are real upstream options. Their presence alone does not show a reduction
in CPU time, heat or frame times on a given game. In particular, disabling TSO
can break multithreaded games; Balanced remains the default.

## FEX and WoWBox64 together

There is a plausible architecture split, rather than running two translators
over the same code:

| Guest code | Current Wine translator slot | Experimental candidate |
|---|---|---|
| x64 / ARM64EC | `HKLM\\Software\\Microsoft\\Wow64\\amd64` | Keep `libarm64ecfex.dll` |
| x86 / WoW64 | `HKLM\\Software\\Microsoft\\Wow64\\x86` | Replace `libwow64fex.dll` with `wowbox64.dll` |

Wine already selects these slots separately in `WineLaunch.seedRegistry`.
A 64-bit game and its 32-bit helper could therefore use different translators
in different processes. This does not combine their JITs, parallelize one game
across both, or guarantee lower CPU use. Ordinary Linux Box64 is not a drop-in
replacement for the native bionic ARM64EC Wine loader.

Source inspected: upstream Box64 commit
`420eda69ff51ab5d5a7cbc3dee2e70433be3411d`:

- [WoW64 build](https://github.com/ptitSeb/box64/blob/420eda69ff51ab5d5a7cbc3dee2e70433be3411d/wine/wow64/CMakeLists.txt)
  builds a native ARM64 Windows `wowbox64.dll` with LLVM MinGW, exporting
  Wine's `BTCpu*` interface. It uses Windows/NT imports, so a separate Linux
  Box64 process or a glibc root filesystem is not inherently required.
- [WoW64 entry point](https://github.com/ptitSeb/box64/blob/420eda69ff51ab5d5a7cbc3dee2e70433be3411d/wine/wow64/wowbox64.c)
  still initializes `box64_pagesize` to 4096 with no host-page-size update in
  that file. Page-protection assumptions need investigation on 16 KB Android,
  particularly after FEX's guard-page bug. This is a compatibility concern,
  not proof that every 16 KB workload fails.
- Its top-level ARM64EC option is explicitly described as a module with
  **no emulation yet** at this revision. Keep FEX for x64.
- The upstream build currently uses LLVM MinGW 20260616 in CI, while this
  project pins 20250920. Establish toolchain compatibility before adding a
  build job; do not silently update the toolchain for the existing runtime.

The Epic launcher now offers an optional, checksum-pinned WoWBox64 DLL from
GameNative. It is selected in a separate prefix, while FEX remains in the
amd64 slot. This is an experimental integration, with no device execution
result yet. The exact source revision of the prebuilt DLL is not identified;
see [component provenance](EPIC-UPSTREAM.md). Before treating it as compatible,
verify its NT imports against the pinned Wine, set the x86 translator before
wineboot, and log the loaded translator. Run all x86 CPU/registry,
child-process, exception, Vulkan, D3D11/D3D12 and audio checks on both page
sizes. Also run x64 launching an x86 helper. Compare against FEX-only with
identical Wine, graphics, prefix seed and game settings. Keep it experimental
until those results and a device run exist.

## Mi Pad 7 measurements

Use one repeatable game scene after shader caches are warm. Start each run at
similar battery level and temperature, unplug the charger, keep brightness,
resolution and refresh rate fixed, and record whether Xiaomi's game booster,
battery saver and sustained-performance mode are enabled. Run long enough to
observe throttling, not just the initial boost. Stop if Android reports a
critical thermal state.

Begin with Balanced translation and a 30 FPS cap, then compare 48 and 60 FPS.
48 is useful for testing a 144 Hz display, but HyperOS may select a different
refresh rate for the app; verify the active rate. Compare Fast only after
establishing a stable baseline. Do not disable thermal protection or force
CPU clocks/affinity as an assumed optimization.

Capture the actual rendered FPS/frame times, Android thermal status and battery
temperature, process CPU time, and power/current readings when the device
exposes them. Battery temperature is not the SoC temperature, and instantaneous
current varies too much to establish energy use from one sample. Record game,
API, bitness, cap, CPU mode, driver, and sustained-performance setting with
each run. `PocketThermals` logcat entries record the device, launch choices,
power-saver state and thermal-status transitions.

Automatic caps are evaluated at session start and currently cover DXVK and
VKD3D-Proton. They do not implement live thermal throttling or cap WineD3D.
Explicit caps and Off remain explicit choices even when power saver is on.

## D3D11 hang experiment

On the prepared native ARM64 runner (the setup requires bionic, lavapipe,
MinGW-built smoke programs, and permission to create the test app paths):

```sh
SMOKE_D3D11_ITERATIONS=20 bash runtime/android/tests/smoke/run.sh app.apk tests-bin logs
```

This repeats new x86/x64 processes, preserving per-iteration results and stopping
after the first failed pair. The limit is 1–100; the default is one, preserving
the normal CI workload. The original CPU, audio, Vulkan and D3D12 checks still
run. Device creation/release phase messages go to each program's result file,
and the existing timeout handler collects thread stacks and wait channels.
No failure is retried into success. Use the final phase and stacks to distinguish
DXGI-adapter release, DXGI-device release, D3D11-device release and later
process shutdown before changing synchronization or memory-ordering defaults.
