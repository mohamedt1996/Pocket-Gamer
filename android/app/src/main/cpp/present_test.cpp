
#include <jni.h>
#include <android/native_window_jni.h>
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>
#include <algorithm>
#include <sstream>
#include <vector>
#include <stdexcept>
#include <string>
#include <limits>
#define VK_OK(expr) do { VkResult _r=(expr); if(_r!=VK_SUCCESS) throw std::runtime_error(std::string(#expr)+" VkResult="+std::to_string(_r)); }while(0)
extern "C" JNIEXPORT jstring JNICALL
Java_org_pocketarcade_madeira_MainActivity_nativePresentTest(JNIEnv* env,jclass,jobject surface){
 std::ostringstream out;
 ANativeWindow* window=surface?ANativeWindow_fromSurface(env,surface):nullptr;
 if(!window)return env->NewStringUTF("Android Surface is unavailable");
 VkInstance instance=VK_NULL_HANDLE;VkSurfaceKHR vkSurface=VK_NULL_HANDLE;
 VkDevice device=VK_NULL_HANDLE;VkSwapchainKHR swapchain=VK_NULL_HANDLE;
 VkCommandPool pool=VK_NULL_HANDLE;VkCommandBuffer cmd=VK_NULL_HANDLE;
 VkSemaphore acquired=VK_NULL_HANDLE,rendered=VK_NULL_HANDLE;VkFence fence=VK_NULL_HANDLE;
 try{
  VkApplicationInfo ai{VK_STRUCTURE_TYPE_APPLICATION_INFO};ai.pApplicationName="Madeira swapchain smoke";ai.apiVersion=VK_API_VERSION_1_0;
  const char* iext[]={"VK_KHR_surface","VK_KHR_android_surface"};
  VkInstanceCreateInfo ici{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};ici.pApplicationInfo=&ai;ici.enabledExtensionCount=2;ici.ppEnabledExtensionNames=iext;
  VK_OK(vkCreateInstance(&ici,nullptr,&instance));
  VkAndroidSurfaceCreateInfoKHR sci{VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};sci.window=window;
  VK_OK(vkCreateAndroidSurfaceKHR(instance,&sci,nullptr,&vkSurface));
  uint32_t nd=0;VK_OK(vkEnumeratePhysicalDevices(instance,&nd,nullptr));
  if(!nd)throw std::runtime_error("No Vulkan physical devices");
  std::vector<VkPhysicalDevice> gpus(nd);VK_OK(vkEnumeratePhysicalDevices(instance,&nd,gpus.data()));
  VkPhysicalDevice gpu=VK_NULL_HANDLE;uint32_t family=UINT32_MAX;
  for(auto candidate:gpus){
   uint32_t nq=0;vkGetPhysicalDeviceQueueFamilyProperties(candidate,&nq,nullptr);
   std::vector<VkQueueFamilyProperties> queues(nq);vkGetPhysicalDeviceQueueFamilyProperties(candidate,&nq,queues.data());
   for(uint32_t q=0;q<nq;q++){
    VkBool32 present=VK_FALSE;
    if((queues[q].queueFlags&VK_QUEUE_GRAPHICS_BIT)&&vkGetPhysicalDeviceSurfaceSupportKHR(candidate,q,vkSurface,&present)==VK_SUCCESS&&present){gpu=candidate;family=q;break;}
   }
   if(gpu)break;
  }
  if(!gpu)throw std::runtime_error("No graphics queue supports Android surface presentation");
  float priority=1.f;
  VkDeviceQueueCreateInfo qci{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};qci.queueFamilyIndex=family;qci.queueCount=1;qci.pQueuePriorities=&priority;
  const char* dext[]={"VK_KHR_swapchain"};
  VkDeviceCreateInfo dci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};dci.queueCreateInfoCount=1;dci.pQueueCreateInfos=&qci;dci.enabledExtensionCount=1;dci.ppEnabledExtensionNames=dext;
  VK_OK(vkCreateDevice(gpu,&dci,nullptr,&device));
  VkQueue queue;vkGetDeviceQueue(device,family,0,&queue);
  VkSurfaceCapabilitiesKHR caps{};VK_OK(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(gpu,vkSurface,&caps));
  uint32_t nf=0;VK_OK(vkGetPhysicalDeviceSurfaceFormatsKHR(gpu,vkSurface,&nf,nullptr));
  if(!nf)throw std::runtime_error("Surface has no formats");
  std::vector<VkSurfaceFormatKHR> formats(nf);VK_OK(vkGetPhysicalDeviceSurfaceFormatsKHR(gpu,vkSurface,&nf,formats.data()));
  VkSurfaceFormatKHR fmt=formats[0];
  for(auto f:formats)if(f.format==VK_FORMAT_R8G8B8A8_UNORM||f.format==VK_FORMAT_B8G8R8A8_UNORM){fmt=f;break;}
  if(!(caps.supportedUsageFlags&VK_IMAGE_USAGE_TRANSFER_DST_BIT))throw std::runtime_error("Swapchain images do not support transfer destination");
  uint32_t np=0;VK_OK(vkGetPhysicalDeviceSurfacePresentModesKHR(gpu,vkSurface,&np,nullptr));
  VkExtent2D extent=caps.currentExtent;
  if(extent.width==UINT32_MAX){
   extent.width=std::clamp((uint32_t)std::max(1,ANativeWindow_getWidth(window)),caps.minImageExtent.width,caps.maxImageExtent.width);
   extent.height=std::clamp((uint32_t)std::max(1,ANativeWindow_getHeight(window)),caps.minImageExtent.height,caps.maxImageExtent.height);
  }
  uint32_t count=std::max(2u,caps.minImageCount);if(caps.maxImageCount)count=std::min(count,caps.maxImageCount);
  VkCompositeAlphaFlagBitsKHR alpha=VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
  if(!(caps.supportedCompositeAlpha&alpha)){
   for(auto option:{VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR,VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR,VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR})
    if(caps.supportedCompositeAlpha&option){alpha=option;break;}
  }
  VkSwapchainCreateInfoKHR sw{VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};
  sw.surface=vkSurface;sw.minImageCount=count;sw.imageFormat=fmt.format;sw.imageColorSpace=fmt.colorSpace;sw.imageExtent=extent;
  sw.imageArrayLayers=1;sw.imageUsage=VK_IMAGE_USAGE_TRANSFER_DST_BIT;sw.imageSharingMode=VK_SHARING_MODE_EXCLUSIVE;
  sw.preTransform=caps.currentTransform;sw.compositeAlpha=alpha;sw.presentMode=VK_PRESENT_MODE_FIFO_KHR;sw.clipped=VK_TRUE;
  VK_OK(vkCreateSwapchainKHR(device,&sw,nullptr,&swapchain));
  uint32_t ni=0;VK_OK(vkGetSwapchainImagesKHR(device,swapchain,&ni,nullptr));
  std::vector<VkImage> images(ni);VK_OK(vkGetSwapchainImagesKHR(device,swapchain,&ni,images.data()));
  VkCommandPoolCreateInfo pci{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};pci.queueFamilyIndex=family;
  VK_OK(vkCreateCommandPool(device,&pci,nullptr,&pool));
  VkCommandBufferAllocateInfo bai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};bai.commandPool=pool;bai.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY;bai.commandBufferCount=1;
  VK_OK(vkAllocateCommandBuffers(device,&bai,&cmd));
  VkSemaphoreCreateInfo si{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
  VK_OK(vkCreateSemaphore(device,&si,nullptr,&acquired));VK_OK(vkCreateSemaphore(device,&si,nullptr,&rendered));
  VkFenceCreateInfo fi{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};VK_OK(vkCreateFence(device,&fi,nullptr,&fence));
  uint32_t imageIndex=0;VK_OK(vkAcquireNextImageKHR(device,swapchain,UINT64_MAX,acquired,VK_NULL_HANDLE,&imageIndex));
  VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};VK_OK(vkBeginCommandBuffer(cmd,&begin));
  VkImageMemoryBarrier barrier{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
  barrier.oldLayout=VK_IMAGE_LAYOUT_UNDEFINED;barrier.newLayout=VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
  barrier.srcQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;barrier.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;
  barrier.image=images[imageIndex];barrier.subresourceRange.aspectMask=VK_IMAGE_ASPECT_COLOR_BIT;barrier.subresourceRange.levelCount=1;barrier.subresourceRange.layerCount=1;
  barrier.dstAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT;
  vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,0,0,nullptr,0,nullptr,1,&barrier);
  VkClearColorValue color{};color.float32[0]=0.06f;color.float32[1]=0.46f;color.float32[2]=0.75f;color.float32[3]=1.f;
  vkCmdClearColorImage(cmd,images[imageIndex],VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,&color,1,&barrier.subresourceRange);
  barrier.oldLayout=VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;barrier.newLayout=VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
  barrier.srcAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT;barrier.dstAccessMask=0;
  vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,0,0,nullptr,0,nullptr,1,&barrier);
  VK_OK(vkEndCommandBuffer(cmd));
  VkPipelineStageFlags stage=VK_PIPELINE_STAGE_TRANSFER_BIT;
  VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};submit.waitSemaphoreCount=1;submit.pWaitSemaphores=&acquired;submit.pWaitDstStageMask=&stage;
  submit.commandBufferCount=1;submit.pCommandBuffers=&cmd;submit.signalSemaphoreCount=1;submit.pSignalSemaphores=&rendered;
  VK_OK(vkQueueSubmit(queue,1,&submit,fence));
  VkPresentInfoKHR present{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};present.waitSemaphoreCount=1;present.pWaitSemaphores=&rendered;present.swapchainCount=1;present.pSwapchains=&swapchain;present.pImageIndices=&imageIndex;
  VK_OK(vkQueuePresentKHR(queue,&present));
  VK_OK(vkQueueWaitIdle(queue));
  out<<"PASS: Vulkan swapchain image cleared and presented at "<<extent.width<<"x"<<extent.height<<"\n";
  out<<"Expected preview: blue rectangle. Not a Wine/DXVK render.";
 }catch(const std::exception& e){out<<"FAIL: "<<e.what();}
 if(device)vkDeviceWaitIdle(device);
 if(fence)vkDestroyFence(device,fence,nullptr);
 if(rendered)vkDestroySemaphore(device,rendered,nullptr);
 if(acquired)vkDestroySemaphore(device,acquired,nullptr);
 if(pool)vkDestroyCommandPool(device,pool,nullptr);
 if(swapchain)vkDestroySwapchainKHR(device,swapchain,nullptr);
 if(device)vkDestroyDevice(device,nullptr);
 if(vkSurface)vkDestroySurfaceKHR(instance,vkSurface,nullptr);
 if(instance)vkDestroyInstance(instance,nullptr);
 ANativeWindow_release(window);
 return env->NewStringUTF(out.str().c_str());
}
