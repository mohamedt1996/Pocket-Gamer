/* Plain Vulkan through Wine's vulkan-1.dll, without DXVK: instance, physical
 * devices, a logical device. Separates a winevulkan/driver problem from a
 * DXVK one. Each step is printed before it runs, so a hang shows where. */
#define VK_USE_PLATFORM_WIN32_KHR
#include <windows.h>
#include "out.h"
#include <vulkan/vulkan.h>

#define STEP(...) out(__VA_ARGS__)

int main(void)
{
    STEP("INFO loading vulkan-1.dll");
    HMODULE vk = LoadLibraryA("vulkan-1.dll");
    if (!vk) { STEP("FAIL vulkan-1.dll: error %lu", GetLastError()); return 1; }
    PFN_vkGetInstanceProcAddr gipa = (PFN_vkGetInstanceProcAddr)(void *)GetProcAddress(vk, "vkGetInstanceProcAddr");
    PFN_vkEnumerateInstanceExtensionProperties enum_ext =
        (PFN_vkEnumerateInstanceExtensionProperties)(void *)gipa(NULL, "vkEnumerateInstanceExtensionProperties");
    PFN_vkCreateInstance create_instance = (PFN_vkCreateInstance)(void *)gipa(NULL, "vkCreateInstance");

    uint32_t count = 0;
    STEP("INFO vkEnumerateInstanceExtensionProperties");
    VkResult r = enum_ext(NULL, &count, NULL);
    STEP("%s instance extensions: %d (count %u)", r == VK_SUCCESS ? "PASS" : "FAIL", r, count);

    VkApplicationInfo app = { VK_STRUCTURE_TYPE_APPLICATION_INFO, NULL, "madeira-smoke", 1, NULL, 0, VK_API_VERSION_1_3 };
    const char *exts[] = { VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_WIN32_SURFACE_EXTENSION_NAME };
    VkInstanceCreateInfo ici = { VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, NULL, 0, &app, 0, NULL, 2, exts };
    VkInstance instance = VK_NULL_HANDLE;
    STEP("INFO vkCreateInstance (surface extensions)");
    r = create_instance(&ici, NULL, &instance);
    if (r != VK_SUCCESS) { STEP("FAIL vkCreateInstance: %d", r); return 1; }
    STEP("PASS vkCreateInstance");

    PFN_vkEnumeratePhysicalDevices enum_dev = (PFN_vkEnumeratePhysicalDevices)(void *)gipa(instance, "vkEnumeratePhysicalDevices");
    PFN_vkGetPhysicalDeviceProperties props = (PFN_vkGetPhysicalDeviceProperties)(void *)gipa(instance, "vkGetPhysicalDeviceProperties");
    PFN_vkCreateDevice create_device = (PFN_vkCreateDevice)(void *)gipa(instance, "vkCreateDevice");
    PFN_vkDestroyDevice destroy_device = (PFN_vkDestroyDevice)(void *)gipa(instance, "vkDestroyDevice");
    PFN_vkDestroyInstance destroy_instance = (PFN_vkDestroyInstance)(void *)gipa(instance, "vkDestroyInstance");

    VkPhysicalDevice devices[8];
    count = 8;
    STEP("INFO vkEnumeratePhysicalDevices");
    r = enum_dev(instance, &count, devices);
    if (r < 0 || !count) { STEP("FAIL vkEnumeratePhysicalDevices: %d count %u", r, count); return 1; }
    VkPhysicalDeviceProperties p;
    props(devices[0], &p);
    STEP("PASS physical device: %s (Vulkan %u.%u)", p.deviceName, VK_VERSION_MAJOR(p.apiVersion), VK_VERSION_MINOR(p.apiVersion));

    float priority = 1.0f;
    VkDeviceQueueCreateInfo qci = { VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO, NULL, 0, 0, 1, &priority };
    VkDeviceCreateInfo dci = { VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO, NULL, 0, 1, &qci, 0, NULL, 0, NULL, NULL };
    VkDevice device = VK_NULL_HANDLE;
    STEP("INFO vkCreateDevice");
    r = create_device(devices[0], &dci, NULL, &device);
    if (r != VK_SUCCESS) { STEP("FAIL vkCreateDevice: %d", r); return 1; }
    STEP("PASS vkCreateDevice");
    destroy_device(device, NULL);
    destroy_instance(instance, NULL);
    STEP("RESULT PASS");
    return 0;
}
