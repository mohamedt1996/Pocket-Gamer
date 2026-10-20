# Madeira Android Runtime Lab (not a Windows emulator yet)

This project builds a real ARM64 Android APK with a C++ JNI library, probes Vulkan availability, launches a separate Android child process, and inspects the DOS MZ signature of selected Windows executables. It **does not** execute Windows EXEs, and does not contain FEX, Wine or DXVK. The Android process probe is not a Windows execution test.

## Why this order
A working FEX + ARM64EC Wine port requires Android-compatible loader, signal/exception mapping, memory management, PE execution, wineserver process model, X11/Wayland or native Android window bridge, audio/input, and Vulkan driver/WSI integration. Simply downloading source code during CI cannot establish that functionality.

## Next runtime milestones
1. Build FEX for Android arm64 with executable-memory, signal, and filesystem smoke tests.
2. Cross-build Wine ARM64EC/WoW64 for Android-compatible sysroot and test `wine --version`, wineserver, and a Windows console `hello.exe`.
3. Add Android window surface/Wayland bridge, DXVK + VKD3D-Proton and validate D3D9/11/12 test executables.
4. Bundle versioned, signed runtime archives and load them in the app; keep third-party license notices and redistribution rights.
5. Only enable the game Play button after on-device Windows execution tests succeed.

Build locally with Android SDK 35, NDK 27, CMake and Gradle 8.9+: `cd android && gradle assembleDebug`.
