#include <jni.h>
#include <unistd.h>
#include <sys/utsname.h>
#include <string>
extern "C" JNIEXPORT jstring JNICALL Java_org_pocketarcade_madeira_MainActivity_nativeProbe(JNIEnv* env,jclass){
 struct utsname u{}; uname(&u);
 std::string text="Native ARM64 library: loaded\nKernel: ";text+=u.release;
 text+="\nPID: "+std::to_string(getpid());
 text+="\nWine: NOT INTEGRATED\nFEX: NOT INTEGRATED\nDXVK: NOT INTEGRATED";
 return env->NewStringUTF(text.c_str());
}
