/* Exercise the i386 Mesa ICD selected by the same manifest used by Box32. */
#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>

typedef struct {
    uint32_t sType;
    const void *pNext;
    uint32_t flags;
    const void *app;
    uint32_t layer_count;
    const char **layers;
    uint32_t ext_count;
    const char **exts;
} instance_create_info;

int main(void) {
    void *library = dlopen("libvulkan.so.1", RTLD_NOW);
    if (!library) { fprintf(stderr, "%s\n", dlerror()); return 1; }
    int (*create)(const instance_create_info *, void *, void **) = dlsym(library, "vkCreateInstance");
    int (*enumerate)(void *, uint32_t *, void *) = dlsym(library, "vkEnumeratePhysicalDevices");
    void (*destroy)(void *, void *) = dlsym(library, "vkDestroyInstance");
    instance_create_info info = {.sType = 1};
    void *instance = 0;
    int result = create(&info, 0, &instance);
    if (result || !instance) { fprintf(stderr, "vkCreateInstance: %d\n", result); return 2; }
    uint32_t count = 0;
    result = enumerate(instance, &count, 0);
    destroy(instance, 0);
    printf("i386 software Vulkan: result=%d devices=%u\n", result, count);
    return result || !count;
}
