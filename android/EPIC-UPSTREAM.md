# Epic integration and reused components

This integration reuses the Epic BuildPatch manifest implementations from
[GameNative](https://github.com/utkarshdalal/GameNative/tree/9c5f4121eb6512707eb467d4a70edb3ae8f08ae3),
commit `9c5f4121eb6512707eb467d4a70edb3ae8f08ae3`, licensed GPL-3.0.
This repository's `android/COPYING` contains the GPL v3 licence text.

Copied/adapted source files:

- `service/epic/manifest/EpicManifest.kt` → `epic/manifest/EpicManifest.kt`.
- `service/epic/manifest/JsonManifestParser.kt` → `epic/manifest/JsonManifestParser.kt`.

Local changes rename the package, bound manifest sizes, verify hashes on both
compressed and uncompressed manifests, and use a bounded zlib decoder. The
parsers retain their original structure and serialization support. Timber is
used by these upstream parsers, with no log tree installed by this integration.

The Epic OAuth endpoints, installed-launcher client identity, library/catalog
pagination, manifest CDN query handling, chunk format and launch arguments are
adapted from GameNative's EpicAuthClient, EpicConstants, EpicManager,
EpicDownloadManager and EpicGameLauncher and the
[Legendary project](https://github.com/derrod/legendary). The HTTP/account,
foreground-download and Compose adapters integrate those flows with this app;
there is no embedded desktop launcher or Python runtime.

## Account and installation behavior

Open Epic's official sign-in in a browser and paste the one-use authorization
code/JSON into Pocket Arcade. The app does not request an Epic password. Account
and refresh tokens are encrypted with a separate Android Keystore key and are
not written to the library cache, install markers or launch reports. Sign-out
cancels active work and clears the account vault and library cache.

The Windows library is paginated, catalog titles are resolved, and private/UE
assets, non-Windows platform entries and catalog add-ons are excluded. Downloads
run in an Android foreground service with Pause, perform manifest/chunk/file
checksums, and reuse completed files only after rehashing them. They download
full chunks rather than implement delta patching. Interrupted files resume from
verified chunk-part checkpoints, then receive a full-file hash check before
installation. An interrupted update blocks launch until verification completes.
Downloaded game content and
per-translator Wine prefixes remain separate. Optional DLC selection, cloud
saves and ownership-token-only title support are not implemented in this first
Epic integration.

The manifest launch executable and command are used, rather than guessing a game
EXE or skipping its anti-cheat bootstrapper. EOS deployment IDs from the asset
sidecar are forwarded. One-use exchange codes are supplied only to the launched
game; they are not included in the diagnostic report.

## Existing WoWBox64 component, no runtime rebuild

The optional component is downloaded from the immutable GameNative asset URL:

`app/src/main/assets/wowbox64/wowbox64-0.4.2.tzst` at the commit above.

- Archive SHA-256: `90e6fefea2c9189c2556d6e0661ec4325dfc41fa82413a44a2485ae223e38efd`
- DLL SHA-256: `37f8dff29d71291c2715be9c82706e80f5a33223c0ed30bd3490311cf0596bde`
- Extracted DLL: `wowbox64.dll`, Windows ARM64 (`IMAGE_FILE_MACHINE_ARM64`).
- Project/source information: [Box64](https://github.com/ptitSeb/box64), including
  its Wine WoW64 module and its bundled notices/licences. The GameNative asset
  labels this binary 0.4.2; its exact Box64 source revision has not been established
  from the archive, which contains only the DLL.

The compressed and extracted hashes and PE architecture are verified before
activation. The existing app zstd decoder is reused. This DLL is fetched as an
optional external component, not bundled into the APK. Its compatible execution
on this Wine revision and the Mi Pad 7 remains experimental.

The separate Linux Proton experiment starts with GameNative's existing
`box86_64/box64-0.4.2-bionic.tzst` at the same immutable commit. The archive's
SHA-256 is `fcd10730ff899bad829a5e91780d8aae6350a7e152069ff2c90e93922ecad192`;
its sole `./usr/bin/box64` binary has SHA-256
`d0e7170fa6a8a2e2609e4e6a84b1ce19fbe39f843ae1e84d91e43e0a6f190883`.
It is an ARM64 ELF built for Android 31 using `/system/bin/linker64`. The app
downloads it only on request, validates both hashes and ELF headers, and can
probe `box64 --version` on the device. The existing JNI zstd decoder expanded
the actual 3.5 MB archive to its 21,862,400-byte TAR in a host check.
This prebuilt Box64 has 4 KB ELF load alignment. On 16 KB page kernels, the
installer instead uses the bundled `runner/box64-android-arm64-16k.zst` built
from [Box64 revision 420eda69](https://github.com/ptitSeb/box64/tree/420eda69ff51ab5d5a7cbc3dee2e70433be3411d)
with NDK r27d, Android API 31 and `-Wl,-z,max-page-size=16384`. The stripped
binary SHA-256 is `99d5c29ce068b35f92bf8500cf6966d94dc5ef8fd6c9b0242f1a006bd55a0b4e`;
its zstd asset SHA-256 is `9968eb903442ffaad060657f928d2c6b9270e4d51773ee5cc29dc58cb2c5cfcb`.
The binary is ARM64 ELF with `/system/bin/linker64`, Android libc/m/dl
dependencies and 16 KB load alignment. Its 4.54 MB compressed asset and the
upstream MIT license notice are included in the APK. The build recipe is
`runtime/android/build-box64-16k.sh`. Both binary and compressed hashes are
checked on device before installation. The Mi Pad 7's actual page size and
Box64 execution still need an on-device check.
This component does not yet launch Proton, load EAC or render a Linux game.

The pinned Wine fork selects its x86 translator via
`HKLM\\Software\\Microsoft\\Wow64\\x86`; it does not implement GameNative's
`HODLL` override. The adapter updates that registry value in an isolated prefix,
leaving `amd64=libarm64ecfex.dll` intact. No Wine/FEX rebuild is introduced;
the optional Linux rootfs is imported separately. Existing native component CI cache keys are
unchanged; a full-runtime APK can reuse cached Wine/FEX/graphics/addons outputs.

## EAC status and validation boundary

Epic sign-in and downloads do not imply anti-cheat compatibility. The normal
EAC/bootstrapper executable stays in the launch path. `epic-launch-report.json`
records the selected executable, translator, EOS deployment presence and host
ABI without tokens, and explicitly reports online compatibility as unverified.
An optional EAC loader trace records only whether Wine observed a Windows EAC
module load, an EAC-related error, or a Linux bridge filename. Raw Wine lines
can contain a one-use Epic exchange code and are never copied into this report.

At [Heroic commit 509a34f](https://github.com/Heroic-Games-Launcher/HeroicGamesLauncher/blob/509a34feea8541b73c928e289f2cf262d432db4a/src/backend/launcher.ts),
the EAC option downloads a Linux runtime and sets `PROTON_EAC_RUNTIME` only on
Linux. At [Proton commit bc89621](https://github.com/ValveSoftware/Proton/blob/bc896217ffcd67f892f952b010f898fe7bd0f0a1/Makefile.in),
its EAC bridge builds x86-64 and i386 Unix modules. The pinned Android Wine
tree has no EAC bridge source; this app builds only FEX's ARM64EC and WoW64
Windows modules with ARM64 Android Unix companions. This ARM64/bionic process
cannot load Proton's x86-64 Linux bridge merely by setting an environment
variable. WoWBox64 only replaces the 32-bit Windows translator slot. A working
host ABI bridge or a full compatible Linux x86-64 Proton execution path must be
established before claiming Rocket League online support. This integration
does not spoof, disable or bypass EAC and does not install arbitrary Linux
anti-cheat binaries as if they were Android libraries.

An existing Android glibc/Box64 route was also checked at GameNative commit
`9c5f4121eb6512707eb467d4a70edb3ae8f08ae3`. Its `GlibcProgramLauncherComponent`
starts an x86-64 Linux Wine executable through native ARM64 Box64, with a
separate glibc image and library paths. The included
`proton-9.0-x86_64_container_pattern.tzst` is an 8.3 MB Wine prefix template;
its file listing contains no Proton executable or EAC bridge/runtime. It
cannot be inserted into this app as a ready EAC solution. A candidate for
device research is **full x86-64 Linux Proton** under a Linux ELF translator,
with a matching glibc userland, Proton EAC bridge, official EAC runtime,
Vulkan/graphics integration and Epic launch credentials in the same session.
Each component must run together on the tablet. Even then, Rocket League's
online EAC acceptance is determined by the game's service, so a real match is
the acceptance test. The optional [Linux Proton path](LINUX-PROTON.md) now
imports complete upstream packages and starts Box64 → x86-64 Python → Proton
in a separate foreground service and prefix. It requires an externally
reachable X11 display and has not been executed on the Mi Pad 7. The path is
an experimental device test, not established Rocket League EAC support.
The [umu launcher](https://github.com/Open-Wine-Components/umu-launcher) uses
Steam Linux Runtime and its `pressure-vessel` launch client for normal Proton
sessions. That container path cannot simply be invoked from this Android app:
it needs a compatible Linux userland and process/container features in addition
to translating x86-64 instructions. A bare Wine prefix template and the
optional WoWBox64 DLL do not provide those features.

On the Mi Pad 7, enable the EAC loader diagnostic for one launch, then inspect
the app-private `epic-launch-report.json` and `sessions/<translator>/wine.log`.
The report contains stage flags, while the raw log is sensitive and should be
redacted before sharing. Check the game bootstrapper, EOS sign-in, EAC module
load, EAC acceptance, and actual online matchmaking separately. A module load
is not proof of EAC server acceptance. Compare FEX-only and the optional
WoWBox64 prefix only after a working host bridge exists.

Automated tests exercise authentication requests, library pagination, manifests,
chunk checksums, file installation/resume, traversal rejection, command quoting,
registry isolation and report contents using synthetic data and fake HTTP.
Live account login, Epic CDN downloads, the optional component on an Android
device, and Rocket League online matches still require device validation. An
app-only debug APK can exercise account/library/download UI; playing requires
the full-runtime APK.
