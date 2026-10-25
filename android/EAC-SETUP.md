# Official Proton EasyAntiCheat Runtime

Pocket Arcade cannot treat Proton or the game's Windows EAC files as the
**Proton EasyAntiCheat Runtime**. It needs the official x86-64 Linux runtime
that Steam distributes as a separate tool. The signed all-in-one workflow
requires an approved official archive before it publishes an APK. Earlier
alpha APKs did not bundle that runtime.

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

The release workflow requires `PROTON_EAC_RUNTIME_URL` as a GitHub Actions
secret, `PROTON_EAC_RUNTIME_SHA256` as a repository variable, and
`PROTON_EAC_REDISTRIBUTION_APPROVED=true` as a repository variable. The URL
must provide an official archive the repository owner is authorized to
redistribute. The workflow verifies its checksum and x86-64 EAC bridge, embeds
it in the sole APK, and fails rather than shipping an incomplete release.

An anonymous SteamCMD install of Valve's tool app `1826330` was tested on a
hosted Linux runner and returned `Failed to install app '1826330' (Missing
configuration)`. The GitHub workflow therefore cannot fetch the official runtime
without an authorized Steam installation or supplied archive. The in-app
installer uses your authenticated Steam session on the tablet.

Only publish an APK containing EAC if you have permission to redistribute the
runtime. Installing the runtime does not guarantee EAC or Rocket League online
play: the game's publisher must support Proton, and this tablet path still
needs on-device testing.
