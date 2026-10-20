/* SPDX-License-Identifier: LGPL-2.1-or-later
 * Madeira Android Vulkan WSI adapter for Wine's version 47 driver interface.
 * Uses wineandroid's per-HWND native-window IPC, never a pointer from the lab
 * activity. Wine win32u owns VkSurfaceKHR; the client owns its window reference.
 */
#if 0
#pragma makedep unix
#endif
#include "config.h"
#include <stdlib.h>
#include "ntstatus.h"
#define WIN32_NO_STATUS
#include "android.h"
#include "wine/vulkan_driver.h"
#include "wine/debug.h"
WINE_DEFAULT_DEBUG_CHANNEL(vulkan);
struct madeira_surface { struct client_surface base; ANativeWindow *window; };
static void surface_destroy(struct client_surface *base)
{
    struct madeira_surface *s = CONTAINING_RECORD(base, struct madeira_surface, base);
    if (s->window) release_ioctl_window(s->window);
}
static void surface_detach(struct client_surface *base) { (void)base; }
static void surface_update(struct client_surface *base) { (void)base; }
static void surface_present(struct client_surface *base, HDC hdc) { (void)base; (void)hdc; }
static const struct client_surface_funcs surface_funcs = {
    .destroy = surface_destroy, .detach = surface_detach,
    .update = surface_update, .present = surface_present,
};
static VkResult surface_create(HWND hwnd, BOOL raw, const struct vulkan_instance *instance,
                               VkSurfaceKHR *handle, struct client_surface **client)
{
    struct madeira_surface *s;
    VkAndroidSurfaceCreateInfoKHR info = {0};
    VkResult result;
    (void)raw;
    *handle = VK_NULL_HANDLE;
    *client = NULL;
    if (!instance->p_vkCreateAndroidSurfaceKHR) return VK_ERROR_EXTENSION_NOT_PRESENT;
    s = client_surface_create(sizeof(*s), &surface_funcs, hwnd);
    if (!s) return VK_ERROR_OUT_OF_HOST_MEMORY;
    s->window = get_client_window(hwnd);
    if (!s->window || !has_client_surface(hwnd)) {
        client_surface_release(&s->base);
        return VK_ERROR_SURFACE_LOST_KHR;
    }
    info.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
    info.window = s->window;
    result = instance->p_vkCreateAndroidSurfaceKHR(instance->host.instance, &info, NULL, handle);
    if (result != VK_SUCCESS) {
        client_surface_release(&s->base);
        return result;
    }
    *client = &s->base;
    return VK_SUCCESS;
}
static VkBool32 presentation_support(struct vulkan_physical_device *device, uint32_t index)
{
    /* Android has no platform-specific presentation-support query. Its WSI
       guarantees presentation on graphics queues; validate index/capabilities. */
    struct vulkan_instance *instance = device->instance;
    uint32_t count = 0;
    VkQueueFamilyProperties *props;
    VkBool32 supported;
    instance->p_vkGetPhysicalDeviceQueueFamilyProperties(device->host.physical_device, &count, NULL);
    if (index >= count || !(props = calloc(count, sizeof(*props)))) return VK_FALSE;
    instance->p_vkGetPhysicalDeviceQueueFamilyProperties(device->host.physical_device, &count, props);
    supported = index < count && (props[index].queueFlags & VK_QUEUE_GRAPHICS_BIT);
    free(props);
    return supported;
}
static void map_instance(struct vulkan_instance_extensions *ext)
{
    if (ext->has_VK_KHR_win32_surface) ext->has_VK_KHR_android_surface = 1;
    if (ext->has_VK_KHR_android_surface) ext->has_VK_KHR_win32_surface = 1;
}
static void map_device(struct vulkan_device_extensions *ext) { (void)ext; }
static const struct vulkan_driver_funcs driver = {
    .p_vulkan_surface_create = surface_create,
    .p_get_physical_device_presentation_support = presentation_support,
    .p_map_instance_extensions = map_instance,
    .p_map_device_extensions = map_device,
};
UINT ANDROID_VulkanInit(UINT version, void *handle, const struct vulkan_driver_funcs **out)
{
    if (version != WINE_VULKAN_DRIVER_VERSION) return STATUS_INVALID_PARAMETER;
    if (!handle) return STATUS_NOT_SUPPORTED;
    *out = &driver;
    return STATUS_SUCCESS;
}
