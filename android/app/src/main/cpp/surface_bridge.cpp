#include <jni.h>
#include <android/native_window_jni.h>
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>
#include <sstream>
#include <vector>
extern "C" JNIEXPORT jstring JNICALL Java_org_pocketarcade_madeira_MainActivity_nativeSurfaceTest(JNIEnv* env,jclass,jobject surface) {
 if(!surface)return env->NewStringUTF("Surface unavailable");
 ANativeWindow* window=ANativeWindow_fromSurface(env,surface);
 if(!window)return env->NewStringUTF("ANativeWindow unavailable");
 std::ostringstream log;
 VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};app.pApplicationName="Madeira WSI";app.apiVersion=VK_API_VERSION_1_0;
 const char* exts[]={"VK_KHR_surface","VK_KHR_android_surface"};
 VkInstanceCreateInfo ci{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};ci.pApplicationInfo=&app;ci.enabledExtensionCount=2;ci.ppEnabledExtensionNames=exts;
 VkInstance inst=VK_NULL_HANDLE;VkSurfaceKHR vkSurface=VK_NULL_HANDLE;
 VkResult r=vkCreateInstance(&ci,nullptr,&inst);
 if(r==VK_SUCCESS){
  VkAndroidSurfaceCreateInfoKHR sci{VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};sci.window=window;
  auto create=reinterpret_cast<PFN_vkCreateAndroidSurfaceKHR>(vkGetInstanceProcAddr(inst,"vkCreateAndroidSurfaceKHR"));
  r=create?create(inst,&sci,nullptr,&vkSurface):VK_ERROR_EXTENSION_NOT_PRESENT;
  log<<"Android Vulkan surface: "<<(r==VK_SUCCESS?"PASS":"FAIL")<<" ("<<r<<")\n";
  if(r==VK_SUCCESS){
   uint32_t n=0;if(vkEnumeratePhysicalDevices(inst,&n,nullptr)==VK_SUCCESS&&n){
    std::vector<VkPhysicalDevice> devices(n);vkEnumeratePhysicalDevices(inst,&n,devices.data());
    for(uint32_t d=0;d<n;d++){
     uint32_t qn=0;vkGetPhysicalDeviceQueueFamilyProperties(devices[d],&qn,nullptr);
     for(uint32_t q=0;q<qn;q++){VkBool32 supported=VK_FALSE;
      if(vkGetPhysicalDeviceSurfaceSupportKHR(devices[d],q,vkSurface,&supported)==VK_SUCCESS&&supported)
       log<<"GPU "<<d<<" queue "<<q<<" supports presentation\n";
     }
     VkSurfaceCapabilitiesKHR caps{};
     if(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(devices[d],vkSurface,&caps)==VK_SUCCESS)
      log<<"Surface extent: "<<caps.currentExtent.width<<"x"<<caps.currentExtent.height<<"\n";
    }
   }
   vkDestroySurfaceKHR(inst,vkSurface,nullptr);
  }
  vkDestroyInstance(inst,nullptr);
 }else log<<"Vulkan instance failed: "<<r<<"\n";
 ANativeWindow_release(window);
 log<<"WSI validation only; Wine/DXVK presentation is not connected.";
 return env->NewStringUTF(log.str().c_str());
}
