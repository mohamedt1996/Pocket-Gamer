# Graphics implementation and verification boundary

The original JNI Vulkan diagnostics remain available: offscreen clear, surface
capabilities and a blue swapchain frame. These execute outside Wine.

The new `runtime/android/patches/wineandroid_vulkan.c` is different: it plugs into
Wine's `user_driver_funcs.pVulkanInit` and the version-47 Vulkan driver interface.
It obtains an HWND-associated Android native-window wrapper using Wine's own
window IPC, creates a VkAndroidSurfaceKHR, and retains the wrapper through the
Wine client-surface lifetime. Wine win32u owns the Vulkan surface destruction.

`patch-wine.py` adds VK_KHR_android_surface to Wine's unexposed host extensions
and regenerates its Vulkan headers and dispatch tables. Windows applications
continue to request VK_KHR_win32_surface. DXVK and VKD3D-Proton are compiled as
x86/x64 Windows DLLs; FEX translates their CPU instructions. They are not ARM64EC
optimized builds in this revision.

The per-game graphics option sets DLL overrides and installs the appropriate
x64 DLLs in system32 and x86 DLLs in syswow64. Selecting Wine built-ins forces
builtin overrides, so previously copied native DLLs do not remain active.

Unverified: native-window IPC compatibility with current vendor Vulkan loaders,
resize/surface-loss behavior, graphics feature availability, game performance,
and the complete D3D12 presentation path. No desktop Vulkan driver or iOS Metal
backend is presented as an Android graphics driver.

References:
- https://github.com/The412Banner/proton-wine
- https://wiki.fex-emu.com/index.php/Development:ARM64EC
- https://github.com/doitsujin/dxvk
- https://github.com/HansKristian-Work/vkd3d-proton
