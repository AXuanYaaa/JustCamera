#include <jni.h>

extern "C" JNIEXPORT jstring JNICALL
Java_top_r2dblog_justcamera_nativecore_NativeCore_nativeVersion(
        JNIEnv* env,
        jobject /* this */) noexcept {
    return env->NewStringUTF("JustCamera Native Core 0.1");
}
