/* SPDX-License-Identifier: GPL-3.0-or-later
 * What the phone's Vulkan driver can run, checked against the requirements of
 * the graphics layers the app ships (pinned in sources.json):
 *   - DXVK (Direct3D 8-11): src/dxvk/dxvk_device_info.cpp, features marked required;
 *   - VKD3D-Proton (Direct3D 12): libs/vkd3d/device.c, the checks that fail device creation;
 *   - WineD3D's Vulkan renderer (Wine's own Direct3D 1-11): Vulkan 1.0 with a graphics queue,
 *     and it decompresses BC textures itself when the GPU cannot sample them.
 * Written as plain C over dlopen("libvulkan.so") so the same file runs in the app (through
 * JNI) and as a command-line check in CI. Output: one JSON object. */
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>

#ifndef VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MAINTENANCE_5_FEATURES_KHR
#define VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MAINTENANCE_5_FEATURES_KHR 1000470000
#endif
#ifndef VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MAINTENANCE_6_FEATURES_KHR
#define VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MAINTENANCE_6_FEATURES_KHR 1000545000
#endif
#ifndef VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VERTEX_ATTRIBUTE_DIVISOR_FEATURES_EXT
#define VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VERTEX_ATTRIBUTE_DIVISOR_FEATURES_EXT 1000190002
#endif

/* Generic feature structs: sType, pNext, then VkBool32 fields (layout of every *Features struct). */
struct feat { VkStructureType sType; void *pNext; VkBool32 f[16]; };

struct out { char *buf; size_t size, len; };
static void put(struct out *o, const char *fmt, const char *s)
{
    int n = snprintf(o->buf + o->len, o->len < o->size ? o->size - o->len : 0, fmt, s);
    if (n > 0) o->len += (size_t)n;
}
static void putn(struct out *o, const char *fmt, unsigned long long v)
{
    int n = snprintf(o->buf + o->len, o->len < o->size ? o->size - o->len : 0, fmt, v);
    if (n > 0) o->len += (size_t)n;
}
/* JSON string (names come from the driver, so escape them). */
static void put_str(struct out *o, const char *s)
{
    char tmp[600]; size_t j = 0;
    for (; *s && j < sizeof(tmp) - 7; s++)
    {
        unsigned char c = (unsigned char)*s;
        if (c == '"' || c == '\\') { tmp[j++] = '\\'; tmp[j++] = c; }
        else if (c < 0x20) j += (size_t)snprintf(tmp + j, 7, "\\u%04x", c);
        else tmp[j++] = c;
    }
    tmp[j] = 0;
    put(o, "\"%s\"", tmp);
}

struct missing { const char *names[48]; int count; };
static void need(struct missing *m, int ok, const char *name) { if (!ok && m->count < 48) m->names[m->count++] = name; }
static void put_missing(struct out *o, const char *key, const struct missing *m)
{
    int i;
    put(o, ",\"%s\":[", key);
    for (i = 0; i < m->count; i++) { if (i) put(o, "%s", ","); put_str(o, m->names[i]); }
    put(o, "%s", "]");
}

static int has_ext(const VkExtensionProperties *e, uint32_t n, const char *name)
{
    uint32_t i;
    for (i = 0; i < n; i++) if (!strcmp(e[i].extensionName, name)) return 1;
    return 0;
}

