# Optional Linux Proton session on Android

This is a separate, opt-in Box64 session for Epic games. It runs a
x86-64 Linux Proton distribution through an x86-64 Linux Python interpreter;
the existing Android Wine/FEX session and its prefixes remain separate. The
app launches the game's original Epic executable and arguments with `proton
run`, sets the Steam compatibility paths, and supplies the official EAC runtime
path when the game requires it. It does not alter EAC files or bypass checks.

## Signed all-in-one release

The release workflow builds one non-debug `assembleRelease` APK. It bundles
Android ARM64EC Wine/FEX and WoWBox64, GE-Proton, the multiarch Debian rootfs,
and Box64. The official Proton EasyAntiCheat Runtime is included only if the
repository owner supplies an archive authorized for redistribution; otherwise
install the Steam tool in the app using your Steam account. The Linux packages are
extracted to app-private storage by **Install included Linux packages** in
**Settings → Pocket Dock**. Plan for several gigabytes of free storage.

A tag matching `android-proton-v*` starts the workflow. For repeatable Android
updates, configure GitHub Actions with these repository settings:

- Secrets: `POCKET_ARCADE_KEYSTORE_BASE64`, `POCKET_ARCADE_KEYSTORE_PASSWORD`,
  `POCKET_ARCADE_KEY_ALIAS`, and `POCKET_ARCADE_KEY_PASSWORD`.
- Variable: `POCKET_ARCADE_SIGNING_CERT_SHA256`.

The keystore must be a persistent private Android signing key kept outside Git.
`POCKET_ARCADE_SIGNING_CERT_SHA256` is the SHA-256 certificate digest reported by
`keytool -list -v -keystore /path/to/release.keystore -alias YOUR_ALIAS`,
with the separating colons removed.
`POCKET_ARCADE_KEYSTORE_BASE64` is the base64 encoding of the keystore file.
The workflow checks the signed APK's certificate against that digest. Keep the
key and passwords backed up; later updates must use the same certificate.
Without these settings the workflow generates a one-time release key. That APK
is non-debug and installable, but a future release cannot update it in place;
back up data before uninstalling. `PACKAGE-STATUS.txt` on the release records
the certificate fingerprint and whether EAC is bundled. See
[EAC-SETUP.md](EAC-SETUP.md) for the optional official EAC archive and
distribution requirement. An install test runs through Android's package manager
before publication.

Android Box32 is still unavailable, so Linux i386 processes cannot run through
Pocket Dock despite the included i386 libraries. The Android-native Wine/FEX
route includes Windows x86 and x64 modules. The Linux Vulkan path uses CPU
rendering; neither Rocket League nor EAC acceptance has been verified on the
Mi Pad 7.

## Package requirements

1. **rootfs**: a relocatable x86-64 and i386 Linux root filesystem with
   `usr/bin/python3`, both glibc architectures, X11 support libraries, and
   Mesa lavapipe Vulkan ICDs for both bitnesses. The importer relocates
   absolute symlinks inside the rootfs. An ARM64-only rootfs is not suitable.
2. **proton**: a complete Linux x86-64 Proton or GE-Proton release archive with
   `proton` and `files/bin/wine`. Do not use a Wine prefix template. Upstream
   distribution: [Valve Proton](https://github.com/ValveSoftware/Proton) or
   [GE-Proton](https://github.com/GloriousEggroll/proton-ge-custom/releases).
3. **eac_runtime** (for EAC games): the official **Proton EasyAntiCheat
   Runtime** from Steam or Heroic, packed as `.tar.gz` with `v2/lib/x86_64-linux-gnu/easyanticheat.so`
   or `easyanticheat_x64.so`. The app requires this before attempting an EAC
   game. The game publisher must also support EAC on Proton.

If an official EAC archive is configured for bundling, the workflow verifies
its x86-64 bridge and requires redistribution approval. Proton itself is never
treated as the EAC runtime. Otherwise, use the in-app Steam tool installer.

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

The bundled Mesa lavapipe ICDs provide a **software Vulkan fallback** for both
Linux architectures. This runs Vulkan on the CPU and is unsuitable as a
performance solution for Adreno 732. It still needs an X11 server. An
Adreno-accelerated Linux route requires a compatible Android X11/Vulkan
presentation wrapper; Box64's Vulkan call wrapper alone cannot create that
bridge. GE-Proton upstream supports non-Steam launches through umu and its
Steam runtime, which this experimental direct launch does not include.

The i386 libraries and Vulkan ICD are packaged and tested, but this APK does
not include an Android Box32 executable. Upstream Box64's current Box32 option
does not build against the pinned Android NDK because it uses glibc-only
interfaces. Games or helpers that need an i386 Linux process cannot launch
through Pocket Dock yet. The Android Wine/FEX route includes Windows x86 and
x64 modules and is the recommended route on the Mi Pad 7.

The cloud build validates archive handling, environment construction, and APK
packaging. On-device execution, X11 access, Vulkan presentation, audio/input,
and Rocket League matchmaking still require validation on the Mi Pad 7. Box64
startup and a Proton process exit code are not proof of EAC server acceptance.
