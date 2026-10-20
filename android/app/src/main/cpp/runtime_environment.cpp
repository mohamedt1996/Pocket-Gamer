#include <jni.h>
#include <dlfcn.h>
#include <unistd.h>
#include <cstdlib>
#include <cerrno>
#include <cstring>
extern "C" JNIEXPORT void JNICALL
Java_org_pocketarcade_madeira_NativeEnvironment_prepare(JNIEnv *env,jclass,jstring libs,jstring directory,jstring tmp) {
    if (!libs || !directory || !tmp) { env->ThrowNew(env->FindClass("java/io/IOException"),"Missing runtime paths"); return; }
    const char *path=env->GetStringUTFChars(libs,nullptr);
    const char *cwd=env->GetStringUTFChars(directory,nullptr);
    const char *temp=env->GetStringUTFChars(tmp,nullptr);
    int error=0;
    if(setenv("LD_LIBRARY_PATH",path,1) || setenv("TMPDIR",temp,1) || chdir(cwd))error=errno;
    // Same legacy Android loader hook used by Wine's own JNI initializer.
    // Must run before System.load(ntdll.so), whose dependencies are in lib/.
    auto update=reinterpret_cast<void(*)(const char*)>(dlsym(RTLD_DEFAULT,"android_update_LD_LIBRARY_PATH"));
    if(update)update(path);
    env->ReleaseStringUTFChars(libs,path);env->ReleaseStringUTFChars(directory,cwd);env->ReleaseStringUTFChars(tmp,temp);
    if(error)env->ThrowNew(env->FindClass("java/io/IOException"),strerror(error));
}
