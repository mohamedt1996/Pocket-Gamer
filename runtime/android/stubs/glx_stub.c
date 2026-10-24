/* libGLX.so.0 for Android: there is no X server and no GLX on Android, but
 * GStreamer's GL library (pulled in by winegstreamer) links GLX's entry points
 * directly. bionic resolves every symbol at load time, so without these the
 * library -- and with it Wine's media decoding -- cannot load. Every function
 * reports "not available"; GStreamer then uses its EGL path, backed by
 * Android's own libEGL.so.
 *
 * Replaces Termux's GLVND libGLX + libGLdispatch, which also use ELF TLS that
 * Android 9's linker cannot load. Built by package-runtime.py with the NDK. */
#include <stddef.h>

#define STUB_PTR(name) void *name(void) { return NULL; }
#define STUB_INT(name) int name(void) { return 0; }
#define STUB_VOID(name) void name(void) { }

STUB_PTR(glXChooseFBConfig)
STUB_PTR(glXChooseVisual)
STUB_PTR(glXCreateContext)
STUB_VOID(glXDestroyContext)
STUB_PTR(glXGetCurrentContext)
STUB_INT(glXGetFBConfigAttrib)
STUB_PTR(glXGetFBConfigs)
STUB_PTR(glXGetProcAddress)
STUB_PTR(glXGetProcAddressARB)
STUB_PTR(glXGetVisualFromFBConfig)
STUB_INT(glXMakeCurrent)
STUB_INT(glXMakeContextCurrent)
STUB_INT(glXQueryContext)
STUB_INT(glXQueryExtension)
STUB_PTR(glXQueryExtensionsString)
STUB_INT(glXQueryVersion)
STUB_VOID(glXSwapBuffers)
STUB_PTR(glXGetCurrentDisplay)
STUB_PTR(glXGetCurrentDrawable)
STUB_PTR(glXCreateNewContext)
STUB_PTR(glXCreateContextAttribsARB)
