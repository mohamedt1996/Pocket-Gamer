# Experimental Android graphics path

This project now executes **real Vulkan API operations** on Android: it enumerates GPUs, creates a logical device and graphics queue, allocates a 64x64 GPU image, records a layout transition and clear command, submits it and waits for completion. This proves that the Android app can access its device's Vulkan driver; it is **not** a D3D translation layer, swapchain renderer or Windows game.

## Windows graphics requirements

- **DXVK**: Windows `d3d9.dll`, `d3d10core.dll`, `d3d11.dll`, `dxgi.dll` compatible with the chosen Wine/FEX x86/x64 guest ABI. Source: https://github.com/doitsujin/dxvk
- **VKD3D-Proton**: Windows `d3d12.dll` and `d3d12core.dll`, matching the guest ABI and Vulkan driver features. Source: https://github.com/HansKristian-Work/vkd3d-proton
- **Wine**: Android-compatible Wine, Vulkan ICD dispatch, `winevulkan` and working window/surface bridge; graphics DLL overrides must be set per game/prefix.
- **FEX**: Android-compatible guest translation and Wine launcher with a compatible sysroot. Upstream Madeira's iOS Metal backend cannot be used as an Android Vulkan backend unchanged.

The runtime ZIP importer can report whether these Windows DLL filenames are present. Presence is **not** evidence of loadability or game compatibility. This build intentionally does not download unverified binary releases and falsely call them an Android port.

## Test

Install the debug APK on an ARM64 Android device. Tap **Test real Vulkan GPU commands**. A successful result prints `64x64 GPU image clear: PASS`. This tests offscreen Vulkan command submission, not on-screen presentation. After providing a genuinely working Android ARM64 Wine/FEX runtime, use **Import runtime ZIP** and attempt a Windows EXE. A launch failure should be diagnosed using its exit code and logs.

## Android WSI experiment
The SurfaceView JNI bridge creates a real VkSurfaceKHR using VK_KHR_android_surface, queries present-capable queues and surface capabilities, then releases all handles. It does not render a Wine window or include FEX/Wine/DXVK/VKD3D binaries. The imported runtime remains externally supplied.
