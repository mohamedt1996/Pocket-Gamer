// SPDX-License-Identifier: GPL-3.0-or-later
// JNI entry for gpu_caps.c (org.pocketarcade.madeira.platform.GpuCaps).
#include <jni.h>
#include <vector>
extern "C" int pocket_gpu_caps(char *buf, size_t size);
extern "C" JNIEXPORT jstring JNICALL
Java_org_pocketarcade_madeira_platform_GpuCaps_nativeProbe(JNIEnv *env, jclass) {
  std::vector<char> buf(16384);
  pocket_gpu_caps(buf.data(), buf.size());
  return env->NewStringUTF(buf.data());
}
