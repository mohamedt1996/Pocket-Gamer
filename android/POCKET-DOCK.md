# Pocket Dock on Android

Pocket Dock is the Android-side launch path for downloaded Steam Windows games.
It uses the app's signed-in Steam session to list and download your library,
then runs a selected game executable with the included Linux Proton packages
and Box64. It does not launch Valve's Windows client or `dockhost.exe`.

1. Install `PocketArcade-Android-arm64-release.apk` from the signed all-in-one
   release and sign in to Steam. It includes the runtime archives in the APK;
   no separate archive download is needed. Check the APK against
   `SHA256SUMS.txt` before installing.
2. Open **Settings → Pocket Dock**. Tap **Install included Linux packages** and
   **Install Box64**. Install and start an X11 display app on the tablet, then
   enter its `DISPLAY` value (usually `:0`) and tap **Save display**.
3. Download a Windows game from the Steam library. Its **Start with** setting
   should say **Pocket Dock**. Choose its Windows program there if Steam's
   launch metadata does not identify one. Tap **Play**.

The setup screen now shows the multiarch Linux rootfs, Proton Wine runtime, Box64, and Box32
installation states separately. If a component says **Missing**, install it
there; **Repair included Linux packages** re-extracts the bundled archives.
Box32 says **Unavailable in this build** because the Android Box32 build is
blocked by upstream glibc dependencies. Games that need i386 Linux processes
cannot use Pocket Dock yet.
Use **Check Proton status** on a game's page or **Check last desktop run** in
Pocket Dock when no X11 window appears.

Pocket Dock keeps Proton's game prefix in app-private `steam-proton/<app ID>`
storage, separate from Steam's downloaded files. Its `proton.log` and
`status.json` files are in that folder's `sessions/proton` directory.

Games requiring the Steam client, Steamworks, Steam DRM, or unsupported anti-cheat
may refuse to start. Pocket Dock does not claim to satisfy those checks. It also
does not currently run Steam's one-time install scripts or trigger automatic
Steam Cloud sync for Proton sessions. The combined APK workflow also packages
the Android ARM64EC Proton-Wine/FEX runtime. Choose **Android Wine/FEX** under
a Steam game's **Start with** setting to try that path with the tablet's system
Vulkan driver. Earlier alpha.6 APKs do not contain this Android runtime.
The bundled Proton Wine runtime is x86-64 Linux software; it is not the removed
Android Wine runtime. The Windows desktop button starts Proton's `explorer.exe`
and needs Pocket Dock's packages, Box64, and an active X11 display.

The Settings GPU section lists Vulkan features missing from the tablet's
current driver. Pocket Dock uses the Linux guest Vulkan ICD independently of
that Android GPU probe. The separate Linux path's
bundled Vulkan ICDs use the CPU, so the Android-native Wine/FEX path remains
the preferred Adreno 732 experiment. An Android-to-Linux GPU and X11 surface
bridge still needs device-specific integration; the software fallback cannot
resolve missing hardware Vulkan features or make VKD3D-Proton fast.