int pocket_gpu_caps(char *buf, size_t size)
{
    struct out o = { buf, size, 0 };
    void *lib = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    PFN_vkGetInstanceProcAddr gipa;
    PFN_vkCreateInstance create_instance;
    PFN_vkEnumerateInstanceVersion enum_version;
    VkInstance instance = VK_NULL_HANDLE;
    uint32_t loader_version = VK_API_VERSION_1_0, count = 0;
    VkPhysicalDevice devices[8];
    if (size) buf[0] = 0;
    if (!lib)
    {
        const char *err = dlerror();
        put(&o, "%s", "{\"vulkan\":false,\"error\":"); put_str(&o, err ? err : "no libvulkan.so"); put(&o, "%s", "}");
        return -1;
    }
    gipa = (PFN_vkGetInstanceProcAddr)dlsym(lib, "vkGetInstanceProcAddr");
    create_instance = gipa ? (PFN_vkCreateInstance)gipa(NULL, "vkCreateInstance") : NULL;
    enum_version = gipa ? (PFN_vkEnumerateInstanceVersion)gipa(NULL, "vkEnumerateInstanceVersion") : NULL;
    if (enum_version) enum_version(&loader_version);
    if (create_instance)
    {
        VkApplicationInfo app = { VK_STRUCTURE_TYPE_APPLICATION_INFO, NULL, "Pocket Arcade GPU check", 1, NULL, 0,
                                  loader_version >= VK_API_VERSION_1_1 ? VK_API_VERSION_1_3 : VK_API_VERSION_1_0 };
        VkInstanceCreateInfo ici = { VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, NULL, 0, &app, 0, NULL, 0, NULL };
        if (create_instance(&ici, NULL, &instance) != VK_SUCCESS) instance = VK_NULL_HANDLE;
    }
    if (!instance) { put(&o, "%s", "{\"vulkan\":false,\"error\":\"vkCreateInstance failed\"}"); return -1; }
    {
#define LOAD(name) PFN_##name p##name = (PFN_##name)gipa(instance, #name)
        LOAD(vkEnumeratePhysicalDevices); LOAD(vkGetPhysicalDeviceProperties); LOAD(vkGetPhysicalDeviceProperties2);
        LOAD(vkGetPhysicalDeviceFeatures2); LOAD(vkEnumerateDeviceExtensionProperties);
        LOAD(vkGetPhysicalDeviceQueueFamilyProperties); LOAD(vkDestroyInstance);
        VkPhysicalDeviceProperties props;
        VkPhysicalDeviceDriverProperties driver = { VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES };
        VkPhysicalDeviceVulkan13Properties p13 = { VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_PROPERTIES, &driver };
        VkPhysicalDeviceTransformFeedbackPropertiesEXT xfbp = { VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TRANSFORM_FEEDBACK_PROPERTIES_EXT, &p13 };
        VkPhysicalDeviceProperties2 props2 = { VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2, &xfbp };
        VkPhysicalDeviceVulkan11Features f11 = { VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES };
        VkPhysicalDeviceVulkan12Features f12 = { VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES, &f11 };
        VkPhysicalDeviceVulkan13Features f13 = { VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES, &f12 };
        VkPhysicalDeviceRobustness2FeaturesEXT rob = { VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ROBUSTNESS_2_FEATURES_EXT, &f13 };
        VkPhysicalDeviceDepthClipEnableFeaturesEXT clip = { VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DEPTH_CLIP_ENABLE_FEATURES_EXT, &rob };
        struct feat m5 = { VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MAINTENANCE_5_FEATURES_KHR, &clip };
        struct feat m6 = { VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MAINTENANCE_6_FEATURES_KHR, &m5 };
        struct feat div = { VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VERTEX_ATTRIBUTE_DIVISOR_FEATURES_EXT, &m6 };
        VkPhysicalDeviceTransformFeedbackFeaturesEXT xfb = { VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TRANSFORM_FEEDBACK_FEATURES_EXT, &div };
        VkPhysicalDeviceFeatures2 f2 = { VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2, &xfb };
        VkExtensionProperties *exts = NULL;
        uint32_t next = 0, nq = 0, i;
        VkQueueFamilyProperties queues[16];
        int graphics = 0, v13, rob2, divisor, m5ok, m6ok;
        struct missing dxvk = {0}, vkd3d = {0};
        VkPhysicalDeviceFeatures *c = &f2.features;

        count = 8;
        if (!pvkEnumeratePhysicalDevices || pvkEnumeratePhysicalDevices(instance, &count, devices) < 0 || !count)
        {
            put(&o, "%s", "{\"vulkan\":false,\"error\":\"no Vulkan GPU\"}");
            if (pvkDestroyInstance) pvkDestroyInstance(instance, NULL);
            return -1;
        }
        pvkGetPhysicalDeviceProperties(devices[0], &props);
        v13 = props.apiVersion >= VK_API_VERSION_1_3 && pvkGetPhysicalDeviceProperties2 && pvkGetPhysicalDeviceFeatures2;
        pvkEnumerateDeviceExtensionProperties(devices[0], NULL, &next, NULL);
        exts = calloc(next ? next : 1, sizeof(*exts));
        pvkEnumerateDeviceExtensionProperties(devices[0], NULL, &next, exts);
        if (!has_ext(exts, next, "VK_KHR_maintenance5")) m5.sType = 0;
        if (!has_ext(exts, next, "VK_KHR_maintenance6")) m6.sType = 0;
        if (props.apiVersion >= VK_API_VERSION_1_1 && pvkGetPhysicalDeviceFeatures2)
        {
            /* Chain only the structs whose core version or extension the driver has. */
            void *chain = NULL;
            if (props.apiVersion >= VK_API_VERSION_1_1) { f11.pNext = chain; chain = &f11; }
            if (props.apiVersion >= VK_API_VERSION_1_2) { f12.pNext = chain; chain = &f12; }
            if (props.apiVersion >= VK_API_VERSION_1_3) { f13.pNext = chain; chain = &f13; }
            if (has_ext(exts, next, "VK_EXT_robustness2") || has_ext(exts, next, "VK_KHR_robustness2")) { rob.pNext = chain; chain = &rob; }
            if (has_ext(exts, next, "VK_EXT_depth_clip_enable")) { clip.pNext = chain; chain = &clip; }
            if (m5.sType) { m5.pNext = chain; chain = &m5; }
            if (m6.sType) { m6.pNext = chain; chain = &m6; }
            if (has_ext(exts, next, "VK_EXT_vertex_attribute_divisor") || has_ext(exts, next, "VK_KHR_vertex_attribute_divisor")) { div.pNext = chain; chain = &div; }
            if (has_ext(exts, next, "VK_EXT_transform_feedback")) { xfb.pNext = chain; chain = &xfb; }
            f2.pNext = chain;
            pvkGetPhysicalDeviceFeatures2(devices[0], &f2);
            chain = NULL;
            if (props.apiVersion >= VK_API_VERSION_1_2) { driver.pNext = chain; chain = &driver; }
            if (props.apiVersion >= VK_API_VERSION_1_3) { p13.pNext = chain; chain = &p13; }
            if (has_ext(exts, next, "VK_EXT_transform_feedback")) { xfbp.pNext = chain; chain = &xfbp; }
            props2.pNext = chain;
            if (pvkGetPhysicalDeviceProperties2) pvkGetPhysicalDeviceProperties2(devices[0], &props2);
        }
        nq = 16;
        pvkGetPhysicalDeviceQueueFamilyProperties(devices[0], &nq, queues);
        for (i = 0; i < nq; i++) if (queues[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) graphics = 1;
        rob2 = rob.robustBufferAccess2 && rob.nullDescriptor;
        divisor = div.f[0] && div.f[1];
        m5ok = m5.sType && m5.f[0];
        m6ok = m6.sType && m6.f[0];

        /* DXVK */
        need(&dxvk, v13, "Vulkan 1.3");
        need(&dxvk, graphics, "graphics queue");
        need(&dxvk, c->textureCompressionBC, "BC texture compression");
        need(&dxvk, c->multiDrawIndirect, "multiDrawIndirect");
        need(&dxvk, c->multiViewport, "multiViewport");
        need(&dxvk, c->occlusionQueryPrecise, "occlusionQueryPrecise");
        need(&dxvk, c->robustBufferAccess, "robustBufferAccess");
        need(&dxvk, c->sampleRateShading, "sampleRateShading");
        need(&dxvk, c->samplerAnisotropy, "samplerAnisotropy");
        need(&dxvk, c->shaderClipDistance && c->shaderCullDistance, "clip/cull distance");
        need(&dxvk, c->shaderImageGatherExtended, "shaderImageGatherExtended");
        need(&dxvk, c->shaderInt16 && c->shaderInt64, "16/64-bit shader integers");
        need(&dxvk, c->shaderSampledImageArrayDynamicIndexing, "sampled image dynamic indexing");
        need(&dxvk, f11.shaderDrawParameters && f11.storageBuffer16BitAccess, "shaderDrawParameters/16-bit storage");
        need(&dxvk, f12.bufferDeviceAddress, "bufferDeviceAddress");
        need(&dxvk, f12.descriptorIndexing && f12.runtimeDescriptorArray && f12.descriptorBindingPartiallyBound &&
                    f12.descriptorBindingSampledImageUpdateAfterBind && f12.descriptorBindingUpdateUnusedWhilePending, "descriptor indexing");
        need(&dxvk, f12.storageBuffer8BitAccess && f12.shaderInt8, "8-bit storage/integers");
        need(&dxvk, f12.hostQueryReset && f12.timelineSemaphore, "hostQueryReset/timeline semaphores");
        need(&dxvk, f12.samplerMirrorClampToEdge, "samplerMirrorClampToEdge");
        need(&dxvk, f12.scalarBlockLayout && f12.uniformBufferStandardLayout, "scalar/standard block layout");
        need(&dxvk, f12.vulkanMemoryModel, "vulkanMemoryModel");
        need(&dxvk, f13.inlineUniformBlock && f13.computeFullSubgroups && f13.subgroupSizeControl, "inline uniforms/subgroup control");
        need(&dxvk, f13.dynamicRendering && f13.synchronization2 && f13.maintenance4, "dynamic rendering/sync2/maintenance4");
        need(&dxvk, f13.shaderDemoteToHelperInvocation && f13.shaderZeroInitializeWorkgroupMemory, "demote/zero-init workgroup memory");
        need(&dxvk, clip.depthClipEnable, "VK_EXT_depth_clip_enable");
        need(&dxvk, rob2, "VK_EXT_robustness2 (robust access, null descriptors)");
        need(&dxvk, has_ext(exts, next, "VK_KHR_load_store_op_none") || has_ext(exts, next, "VK_EXT_load_store_op_none"), "load/store op none");
        need(&dxvk, m5ok && m6ok, "VK_KHR_maintenance5/6");
        need(&dxvk, has_ext(exts, next, "VK_KHR_swapchain"), "VK_KHR_swapchain");
        need(&dxvk, props.limits.maxPushConstantsSize >= 256, "256 bytes of push constants");

        /* VKD3D-Proton */
        need(&vkd3d, v13, "Vulkan 1.3");
        need(&vkd3d, divisor, "vertex attribute divisor");
        need(&vkd3d, xfb.transformFeedback && xfbp.transformFeedbackQueries, "transform feedback");
        need(&vkd3d, (p13.storageTexelBufferOffsetSingleTexelAlignment || p13.storageTexelBufferOffsetAlignmentBytes == 1) &&
                     (p13.uniformTexelBufferOffsetSingleTexelAlignment || p13.uniformTexelBufferOffsetAlignmentBytes == 1), "single texel alignment");
        need(&vkd3d, f12.samplerMirrorClampToEdge, "samplerMirrorClampToEdge");
        need(&vkd3d, rob2 && rob.robustImageAccess2, "VK_EXT_robustness2");
        need(&vkd3d, f11.shaderDrawParameters, "shaderDrawParameters");
        need(&vkd3d, has_ext(exts, next, "VK_KHR_push_descriptor"), "VK_KHR_push_descriptor");
        need(&vkd3d, m5ok && m6ok, "VK_KHR_maintenance5/6");
        need(&vkd3d, c->textureCompressionBC, "BC texture compression");

        put(&o, "%s", "{\"vulkan\":true,\"name\":"); put_str(&o, props.deviceName);
        putn(&o, ",\"vendor\":%llu", props.vendorID); putn(&o, ",\"device\":%llu", props.deviceID);
        putn(&o, ",\"api\":\"%llu", VK_API_VERSION_MAJOR(props.apiVersion)); putn(&o, ".%llu", VK_API_VERSION_MINOR(props.apiVersion));
        putn(&o, ".%llu\"", VK_API_VERSION_PATCH(props.apiVersion));
        put(&o, "%s", ",\"driver\":"); put_str(&o, props.apiVersion >= VK_API_VERSION_1_2 ? driver.driverName : "");
        put(&o, "%s", ",\"driverInfo\":"); put_str(&o, props.apiVersion >= VK_API_VERSION_1_2 ? driver.driverInfo : "");
        putn(&o, ",\"driverVersion\":%llu", props.driverVersion);
        put(&o, ",\"bc\":%s", c->textureCompressionBC ? "true" : "false");
        put(&o, ",\"dxvk\":%s", dxvk.count ? "false" : "true");
        put(&o, ",\"vkd3d\":%s", vkd3d.count ? "false" : "true");
        put(&o, ",\"wined3d\":%s", graphics && has_ext(exts, next, "VK_KHR_swapchain") ? "true" : "false");
        put_missing(&o, "dxvkMissing", &dxvk);
        put_missing(&o, "vkd3dMissing", &vkd3d);
        put(&o, "%s", "}");
        free(exts);
        pvkDestroyInstance(instance, NULL);
    }
    return 0;
}

#ifdef POCKET_GPU_CAPS_MAIN
int main(void)
{
    static char buf[16384];
    int r = pocket_gpu_caps(buf, sizeof(buf));
    puts(buf);
    return r ? 1 : 0;
}
#endif
