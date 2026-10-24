#!/usr/bin/env python3
"""Exercise the rootfs's x86-64 software Vulkan ICD without a GPU or display."""
import ctypes


class InstanceCreateInfo(ctypes.Structure):
    _fields_ = [
        ("sType", ctypes.c_uint32), ("pNext", ctypes.c_void_p),
        ("flags", ctypes.c_uint32), ("pApplicationInfo", ctypes.c_void_p),
        ("enabledLayerCount", ctypes.c_uint32), ("ppEnabledLayerNames", ctypes.c_void_p),
        ("enabledExtensionCount", ctypes.c_uint32), ("ppEnabledExtensionNames", ctypes.c_void_p),
    ]


vk = ctypes.CDLL("libvulkan.so.1")
vk.vkCreateInstance.argtypes = [ctypes.POINTER(InstanceCreateInfo), ctypes.c_void_p, ctypes.POINTER(ctypes.c_void_p)]
vk.vkCreateInstance.restype = ctypes.c_int32
vk.vkEnumeratePhysicalDevices.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_uint32), ctypes.c_void_p]
vk.vkEnumeratePhysicalDevices.restype = ctypes.c_int32
vk.vkDestroyInstance.argtypes = [ctypes.c_void_p, ctypes.c_void_p]
instance = ctypes.c_void_p()
result = vk.vkCreateInstance(InstanceCreateInfo(sType=1), None, ctypes.byref(instance))
if result != 0 or not instance.value:
    raise SystemExit(f"vkCreateInstance failed: {result}")
count = ctypes.c_uint32()
result = vk.vkEnumeratePhysicalDevices(instance, ctypes.byref(count), None)
vk.vkDestroyInstance(instance, None)
if result != 0 or count.value == 0:
    raise SystemExit(f"vkEnumeratePhysicalDevices failed: {result}, count={count.value}")
print(f"Software Vulkan physical devices: {count.value}")
