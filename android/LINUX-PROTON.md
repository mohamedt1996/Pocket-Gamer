# Optional Linux Proton session on Android

This is a separate, opt-in Box64 session for Epic games. It runs a complete
x86-64 Linux Proton distribution through an x86-64 Linux Python interpreter;
the existing Android Wine/FEX session and its prefixes remain separate. The
app launches the game's original Epic executable and arguments with `proton
run`, sets the Steam compatibility paths, and supplies the official EAC runtime
path when the game requires it. It does not alter EAC files or bypass checks.

## Prepare existing components

Install Box64 from the Epic screen. To create an APK with verified GE-Proton and
an x86-64 Debian rootfs, run from the repository root:

```sh
runtime/android/build-linux-proton-packages.sh
cd android
gradle --no-daemon --max-workers=2 -Dorg.gradle.jvmargs='-Xmx12g -Dfile.encoding=UTF-8' assembleDebug
```

This produces `android/app/build/outputs/apk/debug/app-debug.apk`. The larger
Gradle heap is needed while packaging these assets. Tap **Install included Linux packages** to extract
them into app-private storage. The packages add about 703 MB to the APK and
need several gigabytes free while extracting. The standard small APK can still
use the three **Import .tar.gz** buttons to select existing packages:

GitHub Actions runs the same build in **Android Linux Proton APK** on pushes to
`main`, and the downloadable **PocketArcade-Android-Linux-Proton** build artifact
contains the APK, the separate Proton and rootfs archives, Box64, the manifest,
setup notes, and `SHA256SUMS.txt`. From the Actions page, run this workflow on
`main` with **publish_release** enabled to publish those files as a pre-release.
The release assets remain available after the temporary Actions artifact expires.
The workflow verifies the APK signature, 16 KB native library alignment, and
archive bytes before publishing.

1. **rootfs**: a relocatable x86-64 Linux root filesystem with executable
   `usr/bin/python3` and glibc (`lib/x86_64-linux-gnu/libc.so.6` or
   `usr/lib/x86_64-linux-gnu/libc.so.6`). Linux x86-64 support libraries needed
   by Proton, X11 and Vulkan must also be present. An ARM64 rootfs is not
   suitable. The importer relocates absolute symlinks inside the rootfs.
2. **proton**: a complete Linux x86-64 Proton or GE-Proton release archive with
   `proton` and `files/bin/wine`. Do not use a Wine prefix template. Upstream
   distribution: [Valve Proton](https://github.com/ValveSoftware/Proton) or
   [GE-Proton](https://github.com/GloriousEggroll/proton-ge-custom/releases).
3. **eac_runtime** (for EAC games): the official **Proton EasyAntiCheat
   Runtime** from Steam or Heroic, packed as `.tar.gz` with `v2/lib/x86_64-linux-gnu/easyanticheat.so`
   or `easyanticheat_x64.so`. The app requires this before attempting an EAC
   game. The game publisher must also support EAC on Proton.

The current bundled variant includes **rootfs and Proton**, but not the
proprietary EAC runtime. Obtain the latter through Steam or Heroic and import
it, or supply its official `.tar.gz` to the build script with `--eac` when its
distribution terms allow that use. The app never treats Proton itself as an
EAC runtime.

The pinned Proton archive is
[GE-Proton11-11-x86_64](https://github.com/GloriousEggroll/proton-ge-custom/releases/tag/GE-Proton11-11),
with upstream SHA-512
`45721a7b625b2404d8896bb5e2a8b87fc9623a394873a6dd6b900c839681d5c01ec694ca00d003fc9c733c5f6c5648af8171985656f9fa47e20999fbebdbe235`.
The rootfs build uses the pinned official `python:3.11-slim-bookworm` x86-64
container image and Debian packages listed in
`runtime/android/linux-proton-rootfs/Dockerfile`. The generated APK records
each archive's byte length and SHA-256 in `assets/linux-proton/manifest.json`;
the app verifies both before extraction. These large generated assets are
excluded from Git, and the build script regenerates them from the upstream
sources. Its Docker export retains the packages' license notices under
`usr/share/doc` and the GE-Proton archive retains its upstream licenses.

The importer accepts archives with one top-level package folder or files at
the archive root. It streams extraction into private storage, preserves safe
symlinks, rejects escaping paths, and replaces a previous component only after
the new one passes structure checks. Plan for several gigabytes of free space
on the tablet. Extracted files live under the app-private `files/linux-proton`
folder; the exact path is shown on the Epic screen. These packages can also be
staged there for development with Android `run-as`, preserving file modes and
symlinks.

For an x86-64 Linux build host, a minimal Ubuntu rootfs can be prepared from
its official x86-64 container image. Install Python, glibc, X11 and Vulkan
libraries inside that image, then export the container as a `.tar.gz` archive.
The archive must contain the paths above. Dependencies vary with the Proton
release and game; this app deliberately reuses the distribution instead of
forking those components.

## Launch and diagnostics

Run an X11 server on the tablet that the Pocket Arcade app process can reach,
and enter its `DISPLAY` in the Epic screen. An external X11 Android app may
provide this display; its socket or TCP endpoint must be accessible across app
processes. Then select **Play with Linux Proton** for an installed Epic game.
The app runs Box64 → x86-64 Python → the Proton script → Proton Wine, in a
foreground service with a Stop action. Each game gets its own
`Epic/<game>/sessions/proton/compatdata` and `proton.log`; neither is shared
with the Android Wine prefix. The log is app-private and can contain one-use
Epic credentials, so redact it before sharing. A separate `status.json` records
whether the process started and its exit code.

The cloud build validates archive handling, environment construction, and APK
packaging. On-device execution, X11 access, Vulkan presentation, audio/input,
and Rocket League matchmaking still require validation on the Mi Pad 7. Box64
startup and a Proton process exit code are not proof of EAC server acceptance.
