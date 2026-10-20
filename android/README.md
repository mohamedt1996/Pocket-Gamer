# Madeira Android Windows Execution Lab

This project compiles a real Android ARM64 APK, performs native diagnostics, imports a user-supplied Android ARM64 Wine/FEX runtime ZIP, copies a Windows PE executable, and attempts to launch it through a native child process. Logs and exit codes are shown in the UI.

**Important:** This is a runtime loader, not a working Madeira Android port. It does NOT include an Android-compatible FEX interpreter, ARM64EC Wine, wineserver, DXVK, VKD3D, Vulkan window surface, audio or input bridge. Windows executables will NOT run unless these native components are separately ported and supplied in a working runtime bundle. A Linux ARM64 Wine binary is not automatically Android compatible. Android restrictions may also prohibit execution of binaries unpacked to writable app storage.

## Runtime ZIP contract

A ZIP must contain `bin/launcher`, an **Android AArch64 ELF** executable. The launcher receives the absolute path to a selected Windows `.exe` as its first argument. It is responsible for all translation, Wine startup, Windows prefix, display, input, graphics and game dependencies. Optional `lib/` and `share/` directories are extracted alongside. Only use trusted runtime archives; they contain native executable code.

The importer rejects path traversal, caps the extracted size at 1.5 GiB and validates the ELF architecture. The Android process-execution security policy is still a potential blocker and needs physical-device validation.

## Real Windows execution acceptance tests

1. Import a functional Android-native FEX + Wine runtime; confirm the launcher starts.
2. Execute an x86 and an x64 Windows console `hello.exe`, assert stdout and exit code 0.
3. Execute a Windows GUI program; verify touch, keyboard and display bridge.
4. Execute D3D9, D3D11 and D3D12 smoke tests through Vulkan and inspect frame output.
5. Install per-game Adobe AIR, Wine Mono/.NET and VC++ dependencies inside a real Wine prefix and validate representative games.

**No such runtime is bundled in this release, and none of those Windows execution tests have passed.**
