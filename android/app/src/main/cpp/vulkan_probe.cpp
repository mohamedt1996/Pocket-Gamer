#include <jni.h>
#include <vulkan/vulkan.h>
#include <sstream>
#include <vector>
#include <cstring>

extern "C" JNIEXPORT jstring JNICALL
Java_org_pocketarcade_madeira_MainActivity_nativeVulkanTest(JNIEnv* env, jclass) {
 std::ostringstream o;
 VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO}; app.pApplicationName="Madeira Android graphics probe"; app.apiVersion=VK_API_VERSION_1_0;
 VkInstanceCreateInfo ci{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO}; ci.pApplicationInfo=&app;
 VkInstance instance=VK_NULL_HANDLE;
 VkResult r=vkCreateInstance(&ci,nullptr,&instance);
 if(r!=VK_SUCCESS){o<<"vkCreateInstance failed: "<<r;return env->NewStringUTF(o.str().c_str());}
 uint32_t count=0;r=vkEnumeratePhysicalDevices(instance,&count,nullptr);
 if(r!=VK_SUCCESS||!count){o<<"No Vulkan physical device ("<<r<<")";vkDestroyInstance(instance,nullptr);return env->NewStringUTF(o.str().c_str());}
 std::vector<VkPhysicalDevice> devices(count);vkEnumeratePhysicalDevices(instance,&count,devices.data());
 for(uint32_t d=0;d<count;d++){
  VkPhysicalDeviceProperties props{};vkGetPhysicalDeviceProperties(devices[d],&props);
  o<<"GPU "<<d<<": "<<props.deviceName<<" Vulkan "<<VK_VERSION_MAJOR(props.apiVersion)<<"."<<VK_VERSION_MINOR(props.apiVersion)<<"\n";
 }
 VkPhysicalDevice gpu=devices[0];uint32_t nqueues=0;vkGetPhysicalDeviceQueueFamilyProperties(gpu,&nqueues,nullptr);
 std::vector<VkQueueFamilyProperties> families(nqueues);vkGetPhysicalDeviceQueueFamilyProperties(gpu,&nqueues,families.data());
 uint32_t family=UINT32_MAX;for(uint32_t i=0;i<nqueues;i++)if(families[i].queueFlags&VK_QUEUE_GRAPHICS_BIT){family=i;break;}
 if(family==UINT32_MAX){o<<"No graphics queue";vkDestroyInstance(instance,nullptr);return env->NewStringUTF(o.str().c_str());}
 float priority=1.f;VkDeviceQueueCreateInfo qi{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};qi.queueFamilyIndex=family;qi.queueCount=1;qi.pQueuePriorities=&priority;
 VkDeviceCreateInfo di{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};di.queueCreateInfoCount=1;di.pQueueCreateInfos=&qi;
 VkDevice device=VK_NULL_HANDLE;r=vkCreateDevice(gpu,&di,nullptr,&device);
 if(r!=VK_SUCCESS){o<<"vkCreateDevice failed: "<<r;vkDestroyInstance(instance,nullptr);return env->NewStringUTF(o.str().c_str());}
 VkQueue queue;vkGetDeviceQueue(device,family,0,&queue);
 VkImage image=VK_NULL_HANDLE;VkDeviceMemory memory=VK_NULL_HANDLE;VkCommandPool pool=VK_NULL_HANDLE;
 VkImageCreateInfo ii{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};ii.imageType=VK_IMAGE_TYPE_2D;ii.format=VK_FORMAT_R8G8B8A8_UNORM;ii.extent={64,64,1};ii.mipLevels=1;ii.arrayLayers=1;ii.samples=VK_SAMPLE_COUNT_1_BIT;ii.tiling=VK_IMAGE_TILING_OPTIMAL;ii.usage=VK_IMAGE_USAGE_TRANSFER_DST_BIT;ii.initialLayout=VK_IMAGE_LAYOUT_UNDEFINED;
 r=vkCreateImage(device,&ii,nullptr,&image);
 if(r==VK_SUCCESS){
  VkMemoryRequirements req{};vkGetImageMemoryRequirements(device,image,&req);
  VkPhysicalDeviceMemoryProperties mem{};vkGetPhysicalDeviceMemoryProperties(gpu,&mem);
  uint32_t index=UINT32_MAX;for(uint32_t i=0;i<mem.memoryTypeCount;i++)if(req.memoryTypeBits&(1u<<i)){if(mem.memoryTypes[i].propertyFlags&VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT){index=i;break;}if(index==UINT32_MAX)index=i;}
  if(index==UINT32_MAX)r=VK_ERROR_FEATURE_NOT_PRESENT;
  else {VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};ai.allocationSize=req.size;ai.memoryTypeIndex=index;r=vkAllocateMemory(device,&ai,nullptr,&memory);if(r==VK_SUCCESS)r=vkBindImageMemory(device,image,memory,0);}
 }
 if(r==VK_SUCCESS){
  VkCommandPoolCreateInfo pi{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};pi.queueFamilyIndex=family;r=vkCreateCommandPool(device,&pi,nullptr,&pool);
  if(r==VK_SUCCESS){
   VkCommandBufferAllocateInfo ca{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};ca.commandPool=pool;ca.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY;ca.commandBufferCount=1;
   VkCommandBuffer cb; r=vkAllocateCommandBuffers(device,&ca,&cb);
   if(r==VK_SUCCESS){VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};r=vkBeginCommandBuffer(cb,&bi);
    if(r==VK_SUCCESS){VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};b.oldLayout=VK_IMAGE_LAYOUT_UNDEFINED;b.newLayout=VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;b.srcQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;b.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;b.image=image;b.subresourceRange.aspectMask=VK_IMAGE_ASPECT_COLOR_BIT;b.subresourceRange.levelCount=1;b.subresourceRange.layerCount=1;b.dstAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT;
     vkCmdPipelineBarrier(cb,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,0,0,nullptr,0,nullptr,1,&b);
     VkClearColorValue color{};color.float32[0]=0.1f;color.float32[1]=0.4f;color.float32[2]=0.8f;color.float32[3]=1.f;
     VkImageSubresourceRange range{};range.aspectMask=VK_IMAGE_ASPECT_COLOR_BIT;range.levelCount=1;range.layerCount=1;
     vkCmdClearColorImage(cb,image,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,&color,1,&range);
     r=vkEndCommandBuffer(cb);
     if(r==VK_SUCCESS){VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};si.commandBufferCount=1;si.pCommandBuffers=&cb;r=vkQueueSubmit(queue,1,&si,VK_NULL_HANDLE);if(r==VK_SUCCESS)r=vkQueueWaitIdle(queue);}
    }
   }
  }
 }
 o<<"64x64 GPU image clear: "<<(r==VK_SUCCESS?"PASS":"FAIL")<<" (VkResult "<<r<<")\n";
 o<<"DXVK/VKD3D Windows D3D translation: NOT CONNECTED; requires compatible Wine/FEX build and DLLs.\n";
 if(pool)vkDestroyCommandPool(device,pool,nullptr);if(image)vkDestroyImage(device,image,nullptr);if(memory)vkFreeMemory(device,memory,nullptr);vkDestroyDevice(device,nullptr);vkDestroyInstance(instance,nullptr);
 return env->NewStringUTF(o.str().c_str());
}
