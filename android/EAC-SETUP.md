# Official Proton EasyAntiCheat Runtime

Pocket Arcade cannot treat Proton or the game's Windows EAC files as the
**Proton EasyAntiCheat Runtime**. It needs the official x86-64 Linux runtime
that Steam distributes as a separate tool. The public APK currently includes
Box64, Proton and the Linux rootfs, but no EAC runtime.

In **Epic Games → Linux Proton research**, sign in to Steam and tap **Install
EAC runtime from Steam**. Pocket Arcade requests Steam tool app `1826330` from
Steam's content service using your account, selects its Linux depots, and checks
the x86-64 bridge before activating it. Steam may refuse the download if your
account cannot access the tool. A completed download still needs live on-device
testing; it does not establish anti-cheat acceptance.

If Steam's direct download is unavailable, import the official runtime:

1. On a Linux PC or Steam Deck, open Steam's **Library**, enable **Tools** in
   the library filter, and install **Proton EasyAntiCheat Runtime**.
2. Open the tool's **Properties → Installed Files → Browse**. In that folder,
   check for `v2/lib/x86_64-linux-gnu/easyanticheat.so` or
   `easyanticheat_x64.so`. Archive the *contents* of the folder:

   ```sh
   cd '/path/opened/by/Steam/Browse'
   tar -czf "$HOME/proton-eac-runtime.tar.gz" .
   ```

3. Copy `proton-eac-runtime.tar.gz` to the tablet. In Pocket Arcade's **Epic
   Games → Linux Proton research**, tap **Import EAC runtime .tar.gz** and
   select the file. Then use **Play with Linux Proton** for the installed game.

To make a single APK for your own use, pass that same archive to
`runtime/android/build-linux-proton-packages.sh --eac /path/to/proton-eac-runtime.tar.gz`
before building the APK. The app will verify and extract it with the other
included Linux packages. An automated GitHub Actions build can do the same if
the repository owner sets `PROTON_EAC_RUNTIME_URL` (Actions secret pointing to
an official archive they are authorized to distribute) and
`PROTON_EAC_RUNTIME_SHA256` (Actions variable with its SHA-256). The workflow
rejects an archive whose checksum or x86-64 EAC bridge is wrong.

An anonymous SteamCMD install of Valve's tool app `1826330` was tested on a
hosted Linux runner and returned `Failed to install app '1826330' (Missing
configuration)`. The GitHub workflow therefore cannot fetch the official runtime
without an authorized Steam installation or supplied archive. The in-app
installer uses your authenticated Steam session on the tablet.

Only publish an APK containing EAC if you have permission to redistribute the
runtime. Installing the runtime does not guarantee EAC or Rocket League online
play: the game's publisher must support Proton, and this tablet path still
needs on-device testing.
