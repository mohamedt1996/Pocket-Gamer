cmake_minimum_required(VERSION 3.22.1)
project(madeira_probe LANGUAGES C CXX)
add_library(madeira_probe SHARED probe.cpp vulkan_probe.cpp surface_bridge.cpp present_test.cpp runtime_environment.cpp gpu_caps.c gpu_caps_jni.cpp)
target_compile_features(madeira_probe PRIVATE cxx_std_17)
target_link_libraries(madeira_probe android log vulkan dl)
# 16 KB page devices (Android 15+) refuse 4 KB-aligned segments; NDK r27 still defaults to 4 KB.
# Every launch loads this library (NativeEnvironment) before Wine starts.
target_link_options(madeira_probe PRIVATE "-Wl,-z,max-page-size=16384")
# Steam content: the zstd decoder behind org.pocketarcade.madeira.steam.Zstd.
add_library(madeira_steam SHARED zstd/zstd_jni.c zstd/zstd_edu.c)
target_compile_definitions(madeira_steam PRIVATE ZDEC_NO_MESSAGE)
target_compile_options(madeira_steam PRIVATE -O2)
target_link_options(madeira_steam PRIVATE "-Wl,-z,max-page-size=16384")
